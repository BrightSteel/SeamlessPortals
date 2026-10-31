package dev.ryan.netherview;

import dev.ryan.netherview.net.PortalLink;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The rigid transform that carries a point from the near side of a portal to the far side:
 * translate so the portal centres coincide, and turn 90° about Y when the two portals face
 * different axes (the same turn vanilla applies to a player who walks through).
 *
 * <p>Two kinds of opening:</p>
 * <ul>
 *   <li><b>Vertical</b> (nether portals, axis X or Z): {@code width} runs along the axis,
 *       {@code height} up Y, and the opening's plane is the middle of the portal blocks.</li>
 *   <li><b>Horizontal</b> (end portals, axis Y): {@code width} runs along X, {@code height}
 *       along Z, and the plane is the top of the end portal surface, 0.75 into the block.</li>
 * </ul>
 */
public record PortalGeometry(Vec3 sourceCenter, Vec3 sourceNormal, Vec3 destCenter, Vec3 destNormal, int quarterTurns, AABB sourceBox,
		BlockPos sourceMin, Direction.Axis sourceAxis, int sourceWidth, int sourceHeight) {

	/** Height of the end portal surface within its block. */
	public static final double END_PORTAL_SURFACE = 0.75;

	public static PortalGeometry of(PortalLink link) {
		Vec3 sourceCenter = center(link.min(), link.axis(), link.width(), link.height());
		Vec3 destCenter = center(link.destMin(), link.destAxis(), link.destWidth(), link.destHeight());
		boolean bothVertical = link.axis() != Direction.Axis.Y && link.destAxis() != Direction.Axis.Y;
		int turns = bothVertical && link.axis() != link.destAxis() ? 1 : 0;
		return new PortalGeometry(sourceCenter, normal(link.axis()), destCenter, normal(link.destAxis()), turns,
				box(link.min(), link.axis(), link.width(), link.height()), link.min(), link.axis(), link.width(), link.height());
	}

	/** The plane centre of a portal rectangle (see the class comment for the two kinds). */
	public static Vec3 center(BlockPos min, Direction.Axis axis, int width, int height) {
		double halfW = width / 2.0;
		return switch (axis) {
			case X -> new Vec3(min.getX() + halfW, min.getY() + height / 2.0, min.getZ() + 0.5);
			case Z -> new Vec3(min.getX() + 0.5, min.getY() + height / 2.0, min.getZ() + halfW);
			case Y -> new Vec3(min.getX() + halfW, min.getY() + END_PORTAL_SURFACE, min.getZ() + height / 2.0);
		};
	}

	/** The portal blocks' bounding box. */
	public static AABB box(BlockPos min, Direction.Axis axis, int width, int height) {
		Vec3 start = Vec3.atLowerCornerOf(min);
		return switch (axis) {
			case X -> new AABB(start, start.add(width, height, 1));
			case Z -> new AABB(start, start.add(1, height, width));
			case Y -> new AABB(start, start.add(width, 1, height));
		};
	}

	/** Every block position of a portal rectangle. */
	public static Iterable<BlockPos> blocks(BlockPos min, Direction.Axis axis, int width, int height) {
		BlockPos max = switch (axis) {
			case X -> min.offset(width - 1, height - 1, 0);
			case Z -> min.offset(0, height - 1, width - 1);
			case Y -> min.offset(width - 1, 0, height - 1);
		};
		return BlockPos.betweenClosed(min, max);
	}

	/** A portal on axis X spans X and Y, so it faces along Z (and vice versa); an end portal faces up. */
	private static Vec3 normal(Direction.Axis axis) {
		return switch (axis) {
			case X -> new Vec3(0, 0, 1);
			case Z -> new Vec3(1, 0, 0);
			case Y -> new Vec3(0, 1, 0);
		};
	}

	public boolean horizontal() {
		return this.sourceAxis == Direction.Axis.Y;
	}

	/** Rotates a horizontal direction by the portal pair's yaw turn: +90° yaw maps (x, z) to (-z, x). */
	public Vec3 rotate(Vec3 v) {
		Vec3 out = v;
		for (int i = 0; i < this.quarterTurns; i++) {
			out = new Vec3(-out.z, out.y, out.x);
		}
		return out;
	}

	public Vec3 toDest(Vec3 sourcePoint) {
		return this.destCenter.add(this.rotate(sourcePoint.subtract(this.sourceCenter)));
	}

	public float toDestYaw(float yRot) {
		return yRot + 90.0F * this.quarterTurns;
	}

	/**
	 * Whether the segment {@code from → to} passes through the source portal's opening
	 * (crosses its plane inside the rectangle).
	 */
	public boolean crossesSource(Vec3 from, Vec3 to) {
		double before = from.subtract(this.sourceCenter).dot(this.sourceNormal);
		double after = to.subtract(this.sourceCenter).dot(this.sourceNormal);
		if (before == 0 || Math.signum(before) == Math.signum(after)) {
			return false;
		}
		Vec3 hit = from.add(to.subtract(from).scale(before / (before - after)));
		Vec3 local = hit.subtract(this.sourceCenter);
		double along = this.sourceAxis == Direction.Axis.Z ? local.z : local.x;
		double across = this.sourceAxis == Direction.Axis.Y ? local.z : local.y;
		return Math.abs(along) <= this.sourceWidth / 2.0 && Math.abs(across) <= this.sourceHeight / 2.0;
	}

	/** Corners of the source portal opening, pushed {@code offset} blocks toward {@code eye} along the normal. */
	public Vec3[] sourceCorners(Vec3 eye, double offset) {
		return this.sourceCorners(eye, offset, 0.0);
	}

	/** As {@link #sourceCorners(Vec3, double)}, with the rectangle grown by {@code margin} blocks on every side. */
	public Vec3[] sourceCorners(Vec3 eye, double offset, double margin) {
		double side = Math.signum(eye.subtract(this.sourceCenter).dot(this.sourceNormal));
		if (side == 0) {
			side = 1;
		}
		Vec3 shift = this.sourceNormal.scale(side * offset);
		double halfW = this.sourceWidth / 2.0 + margin;
		double halfH = this.sourceHeight / 2.0 + margin;
		Vec3 along = this.sourceAxis == Direction.Axis.Z ? new Vec3(0, 0, halfW) : new Vec3(halfW, 0, 0);
		Vec3 across = this.sourceAxis == Direction.Axis.Y ? new Vec3(0, 0, halfH) : new Vec3(0, halfH, 0);
		Vec3 c = this.sourceCenter.add(shift);
		return new Vec3[] {
				c.subtract(along).subtract(across),
				c.add(along).subtract(across),
				c.add(along).add(across),
				c.subtract(along).add(across)
		};
	}
}
