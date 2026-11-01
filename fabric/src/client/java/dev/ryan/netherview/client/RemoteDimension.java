package dev.ryan.netherview.client;

import dev.ryan.netherview.compat.ClientVersioned;
import dev.ryan.netherview.compat.RemoteLevelRenderer;
import com.mojang.blaze3d.pipeline.TextureTarget;
import dev.ryan.netherview.client.mixin.CloudRendererInvoker;
import dev.ryan.netherview.client.mixin.LevelRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;

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
		this.level = this.renderer.newLevel(connection, data, key, type, CHUNK_RADIUS, key == Level.NETHER ? 32 : 63);
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
		return this.center == null ? new ArrayList<>() : snapshotChunks(this.level, this.center, CHUNK_RADIUS + 3);
	}

	/** Loaded chunks of {@code level} within {@code radius} of {@code center}, as the packets that would load them. */
	static List<ClientboundLevelChunkWithLightPacket> snapshotChunks(ClientLevel level, ChunkPos center, int radius) {
		List<ClientboundLevelChunkWithLightPacket> chunks = new ArrayList<>();
		for (int x = center.x() - radius; x <= center.x() + radius; x++) {
			for (int z = center.z() - radius; z <= center.z() + radius; z++) {
				LevelChunk chunk = level.getChunkSource().getChunk(x, z, false);
				if (chunk != null) {
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
		int x = ClientVersioned.chunkX(packet);
		int z = ClientVersioned.chunkZ(packet);
		ClientVersioned.replaceWithPacketData(this.level.getChunkSource(), x, z, ClientVersioned.chunkData(packet));
		ClientboundLightUpdatePacketData lightData = ClientVersioned.lightData(packet);
		this.level.queueLightUpdate(() -> {
			this.applyLightData(x, z, lightData);
			LevelChunk chunk = this.level.getChunkSource().getChunk(x, z, false);
			if (chunk != null) {
				this.enableChunkLight(chunk, x, z);
				this.renderer.chunkReady(chunk.getPos());
			}
		});
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
