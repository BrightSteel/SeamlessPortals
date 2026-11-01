package dev.ryan.netherview.client;

import dev.ryan.netherview.PortalGeometry;
import dev.ryan.netherview.VanillaPortals;
import dev.ryan.netherview.net.PortalEnd;
import dev.ryan.netherview.net.PortalFill;
import dev.ryan.netherview.net.PortalLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Portal views on servers without the NetherView server half. Nothing is streamed there, so a
 * portal shows its far side only once the player has been through it: the trip is recorded
 * (where the server set them down) and the chunks around both ends are kept on disk as last
 * seen. Such a view is frozen, has no mobs, and is drawn under a see-through swirl; travel stays
 * vanilla (wait in the portal, then the loading screen).
 */
public final class OfflinePortals {
	/** A trip through a portal: from {@code sourceDimension}, as a link to where it landed. */
	record Recorded(String sourceDimension, PortalLink link) {
	}

	/** A trip under way: the portal left through, until the arrival is found. */
	private record Pending(PortalEnd source, ResourceKey<Level> sourceDimension, ResourceKey<Level> destDimension) {
	}

	/** Chunks being copied (a few a tick) around one end of a trip, to save. */
	private record Snapshot(ClientLevel level, ChunkPos center, ArrayDeque<ChunkPos> left, List<ClientboundLevelChunkWithLightPacket> chunks, int until) {
	}

	/** Recorded portals this near the player (blocks) are shown, as the server half's scan would. */
	private static final double SHOW_RADIUS = 24.0;
	private static final int REFRESH_TICKS = 10;
	/** Ticks after arriving before the far end is looked for (the server's chunks and placement first). */
	private static final int ARRIVAL_SETTLE_TICKS = 10;
	private static final int ARRIVAL_GIVE_UP_TICKS = 200;
	private static final int SNAPSHOT_CHUNKS_PER_TICK = 16;
	/** Ticks an arrival snapshot waits for chunks the server hasn't sent yet. */
	private static final int SNAPSHOT_WAIT_TICKS = 200;

	private static boolean pluginSeen;
	private static Path folder;
	private static List<Recorded> recorded;
	private static Pending pending;
	private static int pendingTicks;
	private static final ArrayDeque<Snapshot> snapshots = new ArrayDeque<>();
	private static List<PortalLink> shown = List.of();
	private static ClientLevel shownIn;
	/** Whether the player has stood outside every portal since arriving in {@link #steppedOutIn}. */
	private static boolean steppedOut;
	private static ClientLevel steppedOutIn;
	private static int ticks;

	private OfflinePortals() {
	}

	/** The server sent something of its own: it has the server half, which takes over. */
	static void pluginSeen() {
		pluginSeen = true;
	}

	static boolean active() {
		return ClientConfig.get().offlinePreviews && !pluginSeen && OfflineCache.folder() != null;
	}

	/** Disconnected: the next server starts unknown. */
	static void clear() {
		pluginSeen = false;
		folder = null;
		recorded = null;
		pending = null;
		snapshots.clear();
		shown = List.of();
		shownIn = null;
		steppedOut = false;
		steppedOutIn = null;
	}

	private static List<Recorded> recorded() {
		Path current = OfflineCache.folder();
		if (recorded == null || !current.equals(folder)) {
			folder = current;
			recorded = OfflineCache.loadLinks(folder);
		}
		return recorded;
	}

	/**
	 * The player is leaving {@code level} for {@code destination}. If they're standing in a
	 * portal, start recording the trip (and save the chunks around the portal, the far end of a
	 * trip back).
	 */
	static void onDepart(ClientLevel level, LocalPlayer player, ResourceKey<Level> destination) {
		if (!active()) {
			return;
		}
		// Vanilla never sends a player back through the portal they arrived in until they've
		// stepped out of it: leaving from there is a command or plugin teleport, not a trip to record.
		PortalEnd source = level == steppedOutIn && steppedOut ? portalAt(level, player.getBoundingBox()) : null;
		if (source == null) {
			pending = null;
			return;
		}
		recorded();
		pending = new Pending(source, level.dimension(), destination);
		pendingTicks = 0;
		// Copied now: the level is about to be replaced.
		ChunkPos center = ChunkPos.containing(BlockPos.containing(PortalGeometry.center(source)));
		List<ClientboundLevelChunkWithLightPacket> chunks = new ArrayList<>();
		for (ChunkPos pos : around(center)) {
			LevelChunk chunk = level.getChunkSource().getChunk(pos.x(), pos.z(), false);
			if (chunk != null) {
				chunks.add(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
			}
		}
		OfflineCache.saveChunks(folder, level.dimension(), center, chunks, capBytes());
	}

	/** Every client tick. */
	static void tick(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		LocalPlayer player = minecraft.player;
		if (!active() || level == null || player == null) {
			if (!shown.isEmpty()) {
				shown = List.of();
				RemoteDimensions.setOfflineLinks(shown);
			}
			return;
		}
		if (level != steppedOutIn) {
			steppedOutIn = level;
			steppedOut = false;
		}
		if (!steppedOut && portalAt(level, player.getBoundingBox()) == null) {
			steppedOut = true;
		}
		if (pending != null) {
			findArrival(level, player);
		}
		copySnapshots();
		if (++ticks % REFRESH_TICKS == 0) {
			List<PortalLink> near = new ArrayList<>();
			String here = level.dimension().identifier().toString();
			for (Recorded trip : recorded()) {
				if (trip.sourceDimension().equals(here)
						&& PortalGeometry.center(trip.link().source()).distanceToSqr(player.position()) <= SHOW_RADIUS * SHOW_RADIUS) {
					near.add(trip.link());
				}
			}
			// A new level starts with no links (RemoteDimensions drops them all), whatever was shown before.
			if (!near.equals(shown) || level != shownIn) {
				shown = List.copyOf(near);
				shownIn = level;
				RemoteDimensions.setOfflineLinks(shown);
			}
		}
	}

	/** Once the server has set the player down, the portal they came out of (or the End spot) is where the trip leads. */
	private static void findArrival(ClientLevel level, LocalPlayer player) {
		Pending trip = pending;
		if (level.dimension() != trip.destDimension()) {
			if (level.dimension() != trip.sourceDimension()) {
				pending = null; // went somewhere else after all
			}
			return;
		}
		if (++pendingTicks > ARRIVAL_GIVE_UP_TICKS) {
			pending = null;
			return;
		}
		BlockPos feet = player.blockPosition();
		if (pendingTicks < ARRIVAL_SETTLE_TICKS || level.getChunkSource().getChunk(feet.getX() >> 4, feet.getZ() >> 4, false) == null) {
			return;
		}
		PortalEnd dest;
		if (trip.source().fill() == PortalFill.END_PORTAL) {
			dest = VanillaPortals.endDest(trip.source().width(), trip.source().height(), player.position());
		} else {
			PortalEnd arrived = portalAt(level, player.getBoundingBox().inflate(1.0));
			if (arrived == null || arrived.fill() != PortalFill.NETHER_PORTAL) {
				return; // not yet (or never: then nothing is recorded)
			}
			dest = VanillaPortals.netherDest(trip.source(), arrived.min(), arrived.axis(), arrived.width(), arrived.height(), PortalFill.NETHER_PORTAL);
		}
		pending = null;
		String type = level.dimensionTypeRegistration().unwrapKey().map(key -> key.identifier().toString()).orElse("minecraft:overworld");
		PortalLink link = new PortalLink(trip.source(), level.dimension().identifier().toString(), type, dest);
		String from = trip.sourceDimension().identifier().toString();
		List<Recorded> all = new ArrayList<>(recorded());
		all.removeIf(old -> old.sourceDimension().equals(from) && old.link().min().equals(link.min()));
		all.add(new Recorded(from, link));
		recorded = all;
		OfflineCache.saveLinks(folder, all);
		ChunkPos center = ChunkPos.containing(BlockPos.containing(PortalGeometry.center(dest)));
		snapshots.add(new Snapshot(level, center, new ArrayDeque<>(around(center)), new ArrayList<>(), ticks + SNAPSHOT_WAIT_TICKS));
	}

	/** Copy the arrival's chunks a few a tick (all at once would stall a frame), then save them. */
	private static void copySnapshots() {
		Snapshot snapshot = snapshots.peek();
		if (snapshot == null) {
			return;
		}
		if (Minecraft.getInstance().level != snapshot.level()) {
			snapshots.poll(); // left before it was done: what's copied is still worth keeping
			OfflineCache.saveChunks(folder, snapshot.level().dimension(), snapshot.center(), snapshot.chunks(), capBytes());
			return;
		}
		// A chunk not sent yet goes to the back of the line, until the wait is over.
		boolean waiting = ticks < snapshot.until();
		int tries = Math.min(snapshot.left().size(), SNAPSHOT_CHUNKS_PER_TICK * 4);
		for (int copied = 0; copied < SNAPSHOT_CHUNKS_PER_TICK && tries-- > 0; ) {
			ChunkPos pos = snapshot.left().poll();
			LevelChunk chunk = snapshot.level().getChunkSource().getChunk(pos.x(), pos.z(), false);
			if (chunk != null) {
				snapshot.chunks().add(new ClientboundLevelChunkWithLightPacket(chunk, snapshot.level().getLightEngine(), null, null));
				copied++;
			} else if (waiting) {
				snapshot.left().add(pos);
			}
		}
		if (snapshot.left().isEmpty()) {
			snapshots.poll();
			OfflineCache.saveChunks(folder, snapshot.level().dimension(), snapshot.center(), snapshot.chunks(), capBytes());
		}
	}

	/** Give {@code view} the chunks saved around its center (a recorded portal's far end), if there are any. */
	static void seed(RemoteDimension view) {
		if (!active() || view.center() == null) {
			return;
		}
		ChunkPos center = view.center();
		OfflineCache.loadChunks(folder == null ? OfflineCache.folder() : folder, view.key, center, chunks -> {
			if (!view.isClosed() && center.equals(view.center())) {
				view.queueSeed(chunks);
			}
		});
	}

	/** The nether or end portal whose blocks {@code box} touches, or null. */
	private static PortalEnd portalAt(ClientLevel level, AABB box) {
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			BlockState state = level.getBlockState(pos);
			if (state.is(Blocks.NETHER_PORTAL)) {
				Direction.Axis axis = state.getValue(NetherPortalBlock.AXIS);
				BlockUtil.FoundRectangle rect = BlockUtil.getLargestRectangleAround(pos.immutable(), axis, 21, Direction.Axis.Y, 21, p -> level.getBlockState(p) == state);
				return PortalEnd.netherPortal(rect.minCorner, axis, rect.axis1Size, rect.axis2Size, PortalFill.NETHER_PORTAL);
			}
			if (state.is(Blocks.END_PORTAL)) {
				BlockUtil.FoundRectangle rect = BlockUtil.getLargestRectangleAround(pos.immutable(), Direction.Axis.X, 21, Direction.Axis.Z, 21,
						p -> level.getBlockState(p).is(Blocks.END_PORTAL));
				return PortalEnd.endPortal(rect.minCorner, rect.axis1Size, rect.axis2Size, PortalFill.END_PORTAL);
			}
		}
		return null;
	}

	/** The chunks a view of {@code center} holds, nearest first. */
	private static List<ChunkPos> around(ChunkPos center) {
		List<ChunkPos> chunks = new ArrayList<>();
		int radius = RemoteDimension.chunkRadius();
		for (int ring = 0; ring <= radius; ring++) {
			for (int x = center.x() - ring; x <= center.x() + ring; x++) {
				for (int z = center.z() - ring; z <= center.z() + ring; z++) {
					if (Math.max(Math.abs(x - center.x()), Math.abs(z - center.z())) == ring) {
						chunks.add(new ChunkPos(x, z));
					}
				}
			}
		}
		return chunks;
	}

	private static long capBytes() {
		return ClientConfig.get().offlineCacheMb * 1024L * 1024L;
	}
}
