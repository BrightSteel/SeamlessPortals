package dev.ryan.netherview.server;

import dev.ryan.netherview.net.PortalEnd;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Queues of random safe spots for {@link CustomPortal.Destination.Random} portals, one queue
 * per portal (two random portals never show the same spot). Like an /rtp queue: a few spots
 * are kept ready, topped up in the background by loading one candidate chunk at a time, so
 * the portal can show its next spot before anyone goes through. A player's trip uses up
 * the spot; mobs and items go to it without moving the portal on.
 */
public final class RandomDestinations {
	/** Spots kept ready per portal. */
	private static final int LOCATIONS_TO_QUEUE = 5;
	/** Candidates tried per top-up round, per portal. */
	private static final int MAX_TRIES = 20;
	/** Ticks between top-up rounds. */
	private static final int REFILL_INTERVAL = 60;
	/** A candidate chunk that hasn't loaded after this long is given up on. */
	private static final int LOAD_TIMEOUT = 200;

	/** Where a spot is: standing on {@code ground + 1} at the block's middle, facing {@code yaw}. */
	public record Spot(double x, double y, double z, float yaw) {
	}

	private record Candidate(int x, int z, int radius, int startedAt) {
	}

	private static final class Queue {
		final Deque<Spot> spots = new ArrayDeque<>();
		Candidate pending;
		int triesLeft;
	}

	private static final Map<String, Queue> QUEUES = new HashMap<>();

	private RandomDestinations() {
	}

	/** Drops every queue (another world's portals were loaded). */
	static void clear() {
		QUEUES.clear();
	}

	/** The spot the portal shows and the next trip goes to, or null while none is ready. */
	public static Spot peek(String portal) {
		Queue queue = QUEUES.get(portal);
		return queue == null ? null : queue.spots.peekFirst();
	}

	/** Uses up the current spot (a player went through): the portal moves on to the next. */
	static void advance(String portal) {
		Queue queue = QUEUES.get(portal);
		if (queue != null) {
			queue.spots.pollFirst();
		}
	}

	public static int queued(String portal) {
		Queue queue = QUEUES.get(portal);
		return queue == null ? 0 : queue.spots.size();
	}

	static void tick(MinecraftServer server) {
		int now = server.getTickCount();
		Map<String, CustomPortal> random = new HashMap<>();
		for (CustomPortal portal : CustomPortals.all()) {
			if (portal.destination() instanceof CustomPortal.Destination.Random) {
				random.put(portal.name(), portal);
			}
		}
		// Deleted or relinked portals drop their queue.
		QUEUES.keySet().removeIf(name -> !random.containsKey(name));
		boolean refill = now % REFILL_INTERVAL == 0;
		for (CustomPortal portal : random.values()) {
			ServerLevel level = CustomPortals.level(server, portal.world());
			if (level == null) {
				continue;
			}
			Queue queue = QUEUES.computeIfAbsent(portal.name(), n -> new Queue());
			if (refill && queue.pending == null) {
				queue.triesLeft = MAX_TRIES;
			}
			if (queue.pending != null) {
				Candidate candidate = queue.pending;
				if (!loaded(level, candidate)) {
					if (now - candidate.startedAt() < LOAD_TIMEOUT) {
						continue;
					}
				} else {
					Spot spot = check(level, portal.end(), candidate.x(), candidate.z());
					if (spot != null) {
						queue.spots.addLast(spot);
					}
				}
				queue.pending = null;
			}
			if (queue.spots.size() < LOCATIONS_TO_QUEUE && queue.triesLeft > 0) {
				queue.triesLeft--;
				queue.pending = start(level, portal.end(), now);
			}
		}
	}

	/** Picks a random column (kept away from spawn and the border) and starts loading its chunk. */
	private static Candidate start(ServerLevel level, PortalEnd source, int now) {
		WorldBorder border = level.getWorldBorder();
		int radius = (int) border.getSize() / 2;
		// stay 500 blocks inside the border (less on small borders), around the border's centre - not 0,0
		int bound = Math.max(101, radius - Math.min(500, radius / 2));
		// Mid-chunk, so a portal up to 15 wide fits in the one chunk; wider ones need its neighbours too.
		int x = ((int) border.getCenterX() + randInt(100, bound) & ~15) + 8;
		int z = ((int) border.getCenterZ() + randInt(100, bound) & ~15) + 8;
		int ticketRadius = Math.max(source.width(), source.height()) > 15 ? 1 : 0;
		level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(new BlockPos(x, 0, z)), ticketRadius);
		return new Candidate(x, z, ticketRadius, now);
	}

	private static boolean loaded(ServerLevel level, Candidate candidate) {
		int cx = candidate.x() >> 4;
		int cz = candidate.z() >> 4;
		for (int dx = -candidate.radius(); dx <= candidate.radius(); dx++) {
			for (int dz = -candidate.radius(); dz <= candidate.radius(); dz++) {
				if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * A spot at this column where an opening the size of {@code source} stands on safe ground
	 * with nothing in it or just in front of it, or null. Arrivals keep their compass heading
	 * through a wall portal (the spot faces away from the side it's entered from); a floor or
	 * ceiling portal's spot faces a random way.
	 */
	private static Spot check(ServerLevel level, PortalEnd source, int x, int z) {
		Direction facing = source.facing().getAxis() == Direction.Axis.Y
				? Direction.from2DDataValue(ThreadLocalRandom.current().nextInt(4))
				: source.facing().getOpposite();
		int width = source.width();
		int height = source.height();
		if (level.dimensionType().hasCeiling()) {
			int top = level.getMinY() + level.dimensionType().logicalHeight() - height;
			for (int y = level.getMinY(); y < top; y++) {
				if (fits(level, facing, width, height, x, y + 1, z)) {
					return spot(x, y + 1, z, facing);
				}
			}
			return null;
		}
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return fits(level, facing, width, height, x, y, z) ? spot(x, y, z, facing) : null;
	}

	private static Spot spot(int x, int y, int z, Direction facing) {
		return new Spot(x + 0.5, y, z + 0.5, facing.toYRot());
	}

	/** Safe ground under the middle, and the opening plus a block in front of it empty. */
	private static boolean fits(ServerLevel level, Direction facing, int width, int height, int x, int y, int z) {
		if (!isSafeGround(level.getBlockState(new BlockPos(x, y - 1, z)))) {
			return false;
		}
		// Laid out as CustomPortals#link places a target's opening: centred along its width.
		boolean alongX = facing.getAxis() == Direction.Axis.Z;
		int start = (int) Math.floor(1 - width / 2.0);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int w = start; w < start + width; w++) {
			for (int h = 0; h < height; h++) {
				for (int d = 0; d <= 1; d++) {
					pos.set(x + (alongX ? w : 0) + facing.getStepX() * d, y + h, z + (alongX ? 0 : w) + facing.getStepZ() * d);
					BlockState state = level.getBlockState(pos);
					if (!state.getCollisionShape(level, pos).isEmpty() || !state.getFluidState().isEmpty()) {
						return false;
					}
				}
			}
		}
		return true;
	}

	private static boolean isSafeGround(BlockState state) {
		return state.isSolid() && state.getFluidState().isEmpty()
				&& !state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE) && !state.is(Blocks.CACTUS) && !state.is(Blocks.MAGMA_BLOCK)
				&& !state.is(Blocks.CAMPFIRE) && !state.is(Blocks.SOUL_CAMPFIRE) && !state.is(Blocks.POWDER_SNOW)
				&& !state.is(Blocks.SWEET_BERRY_BUSH) && !state.is(Blocks.BEDROCK);
	}

	private static int randInt(int origin, int bound) {
		int sign = ThreadLocalRandom.current().nextBoolean() ? -1 : 1;
		return ThreadLocalRandom.current().nextInt(origin, bound) * sign;
	}
}
