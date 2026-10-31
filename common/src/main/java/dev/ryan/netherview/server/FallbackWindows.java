package dev.ryan.netherview.server;

import dev.ryan.netherview.PortalGeometry;
import dev.ryan.netherview.net.PortalEnd;
import dev.ryan.netherview.net.PortalLink;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.Mth;
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
	/** A player standing still is redrawn this often (the destination may have changed). */
	private static final int STILL_INTERVAL = 20;
	/**
	 * Every this many ticks the whole view is re-sent anyway. Real block changes under a fake
	 * are caught on each redraw; this is the backstop for chunks the client was sent again.
	 */
	private static final int FULL_RESEND_TICKS = 200;
	/** Packets per bundle (the client refuses bundles over 4096). */
	private static final int MAX_BUNDLE = 1024;

	/** A fake block the client has, and the real block it covers (as it was when we sent it). */
	private record Shown(BlockState fake, BlockState real) {
	}

	private static final class State {
		List<PortalLink> links = List.of();
		/** By {@link BlockPos#asLong()}, as are the other block maps here: tens of thousands of entries per redraw. */
		Long2ObjectMap<Shown> shown = new Long2ObjectOpenHashMap<>();
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
		if (!full && state.lastEye != null && state.lastEye.distanceToSqr(eye) < MOVE_EPSILON * MOVE_EPSILON && now % STILL_INTERVAL != 0) {
			return;
		}
		state.lastEye = eye;

		Long2ObjectMap<Shown> shown = new Long2ObjectOpenHashMap<>(state.shown.size() + 64);
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
			collect(level, dest, link, geometry, eye, shown);
			drawn++;
		}

		// Changes grouped by section: one packet per section, as vanilla sends block changes.
		Long2ObjectMap<Long2ObjectMap<BlockState>> changes = new Long2ObjectOpenHashMap<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		Long2ObjectMap<Shown> previous = state.shown;
		for (Long2ObjectMap.Entry<Shown> entry : shown.long2ObjectEntrySet()) {
			// Sent again when it changed, or when the real block did (its update replaced ours).
			if (!entry.getValue().equals(previous.remove(entry.getLongKey())) || full) {
				change(changes, pos.set(entry.getLongKey()), entry.getValue().fake());
			}
		}
		for (long key : previous.keySet()) {
			// No longer in view: back to the real block.
			change(changes, pos.set(key), level.getBlockState(pos));
		}
		if (PERF && now % 100 == 0) {
			org.slf4j.LoggerFactory.getLogger("netherview").info("[perf] fallback {}: {} fake blocks, {} changed in {} sections",
					player.getScoreboardName(), shown.size(), changes.values().stream().mapToInt(Map::size).sum(), changes.size());
		}
		state.shown = shown;
		if (full) {
			state.lastFull = now;
		}
		List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
		for (Long2ObjectMap.Entry<Long2ObjectMap<BlockState>> section : changes.long2ObjectEntrySet()) {
			packets.add(sectionUpdate(SectionPos.of(section.getLongKey()), section.getValue()));
		}
		for (int start = 0; start < packets.size(); start += MAX_BUNDLE) {
			player.connection.send(new ClientboundBundlePacket(packets.subList(start, Math.min(packets.size(), start + MAX_BUNDLE))));
		}
	}

	private static void change(Long2ObjectMap<Long2ObjectMap<BlockState>> changes, BlockPos pos, BlockState state) {
		changes.computeIfAbsent(SectionPos.asLong(pos), k -> new Long2ObjectOpenHashMap<>()).put(pos.asLong(), state);
	}

	/** A section's block changes in one packet (built through its codec: its constructor reads a real section). */
	private static ClientboundSectionBlocksUpdatePacket sectionUpdate(SectionPos section, Long2ObjectMap<BlockState> blocks) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		try {
			SectionPos.STREAM_CODEC.encode(buf, section);
			buf.writeVarInt(blocks.size());
			for (Long2ObjectMap.Entry<BlockState> entry : blocks.long2ObjectEntrySet()) {
				buf.writeVarLong((long) Block.getId(entry.getValue()) << 12 | SectionPos.sectionRelativePos(pos.set(entry.getLongKey())));
			}
			return ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.decode(buf);
		} finally {
			buf.release();
		}
	}

	/**
	 * The fake blocks for one portal, as seen from {@code eye}, with the real blocks they cover.
	 * Places where the copy matches the real block are left out: the client shows them already.
	 */
	private static void collect(ServerLevel level, ServerLevel dest, PortalLink link, PortalGeometry geometry, Vec3 eye, Long2ObjectMap<Shown> out) {
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
			BlockState real = level.getBlockState(pos);
			if (!real.isAir()) {
				out.put(pos.asLong(), new Shown(Blocks.AIR.defaultBlockState(), real));
			}
		}

		int depthLimit = PortalViewServer.config().fallbackDepth();
		Rotation rotation = rotation(geometry);
		ensureLoaded(dest, geometry.destCenter(), depthLimit);
		Map<BlockPos, String> destPortals = CustomPortals.blocksIn(dest);
		BlockPos.MutableBlockPos destPos = new BlockPos.MutableBlockPos();
		Chunks realChunks = new Chunks(level);
		Chunks destChunks = new Chunks(dest);

		// Walked layer by layer away from the opening. The portal is axis-aligned, so each layer of
		// block centres is a plane, and the part of it seen through the opening is a rectangle that
		// grows with depth: only the blocks inside it are visited. Positions are measured along
		// far / right / up from the origin; block centres sit on half-integers along each.
		double eyeF = eye.dot(far), eyeR = eye.dot(right), eyeU = eye.dot(up);
		double centerF = center.dot(far), centerR = center.dot(right), centerU = center.dot(up);
		for (double f = Math.floor(centerF - 0.5) + 1.5; f - centerF <= depthLimit; f++) {
			double depth = f - centerF;
			if (depth <= 0.0) {
				continue;
			}
			// Seen through the opening: |eye + (q - eye) * t - center| ≤ half size, along right and up.
			double scale = (eyeDistance + depth) / eyeDistance;
			double minR = eyeR + (centerR - eyeR - halfWidth) * scale, maxR = eyeR + (centerR - eyeR + halfWidth) * scale;
			double minU = eyeU + (centerU - eyeU - halfHeight) * scale, maxU = eyeU + (centerU - eyeU + halfHeight) * scale;
			for (double r = Math.ceil(minR - 0.5) + 0.5; r <= maxR; r++) {
				for (double u = Math.ceil(minU - 0.5) + 0.5; u <= maxU; u++) {
					double qx = far.x * f + right.x * r + up.x * u;
					double qy = far.y * f + right.y * r + up.y * u;
					double qz = far.z * f + right.z * r + up.z * u;
					int x = Mth.floor(qx), y = Mth.floor(qy), z = Mth.floor(qz);
					BlockState real = realChunks.get(x, y, z);
					if (real == null) {
						continue; // the client hasn't this chunk either
					}
					Vec3 mapped = geometry.toDest(new Vec3(qx, qy, qz));
					destPos.set(Math.floor(mapped.x), Math.floor(mapped.y), Math.floor(mapped.z));
					BlockState state = destState(destChunks, destPos, destPortals);
					if (state == null) {
						continue;
					}
					state = state.rotate(rotation);
					if (state != real) {
						out.put(BlockPos.asLong(x, y, z), new Shown(state, real));
					}
				}
			}
		}
	}

	/** A destination block as the copy shows it: portal fills (the far portal's own) as air; null if not loaded. */
	private static BlockState destState(Chunks dest, BlockPos pos, Map<BlockPos, String> destPortals) {
		BlockState state = dest.get(pos.getX(), pos.getY(), pos.getZ());
		if (state == null) {
			return null;
		}
		if (state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY) || destPortals.containsKey(pos)) {
			return Blocks.AIR.defaultBlockState();
		}
		return state;
	}

	/** Block lookups in loaded chunks, remembering the last chunk (neighbouring lookups mostly share it). */
	private static final class Chunks {
		private final ServerLevel level;
		private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		private long key = Long.MIN_VALUE;
		private LevelChunk chunk;

		Chunks(ServerLevel level) {
			this.level = level;
		}

		/** The block, or null where the chunk isn't loaded. */
		BlockState get(int x, int y, int z) {
			long chunkKey = ChunkPos.pack(x >> 4, z >> 4);
			if (chunkKey != this.key) {
				this.key = chunkKey;
				this.chunk = this.level.getChunkSource().getChunkNow(x >> 4, z >> 4);
			}
			return this.chunk == null ? null : this.chunk.getBlockState(this.pos.set(x, y, z));
		}
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
