package dev.ryan.netherview.client;

import dev.ryan.netherview.PortalGeometry;
import dev.ryan.netherview.net.PortalLink;
import dev.ryan.netherview.net.PortalLinksPayload;
import dev.ryan.netherview.client.mixin.ClientPacketListenerAccessor;
import dev.ryan.netherview.net.RemoteChunkPayload;
import dev.ryan.netherview.net.RemoteEntitiesPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/** Client-side state: the portals around the player and the remote dimensions they look into. */
public final class RemoteDimensions {
	private static final Logger LOGGER = LoggerFactory.getLogger("netherview");

	private static final Map<ResourceKey<Level>, RemoteDimension> REMOTES = new HashMap<>();
	private static List<Link> links = List.of();
	private static ClientLevel trackedLevel;
	/** The remote that showed the dimension the player just walked into; see {@link PortalViewRenderer}. */
	private static RemoteDimension bridge;
	private static int bridgeTicks;
	private static final int BRIDGE_MIN_TICKS = 2;
	private static final int BRIDGE_MAX_TICKS = 10;
	private static StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> entityCodec;
	private static Object entityCodecRegistries;

	/** A link plus the dimension it leads into, resolved once when the server sends it. */
	public record Link(PortalLink link, PortalGeometry geometry, ResourceKey<Level> dest) {
	}

	private RemoteDimensions() {
	}

	public static List<Link> links() {
		return links;
	}

	public static RemoteDimension bridge() {
		return bridge;
	}

	/**
	 * The player just came through {@code back}'s destination: install the way back right
	 * away (so that portal is a window from the first frame, not a swirl until the server's
	 * next scan) and seed its remote level with the chunks of the dimension just left.
	 */
	static void installArrival(PortalLink back, ResourceKey<Level> backDimension, List<ClientboundLevelChunkWithLightPacket> chunks) {
		Minecraft minecraft = Minecraft.getInstance();
		syncLevel(minecraft);
		Link link = new Link(back, PortalGeometry.of(back), backDimension);
		RemoteDimension remote = ensure(minecraft, backDimension);
		remote.centerOn(ChunkPos.containing(BlockPos.containing(link.geometry().destCenter())));
		for (ClientboundLevelChunkWithLightPacket chunk : chunks) {
			remote.acceptChunk(chunk);
		}
		links = List.of(link);
		PortalHiding.setLinks(links);
	}

	/** Keep the remote for {@code dimension} alive as the bridge while the main renderer meshes the new level. */
	static void adoptAsBridge(ResourceKey<Level> dimension) {
		RemoteDimension remote = REMOTES.remove(dimension);
		if (remote == null) {
			return;
		}
		closeBridge();
		bridge = remote;
		bridgeTicks = 0;
		publishRemoteLevels();
	}

	private static void closeBridge() {
		if (bridge != null) {
			PortalViewRenderer.forgetRemotes();
			bridge.close();
			bridge = null;
			publishRemoteLevels();
		}
	}

	public static RemoteDimension get(ResourceKey<Level> key) {
		return REMOTES.get(key);
	}

	public static boolean isRemoteExtractor(LevelExtractor extractor) {
		for (RemoteDimension remote : REMOTES.values()) {
			if (remote.extractor == extractor) {
				return true;
			}
		}
		return false;
	}

	public static void onLinks(PortalLinksPayload payload) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		syncLevel(minecraft);
		List<Link> resolved = new ArrayList<>();
		for (PortalLink link : payload.links()) {
			ResourceKey<Level> dest = ResourceKey.create(Registries.DIMENSION, Identifier.parse(link.destDimension()));
			if (dest == minecraft.level.dimension()) {
				continue;
			}
			resolved.add(new Link(link, PortalGeometry.of(link), dest));
		}
		links = List.copyOf(resolved);
		PortalHiding.setLinks(links);
		if (!resolved.isEmpty()) {
			// The server streams the nearest portal's destination; keep that window loaded.
			Link nearest = resolved.getFirst();
			ensure(minecraft, nearest.dest()).centerOn(ChunkPos.containing(BlockPos.containing(nearest.geometry().destCenter())));
		}
	}

	public static void onChunk(RemoteChunkPayload payload) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.getConnection() == null) {
			return;
		}
		syncLevel(minecraft);
		ResourceKey<Level> dest = ResourceKey.create(Registries.DIMENSION, Identifier.parse(payload.dimension()));
		if (dest == minecraft.level.dimension()) {
			return;
		}
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload.chunkPacket()), minecraft.getConnection().registryAccess());
		try {
			ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buf);
			ensure(minecraft, dest).acceptChunk(packet);
		} catch (RuntimeException e) {
			LOGGER.warn("Dropping remote chunk for {}: {}", dest.identifier(), e.toString());
		} finally {
			buf.release();
		}
	}

	public static void onEntities(RemoteEntitiesPayload payload) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientPacketListener connection = minecraft.getConnection();
		if (minecraft.level == null || connection == null) {
			return;
		}
		syncLevel(minecraft);
		ResourceKey<Level> dest = ResourceKey.create(Registries.DIMENSION, Identifier.parse(payload.dimension()));
		if (dest == minecraft.level.dimension()) {
			return;
		}
		RemoteDimension remote = ensure(minecraft, dest);
		if (entityCodecRegistries != connection.registryAccess()) {
			entityCodec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(connection.registryAccess())).codec();
			entityCodecRegistries = connection.registryAccess();
		}
		ClientPacketListenerAccessor listener = (ClientPacketListenerAccessor) connection;
		ClientLevel playerLevel = listener.netherview$getLevel();
		listener.netherview$setLevel(remote.level);
		try {
			for (byte[] bytes : payload.packets()) {
				ByteBuf buf = Unpooled.wrappedBuffer(bytes);
				try {
					@SuppressWarnings("unchecked")
					Packet<ClientGamePacketListener> packet = (Packet<ClientGamePacketListener>) entityCodec.decode(buf);
					packet.handle(connection);
				} catch (RuntimeException e) {
					LOGGER.debug("Dropping remote entity packet for {}: {}", dest.identifier(), e.toString());
				} finally {
					buf.release();
				}
			}
		} finally {
			listener.netherview$setLevel(playerLevel);
		}
	}

	public static void tick(Minecraft minecraft) {
		syncLevel(minecraft);
		if (minecraft.level == null) {
			return;
		}
		for (RemoteDimension remote : REMOTES.values()) {
			remote.tick(minecraft.level);
		}
		if (bridge != null) {
			bridge.tick(minecraft.level);
			bridgeTicks++;
			// The bridge doubles the frame's cost, so it ends as soon as the main renderer has
			// meshed what it was given (fade-in is off right after a hand-off, so nothing is half-drawn).
			boolean caughtUp = bridgeTicks >= BRIDGE_MIN_TICKS
					&& minecraft.levelRenderer.isSectionCompiledAndVisible(minecraft.gameRenderer.mainCamera().blockPosition(), 0L)
					&& minecraft.levelRenderer.hasRenderedAllSections();
			if (caughtUp || bridgeTicks >= BRIDGE_MAX_TICKS || bridge.level.dimension() != minecraft.level.dimension()) {
				closeBridge();
			}
		}
	}

	/** Everything, bridge included (disconnect). */
	public static void clear() {
		closeBridge();
		clearRemotes();
	}

	private static void clearRemotes() {
		PortalViewRenderer.forgetRemotes();
		REMOTES.values().forEach(RemoteDimension::close);
		REMOTES.clear();
		publishRemoteLevels();
		links = List.of();
		PortalHiding.setLinks(links);
		trackedLevel = null;
	}

	private static RemoteDimension ensure(Minecraft minecraft, ResourceKey<Level> key) {
		RemoteDimension remote = REMOTES.get(key);
		if (remote == null) {
			remote = new RemoteDimension(minecraft, key);
			REMOTES.put(key, remote);
			publishRemoteLevels();
		}
		return remote;
	}

	private static void publishRemoteLevels() {
		Set<ClientLevel> levels = new HashSet<>();
		for (RemoteDimension remote : REMOTES.values()) {
			levels.add(remote.level);
		}
		if (bridge != null) {
			levels.add(bridge.level);
		}
		PortalHiding.setRemoteLevels(levels);
	}

	/** The player changed dimension (or left): everything remote is stale. */
	private static void syncLevel(Minecraft minecraft) {
		if (minecraft.level != trackedLevel) {
			clearRemotes();
			trackedLevel = minecraft.level;
		}
	}
}
