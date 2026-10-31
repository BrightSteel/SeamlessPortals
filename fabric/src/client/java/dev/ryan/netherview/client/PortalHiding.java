package dev.ryan.netherview.client;

import dev.ryan.netherview.net.PortalLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which portal blocks are left out of the chunk mesh. A portal with a live view is a hole
 * in its frame with the window on its centre plane, so its swirl must not be drawn: in
 * the player's level that is every linked portal, and in a remote level it is each link's
 * destination portal (the view looks out through it, not at its swirl). Any other portal
 * seen through a window keeps its vanilla swirl: views are not rendered recursively.
 *
 * <p>Read from chunk-mesh worker threads, so the state is published as immutable snapshots.</p>
 */
public final class PortalHiding {
	private static volatile Set<BlockPos> hiddenInPlayerLevel = Set.of();
	private static volatile Set<BlockPos> hiddenInRemoteLevels = Set.of();
	private static volatile Set<ClientLevel> remoteLevels = Set.of();

	private PortalHiding() {
	}

	public static boolean shouldHide(ClientLevel level, BlockState state, BlockPos pos) {
		if (!state.is(Blocks.NETHER_PORTAL)) {
			return false;
		}
		return remoteLevels.contains(level) ? hiddenInRemoteLevels.contains(pos) : hiddenInPlayerLevel.contains(pos);
	}

	/** Whether any block the entity overlaps belongs to a portal with a live view. */
	public static boolean touchesLinkedPortal(Entity entity) {
		Set<BlockPos> hidden = hiddenInPlayerLevel;
		if (hidden.isEmpty()) {
			return false;
		}
		AABB box = entity.getBoundingBox();
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			if (hidden.contains(pos)) {
				return true;
			}
		}
		return false;
	}

	static void setRemoteLevels(Set<ClientLevel> levels) {
		remoteLevels = Set.copyOf(levels);
	}

	/** Swaps in the linked portals' blocks and re-meshes every portal that changed state. */
	static void setLinks(List<RemoteDimensions.Link> links) {
		Set<BlockPos> nextRemote = new HashSet<>();
		for (RemoteDimensions.Link link : links) {
			PortalLink portal = link.link();
			addRect(nextRemote, portal.destMin(), portal.destAxis(), portal.destWidth(), portal.destHeight());
		}
		Set<BlockPos> previousRemote = hiddenInRemoteLevels;
		if (!nextRemote.equals(previousRemote)) {
			hiddenInRemoteLevels = Set.copyOf(nextRemote);
			RemoteDimensions.markBlocksDirty(changed(previousRemote, nextRemote));
		}

		Set<BlockPos> next = new HashSet<>();
		for (RemoteDimensions.Link link : links) {
			PortalLink portal = link.link();
			addRect(next, portal.min(), portal.axis(), portal.width(), portal.height());
		}
		Set<BlockPos> previous = hiddenInPlayerLevel;
		if (next.equals(previous)) {
			return;
		}
		hiddenInPlayerLevel = Set.copyOf(next);
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		for (BlockPos pos : changed(previous, next)) {
			minecraft.levelExtractor.setBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
		}
	}

	private static void addRect(Set<BlockPos> into, BlockPos min, Direction.Axis axis, int width, int height) {
		for (int w = 0; w < width; w++) {
			for (int h = 0; h < height; h++) {
				into.add(min.relative(axis, w).above(h));
			}
		}
	}

	/** Positions in exactly one of the two sets. */
	private static Set<BlockPos> changed(Set<BlockPos> before, Set<BlockPos> after) {
		Set<BlockPos> changed = new HashSet<>(before);
		changed.addAll(after);
		changed.removeIf(pos -> before.contains(pos) && after.contains(pos));
		return changed;
	}
}
