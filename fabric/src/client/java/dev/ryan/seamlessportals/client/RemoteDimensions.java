package dev.ryan.seamlessportals.client;

import dev.ryan.seamlessportals.compat.ClientVersioned;
import dev.ryan.seamlessportals.PortalGeometry;
import dev.ryan.seamlessportals.client.mixin.ClientPacketListenerAccessor;
import dev.ryan.seamlessportals.net.PortalLink;
import dev.ryan.seamlessportals.net.PortalLinksPayload;
import dev.ryan.seamlessportals.net.RemoteChunkPayload;
import dev.ryan.seamlessportals.net.RemoteEntitiesPayload;
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
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
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
	private static final Logger LOGGER = LoggerFactory.getLogger("seamlessportals");

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
	/** Remembered chunks loaded into a view per tick (see {@link OfflinePortals}). */
	private static final int SEED_CHUNKS_PER_TICK = 24;
	/**
	 * A view of the player's own dimension around a portal they're backing into, built ahead
	 * so it can become the window back on arrival already meshed; see {@link #prepareWayBack}.
	 */
	private static RemoteDimension prepared;
	/** Chunks of the player's level still to copy into {@link #prepared}, nearest first. */
	private static final ArrayDeque<ChunkPos> preparedQueue = new ArrayDeque<>();
	private static int preparedIdleTicks;
	/** Chunks copied into the prepared view each frame (copying them all at once would hitch). */
	private static final int PREPARED_CHUNKS_PER_FRAME = 8;
	/** Ticks the prepared view is kept once the player is no longer near a portal. */
	private static final int PREPARED_KEEP_TICKS = 100;
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

	/**
	 * A link plus the dimension it leads into, resolved once when the server sends it.
	 * {@code offline}: a trip recorded on a server without the server half (see {@link OfflinePortals}).
	 */
	public record Link(PortalLink link, PortalGeometry geometry, ResourceKey<Level> dest, boolean offline) {
		public Link(PortalLink link, PortalGeometry geometry, ResourceKey<Level> dest) {
			this(link, geometry, dest, false);
		}
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
		RemoteDimension assigned = PortalViewRenderer.assignedView(link.source());
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
		OfflinePortals.seed(view);
	}

	/** {@code view} has just lit the chunk at {@code x, z}: give it to sibling views that are missing it. */
	static void chunkLit(RemoteDimension view, int x, int z) {
		for (RemoteDimension sibling : VIEWS.getOrDefault(view.key, List.of())) {
			if (sibling != view && !sibling.isClosed() && sibling.lacksChunk(x, z)) {
				sibling.copyChunk(view, x, z);
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

	/** Whether {@code level} is a remote view's (not the player's). */
	public static boolean isRemoteLevel(ClientLevel level) {
		for (RemoteDimension view : published) {
			if (view.level == level) {
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
	 * next scan) and seed its remote level with the chunks and entities of the dimension just left.
	 */
	static RemoteDimension installArrival(PortalLink back, ResourceKey<Level> backDimension, List<ClientboundLevelChunkWithLightPacket> chunks,
			List<Packet<ClientGamePacketListener>> entities) {
		return installWayBack(back, backDimension, chunks, entities, false);
	}

	/**
	 * As {@link #installArrival}, for a portal within the player's own world: the other
	 * portals around stay linked (the level didn't change), and the way back gets a view of
	 * its own rather than taking one another window is using.
	 */
	static RemoteDimension installSameWorldArrival(PortalLink back, ResourceKey<Level> dimension, List<ClientboundLevelChunkWithLightPacket> chunks,
			List<Packet<ClientGamePacketListener>> entities) {
		return installWayBack(back, dimension, chunks, entities, true);
	}

	private static RemoteDimension installWayBack(PortalLink back, ResourceKey<Level> backDimension, List<ClientboundLevelChunkWithLightPacket> chunks,
			List<Packet<ClientGamePacketListener>> entities, boolean sameWorld) {
		Minecraft minecraft = Minecraft.getInstance();
		syncLevel(minecraft);
		Link link = new Link(back, PortalGeometry.of(back), backDimension);
		TYPES.put(backDimension, back.destDimensionType());
		ChunkPos center = ChunkPos.containing(BlockPos.containing(link.geometry().destCenter()));
		RemoteDimension remote;
		if (prepared != null && prepared.key == backDimension && center.equals(prepared.center())) {
			// Built while the player backed in: already meshed, it only lacks what wasn't copied yet.
			remote = prepared;
			prepared = null;
			preparedQueue.clear();
			VIEWS.computeIfAbsent(backDimension, key -> new ArrayList<>()).addFirst(remote);
			publishRemoteLevels();
		} else {
			remote = view(backDimension, sameWorld ? VIEWS.getOrDefault(backDimension, List.of()).size() : 0);
			remote.centerOn(center);
		}
		// Nearest first, and the chunks right behind the window at once: the player may be
		// looking straight back through it, and the light queue would hold them for a while.
		List<ClientboundLevelChunkWithLightPacket> ordered = new ArrayList<>(chunks);
		ordered.sort(Comparator.comparingInt(chunk -> arrivalDistance(chunk, center)));
		for (ClientboundLevelChunkWithLightPacket chunk : ordered) {
			if (remote.level.getChunkSource().getChunk(ClientVersioned.chunkX(chunk), ClientVersioned.chunkZ(chunk), false) == null) {
				remote.acceptChunk(chunk, arrivalDistance(chunk, center) <= ARRIVAL_NOW_RADIUS);
			}
		}
		replay(remote, entities);
		if (sameWorld) {
			List<Link> kept = new ArrayList<>();
			for (Link other : links) {
				if (!other.link().min().equals(back.min())) {
					kept.add(other);
				}
			}
			kept.add(link);
			links = List.copyOf(kept);
		} else {
			links = List.of(link);
		}
		PortalHiding.setLinks(links);
		return remote;
	}

	/** Handle entity packets as if the server had sent them to {@code view}'s level. */
	private static void replay(RemoteDimension view, List<Packet<ClientGamePacketListener>> packets) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null || packets.isEmpty()) {
			return;
		}
		ClientPacketListenerAccessor listener = (ClientPacketListenerAccessor) connection;
		ClientLevel playerLevel = listener.seamlessportals$getLevel();
		listener.seamlessportals$setLevel(view.level);
		try {
			for (Packet<ClientGamePacketListener> packet : packets) {
				try {
					packet.handle(connection);
				} catch (RuntimeException e) {
					LOGGER.debug("Way-back entity packet failed for {}: {}", view.key.identifier(), e.toString());
				}
			}
		} finally {
			listener.seamlessportals$setLevel(playerLevel);
		}
	}

	/**
	 * The player is near {@code link}: keep a copy of their own level around it, filled a few
	 * chunks a frame, for {@link PortalViewRenderer} to mesh from their camera. If they go
	 * through, the arrival takes it as the way back. Returns it, or null while the bridge of
	 * a trip through this very portal is still up (it becomes this view when it retires).
	 */
	static RemoteDimension prepareWayBack(Link link) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) {
			return null;
		}
		preparedIdleTicks = 0;
		ChunkPos center = ChunkPos.containing(BlockPos.containing(link.geometry().sourceCenter()));
		if (bridge != null && bridge.key == level.dimension() && center.equals(bridge.center())) {
			return null; // just arrived through this portal: the bridge becomes this view when it retires
		}
		if (prepared != null && prepared.key == level.dimension() && !center.equals(prepared.center())) {
			// Another portal: move the view rather than build a new one (its first render stalls).
			prepared.centerOn(center);
			queuePrepared(center);
		}
		if (prepared == null || prepared.key != level.dimension()) {
			closePrepared();
			String type = level.dimensionTypeRegistration().unwrapKey().map(key -> key.identifier().toString()).orElse("minecraft:overworld");
			prepared = new RemoteDimension(minecraft, level.dimension(), type);
			prepared.centerOn(center);
			queuePrepared(center);
			publishRemoteLevels();
		}
		for (int i = 0; i < PREPARED_CHUNKS_PER_FRAME && !preparedQueue.isEmpty(); i++) {
			ChunkPos pos = preparedQueue.poll();
			if (prepared.level.getChunkSource().getChunk(pos.x(), pos.z(), false) != null) {
				continue; // kept from before it moved
			}
			LevelChunk chunk = level.getChunkSource().getChunk(pos.x(), pos.z(), false);
			if (chunk == null) {
				preparedQueue.add(pos); // not loaded yet (just joined or arrived): try again later
				continue;
			}
			int ring = Math.max(Math.abs(pos.x() - center.x()), Math.abs(pos.z() - center.z()));
			prepared.acceptChunk(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null), ring <= ARRIVAL_NOW_RADIUS);
		}
		return prepared;
	}

	/** The chunks around {@code center} to copy into the prepared view, nearest first. */
	private static void queuePrepared(ChunkPos center) {
		preparedQueue.clear();
		int radius = RemoteDimension.chunkRadius();
		for (int ring = 0; ring <= radius; ring++) {
			for (int x = center.x() - ring; x <= center.x() + ring; x++) {
				for (int z = center.z() - ring; z <= center.z() + ring; z++) {
					if (Math.max(Math.abs(x - center.x()), Math.abs(z - center.z())) == ring) {
						preparedQueue.add(new ChunkPos(x, z));
					}
				}
			}
		}
	}

	/** Whether the prepared view of {@code dimension} around {@code center} already has chunk x, z. */
	static boolean preparedHas(ResourceKey<Level> dimension, ChunkPos center, int x, int z) {
		return prepared != null && prepared.key == dimension && center.equals(prepared.center())
				&& prepared.level.getChunkSource().getChunk(x, z, false) != null;
	}

	private static void closePrepared() {
		preparedQueue.clear();
		if (prepared != null) {
			prepared.close();
			prepared = null;
			publishRemoteLevels();
		}
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
		// A bridge still up from the trip before is a meshed copy of the world now being left,
		// around the portal just used: the way back's view, if the player turned straight round.
		if (bridge != null) {
			retireBridge();
		}
		bridge = view;
		bridgeTicks = 0;
		dropBridgeEntities = entitiesCopied;
		PortalViewRenderer.forgetRemotes();
		views.forEach(RemoteDimension::close);
		publishRemoteLevels();
	}

	/**
	 * The bridge is done. It is a meshed copy of the player's new level around the portal they
	 * came out of, which is what {@link #prepareWayBack} would build (at a cost, a frame's
	 * stall) for a trip back through it: keep it as that instead of closing it.
	 */
	private static void retireBridge() {
		// Nothing is destroyed, so only the bridge pass is forgotten: a full reset would also
		// lose a crossing in progress (the player may be walking straight back).
		PortalViewRenderer.forgetBridge();
		if (dropBridgeEntities) {
			dropBridgeEntities();
		}
		closePrepared();
		prepared = bridge;
		preparedIdleTicks = 0;
		bridge = null;
		publishRemoteLevels();
		// As the bridge it hid portals as the player's level does; now it's a remote view, which
		// hides others (a way back's destination among them). Re-mesh them under the new rules
		// before it's shown as one.
		for (BlockPos pos : PortalHiding.allHidden()) {
			prepared.renderer.setBlockDirty(pos);
		}
	}

	/** The new level has the bridge's entities and the server keeps those updated; its own copies would stop moving and show through as frozen doubles. */
	private static void dropBridgeEntities() {
		dropBridgeEntities = false;
		List<Entity> stale = new ArrayList<>();
		bridge.level.entitiesForRendering().forEach(stale::add);
		for (Entity entity : stale) {
			bridge.level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
		}
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
		OfflinePortals.pluginSeen();
		setLinks(payload.links(), false);
	}

	/** The trips recorded through portals near the player, on a server without the server half. */
	static void setOfflineLinks(List<PortalLink> recorded) {
		setLinks(recorded, true);
	}

	private static void setLinks(List<PortalLink> portals, boolean offline) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		syncLevel(minecraft);
		List<Link> resolved = new ArrayList<>();
		for (PortalLink link : portals) {
			ResourceKey<Level> dest = ResourceKey.create(Registries.DIMENSION, Identifier.parse(link.destDimension()));
			int maxSize = ClientConfig.get().maxPortalSize;
			if (link.width() > maxSize || link.height() > maxSize) {
				continue; // too big for this client: left as a vanilla portal
			}
			TYPES.put(dest, link.destDimensionType());
			resolved.add(new Link(link, PortalGeometry.of(link), dest, offline));
		}
		links = List.copyOf(resolved);
		PortalHiding.setLinks(links);
		if (!resolved.isEmpty()) {
			// The primary view follows the nearest portal until the renderer assigns views.
			Link nearest = resolved.getFirst();
			RemoteDimension primary = view(nearest.dest(), 0);
			if (primary.center() == null) {
				recenter(primary, ChunkPos.containing(BlockPos.containing(nearest.geometry().destCenter())));
			}
		}
	}

	public static void onChunk(RemoteChunkPayload payload) {
		OfflinePortals.pluginSeen();
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
		OfflinePortals.pluginSeen();
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
		ClientLevel playerLevel = listener.seamlessportals$getLevel();
		try {
			List<Integer> added = new ArrayList<>();
			for (Packet<ClientGamePacketListener> packet : packets) {
				if (packet instanceof ClientboundAddEntityPacket add) {
					added.add(add.getId());
				}
			}
			for (RemoteDimension view : VIEWS.get(dest)) {
				listener.seamlessportals$setLevel(view.level);
				view.entitiesUnticked |= !added.isEmpty();
				for (Packet<ClientGamePacketListener> packet : packets) {
					try {
						packet.handle(connection);
					} catch (RuntimeException e) {
						LOGGER.debug("Remote entity packet failed for {}: {}", dest.identifier(), e.toString());
					}
				}
				// A display shows nothing, and takes no transformation to interpolate from, until
				// its first tick: ticked now, as it arrives. Otherwise a change arriving before
				// that tick (a door that starts to swing as it's sent) is where it first shows,
				// already swung.
				for (int id : added) {
					if (view.level.getEntity(id) instanceof Display display) {
						display.tick();
					}
				}
			}
		} finally {
			listener.seamlessportals$setLevel(playerLevel);
		}
	}

	public static void tick(Minecraft minecraft) {
		syncLevel(minecraft);
		if (minecraft.level == null) {
			return;
		}
		OfflinePortals.tick(minecraft);
		ClientPacketListener connection = minecraft.getConnection();
		for (RemoteDimension view : allViews()) {
			if (view != bridge) {
				view.tick(minecraft.level);
			}
			if (connection != null) {
				view.seedFromCache(connection, SEED_CHUNKS_PER_TICK);
			}
		}
		RemoteClocks.tick(minecraft.level.getGameTime());
		if (prepared != null && ++preparedIdleTicks > PREPARED_KEEP_TICKS) {
			closePrepared();
		}
		if (bridge != null) {
			bridge.tick(minecraft.level);
			bridgeTicks++;
			if (dropBridgeEntities && bridgeTicks >= BRIDGE_ENTITY_TICKS) {
				dropBridgeEntities();
			}
			// The bridge doubles the frame's cost, so it ends as soon as the main renderer draws
			// (nearly) as much as the bridge does: fade-in is off right after a hand-off, so
			// nothing is half-drawn. The compile queue alone says nothing early on: it's empty
			// until the new level's sections are found visible and scheduled.
			boolean caughtUp = bridgeTicks >= BRIDGE_MIN_TICKS
					&& ClientVersioned.isSectionCompiledAndVisible(minecraft.levelRenderer, ClientVersioned.mainCamera().blockPosition())
					&& ClientVersioned.mainRenderedSections() * 10 >= bridge.renderer.renderedSections() * 9
					&& minecraft.levelRenderer.hasRenderedAllSections();
			if (bridge.level.dimension() != minecraft.level.dimension()) {
				closeBridge();
			} else if (caughtUp || bridgeTicks >= BRIDGE_MAX_TICKS) {
				retireBridge();
			}
		}
	}

	/** Everything, bridge included (disconnect). */
	public static void clear() {
		OfflinePortals.clear();
		RemoteClocks.clear();
		closeBridge();
		closePrepared();
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
		if (prepared != null) {
			all.add(prepared);
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

	/** Which dimensions are superflat, as seen while the player was in them (see {@link #isFlat(ResourceKey)}). */
	private static final Map<ResourceKey<Level>, Boolean> FLAT = new HashMap<>();

	/** The player changed dimension (or left): everything remote is stale. */
	private static void syncLevel(Minecraft minecraft) {
		if (minecraft.level != trackedLevel) {
			if (minecraft.level == null) {
				// Left the server: the bridge and the view readied for the way back go too
				// (across a dimension change they're what keeps the crossing seamless).
				closeBridge();
				closePrepared();
			}
			clearRemotes();
			trackedLevel = minecraft.level;
			if (trackedLevel != null) {
				FLAT.put(trackedLevel.dimension(), isFlat(trackedLevel));
			}
		}
	}

	/**
	 * Whether the dimension {@code key} is a superflat world, as the player's level was when they
	 * were last there: its sky's horizon is at the bottom, not at sea level. A view made with the
	 * wrong one darkens the sky as if the camera were deep underground.
	 */
	static boolean isFlat(ResourceKey<Level> key) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null && minecraft.level.dimension() == key) {
			FLAT.put(key, isFlat(minecraft.level));
		}
		return FLAT.getOrDefault(key, false);
	}

	private static boolean isFlat(ClientLevel level) {
		return level.getLevelData().getHorizonHeight(level) <= level.getMinY();
	}
}
