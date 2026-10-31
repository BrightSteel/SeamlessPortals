package dev.ryan.netherview.client;

import dev.ryan.netherview.net.PortalLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
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
 * the player's level that is every linked portal, and in a remote level it is every
 * portal (the far side of the opening must look through, not at, the destination swirl).
 *
 * <p>Read from chunk-mesh worker threads, so the state is published as immutable snapshots.</p>
 */
public final class PortalHiding {
	private static volatile Set<BlockPos> hiddenInPlayerLevel = Set.of();
	private static volatile Set<ClientLevel> remoteLevels = Set.of();

	private PortalHiding() {
	}

	public static boolean shouldHide(ClientLevel level, BlockState state, BlockPos pos) {
		if (!state.is(Blocks.NETHER_PORTAL)) {
			return false;
		}
		return remoteLevels.contains(level) || hiddenInPlayerLevel.contains(pos);
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
		Set<BlockPos> next = new HashSet<>();
		for (RemoteDimensions.Link link : links) {
			PortalLink portal = link.link();
			for (int w = 0; w < portal.width(); w++) {
				for (int h = 0; h < portal.height(); h++) {
					next.add(portal.min().relative(portal.axis(), w).above(h));
				}
			}
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
		Set<BlockPos> changed = new HashSet<>(next);
		changed.addAll(previous);
		changed.removeIf(pos -> next.contains(pos) && previous.contains(pos));
		for (BlockPos pos : changed) {
			minecraft.levelExtractor.setBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
		}
	}
}
