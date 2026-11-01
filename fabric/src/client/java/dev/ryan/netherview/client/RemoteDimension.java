package dev.ryan.netherview.client;

import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import dev.ryan.netherview.compat.ClientVersioned;
import dev.ryan.netherview.compat.RemoteLevelRenderer;
import com.mojang.blaze3d.pipeline.TextureTarget;
import dev.ryan.netherview.client.mixin.CloudRendererInvoker;
import dev.ryan.netherview.client.mixin.LevelRendererAccessor;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * A dimension the player is not in, kept alive client-side so it can be drawn through
 * a portal: its own {@link ClientLevel} (fed with chunks the server streams over), plus
 * a full private copy of the per-level render stack — extractor, render state,
 * {@link LevelRenderer}, fog and lightmap buffers, and the offscreen framebuffer the
 * view is rendered into.
 */
public final class RemoteDimension implements AutoCloseable {
	/** Chunk cache radius for the remote level. Must cover what the server streams. */
	private static final int CHUNK_RADIUS = 8;

	final ResourceKey<Level> key;
	final ClientLevel level;
	final LevelRenderState renderState = new LevelRenderState();
	final RemoteLevelRenderer renderer;
	final FogRenderer fog = new FogRenderer();
	final Lightmap lightmap = new Lightmap();
	final LightmapRenderState lightmapState = new LightmapRenderState();
	final CrossFrameResourcePool resourcePool = new CrossFrameResourcePool(3);
	final ProjectionMatrixBuffer projection = new ProjectionMatrixBuffer("netherview remote");
	final PortalCamera camera = new PortalCamera();
	private final ArrayDeque<byte[]> seedQueue = new ArrayDeque<>();
	/** Remembered chunks lit at once when loaded (nearest first): what the window shows first. */
	private static final int SEED_NOW_CHUNKS = 9;
	TextureTarget target;
	private ChunkPos center;
	private boolean probePrimed;
	private boolean closed;

	RemoteDimension(Minecraft minecraft, ResourceKey<Level> key, String dimensionTypeId) {
		this.key = key;
		int width = minecraft.getWindow().getWidth();
		int height = minecraft.getWindow().getHeight();
		this.renderer = new RemoteLevelRenderer(minecraft, this.renderState, width, height);
		CloudRendererInvoker clouds = (CloudRendererInvoker) this.renderer.clouds();
		clouds.netherview$apply(clouds.netherview$prepare(minecraft.getResourceManager(), Profiler.get()), minecraft.getResourceManager(), Profiler.get());

		ClientPacketListener connection = minecraft.getConnection();
		// The type the server named for this world (any datapack or plugin-made world), else a guess from its name.
		Registry<DimensionType> types = connection.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
		Holder<DimensionType> type = dimensionTypeId == null ? null
				: types.get(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(dimensionTypeId))).orElse(null);
		if (type == null) {
			type = types.getOrThrow(dimensionType(key));
		}
		ClientLevel.ClientLevelData data = new ClientLevel.ClientLevelData(minecraft.level.getDifficulty(), false, false);
		RemoteClocks.beginConstructing(key);
		try {
			this.level = this.renderer.newLevel(connection, data, key, type, CHUNK_RADIUS, key == Level.NETHER ? 32 : 63);
		} finally {
			RemoteClocks.endConstructing();
		}
		this.renderer.setLevel(this.level);
		this.target = ClientVersioned.newTarget("netherview " + key.identifier(), width, height);
	}

	private static ResourceKey<DimensionType> dimensionType(ResourceKey<Level> key) {
		if (key == Level.NETHER) {
			return BuiltinDimensionTypes.NETHER;
		}
		if (key == Level.END) {
			return BuiltinDimensionTypes.END;
		}
		return BuiltinDimensionTypes.OVERWORLD;
	}

	/** Keeps the offscreen view and the renderer's own targets the size of the window. */
	void ensureSize(int width, int height) {
		if (this.target.width != width || this.target.height != height) {
			this.target.resize(width, height);
			this.renderer.resize(width, height);
		}
	}

	void centerOn(ChunkPos chunk) {
		this.center = chunk;
		this.level.getChunkSource().updateViewCenter(chunk.x(), chunk.z());
	}

	ChunkPos center() {
		return this.center;
	}

	/** Every loaded chunk, re-encoded as the packet that would load it, blocks and light included. */
	List<ClientboundLevelChunkWithLightPacket> snapshotChunks() {
		return snapshotChunks(CHUNK_RADIUS + 3);
	}

	/** Its loaded chunks within {@code radius} of its center, as the packets that would load them. */
	List<ClientboundLevelChunkWithLightPacket> snapshotChunks(int radius) {
		return this.center == null ? new ArrayList<>() : snapshotChunks(this.level, this.center, radius);
	}

	/**
	 * This view's entities (mirrored from the server, with the server's ids), as the packets
	 * that would add them to a level: spawn, synced data and equipment. Other players only if
	 * the client knows them (a player can't be added otherwise).
	 */
	List<Packet<ClientGamePacketListener>> snapshotEntities(ClientPacketListener connection) {
		return snapshotEntities(this.level, connection, null, 0);
	}

	/** {@link #snapshotEntities(ClientPacketListener)} for any level, only within {@code radius} chunks of {@code center} if given; never the local player. */
	static List<Packet<ClientGamePacketListener>> snapshotEntities(ClientLevel level, ClientPacketListener connection, ChunkPos center, int radius) {
		List<Packet<ClientGamePacketListener>> packets = new ArrayList<>();
		for (Entity entity : level.entitiesForRendering()) {
			if (entity instanceof LocalPlayer || entity instanceof Player && connection.getPlayerInfo(entity.getUUID()) == null) {
				continue;
			}
			if (center != null && Math.max(Math.abs(entity.chunkPosition().x() - center.x()), Math.abs(entity.chunkPosition().z() - center.z())) > radius) {
				continue;
			}
			int data = entity instanceof FallingBlockEntity falling ? Block.getId(falling.getBlockState())
					: entity instanceof HangingEntity hanging ? hanging.getDirection().get3DDataValue() : 0;
			packets.add(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), entity.getX(), entity.getY(), entity.getZ(),
					entity.getXRot(), entity.getYRot(), entity.getType(), data, entity.getDeltaMovement(), entity.getYHeadRot()));
			List<SynchedEntityData.DataValue<?>> values = entity.getEntityData().getNonDefaultValues();
			if (values != null) {
				packets.add(new ClientboundSetEntityDataPacket(entity.getId(), values));
			}
			if (entity instanceof LivingEntity living) {
				List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
				for (EquipmentSlot slot : EquipmentSlot.values()) {
					ItemStack item = living.getItemBySlot(slot);
					if (!item.isEmpty()) {
						equipment.add(Pair.of(slot, item.copy()));
					}
				}
				if (!equipment.isEmpty()) {
					packets.add(new ClientboundSetEquipmentPacket(entity.getId(), equipment));
				}
			}
		}
		return packets;
	}

	/** Loaded chunks of {@code level} within {@code radius} of {@code center}, as the packets that would load them. */
	static List<ClientboundLevelChunkWithLightPacket> snapshotChunks(ClientLevel level, ChunkPos center, int radius) {
		return snapshotChunks(level, center, radius, (x, z) -> false);
	}

	/** {@link #snapshotChunks(ClientLevel, ChunkPos, int)}, leaving out the chunks {@code skip} names. */
	static List<ClientboundLevelChunkWithLightPacket> snapshotChunks(ClientLevel level, ChunkPos center, int radius, BiPredicate<Integer, Integer> skip) {
		List<ClientboundLevelChunkWithLightPacket> chunks = new ArrayList<>();
		for (int x = center.x() - radius; x <= center.x() + radius; x++) {
			for (int z = center.z() - radius; z <= center.z() + radius; z++) {
				LevelChunk chunk = level.getChunkSource().getChunk(x, z, false);
				if (chunk != null && !skip.test(x, z)) {
					chunks.add(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
				}
			}
		}
		return chunks;
	}

	static int chunkRadius() {
		return CHUNK_RADIUS;
	}

	/** Copy over the chunks around this view's centre that {@code source} has and this view doesn't. */
	void seedFrom(RemoteDimension source) {
		if (this.center == null) {
			return;
		}
		for (int x = this.center.x() - CHUNK_RADIUS; x <= this.center.x() + CHUNK_RADIUS; x++) {
			for (int z = this.center.z() - CHUNK_RADIUS; z <= this.center.z() + CHUNK_RADIUS; z++) {
				if (this.level.getChunkSource().getChunk(x, z, false) != null) {
					continue;
				}
				LevelChunk chunk = source.level.getChunkSource().getChunk(x, z, false);
				if (chunk != null) {
					this.acceptChunk(new ClientboundLevelChunkWithLightPacket(chunk, source.level.getLightEngine(), null, null));
				}
			}
		}
	}

	/** Same steps as ClientPacketListener.handleLevelChunkWithLight, aimed at this level. */
	void acceptChunk(ClientboundLevelChunkWithLightPacket packet) {
		acceptChunk(packet, false);
	}

	/** Chunks remembered from an earlier visit (see {@link OfflinePortals}), as saved packets. */
	void queueSeed(List<byte[]> chunks) {
		this.seedQueue.clear();
		this.seedQueue.addAll(chunks);
	}

	/** Load up to {@code max} of the remembered chunks (a few a tick: decoding them all at once would stall). */
	void seedFromCache(ClientPacketListener connection, int max) {
		for (int i = 0; i < max && !this.seedQueue.isEmpty(); i++) {
			byte[] bytes = this.seedQueue.poll();
			RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), connection.registryAccess());
			try {
				ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buf);
				if (this.level.getChunkSource().getChunk(ClientVersioned.chunkX(packet), ClientVersioned.chunkZ(packet), false) == null) {
					this.acceptChunk(packet, i < SEED_NOW_CHUNKS);
				}
			} catch (RuntimeException e) {
				this.seedQueue.clear(); // a cache from something else: stop, the portal shows what it has
				return;
			} finally {
				buf.release();
			}
		}
	}

	/**
	 * Load a chunk. Its light (and so its meshing) normally waits in the level's light queue,
	 * a few dozen chunks a tick as vanilla does; {@code now} applies it at once instead.
	 */
	void acceptChunk(ClientboundLevelChunkWithLightPacket packet, boolean now) {
		int x = ClientVersioned.chunkX(packet);
		int z = ClientVersioned.chunkZ(packet);
		ClientVersioned.replaceWithPacketData(this.level.getChunkSource(), x, z, ClientVersioned.chunkData(packet));
		ClientboundLightUpdatePacketData lightData = ClientVersioned.lightData(packet);
		Runnable light = () -> {
			this.applyLightData(x, z, lightData);
			LevelChunk chunk = this.level.getChunkSource().getChunk(x, z, false);
			if (chunk != null) {
				this.enableChunkLight(chunk, x, z);
				this.renderer.chunkReady(chunk.getPos());
			}
		};
		if (now) {
			light.run();
		} else {
			this.level.queueLightUpdate(light);
		}
	}

	private void applyLightData(int x, int z, ClientboundLightUpdatePacketData lightData) {
		LevelLightEngine lightEngine = this.level.getChunkSource().getLightEngine();
		this.readSectionList(x, z, lightEngine, LightLayer.SKY, ClientVersioned.skyYMask(lightData), ClientVersioned.emptySkyYMask(lightData), ClientVersioned.skyUpdates(lightData).iterator());
		this.readSectionList(x, z, lightEngine, LightLayer.BLOCK, ClientVersioned.blockYMask(lightData), ClientVersioned.emptyBlockYMask(lightData), ClientVersioned.blockUpdates(lightData).iterator());
		lightEngine.setLightEnabled(new ChunkPos(x, z), true);
	}

	private void readSectionList(int chunkX, int chunkZ, LevelLightEngine lightEngine, LightLayer layer, BitSet yMask, BitSet emptyYMask, Iterator<byte[]> updates) {
		for (int sectionIndex = 0; sectionIndex < lightEngine.getLightSectionCount(); sectionIndex++) {
			int sectionY = lightEngine.getMinLightSection() + sectionIndex;
			boolean haveData = yMask.get(sectionIndex);
			boolean haveEmpty = emptyYMask.get(sectionIndex);
			if (haveData || haveEmpty) {
				lightEngine.queueSectionData(layer, SectionPos.of(chunkX, sectionY, chunkZ), haveData ? new DataLayer(updates.next().clone()) : new DataLayer());
			}
		}
	}

	private void enableChunkLight(LevelChunk chunk, int x, int z) {
		LevelLightEngine lightEngine = this.level.getChunkSource().getLightEngine();
		LevelChunkSection[] sections = chunk.getSections();
		ChunkPos chunkPos = chunk.getPos();
		for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
			int sectionY = this.level.getSectionYFromSectionIndex(sectionIndex);
			lightEngine.updateSectionStatus(SectionPos.of(chunkPos, sectionY), sections[sectionIndex].hasOnlyAir());
		}
		this.level.setSectionRangeDirty(x - 1, this.level.getMinSectionY(), z - 1, x + 1, this.level.getMaxSectionY(), z + 1);
	}

	/** A new camera's probe has never sampled anything and would give default colours; sample it once now. */
	void primeProbe(Vec3 eye) {
		if (!this.probePrimed) {
			this.camera.attributeProbe().tick(this.level, eye);
			this.probePrimed = true;
		}
	}

	/** Once per client tick: drain queued light work, step entities (interpolation, animation), and keep environment attributes (fog colour etc.) current. */
	void tick(ClientLevel playerLevel) {
		this.level.setTimeFromServer(playerLevel.getGameTime());
		// Sky colour, sun angle and the like are cached a tick at a time; vanilla clears the
		// cache in the player's level's own tick, which a remote level never runs. Left alone,
		// a view kept for a while shows the sky of whenever it last filled the cache.
		this.level.environmentAttributes().invalidateTickCache();
		if (this.key == playerLevel.dimension()) {
			// A view of the player's own world (a portal within it): the same weather.
			this.level.setRainLevel(playerLevel.getRainLevel(1.0F));
			this.level.setThunderLevel(playerLevel.getThunderLevel(1.0F));
		}
		this.level.update();
		this.level.tickEntities();
		if (this.camera.isInitialized()) {
			this.camera.attributeProbe().tick(this.level, this.camera.position());
		}
	}

	boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		this.closed = true;
		this.renderer.close();
		this.fog.close();
		this.lightmap.close();
		this.resourcePool.close();
		this.projection.close();
		this.target.destroyBuffers();
	}
}
