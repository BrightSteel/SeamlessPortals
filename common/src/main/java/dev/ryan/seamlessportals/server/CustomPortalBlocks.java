package dev.ryan.seamlessportals.server;

import dev.ryan.seamlessportals.net.PortalEnd;
import dev.ryan.seamlessportals.net.PortalFill;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/** Placing and clearing a custom portal's fill. */
public final class CustomPortalBlocks {
	/** Tell clients, but don't update neighbours: a nether portal without an obsidian frame would break itself. */
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	/** Ticks between {@link #repair} passes. */
	static final int REPAIR_INTERVAL = 10;

	private CustomPortalBlocks() {
	}

	public static BlockState fillState(PortalEnd end) {
		return switch (end.fill()) {
			case NETHER_PORTAL -> Blocks.NETHER_PORTAL.defaultBlockState()
					.setValue(NetherPortalBlock.AXIS, end.axis() == Direction.Axis.Z ? Direction.Axis.Z : Direction.Axis.X);
			case END_PORTAL -> Blocks.END_PORTAL.defaultBlockState();
			case END_GATEWAY -> Blocks.END_GATEWAY.defaultBlockState();
			case WATER -> Blocks.WATER.defaultBlockState();
			case LAVA -> Blocks.LAVA.defaultBlockState();
			case NONE, WINDOW, STARFIELD -> Blocks.AIR.defaultBlockState();
		};
	}

	/** Whether {@code fill} makes sense for an opening facing {@code facing}. */
	public static String checkFill(PortalFill fill, Direction facing) {
		if (fill.blockless()) {
			return null; // nothing physical: any way up
		}
		boolean flat = facing.getAxis() == Direction.Axis.Y;
		if (fill == PortalFill.NETHER_PORTAL && flat) {
			return "nether_portal only works upright (a wall), not as a floor or ceiling";
		}
		if (fill == PortalFill.END_PORTAL && !flat) {
			return "end_portal only shows its surface from above, so it only works as a floor or ceiling";
		}
		return null;
	}

	/** Blocks in the opening that aren't empty (or already the fill): those would be overwritten. */
	public static int blockedCount(ServerLevel level, PortalEnd end) {
		return blockedCount(level, end, null);
	}

	/** {@link #blockedCount(ServerLevel, PortalEnd)}, not counting the blocks of {@code leaving} (a portal moving out of the way). */
	public static int blockedCount(ServerLevel level, PortalEnd end, PortalEnd leaving) {
		if (end.fill().blockless()) {
			return 0; // places nothing, so nothing is in its way
		}
		BlockState fill = fillState(end);
		Set<BlockPos> leavingBlocks = new HashSet<>();
		if (leaving != null) {
			leaving.blocks().forEach(pos -> leavingBlocks.add(pos.immutable()));
		}
		int blocked = 0;
		for (BlockPos pos : end.blocks()) {
			if (leavingBlocks.contains(pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (!state.isAir() && !state.canBeReplaced() && !state.is(fill.getBlock())) {
				blocked++;
			}
		}
		return blocked;
	}

	public static void place(ServerLevel level, PortalEnd end) {
		if (end.fill().blockless()) {
			return;
		}
		BlockState fill = fillState(end);
		for (BlockPos pos : end.blocks()) {
			level.setBlock(pos, fill, FLAGS);
			// A gateway without an exit generates an outer End island the first time something
			// touches it; point it at itself (its trips are cancelled anyway).
			if (level.getBlockEntity(pos) instanceof TheEndGatewayBlockEntity gateway) {
				gateway.setExitPosition(pos.immutable(), true);
			}
		}
	}

	/**
	 * Puts back fill blocks that went missing in loaded chunks: a nether portal without a
	 * full obsidian frame removes itself on the next shape update next to it, and the
	 * platforms' protection can't catch everything.
	 */
	static void repair(MinecraftServer server) {
		for (CustomPortal portal : CustomPortals.all()) {
			ServerLevel level = CustomPortals.level(server, portal.world());
			if (level == null || portal.end().fill().blockless()) {
				continue;
			}
			BlockState fill = fillState(portal.end());
			for (BlockPos pos : portal.end().blocks()) {
				if (level.isLoaded(pos) && !level.getBlockState(pos).equals(fill)) {
					level.setBlock(pos, fill, FLAGS);
					if (level.getBlockEntity(pos) instanceof TheEndGatewayBlockEntity gateway) {
						gateway.setExitPosition(pos.immutable(), true);
					}
				}
			}
		}
	}

	public static void clear(ServerLevel level, PortalEnd end) {
		if (end.fill().blockless()) {
			return;
		}
		for (BlockPos pos : end.blocks()) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
		}
	}
}
