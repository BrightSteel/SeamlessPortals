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
 */
public record PortalGeometry(Vec3 sourceCenter, Vec3 sourceNormal, Vec3 destCenter, Vec3 destNormal, int quarterTurns, AABB sourceBox,
		BlockPos sourceMin, Direction.Axis sourceAxis, int sourceWidth, int sourceHeight) {

	public static PortalGeometry of(PortalLink link) {
		Vec3 sourceCenter = center(link.min(), link.axis(), link.width(), link.height());
		Vec3 destCenter = center(link.destMin(), link.destAxis(), link.destWidth(), link.destHeight());
		int turns = link.axis() == link.destAxis() ? 0 : 1;
		BlockPos max = link.min().relative(link.axis(), link.width()).above(link.height());
		AABB box = new AABB(Vec3.atLowerCornerOf(link.min()), Vec3.atLowerCornerOf(max).add(
				link.axis() == Direction.Axis.Z ? 1 : 0, 0, link.axis() == Direction.Axis.X ? 1 : 0));
		return new PortalGeometry(sourceCenter, normal(link.axis()), destCenter, normal(link.destAxis()), turns, box,
				link.min(), link.axis(), link.width(), link.height());
	}

	private static Vec3 center(BlockPos min, Direction.Axis axis, int width, int height) {
		double halfW = width / 2.0;
		return axis == Direction.Axis.X
				? new Vec3(min.getX() + halfW, min.getY() + height / 2.0, min.getZ() + 0.5)
				: new Vec3(min.getX() + 0.5, min.getY() + height / 2.0, min.getZ() + halfW);
	}

	/** A portal on axis X spans X and Y, so it faces along Z (and vice versa). */
	private static Vec3 normal(Direction.Axis axis) {
		return axis == Direction.Axis.X ? new Vec3(0, 0, 1) : new Vec3(1, 0, 0);
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
	 * (crosses its centre plane inside the rectangle).
	 */
	public boolean crossesSource(Vec3 from, Vec3 to) {
		double before = from.subtract(this.sourceCenter).dot(this.sourceNormal);
		double after = to.subtract(this.sourceCenter).dot(this.sourceNormal);
		if (before == 0 || Math.signum(before) == Math.signum(after)) {
			return false;
		}
		Vec3 hit = from.add(to.subtract(from).scale(before / (before - after)));
		Vec3 local = hit.subtract(this.sourceCenter);
		double along = this.sourceAxis == Direction.Axis.X ? local.x : local.z;
		return Math.abs(along) <= this.sourceWidth / 2.0 && Math.abs(local.y) <= this.sourceHeight / 2.0;
	}

	/** Corners of the source portal opening, pushed {@code offset} blocks toward {@code eye} along the normal. */
	public Vec3[] sourceCorners(Vec3 eye, double offset) {
		double side = Math.signum(eye.subtract(this.sourceCenter).dot(this.sourceNormal));
		if (side == 0) {
			side = 1;
		}
		Vec3 shift = this.sourceNormal.scale(side * offset);
		double halfW = this.sourceWidth / 2.0;
		double halfH = this.sourceHeight / 2.0;
		Vec3 along = this.sourceAxis == Direction.Axis.X ? new Vec3(halfW, 0, 0) : new Vec3(0, 0, halfW);
		Vec3 up = new Vec3(0, halfH, 0);
		Vec3 c = this.sourceCenter.add(shift);
		return new Vec3[] {
				c.subtract(along).subtract(up),
				c.add(along).subtract(up),
				c.add(along).add(up),
				c.subtract(along).add(up)
		};
	}
}
