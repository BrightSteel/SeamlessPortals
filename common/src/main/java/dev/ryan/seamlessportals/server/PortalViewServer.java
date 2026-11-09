package dev.ryan.seamlessportals.server;

import dev.ryan.seamlessportals.PortalGeometry;
import dev.ryan.seamlessportals.VanillaPortals;
import dev.ryan.seamlessportals.net.PortalEnd;
import dev.ryan.seamlessportals.net.PortalFill;
import dev.ryan.seamlessportals.net.PortalLink;
import dev.ryan.seamlessportals.net.PortalLinksPayload;
import dev.ryan.seamlessportals.net.PortalTravelPayload;
import dev.ryan.seamlessportals.net.RemoteChunkPayload;
import dev.ryan.seamlessportals.net.RemoteEntitiesPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Finds the portals around each player, works out where each one leads (the same way
 * vanilla does when you go through), and streams the destination's chunks and entities to
 * the client so it can render the other side.
 *
 * <p>Two kinds of portal:</p>
 * <ul>
 *   <li><b>Nether portals</b> lead to the nearest portal vanilla's search finds on the other
 *       side (or where it would build one). Players with the client mod cross them by
 *       walking through the plane.</li>
 *   <li><b>End portals</b> lie flat. The stronghold portal leads to the End spawn platform;
 *       the End's exit portal leads to the player's respawn point. Players fall through the
 *       stronghold portal seamlessly; the exit portal keeps vanilla travel (credits, respawn
 *       rules), but its view is still shown.</li>
 * </ul>
 */
public final class PortalViewServer {
	private static final int SCAN_INTERVAL_TICKS = 10;
	/** How far (chunks) around a destination's own chunk its chunks are all sent at once: its neighbours, with theirs. */
	private static final int FIRST_CHUNKS_RADIUS = 2;
	/** Whether the next tick scans, whatever the interval: custom portals changed (a doorway opened, say). */
	private static volatile boolean rescan;
	/**
	 * How often each already-sent chunk is re-encoded and re-sent if it changed. The check is
	 * spread over the scans in that time (a slice per scan), so it never lands in one tick.
	 */
	private static final int RESEND_CHECK_TICKS = 100;
	private static final int FOUND_DEST_CACHE_TICKS = 1200;
	private static final int VIRTUAL_DEST_CACHE_TICKS = 200;
	/** Largest eye movement in one tick still treated as walking (or flying) rather than a teleport. */
	private static final double MAX_STEP = 4.0;
	/**
	 * How far behind the server a client sees a vehicle the server drives (ticks): minecarts
	 * send their position every few ticks and the client eases between them. Half the ping
	 * comes on top.
	 */
	private static final int FOLLOW_TICKS = 4;
	private static final int MAX_FOLLOW_TICKS = 20;
	/** Where vanilla stands a player arriving in the End: on the platform it builds at END_SPAWN_POINT. */
	private static final Vec3 END_ARRIVAL = Vec3.atBottomCenterOf(ServerLevel.END_SPAWN_POINT).subtract(0.0, 1.0, 0.0);
	/**
	 * An end portal's far side is a flat opening this far above where the player arrives, so
	 * falling through the portal carries on into a short drop onto the platform.
	 */

	private static final Map<UUID, PlayerState> PLAYERS = new HashMap<>();
	private static final Map<DestKey, CachedDest> DESTINATIONS = new HashMap<>();

	private static PayloadSender sender;
	private static ServerConfig config = ServerConfig.DEFAULTS;

	private static final boolean PERF = Boolean.getBoolean("seamlessportals.perf");
	private static long perfNanos;
	private static long perfMaxNanos;
	private static int perfTicks;

	private PortalViewServer() {
	}

	static ServerConfig config() {
		return config;
	}

	/** Whether the player's client has the mod (it takes our payloads). */
	public static boolean hasMod(ServerPlayer player) {
		return sender != null && sender.canReceive(player);
	}

	/** Called once by the platform (Fabric mod or Paper plugin) before the first tick. */
	public static void init(PayloadSender payloadSender, ServerConfig serverConfig) {
		sender = payloadSender;
		config = serverConfig;
	}

	/**
	 * A player is about to be moved through a portal: tell their client (if modded) which pair,
	 * so it can hand its preloaded view over, and drop the links of the side being left.
	 */
	static void announceTravel(ServerPlayer player, PortalLink link) {
		if (!sender.canReceive(player)) {
			return;
		}
		PlayerState state = PLAYERS.get(player.getUUID());
		if (state != null) {
			state.links = List.of();
			state.lastEye = null;
		}
		sender.send(player, new PortalTravelPayload(link), PortalTravelPayload.STREAM_CODEC);
	}

	public static void tick(MinecraftServer server) {
		if (!PERF) {
			tickPlayers(server);
			return;
		}
		long start = System.nanoTime();
		tickPlayers(server);
		long took = System.nanoTime() - start;
		perfNanos += took;
		perfMaxNanos = Math.max(perfMaxNanos, took);
		if (++perfTicks == 100) {
			org.slf4j.LoggerFactory.getLogger("seamlessportals").info("[perf] server: {} us/tick avg, {} us max, {} players tracked",
					perfNanos / perfTicks / 1000, perfMaxNanos / 1000, PLAYERS.size());
			perfNanos = 0;
			perfMaxNanos = 0;
			perfTicks = 0;
		}
	}

	/**
	 * Custom portals changed: scan on the next tick, not at the next interval, so a portal made
	 * or moved shows its far side as soon as it can (a plugin may be timing something to it).
	 */
	public static void rescan() {
		rescan = true;
	}

	/**
	 * Chunks a block's changed in (see {@link #changed}), as "dimension chunk" keys: re-sent on
	 * the next scan to whoever's been sent them, ahead of everything else. Those this scan's
	 * taken are in {@link #changing}.
	 */
	private static final Set<String> CHANGED = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static Set<String> changing = Set.of();

	/** A block changed in that chunk of {@code dimension}: views showing it are sent it on the next tick. */
	public static void changed(String dimension, int chunkX, int chunkZ) {
		CHANGED.add(dimension + " " + new ChunkPos(chunkX, chunkZ).pack());
		rescan = true;
	}

	public static void forget(ServerPlayer player) {
		PLAYERS.remove(player.getUUID());
		PortalWands.forget(player.getUUID());
	}

	private static void tickPlayers(MinecraftServer server) {
		CustomPortalTravel.tick(server);
		StarfieldPanels.tick(server);
		RandomDestinations.tick(server);
		PortalWands.tick(server);
		if (server.getTickCount() % CustomPortalBlocks.REPAIR_INTERVAL == 0) {
			CustomPortalBlocks.repair(server);
		}
		int now = server.getTickCount();
		boolean scan = now % SCAN_INTERVAL_TICKS == 0 || rescan;
		rescan = false;
		if (scan) {
			DESTINATIONS.values().removeIf(cached -> cached.expiresAt < now);
			changing = Set.copyOf(CHANGED);
			CHANGED.removeAll(changing);
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!sender.canReceive(player)) {
				continue; // without the client mod: nothing to stream, and vanilla travel
			}
			// (just arrived, at once: until its portals are known, walking back through one goes unseen)
			PlayerState state = PLAYERS.get(player.getUUID());
			boolean arrived = state != null && player.level().dimension() != state.streamedFrom;
			boolean synced = (scan || arrived) && update(server, player, now);
			if (travelThroughPortal(server, player)) {
				continue;
			}
			if (!synced) {
				syncEntities(server, player, now);
			}
		}
	}

	private enum Kind {
		NETHER,
		END
	}

	// ── travel ──────────────────────────────────────────────────────────────

	/** Whether this player's travel through the portal block at {@code pos} is handled here instead of by vanilla. */
	public static boolean handlesTravel(ServerPlayer player, BlockPos pos) {
		PlayerState state = PLAYERS.get(player.getUUID());
		if (state == null || !config.seamlessTravel()) {
			return false;
		}
		Vec3 center = Vec3.atCenterOf(pos);
		for (PortalLink link : state.links) {
			if (!link.source().fill().blockless() && seamless(player.level(), link) && PortalGeometry.box(link.source()).contains(center)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether {@code entity}'s travel through the portal block at {@code pos} is handled here: a
	 * player's who sees through it, or a vehicle's carrying one (vanilla would send a vehicle on
	 * touch, before its rider reaches the plane).
	 */
	public static boolean handlesTravel(Entity entity, BlockPos pos) {
		if (entity instanceof ServerPlayer player) {
			return handlesTravel(player, pos);
		}
		for (Entity passenger : entity.getIndirectPassengers()) {
			if (passenger instanceof ServerPlayer player && handlesTravel(player, pos)) {
				return true;
			}
		}
		return false;
	}

	/** Whether the player is touching any portal whose travel is handled here (for platforms without a per-block hook). */
	public static boolean handlesTravel(ServerPlayer player) {
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(player.getBoundingBox().getMinPosition()), BlockPos.containing(player.getBoundingBox().getMaxPosition()))) {
			if (handlesTravel(player, pos)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Nether portals and the way into the End are crossed seamlessly. The End's exit portal
	 * keeps vanilla travel: the first trip shows the credits instead, and where you land
	 * follows vanilla's respawn rules.
	 */
	private static boolean seamless(Level level, PortalLink link) {
		return link.source().facing().getAxis() != Direction.Axis.Y || level.dimension() != Level.END;
	}

	/**
	 * Every tick: if the player passed through a linked portal's plane since last tick (their
	 * eye for a nether portal, their feet for an end portal, which you fall into), move them to
	 * the matching point beyond the destination, with the same rigid transform the client uses
	 * to draw the view, so going through is continuous. Velocity and look direction carry
	 * through (turned 90° when nether portal frames face differently).
	 */
	private static boolean travelThroughPortal(MinecraftServer server, ServerPlayer player) {
		PlayerState state = PLAYERS.get(player.getUUID());
		if (state == null || !config.seamlessTravel()) {
			return false;
		}
		ServerLevel level = player.level();
		// Riding, the vehicle goes through with the player on it: what falls into an end portal is its feet.
		Entity root = player.getRootVehicle();
		Vec3 eye = player.getEyePosition();
		Vec3 feet = root.position();
		Vec3 lastEye = state.lastEye;
		Vec3 lastFeet = state.lastFeet;
		boolean sameLevel = state.lastDimension == level.dimension();
		state.lastEye = eye;
		state.lastFeet = feet;
		state.lastDimension = level.dimension();
		PortalLink waiting = state.waitingCrossing;
		if (waiting != null) {
			// A crossing the client is still catching up with (see below): go once it has, if the
			// vehicle hasn't backed out of the portal meanwhile.
			if (!sameLevel || root == player || --state.waitingTicks > 0) {
				state.waitingCrossing = sameLevel && root != player ? waiting : null;
				return false;
			}
			state.waitingCrossing = null;
			PortalGeometry geometry = PortalGeometry.of(waiting);
			return side(geometry, geometry.horizontal() ? feet : eye) * state.waitingSide > 0 && travel(server, player, level, root, waiting, geometry);
		}
		// A jump of more than a few blocks in one tick is a teleport or respawn, not a step
		// through a portal (the straight line between the two spots could cross one).
		if (lastEye == null || !sameLevel || state.links.isEmpty() || player.isSleeping()
				|| lastEye.distanceToSqr(eye) > MAX_STEP * MAX_STEP) {
			return false;
		}
		for (PortalLink link : state.links) {
			PortalGeometry geometry = PortalGeometry.of(link);
			boolean crossed = geometry.horizontal() ? geometry.entersSource(lastFeet, feet) : geometry.entersSource(lastEye, eye);
			if (!crossed || !seamless(level, link) || !vanillaAt(level, link)) {
				continue; // custom portals are travelled by CustomPortalTravel, for everyone
			}
			if (root != player && root.getControllingPassenger() != player) {
				// A vehicle the server drives (a minecart): the client only follows its moves, a
				// few ticks behind, and would see itself set down short of the far portal.
				state.waitingCrossing = link;
				state.waitingTicks = Math.min(MAX_FOLLOW_TICKS, FOLLOW_TICKS + player.connection.latency() / 100);
				state.waitingSide = Math.signum(side(geometry, geometry.horizontal() ? feet : eye));
				return false;
			}
			return travel(server, player, level, root, link, geometry);
		}
		return false;
	}

	/**
	 * Whether {@code link} is a vanilla portal still standing: one of its blocks at its corner,
	 * and no custom portal there. Not a custom portal that owns no blocks (a window, a
	 * starfield), nor a link to one just removed: travelled as a nether portal, vanilla's search
	 * would send the player to whatever portal is near the same spot (and build one if none is).
	 */
	private static boolean vanillaAt(Level level, PortalLink link) {
		BlockState state = level.getBlockState(link.min());
		return (state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL))
				&& CustomPortals.at(level, link.min()) == null && !CustomPortals.opensAt(level, link.min());
	}

	/** Which side of a portal's plane {@code point} is on (signed distance from it). */
	private static double side(PortalGeometry geometry, Vec3 point) {
		return point.subtract(geometry.sourceCenter()).dot(geometry.sourceNormal());
	}

	/**
	 * Move the player (or the vehicle they ride, with everyone on it, and whatever they lead)
	 * through {@code link}. Each modded rider is told the pair first, and their links, which
	 * belong to the dimension being left, dropped; the next scan sends the new ones.
	 */
	private static boolean travel(MinecraftServer server, ServerPlayer player, ServerLevel level, Entity root, PortalLink link, PortalGeometry geometry) {
		ServerLevel dest = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(link.destDimension())));
		if (dest == null) {
			return false;
		}
		PortalLink target = geometry.horizontal() ? enterEnd(dest, link) : vanillaNetherDestination(level, dest, link, player);
		if (target == null) {
			return false;
		}
		PortalGeometry through = PortalGeometry.of(target);
		return TravelCompanions.travel(dest, root, null, target, through, through.toDest(root.position()),
				TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)) != null;
	}

	/**
	 * The portal vanilla would send this player to, found the way vanilla does it: from the
	 * player's exact position (not the portal's centre, which is all the view uses), so portal
	 * hubs and linking tricks behave exactly as without the mod. Builds a portal if vanilla
	 * would. The player is then placed relative to that portal with the view's transform.
	 */
	private static PortalLink vanillaNetherDestination(ServerLevel level, ServerLevel dest, PortalLink link, ServerPlayer player) {
		double scale = DimensionType.getTeleportationScale(level.dimensionType(), dest.dimensionType());
		WorldBorder border = dest.getWorldBorder();
		BlockPos approx = border.clampToBounds(player.getX() * scale, player.getY(), player.getZ() * scale);
		Optional<BlockPos> found = dest.getPortalForcer().findClosestPortalPosition(approx, dest.dimension() == Level.NETHER, border);
		BlockUtil.FoundRectangle rect;
		Direction.Axis destAxis;
		if (found.isPresent() && dest.getBlockState(found.get()).is(Blocks.NETHER_PORTAL)) {
			BlockState state = dest.getBlockState(found.get());
			destAxis = state.getValue(NetherPortalBlock.AXIS);
			rect = BlockUtil.getLargestRectangleAround(found.get(), destAxis, 21, Direction.Axis.Y, 21, p -> dest.getBlockState(p) == state);
		} else {
			Optional<BlockUtil.FoundRectangle> created = dest.getPortalForcer().createPortal(approx, link.axis());
			if (created.isEmpty()) {
				return null;
			}
			rect = created.get();
			destAxis = dest.getBlockState(rect.minCorner).getValue(NetherPortalBlock.AXIS);
		}
		DESTINATIONS.remove(new DestKey(level.dimension(), link.min(), link.axis(), link.width(), link.height(), null));
		return new PortalLink(link.source(), link.destDimension(), link.destDimensionType(),
				VanillaPortals.netherDest(link.source(), rect.minCorner, destAxis, rect.axis1Size, rect.axis2Size, PortalFill.NETHER_PORTAL));
	}

	/** Into the End: build the spawn platform exactly as vanilla does on arrival. The link already points above it. */
	private static PortalLink enterEnd(ServerLevel end, PortalLink link) {
		EndPlatformFeature.createEndPlatform(end, BlockPos.containing(END_ARRIVAL), true);
		return link;
	}

	// ── portal discovery ────────────────────────────────────────────────────

	/** Finds the player's portals and streams their far sides; true if it synced their entities this tick too. */
	private static boolean update(MinecraftServer server, ServerPlayer player, int now) {
		PlayerState state = PLAYERS.computeIfAbsent(player.getUUID(), id -> new PlayerState());
		ServerLevel level = player.level();
		if (level.dimension() != state.streamedFrom) {
			// A mod client drops every remote level when its own level changes (portal, /tp,
			// respawn), so nothing sent before counts any more: start streaming afresh.
			state.sentChunkHashes.clear();
			state.recheckCursor.clear();
			state.entities.clear();
			state.streamedFrom = level.dimension();
			state.linksSent = false;
		}
		List<PortalLink> links = findLinks(server, player, now);
		boolean changed = !state.linksSent || !links.equals(state.links);
		state.links = links;
		for (Map.Entry<String, List<Vec3>> entry : streamedCenters(links).entrySet()) {
			ServerLevel dest = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(entry.getKey())));
			if (dest != null) {
				sendChunks(player, state, dest, entry.getKey(), entry.getValue(), now);
			}
		}
		if (!changed) {
			return false;
		}
		// The far sides' chunks and entities first, the links last: a client with a far side's
		// terrain already (a doorway opened again) draws its view at once, and its mobs and
		// displays (a room's doors) must be there by then.
		syncEntities(server, player, now);
		// Sent once per world even when empty: that's how a client knows it has a server half
		// to stream to it (otherwise it may draw recorded portals of its own).
		state.linksSent = true;
		sender.send(player, new PortalLinksPayload(links), PortalLinksPayload.STREAM_CODEC);
		return true;
	}

	/** The portals around the player they may see through, nearest first, at most {@link ServerConfig#maxPortals()}. */
	private static List<PortalLink> findLinks(MinecraftServer server, ServerPlayer player, int now) {
		List<PortalLink> links = new ArrayList<>();
		ServerLevel level = player.level();
		BlockPos center = player.blockPosition();
		findNetherLinks(server, level, center, now, links);
		findEndLinks(server, player, level, center, now, links);
		for (CustomPortal portal : CustomPortals.near(level, player.position(), config.scanRadius())) {
			if (CustomPortals.canSee(player, portal) && portal.end().width() <= config.maxPortalSize() && portal.end().height() <= config.maxPortalSize()) {
				links.addAll(CustomPortals.links(server, portal));
			}
		}
		links.sort(Comparator.comparingDouble(link ->
				PortalGeometry.center(link.source()).distanceToSqr(player.position())));
		return links.size() > config.maxPortals() ? List.copyOf(links.subList(0, config.maxPortals())) : links;
	}

	private static void findNetherLinks(MinecraftServer server, ServerLevel level, BlockPos center, int now, List<PortalLink> out) {
		ResourceKey<Level> destKey = level.dimension() == Level.OVERWORLD ? Level.NETHER : level.dimension() == Level.NETHER ? Level.OVERWORLD : null;
		ServerLevel dest = destKey == null ? null : server.getLevel(destKey);
		if (dest == null) {
			return;
		}
		List<BlockPos> portalBlocks = level.getPoiManager()
				.getInSquare(type -> type.is(PoiTypes.NETHER_PORTAL), center, config.scanRadius(), PoiManager.Occupancy.ANY)
				.map(PoiRecord::getPos)
				.sorted(Comparator.comparingDouble(pos -> pos.distSqr(center)))
				.toList();
		Set<BlockPos> covered = new HashSet<>();
		for (BlockPos pos : portalBlocks) {
			if (covered.contains(pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (!state.is(Blocks.NETHER_PORTAL) || CustomPortals.at(level, pos) != null) {
				continue; // custom portals' nether portal blocks are theirs, not vanilla portals
			}
			Direction.Axis axis = state.getValue(NetherPortalBlock.AXIS);
			BlockUtil.FoundRectangle rect = BlockUtil.getLargestRectangleAround(
					pos, axis, 21, Direction.Axis.Y, 21, p -> level.getBlockState(p) == state && CustomPortals.at(level, p) == null);
			PortalEnd.netherPortal(rect.minCorner, axis, rect.axis1Size, rect.axis2Size, PortalFill.NETHER_PORTAL).blocks().forEach(p -> covered.add(p.immutable()));
			if (tooBig(rect)) {
				continue; // too big to show through: stays a vanilla portal
			}
			PortalLink link = resolveNether(level, dest, rect, axis, now);
			if (link != null) {
				out.add(link);
			}
		}
	}

	/**
	 * End portals have no point-of-interest record, but every end portal block has a block
	 * entity, so the chunks around the player are checked for those (cheap: a handful of map
	 * lookups per chunk).
	 */
	private static void findEndLinks(MinecraftServer server, ServerPlayer player, ServerLevel level, BlockPos center, int now, List<PortalLink> out) {
		int radius = config.scanRadius();
		double radiusSqr = (double) radius * radius;
		Set<BlockPos> covered = new HashSet<>();
		ChunkPos min = ChunkPos.containing(center.offset(-radius, 0, -radius));
		ChunkPos max = ChunkPos.containing(center.offset(radius, 0, radius));
		for (int cx = min.x(); cx <= max.x(); cx++) {
			for (int cz = min.z(); cz <= max.z(); cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					BlockPos pos = blockEntity.getBlockPos();
					// End gateways share this block entity class; only real end portals count.
					if (!(blockEntity instanceof TheEndPortalBlockEntity) || covered.contains(pos) || pos.distSqr(center) > radiusSqr
							|| !level.getBlockState(pos).is(Blocks.END_PORTAL) || CustomPortals.at(level, pos) != null) {
						continue;
					}
					BlockUtil.FoundRectangle rect = BlockUtil.getLargestRectangleAround(
							pos, Direction.Axis.X, 21, Direction.Axis.Z, 21, p -> level.getBlockState(p).is(Blocks.END_PORTAL) && CustomPortals.at(level, p) == null);
					PortalEnd.endPortal(rect.minCorner, rect.axis1Size, rect.axis2Size, PortalFill.END_PORTAL).blocks().forEach(p -> covered.add(p.immutable()));
					if (tooBig(rect)) {
						continue;
					}
					PortalLink link = resolveEnd(server, player, level, rect, now);
					if (link != null) {
						out.add(link);
					}
				}
			}
		}
	}

	private static boolean tooBig(BlockUtil.FoundRectangle rect) {
		return rect.axis1Size > config.maxPortalSize() || rect.axis2Size > config.maxPortalSize();
	}

	/** Where this nether portal leads: the closest existing portal vanilla would pick, or the spot it would build one. */
	private static PortalLink resolveNether(ServerLevel level, ServerLevel dest, BlockUtil.FoundRectangle rect, Direction.Axis axis, int now) {
		DestKey key = new DestKey(level.dimension(), rect.minCorner, axis, rect.axis1Size, rect.axis2Size, null);
		CachedDest cached = DESTINATIONS.get(key);
		if (cached != null) {
			return cached.link;
		}

		PortalEnd source = PortalEnd.netherPortal(rect.minCorner, axis, rect.axis1Size, rect.axis2Size, PortalFill.NETHER_PORTAL);
		Vec3 center = PortalGeometry.center(source);
		double scale = DimensionType.getTeleportationScale(level.dimensionType(), dest.dimensionType());
		WorldBorder border = dest.getWorldBorder();
		BlockPos approx = border.clampToBounds(center.x * scale, center.y, center.z * scale);
		String destId = dest.dimension().identifier().toString();
		String destType = dimensionTypeId(dest);

		// The platform's own portal search (vanilla on Fabric, Paper's optimised lookup on
		// Paper), so the view points at exactly the portal a real trip would use. It can load
		// chunks, so results are cached (see FOUND_DEST_CACHE_TICKS).
		Optional<BlockPos> found = dest.getPortalForcer().findClosestPortalPosition(approx, dest.dimension() == Level.NETHER, border);

		PortalLink link;
		if (found.isPresent()) {
			BlockPos pos = found.get();
			ChunkPos chunk = ChunkPos.containing(pos);
			if (dest.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) {
				// Load it in the background and try again on a later scan.
				loadWithoutTicking(dest, chunk);
				return null;
			}
			BlockState destState = dest.getBlockState(pos);
			if (!destState.is(Blocks.NETHER_PORTAL)) {
				return null;
			}
			Direction.Axis destAxis = destState.getValue(NetherPortalBlock.AXIS);
			BlockUtil.FoundRectangle destRect = BlockUtil.getLargestRectangleAround(
					pos, destAxis, 21, Direction.Axis.Y, 21, p -> dest.getBlockState(p) == destState);
			link = new PortalLink(source, destId, destType,
					VanillaPortals.netherDest(source, destRect.minCorner, destAxis, destRect.axis1Size, destRect.axis2Size, PortalFill.NETHER_PORTAL));
			DESTINATIONS.put(key, new CachedDest(link, now + FOUND_DEST_CACHE_TICKS));
		} else {
			// No portal yet: show the area a new one would be built around, same size and facing.
			int minY = dest.getMinY() + 1;
			int maxY = dest.getMinY() + dest.getLogicalHeight() - rect.axis2Size - 1;
			int y = Math.clamp(approx.getY() - rect.axis2Size / 2, minY, Math.max(minY, maxY));
			BlockPos destMin = new BlockPos(approx.getX(), y, approx.getZ()).relative(axis, -rect.axis1Size / 2);
			link = new PortalLink(source, destId, destType, VanillaPortals.netherDest(source, destMin, axis, rect.axis1Size, rect.axis2Size, PortalFill.NONE));
			DESTINATIONS.put(key, new CachedDest(link, now + VIRTUAL_DEST_CACHE_TICKS));
		}
		return link;
	}

	/**
	 * Where this end portal leads, as a flat opening of the same size centred {@link VanillaPortals#END_DROP}
	 * above where the player would land: the End spawn platform, or (from the End) the player's
	 * own respawn point, found the way vanilla finds it.
	 */
	private static PortalLink resolveEnd(MinecraftServer server, ServerPlayer player, ServerLevel level, BlockUtil.FoundRectangle rect, int now) {
		boolean fromEnd = level.dimension() == Level.END;
		DestKey key = new DestKey(level.dimension(), rect.minCorner, Direction.Axis.Y, rect.axis1Size, rect.axis2Size,
				fromEnd ? player.getUUID() : null);
		CachedDest cached = DESTINATIONS.get(key);
		if (cached != null) {
			return cached.link;
		}
		ResourceKey<Level> destKey;
		Vec3 arrival;
		if (fromEnd) {
			// The player's set respawn point (bed or anchor) or else the world spawn, read
			// without vanilla's respawn lookup, which validates and may use the spawn block and,
			// on Paper, fires plugin events. Only a preview; the real trip stays vanilla.
			ServerPlayer.RespawnConfig respawn = player.getRespawnConfig();
			LevelData.RespawnData data = respawn != null ? respawn.respawnData() : level.getRespawnData();
			destKey = data.dimension();
			arrival = Vec3.atBottomCenterOf(data.pos()).add(0.0, respawn != null ? 0.6 : 0.0, 0.0);
		} else {
			destKey = Level.END;
			arrival = END_ARRIVAL;
		}
		ServerLevel destLevel = server.getLevel(destKey);
		if (destLevel == null) {
			return null;
		}
		PortalLink link = new PortalLink(PortalEnd.endPortal(rect.minCorner, rect.axis1Size, rect.axis2Size, PortalFill.END_PORTAL),
				destKey.identifier().toString(), dimensionTypeId(destLevel), VanillaPortals.endDest(rect.axis1Size, rect.axis2Size, arrival));
		DESTINATIONS.put(key, new CachedDest(link, now + (fromEnd ? VIRTUAL_DEST_CACHE_TICKS : FOUND_DEST_CACHE_TICKS)));
		return link;
	}

	/**
	 * The far end of a nether portal link, facing so that walking through keeps your heading:
	 * straight through when both portals share an axis, turned 90° when they don't (as vanilla).
	 */
	public static String dimensionTypeId(ServerLevel level) {
		return level.dimensionTypeRegistration().unwrapKey().map(key -> key.identifier().toString()).orElse("minecraft:overworld");
	}

	// ── streaming ───────────────────────────────────────────────────────────

	/**
	 * Destination centres that get chunks and entities, grouped by dimension, nearest portal
	 * first, de-duplicated (many portals often lead to the same place), and capped at
	 * {@link ServerConfig#streamedDestinations()} in total.
	 */
	private static Map<String, List<Vec3>> streamedCenters(List<PortalLink> links) {
		Map<String, List<Vec3>> byDimension = new LinkedHashMap<>();
		int count = 0;
		for (PortalLink link : links) {
			Vec3 center = PortalGeometry.center(link.dest());
			List<Vec3> centers = byDimension.computeIfAbsent(link.destDimension(), d -> new ArrayList<>());
			if (centers.stream().noneMatch(c -> c.distanceToSqr(center) < 16 * 16)) {
				centers.add(center);
				if (++count == config.streamedDestinations()) {
					break;
				}
			}
		}
		return byDimension;
	}

	/** Every tick: mirror the entities around the streamed destinations, per dimension. */
	private static void syncEntities(MinecraftServer server, ServerPlayer player, int now) {
		PlayerState state = PLAYERS.get(player.getUUID());
		if (state == null) {
			return;
		}
		Map<String, List<Vec3>> streamed = streamedCenters(state.links);
		for (Map.Entry<String, List<Vec3>> entry : streamed.entrySet()) {
			ServerLevel dest = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(entry.getKey())));
			if (dest == null) {
				continue;
			}
			RemoteEntityTracker tracker = state.entities.computeIfAbsent(entry.getKey(), d -> new RemoteEntityTracker());
			send(player, entry.getKey(), tracker.update(player, dest, entry.getKey(), entry.getValue(), config.entityRadius(), now));
		}
		// Dimensions no longer looked into: remove what was shown.
		state.entities.entrySet().removeIf(entry -> {
			if (streamed.containsKey(entry.getKey())) {
				return false;
			}
			ServerLevel dest = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(entry.getKey())));
			send(player, entry.getKey(), entry.getValue().reset(dest));
			return true;
		});
	}

	private static void send(ServerPlayer player, String dimension, List<byte[]> entityPackets) {
		if (!entityPackets.isEmpty()) {
			sender.send(player, new RemoteEntitiesPayload(dimension, entityPackets), RemoteEntitiesPayload.STREAM_CODEC);
		}
	}

	private static void sendChunks(ServerPlayer player, PlayerState state, ServerLevel dest, String destId, List<Vec3> centers, int now) {
		// Nearest destination first, so its view fills in before the others.
		Set<ChunkPos> wantedSet = new LinkedHashSet<>();
		for (Vec3 center : centers) {
			ChunkPos centerChunk = ChunkPos.containing(BlockPos.containing(center));
			if (config.tickDestination()) {
				dest.getChunkSource().addTicketWithRadius(TicketType.PORTAL, centerChunk, config.viewRadiusChunks());
			}
			for (ChunkPos pos : chunksAround(centerChunk, config.viewRadiusChunks())) {
				if (wantedSet.add(pos)) {
					if (!config.tickDestination()) {
						loadWithoutTicking(dest, pos);
					}
				}
			}
		}

		List<ChunkPos> wanted = new ArrayList<>(wantedSet);
		Map<Long, Integer> sent = state.sentChunkHashes.computeIfAbsent(destId, d -> new HashMap<>());
		int budget = config.chunksPerScan();
		// Chunks a block's changed in that this player's been sent, first and past the budget:
		// someone's likely looking at it (see changed).
		if (!changing.isEmpty()) {
			for (Map.Entry<Long, Integer> entry : List.copyOf(sent.entrySet())) {
				if (changing.contains(destId + " " + entry.getKey())) {
					sendIfChanged(player, dest, destId, ChunkPos.unpack(entry.getKey()), sent);
				}
			}
		}
		// The chunks around each destination's own first, all together, past the budget if need
		// be: the client meshes a chunk only once all its neighbours are there, so a new view
		// with any missing shows only part of what's around its far end until the next scan or
		// two (and a portal with no blocks of its own isn't drawn until they're in).
		for (Vec3 center : centers) {
			ChunkPos own = ChunkPos.containing(BlockPos.containing(center));
			for (int dx = -FIRST_CHUNKS_RADIUS; dx <= FIRST_CHUNKS_RADIUS; dx++) {
				for (int dz = -FIRST_CHUNKS_RADIUS; dz <= FIRST_CHUNKS_RADIUS; dz++) {
					ChunkPos pos = new ChunkPos(own.x() + dx, own.z() + dz);
					if (!sent.containsKey(pos.pack()) && sendIfChanged(player, dest, destId, pos, sent)) {
						budget = Math.max(0, budget - 1);
					}
				}
			}
		}
		// Then the chunks not sent yet, nearest first.
		for (ChunkPos pos : wanted) {
			if (budget == 0) {
				return;
			}
			if (!sent.containsKey(pos.pack()) && sendIfChanged(player, dest, destId, pos, sent)) {
				budget--;
			}
		}
		// Then this scan's slice of the sent ones, in case they changed.
		int slice = Math.ceilDiv(wanted.size() * SCAN_INTERVAL_TICKS, RESEND_CHECK_TICKS);
		int cursor = state.recheckCursor.getOrDefault(destId, 0);
		for (int i = 0; i < slice && budget > 0; i++) {
			ChunkPos pos = wanted.get((cursor + i) % wanted.size());
			if (sent.containsKey(pos.pack()) && sendIfChanged(player, dest, destId, pos, sent)) {
				budget--;
			}
		}
		state.recheckCursor.put(destId, wanted.isEmpty() ? 0 : (cursor + slice) % wanted.size());
	}

	/** Sends the chunk if it's loaded and differs from what this player was last sent. */
	private static boolean sendIfChanged(ServerPlayer player, ServerLevel dest, String destId, ChunkPos pos, Map<Long, Integer> sent) {
		LevelChunk chunk = dest.getChunkSource().getChunkNow(pos.x(), pos.z());
		if (chunk == null) {
			return false;
		}
		byte[] bytes = encodeChunk(dest, chunk);
		int hash = Arrays.hashCode(bytes);
		Integer previous = sent.get(pos.pack());
		if (previous != null && previous == hash) {
			return false;
		}
		sender.send(player, new RemoteChunkPayload(destId, bytes), RemoteChunkPayload.STREAM_CODEC);
		sent.put(pos.pack(), hash);
		return true;
	}

	/**
	 * A ticket at the "full chunk" level and no wider: the chunk is loaded and can be sent,
	 * but nothing in it ticks (no random ticks, mob spawning or entity movement), exactly
	 * like chunks at the edge of a player's view distance. A ticket's level is 33 minus its
	 * radius; ticking starts at 32 (blocks) and 31 (entities).
	 */
	private static void loadWithoutTicking(ServerLevel level, ChunkPos pos) {
		level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, pos, 0);
	}

	private static List<ChunkPos> chunksAround(ChunkPos center, int radius) {
		List<ChunkPos> out = new ArrayList<>();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx * dx + dz * dz <= radius * radius + 1) {
					out.add(new ChunkPos(center.x() + dx, center.z() + dz));
				}
			}
		}
		out.sort(Comparator.comparingInt(p -> (p.x() - center.x()) * (p.x() - center.x()) + (p.z() - center.z()) * (p.z() - center.z())));
		return out;
	}

	/**
	 * The chunk packet a client would get for {@code chunk}, blocks shown as the platform's hooks
	 * say (plugins' custom blocks, see {@link PortalHooks#looks}): those sections are swapped for
	 * changed copies while it's encoded, then put back. Only how blocks look differs in them (the
	 * same blocks, other states), so anything reading the chunk meanwhile is none the worse.
	 */
	private static byte[] encodeChunk(ServerLevel level, LevelChunk chunk) {
		LevelChunkSection[] sections = chunk.getSections();
		LevelChunkSection[] originals = looked(level, chunk, sections);
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
		try {
			ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(
					buf, new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			return bytes;
		} finally {
			buf.release();
			if (originals != null) {
				System.arraycopy(originals, 0, sections, 0, sections.length);
			}
		}
	}

	/** Swaps in copies of the sections with blocks that look like something else; returns the originals (null if none changed). */
	private static LevelChunkSection[] looked(ServerLevel level, LevelChunk chunk, LevelChunkSection[] sections) {
		PortalHooks hooks = PortalAdmin.hooks();
		LevelChunkSection[] originals = null;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section.hasOnlyAir() || !section.maybeHas(hooks::mayChangeLooks)) {
				continue;
			}
			int y0 = level.getSectionYFromSectionIndex(i) << 4;
			LevelChunkSection copy = null;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (!hooks.mayChangeLooks(state)) {
							continue;
						}
						BlockState looks = hooks.looks(level, pos.set(x0 + x, y0 + y, z0 + z), state);
						if (looks != null && looks != state) {
							if (copy == null) {
								copy = section.copy();
							}
							copy.setBlockState(x, y, z, looks, false);
						}
					}
				}
			}
			if (copy != null) {
				if (originals == null) {
					originals = sections.clone();
				}
				sections[i] = copy;
			}
		}
		return originals;
	}

	private static final class PlayerState {
		List<PortalLink> links = List.of();
		boolean linksSent;
		/** Per destination dimension: chunk → hash of the bytes last sent. */
		final Map<String, Map<Long, Integer>> sentChunkHashes = new HashMap<>();
		/** Per destination, where the next scan's re-check slice starts in its list of chunks. */
		final Map<String, Integer> recheckCursor = new HashMap<>();
		final Map<String, RemoteEntityTracker> entities = new HashMap<>();
		Vec3 lastEye;
		Vec3 lastFeet;
		/** A crossing on a vehicle the server drives, made once the client has seen it (ticks to go, and which side is through). */
		PortalLink waitingCrossing;
		int waitingTicks;
		double waitingSide;
		ResourceKey<Level> lastDimension;
		/** The player's dimension when remote data was last streamed. */
		ResourceKey<Level> streamedFrom;
	}

	/** A source portal; {@code player} is set for destinations that depend on who goes through (the End's exit). */
	private record DestKey(ResourceKey<Level> dimension, BlockPos min, Direction.Axis axis, int width, int height, UUID player) {
	}

	private record CachedDest(PortalLink link, int expiresAt) {
	}
}
