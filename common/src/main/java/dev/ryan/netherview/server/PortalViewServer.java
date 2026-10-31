package dev.ryan.netherview.server;

import dev.ryan.netherview.PortalGeometry;
import dev.ryan.netherview.net.PortalLink;
import dev.ryan.netherview.net.PortalLinksPayload;
import dev.ryan.netherview.net.PortalTravelPayload;
import dev.ryan.netherview.net.RemoteChunkPayload;
import dev.ryan.netherview.net.RemoteEntitiesPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Finds the nether portals around each player, works out where each one leads (the same
 * search vanilla does when you step through), and streams the destination's chunks to the
 * client so it can render the other side.
 */
public final class PortalViewServer {
	private static final int SCAN_INTERVAL_TICKS = 10;
	/** Chunks around the destination portal that are sent, as a radius. */
	/** How often already-sent chunks are re-encoded and re-sent if they changed. */
	private static final int RESEND_CHECK_TICKS = 100;
	private static final int FOUND_DEST_CACHE_TICKS = 1200;
	/** Largest eye movement in one tick still treated as walking (or flying) rather than a teleport. */
	private static final double MAX_STEP = 4.0;
	private static final int VIRTUAL_DEST_CACHE_TICKS = 200;

	private static final Map<UUID, PlayerState> PLAYERS = new HashMap<>();
	private static final Map<DestKey, CachedDest> DESTINATIONS = new HashMap<>();

	private PortalViewServer() {
	}

	private static PayloadSender sender;

	/** Called once by the platform (Fabric mod or Paper plugin) before the first tick. */
	private static ServerConfig config = ServerConfig.DEFAULTS;

	public static void init(PayloadSender payloadSender, ServerConfig serverConfig) {
		sender = payloadSender;
		config = serverConfig;
	}

	private static final boolean PERF = Boolean.getBoolean("netherview.perf");
	private static long perfNanos;
	private static long perfMaxNanos;
	private static int perfTicks;

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
			org.slf4j.LoggerFactory.getLogger("netherview").info("[perf] server: {} us/tick avg, {} us max, {} players tracked",
					perfNanos / perfTicks / 1000, perfMaxNanos / 1000, PLAYERS.size());
			perfNanos = 0;
			perfMaxNanos = 0;
			perfTicks = 0;
		}
	}

	private static void tickPlayers(MinecraftServer server) {
		int now = server.getTickCount();
		boolean scan = now % SCAN_INTERVAL_TICKS == 0;
		if (scan) {
			DESTINATIONS.values().removeIf(cached -> cached.expiresAt < now);
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!sender.canReceive(player)) {
				continue;
			}
			if (scan) {
				update(server, player, now);
			}
			if (travelThroughPortal(server, player)) {
				continue;
			}
			syncEntities(server, player, now);
		}
	}

	// ── travel ──────────────────────────────────────────────────────────────

	/** Whether this player's travel through the portal at {@code pos} is handled here instead of by vanilla. */
	public static boolean handlesTravel(ServerPlayer player, BlockPos pos) {
		PlayerState state = PLAYERS.get(player.getUUID());
		if (state == null || !config.seamlessTravel()) {
			return false;
		}
		for (PortalLink link : state.links) {
			if (contains(link, pos)) {
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

	private static boolean contains(PortalLink link, BlockPos pos) {
		BlockPos min = link.min();
		int along = link.axis() == Direction.Axis.X ? pos.getX() - min.getX() : pos.getZ() - min.getZ();
		int across = link.axis() == Direction.Axis.X ? pos.getZ() - min.getZ() : pos.getX() - min.getX();
		int up = pos.getY() - min.getY();
		return across == 0 && along >= 0 && along < link.width() && up >= 0 && up < link.height();
	}

	/**
	 * Every tick: if the player's eye passed through a linked portal's plane since last tick,
	 * move them to the matching point beyond the destination portal, with the same rigid
	 * transform the client uses to draw the view, so stepping through is continuous.
	 * Velocity and look direction carry through (turned 90° when the frames face differently).
	 */
	private static boolean travelThroughPortal(MinecraftServer server, ServerPlayer player) {
		PlayerState state = PLAYERS.get(player.getUUID());
		if (state == null || !config.seamlessTravel()) {
			return false;
		}
		ServerLevel level = player.level();
		Vec3 eye = player.getEyePosition();
		Vec3 lastEye = state.lastEye;
		boolean sameLevel = state.lastEyeDimension == level.dimension();
		state.lastEye = eye;
		state.lastEyeDimension = level.dimension();
		// A jump of more than a few blocks in one tick is a teleport or respawn, not a step
		// through a portal (the straight line between the two spots could cross one).
		if (lastEye == null || !sameLevel || state.links.isEmpty() || player.isPassenger() || player.isSleeping()
				|| lastEye.distanceToSqr(eye) > MAX_STEP * MAX_STEP) {
			return false;
		}
		for (PortalLink link : state.links) {
			PortalGeometry geometry = PortalGeometry.of(link);
			if (!geometry.crossesSource(lastEye, eye)) {
				continue;
			}
			ResourceKey<Level> destKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(link.destDimension()));
			ServerLevel dest = server.getLevel(destKey);
			if (dest == null) {
				return false;
			}
			PortalLink target = vanillaDestination(level, dest, link, player);
			if (target == null) {
				return false;
			}
			geometry = PortalGeometry.of(target);
			Vec3 destFeet = geometry.toDest(player.position());
			Vec3 velocity = geometry.rotate(player.getDeltaMovement());
			float yRot = geometry.toDestYaw(player.getYRot());
			state.lastEye = null;
			// These links belong to the dimension being left; the next scan sends the new ones.
			state.links = List.of();
			sender.send(player, new PortalTravelPayload(target), PortalTravelPayload.STREAM_CODEC);
			player.teleport(new TeleportTransition(dest, destFeet, velocity, yRot, player.getXRot(),
					TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)));
			// Arriving just past the destination plane still overlaps its portal blocks; the
			// cooldown keeps vanilla from sending the player straight back.
			player.setPortalCooldown();
			return true;
		}
		return false;
	}

	/**
	 * The portal vanilla would send this player to, found the way vanilla does it: from the
	 * player's exact position (not the portal's centre, which is all the view uses), so portal
	 * hubs and linking tricks behave exactly as without the mod. Builds a portal if vanilla
	 * would. The player is then placed relative to that portal with the view's transform.
	 */
	private static PortalLink vanillaDestination(ServerLevel level, ServerLevel dest, PortalLink link, ServerPlayer player) {
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
		DESTINATIONS.remove(new DestKey(level.dimension(), link.min(), link.axis(), link.width(), link.height()));
		return new PortalLink(link.min(), link.axis(), link.width(), link.height(),
				link.destDimension(), rect.minCorner, destAxis, rect.axis1Size, rect.axis2Size);
	}

	/** Every tick: mirror the entities around the streamed destinations. */
	private static void syncEntities(MinecraftServer server, ServerPlayer player, int now) {
		PlayerState state = PLAYERS.get(player.getUUID());
		if (state == null) {
			return;
		}
		ResourceKey<Level> destKey = otherSide(player.level().dimension());
		ServerLevel dest = destKey == null ? null : server.getLevel(destKey);
		List<byte[]> packets;
		String destId;
		if (dest == null || state.links.isEmpty()) {
			destId = state.entities.dimension();
			packets = state.entities.reset(dest);
		} else {
			destId = destKey.identifier().toString();
			packets = state.entities.update(player, dest, destId, streamedCenters(state.links), config.entityRadius(), now);
		}
		if (!packets.isEmpty() && destId != null) {
			sender.send(player, new RemoteEntitiesPayload(destId, packets), RemoteEntitiesPayload.STREAM_CODEC);
		}
	}

	public static void forget(ServerPlayer player) {
		PLAYERS.remove(player.getUUID());
	}

	private static void update(MinecraftServer server, ServerPlayer player, int now) {
		PlayerState state = PLAYERS.computeIfAbsent(player.getUUID(), id -> new PlayerState());
		ServerLevel level = player.level();
		ResourceKey<Level> destKey = otherSide(level.dimension());
		ServerLevel dest = destKey == null ? null : server.getLevel(destKey);

		List<PortalLink> links = dest == null ? List.of() : findLinks(level, dest, player.blockPosition(), now);
		if (!links.equals(state.links)) {
			state.links = links;
			sender.send(player, new PortalLinksPayload(links), PortalLinksPayload.STREAM_CODEC);
		}
		if (links.isEmpty()) {
			return;
		}

		String destId = destKey.identifier().toString();
		if (!destId.equals(state.sentDimension)) {
			state.sentDimension = destId;
			state.sentChunkHashes.clear();
			state.lastFullCheck = now;
		}
		sendChunks(player, state, dest, destId, streamedCenters(links), now);
	}

	/**
	 * Destination centres that get chunks and entities, nearest portal first, de-duplicated
	 * (many portals often lead to the same one), capped at {@link ServerConfig#streamedDestinations()}.
	 */
	private static List<Vec3> streamedCenters(List<PortalLink> links) {
		List<Vec3> centers = new ArrayList<>();
		for (PortalLink link : links) {
			Vec3 center = rectCenter(link.destMin(), link.destAxis(), link.destWidth(), link.destHeight());
			if (centers.stream().noneMatch(c -> c.distanceToSqr(center) < 16 * 16)) {
				centers.add(center);
				if (centers.size() == config.streamedDestinations()) {
					break;
				}
			}
		}
		return centers;
	}

	private static ResourceKey<Level> otherSide(ResourceKey<Level> dimension) {
		if (dimension == Level.OVERWORLD) {
			return Level.NETHER;
		}
		if (dimension == Level.NETHER) {
			return Level.OVERWORLD;
		}
		return null;
	}

	// ── portal discovery ────────────────────────────────────────────────────

	private static List<PortalLink> findLinks(ServerLevel level, ServerLevel dest, BlockPos center, int now) {
		List<BlockPos> portalBlocks = level.getPoiManager()
				.getInSquare(type -> type.is(PoiTypes.NETHER_PORTAL), center, config.scanRadius(), PoiManager.Occupancy.ANY)
				.map(PoiRecord::getPos)
				.sorted(Comparator.comparingDouble(pos -> pos.distSqr(center)))
				.toList();

		Set<BlockPos> covered = new HashSet<>();
		List<PortalLink> links = new ArrayList<>();
		for (BlockPos pos : portalBlocks) {
			if (covered.contains(pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (!state.is(Blocks.NETHER_PORTAL)) {
				continue;
			}
			Direction.Axis axis = state.getValue(NetherPortalBlock.AXIS);
			BlockUtil.FoundRectangle rect = BlockUtil.getLargestRectangleAround(
					pos, axis, 21, Direction.Axis.Y, 21, p -> level.getBlockState(p) == state);
			for (int w = 0; w < rect.axis1Size; w++) {
				for (int h = 0; h < rect.axis2Size; h++) {
					covered.add(rect.minCorner.relative(axis, w).above(h));
				}
			}
			if (rect.axis1Size > config.maxPortalSize() || rect.axis2Size > config.maxPortalSize()) {
				continue; // too big to show through: stays a vanilla portal
			}
			PortalLink link = resolve(level, dest, rect, axis, now);
			if (link != null) {
				links.add(link);
			}
		}
		return links;
	}

	/** Where this portal leads: the closest existing portal vanilla would pick, or the spot it would build one. */
	private static PortalLink resolve(ServerLevel level, ServerLevel dest, BlockUtil.FoundRectangle rect, Direction.Axis axis, int now) {
		DestKey key = new DestKey(level.dimension(), rect.minCorner, axis, rect.axis1Size, rect.axis2Size);
		CachedDest cached = DESTINATIONS.get(key);
		if (cached != null) {
			return cached.link;
		}

		Vec3 center = rectCenter(rect.minCorner, axis, rect.axis1Size, rect.axis2Size);
		double scale = DimensionType.getTeleportationScale(level.dimensionType(), dest.dimensionType());
		WorldBorder border = dest.getWorldBorder();
		BlockPos approx = border.clampToBounds(center.x * scale, center.y, center.z * scale);
		String destId = dest.dimension().identifier().toString();

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
			link = new PortalLink(rect.minCorner, axis, rect.axis1Size, rect.axis2Size,
					destId, destRect.minCorner, destAxis, destRect.axis1Size, destRect.axis2Size);
			DESTINATIONS.put(key, new CachedDest(link, now + FOUND_DEST_CACHE_TICKS));
		} else {
			// No portal yet: show the area a new one would be built around, same size and facing.
			int minY = dest.getMinY() + 1;
			int maxY = dest.getMinY() + dest.getLogicalHeight() - rect.axis2Size - 1;
			int y = Math.clamp(approx.getY() - rect.axis2Size / 2, minY, Math.max(minY, maxY));
			BlockPos destMin = new BlockPos(approx.getX(), y, approx.getZ()).relative(axis, -rect.axis1Size / 2);
			link = new PortalLink(rect.minCorner, axis, rect.axis1Size, rect.axis2Size,
					destId, destMin, axis, rect.axis1Size, rect.axis2Size);
			DESTINATIONS.put(key, new CachedDest(link, now + VIRTUAL_DEST_CACHE_TICKS));
		}
		return link;
	}

	private static Vec3 rectCenter(BlockPos min, Direction.Axis axis, int width, int height) {
		double halfW = width / 2.0;
		return axis == Direction.Axis.X
				? new Vec3(min.getX() + halfW, min.getY() + height / 2.0, min.getZ() + 0.5)
				: new Vec3(min.getX() + 0.5, min.getY() + height / 2.0, min.getZ() + halfW);
	}

	// ── chunk streaming ─────────────────────────────────────────────────────

	private static void sendChunks(ServerPlayer player, PlayerState state, ServerLevel dest, String destId, List<Vec3> centers, int now) {
		// Nearest destination first, so its view fills in before the others.
		List<ChunkPos> wanted = new ArrayList<>();
		for (Vec3 center : centers) {
			ChunkPos centerChunk = ChunkPos.containing(BlockPos.containing(center));
			if (config.tickDestination()) {
				dest.getChunkSource().addTicketWithRadius(TicketType.PORTAL, centerChunk, config.viewRadiusChunks());
			}
			for (ChunkPos pos : chunksAround(centerChunk, config.viewRadiusChunks())) {
				if (!wanted.contains(pos)) {
					wanted.add(pos);
					if (!config.tickDestination()) {
						loadWithoutTicking(dest, pos);
					}
				}
			}
		}

		boolean fullCheck = now - state.lastFullCheck >= RESEND_CHECK_TICKS;
		int budget = config.chunksPerScan();
		for (ChunkPos pos : wanted) {
			long key = pos.pack();
			if (!fullCheck && state.sentChunkHashes.containsKey(key)) {
				continue;
			}
			LevelChunk chunk = dest.getChunkSource().getChunkNow(pos.x(), pos.z());
			if (chunk == null) {
				continue;
			}
			byte[] bytes = encodeChunk(dest, chunk);
			int hash = Arrays.hashCode(bytes);
			Integer previous = state.sentChunkHashes.get(key);
			if (previous != null && previous == hash) {
				continue;
			}
			sender.send(player, new RemoteChunkPayload(destId, bytes), RemoteChunkPayload.STREAM_CODEC);
			state.sentChunkHashes.put(key, hash);
			if (--budget == 0) {
				return;
			}
		}
		if (fullCheck) {
			state.lastFullCheck = now;
		}
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

	private static byte[] encodeChunk(ServerLevel level, LevelChunk chunk) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
		try {
			ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(
					buf, new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			return bytes;
		} finally {
			buf.release();
		}
	}

	private static final class PlayerState {
		List<PortalLink> links = List.of();
		String sentDimension;
		final Map<Long, Integer> sentChunkHashes = new HashMap<>();
		final RemoteEntityTracker entities = new RemoteEntityTracker();
		Vec3 lastEye;
		ResourceKey<Level> lastEyeDimension;
		int lastFullCheck;
	}

	private record DestKey(ResourceKey<Level> dimension, BlockPos min, Direction.Axis axis, int width, int height) {
	}

	private record CachedDest(PortalLink link, int expiresAt) {
	}
}
