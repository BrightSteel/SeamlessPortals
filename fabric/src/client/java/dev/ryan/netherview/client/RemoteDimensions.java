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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
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
	/** The bridge's entities were copied into the new level: drop its own after {@link #BRIDGE_ENTITY_TICKS}. */
	private static boolean dropBridgeEntities;
	/** The new level's copies only show from the frame after the hand-off, so the bridge's cover it until then. */
	private static final int BRIDGE_ENTITY_TICKS = 2;
	private static final int BRIDGE_MIN_TICKS = 2;
	/** Longest the bridge is kept (3 s), in case the main renderer never quite matches it. */
	private static final int BRIDGE_MAX_TICKS = 60;
	/** Way-back chunks this near the window (in chunks) are lit and meshable on arrival. */
	private static final int ARRIVAL_NOW_RADIUS = 2;
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
	/**
	 * The view showing {@code link}'s far side: the one the renderer gave that portal, else the
	 * view of {@code key} centred nearest its destination. (Several portals into one dimension
	 * each have their own view, centred on their own destination.)
	 */
	public static RemoteDimension viewFor(PortalLink link, ResourceKey<Level> key) {
		RemoteDimension assigned = PortalViewRenderer.assignedView(link.min());
		if (assigned != null && assigned.key == key && !assigned.isClosed()) {
			return assigned;
		}
		List<RemoteDimension> views = VIEWS.get(key);
		if (views == null || views.isEmpty()) {
			return null;
		}
		ChunkPos dest = ChunkPos.containing(BlockPos.containing(PortalGeometry.center(link.dest())));
		RemoteDimension nearest = views.getFirst();
		long best = Long.MAX_VALUE;
		for (RemoteDimension view : views) {
			if (view.center() != null) {
				long dx = view.center().x() - dest.x();
				long dz = view.center().z() - dest.z();
				if (dx * dx + dz * dz < best) {
					best = dx * dx + dz * dz;
					nearest = view;
				}
			}
		}
		return nearest;
	}

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
		ChunkPos center = ChunkPos.containing(BlockPos.containing(link.geometry().destCenter()));
		remote.centerOn(center);
		// Nearest first, and the chunks right behind the window at once: the player may be
		// looking straight back through it, and the light queue would hold them for a while.
		List<ClientboundLevelChunkWithLightPacket> ordered = new ArrayList<>(chunks);
		ordered.sort(Comparator.comparingInt(chunk -> arrivalDistance(chunk, center)));
		for (ClientboundLevelChunkWithLightPacket chunk : ordered) {
			remote.acceptChunk(chunk, arrivalDistance(chunk, center) <= ARRIVAL_NOW_RADIUS);
		}
		links = List.of(link);
		PortalHiding.setLinks(links);
	}

	private static int arrivalDistance(ClientboundLevelChunkWithLightPacket chunk, ChunkPos center) {
		return Math.max(Math.abs(ClientVersioned.chunkX(chunk) - center.x()), Math.abs(ClientVersioned.chunkZ(chunk) - center.z()));
	}

	/**
	 * Keep {@code view} (one of {@code dimension}'s) alive as the bridge while the main renderer
	 * meshes the new level. {@code entitiesCopied}: its entities were added to the new level.
	 */
	static void adoptAsBridge(ResourceKey<Level> dimension, RemoteDimension view, boolean entitiesCopied) {
		List<RemoteDimension> views = VIEWS.remove(dimension);
		if (views == null || !views.remove(view)) {
			if (views != null) {
				views.forEach(RemoteDimension::close);
			}
			return;
		}
		closeBridge();
		bridge = view;
		bridgeTicks = 0;
		dropBridgeEntities = entitiesCopied;
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
			if (dropBridgeEntities && bridgeTicks >= BRIDGE_ENTITY_TICKS) {
				// The new level has them now and the server keeps those updated; this copy would
				// stop moving and show through as frozen doubles.
				dropBridgeEntities = false;
				List<Entity> stale = new ArrayList<>();
				bridge.level.entitiesForRendering().forEach(stale::add);
				for (Entity entity : stale) {
					bridge.level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
				}
			}
			// The bridge doubles the frame's cost, so it ends as soon as the main renderer draws
			// (nearly) as much as the bridge does: fade-in is off right after a hand-off, so
			// nothing is half-drawn. The compile queue alone says nothing early on: it's empty
			// until the new level's sections are found visible and scheduled.
			boolean caughtUp = bridgeTicks >= BRIDGE_MIN_TICKS
					&& ClientVersioned.isSectionCompiledAndVisible(minecraft.levelRenderer, ClientVersioned.mainCamera().blockPosition())
					&& ClientVersioned.mainRenderedSections() * 10 >= bridge.renderer.renderedSections() * 9
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
			// The bridge stands in for the level the player has just entered, so its portals
			// are hidden as the player's are (the way back included), not as a remote view's.
			if (view != bridge) {
				levels.add(view.level);
			}
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
