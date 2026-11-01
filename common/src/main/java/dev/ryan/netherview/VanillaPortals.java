package dev.ryan.netherview;

import dev.ryan.netherview.net.PortalEnd;
import dev.ryan.netherview.net.PortalFill;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Where vanilla portals lead, as link ends: shared by the server half (which works it out the
 * way vanilla does) and the client on servers without it (which records where a trip landed).
 */
public final class VanillaPortals {
	/** How far above where a player lands the far end of an end portal's flat opening sits. */
	public static final double END_DROP = 1.75;

	private VanillaPortals() {
	}

	/** The far end of a nether portal: the portal at {@code min}, facing the way the player comes out. */
	public static PortalEnd netherDest(PortalEnd source, BlockPos min, Direction.Axis destAxis, int width, int height, PortalFill fill) {
		Direction carried = source.axis() == destAxis ? source.facing() : source.facing().getClockWise();
		return new PortalEnd(min, carried.getOpposite(), Direction.UP, width, height, 8, fill);
	}

	/** The far end of a {@code width} × {@code height} end portal: a flat opening of its size over {@code arrival}. */
	public static PortalEnd endDest(int width, int height, Vec3 arrival) {
		double planeY = arrival.y + END_DROP;
		BlockPos min = BlockPos.containing(arrival.x - width / 2.0, planeY - PortalGeometry.END_PORTAL_SURFACE, arrival.z - height / 2.0);
		return new PortalEnd(min, Direction.DOWN, Direction.SOUTH, width, height, 12, PortalFill.NONE);
	}
}
