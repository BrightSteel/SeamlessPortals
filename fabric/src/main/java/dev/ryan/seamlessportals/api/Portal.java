package dev.ryan.seamlessportals.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * A custom portal as it was when you got it (a snapshot: ask {@link SeamlessPortalsApi} again
 * for its current state). Its opening is the box of blocks from {@code min} to {@code max},
 * one block thick along {@code facing}, the side it's seen and entered from.
 *
 * @param name   unique: letters, digits, {@code _} or {@code -}, up to 32 characters
 * @param width  blocks across the opening
 * @param height blocks up the opening (along {@code up})
 * @param up     which way is up in the opening ({@link Direction#UP} for a wall)
 */
public record Portal(String name, ResourceKey<Level> world, BlockPos min, BlockPos max, int width, int height, Direction facing, Direction up,
		PortalFill fill, PortalAccess access, PortalDestination destination) {
	/** The opening's blocks as a box. */
	public AABB box() {
		return AABB.encapsulatingFullBlocks(this.min, this.max);
	}

	public boolean contains(ResourceKey<Level> world, BlockPos pos) {
		return this.world.equals(world) && pos.getX() >= this.min.getX() && pos.getX() <= this.max.getX()
				&& pos.getY() >= this.min.getY() && pos.getY() <= this.max.getY()
				&& pos.getZ() >= this.min.getZ() && pos.getZ() <= this.max.getZ();
	}
}
