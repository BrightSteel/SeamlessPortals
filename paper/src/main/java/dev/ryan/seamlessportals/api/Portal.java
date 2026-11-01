package dev.ryan.seamlessportals.api;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

/**
 * A custom portal as it was when you got it (a snapshot: ask the API again for its current
 * state). Its opening is the box of blocks from {@code min} to {@code max}, one block thick
 * along {@code facing}, the side it's seen and entered from.
 *
 * @param name        unique: letters, digits, {@code _} or {@code -}, up to 32 characters
 * @param min         the opening's lowest-coordinate block
 * @param max         the opening's highest-coordinate block
 * @param width       blocks across the opening
 * @param height      blocks up the opening (along {@code up})
 * @param facing      the side it's seen and entered from
 * @param up          which way is up in the opening ({@link BlockFace#UP} for a wall)
 */
public record Portal(String name, World world, Location min, Location max, int width, int height, BlockFace facing, BlockFace up,
		PortalFill fill, PortalAccess access, PortalDestination destination) {
	public Portal {
		min = min.clone();
		max = max.clone();
	}

	@Override
	public Location min() {
		return this.min.clone();
	}

	@Override
	public Location max() {
		return this.max.clone();
	}

	/** The middle of the opening. */
	public Location center() {
		return new Location(this.world, (this.min.getBlockX() + this.max.getBlockX() + 1) / 2.0,
				(this.min.getBlockY() + this.max.getBlockY() + 1) / 2.0, (this.min.getBlockZ() + this.max.getBlockZ() + 1) / 2.0);
	}

	/** Whether {@code location} is in one of the opening's blocks. */
	public boolean contains(Location location) {
		return location.getWorld() != null && location.getWorld().equals(this.world)
				&& location.getBlockX() >= this.min.getBlockX() && location.getBlockX() <= this.max.getBlockX()
				&& location.getBlockY() >= this.min.getBlockY() && location.getBlockY() <= this.max.getBlockY()
				&& location.getBlockZ() >= this.min.getBlockZ() && location.getBlockZ() <= this.max.getBlockZ();
	}
}
