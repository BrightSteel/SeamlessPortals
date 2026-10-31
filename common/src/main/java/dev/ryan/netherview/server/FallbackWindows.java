package dev.ryan.netherview.server;

import dev.ryan.netherview.PortalGeometry;
import dev.ryan.netherview.net.PortalEnd;
import dev.ryan.netherview.net.PortalLink;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A see-through portal for players without the client mod, made of fake blocks. Every few
 * ticks, the blocks behind each nearby portal that the player sees through its opening (block
 * centre inside the pyramid from their eye through the opening, up to
 * {@link ServerConfig#fallbackDepth()} deep) are replaced, for that player only, by a copy of the destination,
 * rotated to match. The portal's own fill shows as air. Blocks that leave the view are put
 * back. The client lights the copy with its own dimension's rules and draws its own sky.
 */
final class FallbackWindows {
	private static final boolean PERF = Boolean.getBoolean("netherview.perf");
	/** Portals further than this from the player aren't drawn. */
	private static final double RANGE = 32.0;
	/** At most this many portals are drawn per player, nearest first. */
	private static final int MAX_PORTALS = 2;
	private static final int UPDATE_INTERVAL = 2;
	/** Eye movement that triggers a redraw between scheduled ones. */
	private static final double MOVE_EPSILON = 0.05;
	/** Every this many ticks the whole view is re-sent (the real block may have been re-sent over it). */
	private static final int FULL_RESEND_TICKS = 40;
	/** Packets per bundle (the client refuses bundles over 4096). */
	private static final int MAX_BUNDLE = 1024;

	private static final class State {
		List<PortalLink> links = List.of();
		/** Fake blocks the client currently has → what we sent. */
		Map<BlockPos, BlockState> shown = new HashMap<>();
		ResourceKey<Level> level;
		Vec3 lastEye;
		int lastFull;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	private FallbackWindows() {
	}

	static void setLinks(ServerPlayer player, List<PortalLink> links) {
		STATES.computeIfAbsent(player.getUUID(), id -> new State()).links = links;
	}

	static void forget(ServerPlayer player) {
		STATES.remove(player.getUUID());
	}

	static void tick(MinecraftServer server, ServerPlayer player, int now) {
		State state = STATES.computeIfAbsent(player.getUUID(), id -> new State());
		ServerLevel level = player.level();
		if (state.level != level.dimension()) {
			// The client dropped its chunks (and our fakes with them).
			state.shown.clear();
			state.level = level.dimension();
			state.lastEye = null;
		}
		Vec3 eye = player.getEyePosition();
		boolean full = now - state.lastFull >= FULL_RESEND_TICKS;
		if (!full && now % UPDATE_INTERVAL != 0 && state.lastEye != null) {
			return;
		}
		if (!full && state.lastEye != null && state.lastEye.distanceToSqr(eye) < MOVE_EPSILON * MOVE_EPSILON && now % 10 != 0) {
			return;
		}
		state.lastEye = eye;

		Map<BlockPos, BlockState> wanted = new HashMap<>();
		int drawn = 0;
		for (PortalLink link : state.links) {
			if (drawn == MAX_PORTALS) {
				break;
			}
			PortalGeometry geometry = PortalGeometry.of(link);
			if (geometry.sourceCenter().distanceToSqr(eye) > RANGE * RANGE) {
				continue;
			}
			ServerLevel dest = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(link.destDimension())));
			if (dest == null) {
				continue;
			}
			collect(level, dest, link, geometry, eye, wanted);
			drawn++;
		}

		// Changes grouped by section: one packet per section, as vanilla sends block changes.
		Map<Long, Map<BlockPos, BlockState>> changes = new HashMap<>();
		for (BlockPos pos : state.shown.keySet()) {
			if (!wanted.containsKey(pos)) {
				// No longer in view: back to the real block.
				changes.computeIfAbsent(SectionPos.asLong(pos), k -> new HashMap<>()).put(pos, level.getBlockState(pos));
			}
		}
		for (Map.Entry<BlockPos, BlockState> entry : wanted.entrySet()) {
			if (full || !entry.getValue().equals(state.shown.get(entry.getKey()))) {
				changes.computeIfAbsent(SectionPos.asLong(entry.getKey()), k -> new HashMap<>()).put(entry.getKey(), entry.getValue());
			}
		}
		if (PERF && now % 100 == 0) {
			org.slf4j.LoggerFactory.getLogger("netherview").info("[perf] fallback {}: {} fake blocks, {} changed in {} sections",
					player.getScoreboardName(), wanted.size(), changes.values().stream().mapToInt(Map::size).sum(), changes.size());
		}
		state.shown = wanted;
		if (full) {
			state.lastFull = now;
		}
		List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
		for (Map.Entry<Long, Map<BlockPos, BlockState>> section : changes.entrySet()) {
			packets.add(sectionUpdate(SectionPos.of(section.getKey()), section.getValue()));
		}
		for (int start = 0; start < packets.size(); start += MAX_BUNDLE) {
			player.connection.send(new ClientboundBundlePacket(packets.subList(start, Math.min(packets.size(), start + MAX_BUNDLE))));
		}
	}

	/** A section's block changes in one packet (built through its codec: its constructor reads a real section). */
	private static ClientboundSectionBlocksUpdatePacket sectionUpdate(SectionPos section, Map<BlockPos, BlockState> blocks) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			SectionPos.STREAM_CODEC.encode(buf, section);
			buf.writeVarInt(blocks.size());
			for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
				buf.writeVarLong((long) Block.getId(entry.getValue()) << 12 | SectionPos.sectionRelativePos(entry.getKey()));
			}
			return ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.decode(buf);
		} finally {
			buf.release();
		}
	}

	/** The fake blocks for one portal, as seen from {@code eye}. */
	private static void collect(ServerLevel level, ServerLevel dest, PortalLink link, PortalGeometry geometry, Vec3 eye, Map<BlockPos, BlockState> out) {
		PortalEnd source = link.source();
		Vec3 center = geometry.sourceCenter();
		Vec3 normal = geometry.sourceNormal();
		double eyeSide = eye.subtract(center).dot(normal);
		if (Math.abs(eyeSide) < 0.05) {
			return;
		}
		// The far side, away from the eye.
		Vec3 far = normal.scale(-Math.signum(eyeSide));
		double eyeDistance = Math.abs(eyeSide);
		Vec3 right = Vec3.atLowerCornerOf(source.right().getUnitVec3i());
		Vec3 up = Vec3.atLowerCornerOf(source.up().getUnitVec3i());
		double halfWidth = source.width() / 2.0;
		double halfHeight = source.height() / 2.0;

		// The portal's own fill shows as nothing.
		for (BlockPos pos : source.blocks()) {
			out.put(pos.immutable(), Blocks.AIR.defaultBlockState());
		}

		// Bounding box of the view pyramid: the opening's corners and where the rays through them reach the depth limit.
		int depthLimit = PortalViewServer.config().fallbackDepth();
		AABB bounds = null;
		double stretch = (eyeDistance + depthLimit) / eyeDistance;
		for (Vec3 corner : geometry.sourceCorners(eye, 0.0)) {
			Vec3 deep = eye.add(corner.subtract(eye).scale(stretch));
			AABB both = new AABB(corner, deep);
			bounds = bounds == null ? both : bounds.minmax(both);
		}
		Rotation rotation = rotation(geometry);
		ensureLoaded(dest, geometry.destCenter(), depthLimit);
		BlockPos.MutableBlockPos destPos = new BlockPos.MutableBlockPos();
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ), BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ))) {
			Vec3 q = Vec3.atCenterOf(pos);
			double depth = q.subtract(center).dot(far);
			if (depth <= 0.0 || depth > depthLimit) {
				continue;
			}
			// Where the line from the eye to this block's centre crosses the portal plane.
			Vec3 hit = eye.add(q.subtract(eye).scale(eyeDistance / (eyeDistance + depth)));
			Vec3 local = hit.subtract(center);
			if (Math.abs(local.dot(right)) > halfWidth || Math.abs(local.dot(up)) > halfHeight) {
				continue;
			}
			if (out.containsKey(pos)) {
				continue;
			}
			Vec3 mapped = geometry.toDest(q);
			destPos.set(Math.floor(mapped.x), Math.floor(mapped.y), Math.floor(mapped.z));
			BlockState state = destState(dest, destPos);
			if (state == null) {
				continue;
			}
			out.put(pos.immutable(), state.rotate(rotation));
		}
	}

	/** A destination block as the copy shows it: portal fills (the far portal's own) as air; null if not loaded. */
	private static BlockState destState(ServerLevel dest, BlockPos pos) {
		LevelChunk chunk = dest.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		if (chunk == null) {
			return null;
		}
		BlockState state = chunk.getBlockState(pos);
		if (state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY) || CustomPortals.at(dest, pos) != null) {
			return Blocks.AIR.defaultBlockState();
		}
		return state;
	}

	/** Loads (without ticking) the chunks the copy reads from. */
	private static void ensureLoaded(ServerLevel dest, Vec3 center, int depthLimit) {
		ChunkPos middle = ChunkPos.containing(BlockPos.containing(center));
		int radius = (depthLimit >> 4) + 1;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				dest.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(middle.x() + dx, middle.z() + dz), 0);
			}
		}
	}

	/** The turn about the vertical axis that carries source blocks to the destination, if it is one. */
	private static Rotation rotation(PortalGeometry geometry) {
		Vec3 east = geometry.rotate(new Vec3(1, 0, 0));
		Vec3 upward = geometry.rotate(new Vec3(0, 1, 0));
		if (upward.y < 0.99) {
			return Rotation.NONE; // not a turn about the vertical axis (a floor-to-wall pair): left as is
		}
		Direction to = Direction.getApproximateNearest(east.x, east.y, east.z);
		return switch (to) {
			case SOUTH -> Rotation.CLOCKWISE_90;
			case WEST -> Rotation.CLOCKWISE_180;
			case NORTH -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}
}
