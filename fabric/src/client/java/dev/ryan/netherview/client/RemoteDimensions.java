package dev.ryan.netherview.client;

import dev.ryan.netherview.compat.ClientVersioned;
import dev.ryan.netherview.PortalGeometry;
import dev.ryan.netherview.client.mixin.ClientPacketListenerAccessor;
import dev.ryan.netherview.net.PortalLink;
import dev.ryan.netherview.net.PortalLinksPayload;
import dev.ryan.netherview.net.RemoteChunkPayload;
import dev.ryan.netherview.net.RemoteEntitiesPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-side state: the portals around the player and the remote dimensions they look into.
 *
 * <p>Each remote dimension has one or more <em>views</em>: complete client copies of that
 * dimension (level, renderer, camera, offscreen target). Every live portal needs its own,
 * because a renderer's visibility state and meshes follow its camera; sharing one between
 * portals would rebuild them every pass. Chunk and entity updates go to every view of the
 * dimension; view 0 is the primary, used for crossing and the arrival hand-off.</p>
 */
public final class RemoteDimensions {
	private static final Logger LOGGER = LoggerFactory.getLogger("netherview");

	private static final Map<ResourceKey<Level>, List<RemoteDimension>> VIEWS = new HashMap<>();
	/** Each destination world's dimension type, as the server named it in links. */
	private static final Map<ResourceKey<Level>, String> TYPES = new HashMap<>();
	private static List<Link> links = List.of();
	private static ClientLevel trackedLevel;
	/** The remote that showed the dimension the player just walked into; see {@link PortalViewRenderer}. */
	private static RemoteDimension bridge;
	/** Every view, bridge included, as last published: mixins ask from worker threads too (26.1's culling graph). */
	private static volatile List<RemoteDimension> published = List.of();
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

	/** The primary view of {@code key}, if that dimension has been seen. */
	public static RemoteDimension get(ResourceKey<Level> key) {
		List<RemoteDimension> views = VIEWS.get(key);
		return views == null || views.isEmpty() ? null : views.getFirst();
	}

	/**
	 * View {@code index} of {@code key}, creating views up to it. A new view starts with the
	 * primary's chunks, so it has terrain before the server sends anything more.
	 */
	static RemoteDimension view(ResourceKey<Level> key, int index) {
		Minecraft minecraft = Minecraft.getInstance();
		List<RemoteDimension> views = VIEWS.computeIfAbsent(key, k -> new ArrayList<>());
		while (views.size() <= index) {
			views.add(new RemoteDimension(minecraft, key, TYPES.get(key)));
			publishRemoteLevels();
		}
		return views.get(index);
	}

	/** Point a view at a new destination and give it whatever chunks its siblings have there. */
	static void recenter(RemoteDimension view, ChunkPos center) {
		if (center.equals(view.center())) {
			return;
		}
		view.centerOn(center);
		for (RemoteDimension sibling : VIEWS.getOrDefault(view.key, List.of())) {
			if (sibling != view) {
				view.seedFrom(sibling);
			}
		}
	}

	/** Re-mesh these positions in every remote view (portal hiding changed there). */
	static void markBlocksDirty(Set<BlockPos> positions) {
		if (positions.isEmpty()) {
			return;
		}
		for (RemoteDimension view : allViews()) {
			for (BlockPos pos : positions) {
				view.renderer.setBlockDirty(pos);
			}
		}
	}

	/** Whether a level renderer or extractor (as a mixin sees it) is a remote view's. */
	public static boolean isRemote(Object rendererPart) {
		for (RemoteDimension view : published) {
			if (view.renderer.owns(rendererPart)) {
				return true;
			}
		}
		return false;
	}

	public static boolean isRemoteGraph(SectionOcclusionGraph graph) {
		for (RemoteDimension view : published) {
			if (view.renderer.sectionOcclusionGraph() == graph) {
				return true;
			}
		}
		return false;
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
		TYPES.put(backDimension, back.destDimensionType());
		RemoteDimension remote = view(backDimension, 0);
		remote.centerOn(ChunkPos.containing(BlockPos.containing(link.geometry().destCenter())));
		for (ClientboundLevelChunkWithLightPacket chunk : chunks) {
			remote.acceptChunk(chunk);
		}
		links = List.of(link);
		PortalHiding.setLinks(links);
	}

	/** Keep the primary view of {@code dimension} alive as the bridge while the main renderer meshes the new level. */
	static void adoptAsBridge(ResourceKey<Level> dimension) {
		List<RemoteDimension> views = VIEWS.remove(dimension);
		if (views == null || views.isEmpty()) {
			return;
		}
		closeBridge();
		bridge = views.removeFirst();
		bridgeTicks = 0;
		PortalViewRenderer.forgetRemotes();
		views.forEach(RemoteDimension::close);
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

	public static void onLinks(PortalLinksPayload payload) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		syncLevel(minecraft);
		List<Link> resolved = new ArrayList<>();
		for (PortalLink link : payload.links()) {
			ResourceKey<Level> dest = ResourceKey.create(Registries.DIMENSION, Identifier.parse(link.destDimension()));
			int maxSize = ClientConfig.get().maxPortalSize;
			if (link.width() > maxSize || link.height() > maxSize) {
				continue; // too big for this client: left as a vanilla portal
			}
			TYPES.put(dest, link.destDimensionType());
			resolved.add(new Link(link, PortalGeometry.of(link), dest));
		}
		links = List.copyOf(resolved);
		PortalHiding.setLinks(links);
		if (!resolved.isEmpty()) {
			// The primary view follows the nearest portal until the renderer assigns views.
			Link nearest = resolved.getFirst();
			RemoteDimension primary = view(nearest.dest(), 0);
			if (primary.center() == null) {
				primary.centerOn(ChunkPos.containing(BlockPos.containing(nearest.geometry().destCenter())));
			}
		}
	}

	public static void onChunk(RemoteChunkPayload payload) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.getConnection() == null) {
			return;
		}
		syncLevel(minecraft);
		ResourceKey<Level> dest = ResourceKey.create(Registries.DIMENSION, Identifier.parse(payload.dimension()));
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload.chunkPacket()), minecraft.getConnection().registryAccess());
		try {
			ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buf);
			view(dest, 0);
			for (RemoteDimension view : VIEWS.get(dest)) {
				view.acceptChunk(packet);
			}
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
		view(dest, 0);
		if (entityCodecRegistries != connection.registryAccess()) {
			entityCodec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(connection.registryAccess())).codec();
			entityCodecRegistries = connection.registryAccess();
		}
		List<Packet<ClientGamePacketListener>> packets = new ArrayList<>();
		for (byte[] bytes : payload.packets()) {
			ByteBuf buf = Unpooled.wrappedBuffer(bytes);
			try {
				@SuppressWarnings("unchecked")
				Packet<ClientGamePacketListener> packet = (Packet<ClientGamePacketListener>) entityCodec.decode(buf);
				packets.add(packet);
			} catch (RuntimeException e) {
				LOGGER.debug("Dropping remote entity packet for {}: {}", dest.identifier(), e.toString());
			} finally {
				buf.release();
			}
		}
		ClientPacketListenerAccessor listener = (ClientPacketListenerAccessor) connection;
		ClientLevel playerLevel = listener.netherview$getLevel();
		try {
			for (RemoteDimension view : VIEWS.get(dest)) {
				listener.netherview$setLevel(view.level);
				for (Packet<ClientGamePacketListener> packet : packets) {
					try {
						packet.handle(connection);
					} catch (RuntimeException e) {
						LOGGER.debug("Remote entity packet failed for {}: {}", dest.identifier(), e.toString());
					}
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
		for (RemoteDimension view : allViews()) {
			if (view != bridge) {
				view.tick(minecraft.level);
			}
		}
		if (bridge != null) {
			bridge.tick(minecraft.level);
			bridgeTicks++;
			// The bridge doubles the frame's cost, so it ends as soon as the main renderer has
			// meshed what it was given (fade-in is off right after a hand-off, so nothing is half-drawn).
			boolean caughtUp = bridgeTicks >= BRIDGE_MIN_TICKS
					&& ClientVersioned.isSectionCompiledAndVisible(minecraft.levelRenderer, ClientVersioned.mainCamera().blockPosition())
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
		VIEWS.values().forEach(views -> views.forEach(RemoteDimension::close));
		VIEWS.clear();
		TYPES.clear();
		publishRemoteLevels();
		links = List.of();
		PortalHiding.setLinks(links);
		trackedLevel = null;
	}

	private static List<RemoteDimension> allViews() {
		List<RemoteDimension> all = new ArrayList<>();
		VIEWS.values().forEach(all::addAll);
		if (bridge != null) {
			all.add(bridge);
		}
		return all;
	}

	private static void publishRemoteLevels() {
		List<RemoteDimension> all = allViews();
		Set<ClientLevel> levels = new HashSet<>();
		for (RemoteDimension view : all) {
			levels.add(view.level);
		}
		published = List.copyOf(all);
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
