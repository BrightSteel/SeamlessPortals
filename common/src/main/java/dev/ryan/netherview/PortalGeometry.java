package dev.ryan.netherview;

import dev.ryan.netherview.net.PortalEnd;
import dev.ryan.netherview.net.PortalLink;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/**
 * The rigid transform that carries a point from the near side of a portal to the far side.
 *
 * <p>Each end has a frame: right, up and facing (its normal, pointing out of its front). Going
 * in through the source's front brings you out of the destination's front with "up" kept as
 * "up": the rotation maps the source's -facing to the destination's +facing, up to up, and
 * (so it stays a rotation) -right to right. Then it translates centre to centre. Any pair of
 * orientations works: wall to wall, wall to floor, floor to ceiling.</p>
 */
public record PortalGeometry(PortalEnd sourceEnd, PortalEnd destEnd, Vec3 sourceCenter, Vec3 sourceNormal, Vec3 destCenter, Vec3 destNormal,
		Matrix3d rotation, AABB sourceBox) {

	/** Height of the end portal surface within its block. */
	public static final double END_PORTAL_SURFACE = 0.75;

	public static PortalGeometry of(PortalLink link) {
		PortalEnd source = link.source();
		PortalEnd dest = link.dest();
		// columns (right, up, facing) of each frame; M = destFrame · flip(right, facing) · sourceFrameᵀ
		Matrix3d sourceFrame = frame(source);
		Matrix3d destFrame = frame(dest);
		Matrix3d flip = new Matrix3d().scaling(-1.0, 1.0, -1.0);
		Matrix3d rotation = new Matrix3d(destFrame).mul(flip).mul(new Matrix3d(sourceFrame).transpose());
		return new PortalGeometry(source, dest, center(source), unit(source.facing()), center(dest), unit(dest.facing()), rotation, box(source));
	}

	private static Matrix3d frame(PortalEnd end) {
		Vec3 r = unit(end.right());
		Vec3 u = unit(end.up());
		Vec3 n = unit(end.facing());
		return new Matrix3d(r.x, r.y, r.z, u.x, u.y, u.z, n.x, n.y, n.z);
	}

	private static Vec3 unit(Direction direction) {
		return Vec3.atLowerCornerOf(direction.getUnitVec3i());
	}

	private static Vec3 absolute(Direction direction) {
		Vec3i v = direction.getUnitVec3i();
		return new Vec3(Math.abs(v.getX()), Math.abs(v.getY()), Math.abs(v.getZ()));
	}

	/** The centre of an end's opening, on its plane. */
	public static Vec3 center(PortalEnd end) {
		Vec3 extent = absolute(end.right()).scale(end.width()).add(absolute(end.up()).scale(end.height()));
		Vec3 inPlane = Vec3.atLowerCornerOf(end.min()).add(extent.scale(0.5));
		// the plane's coordinate along the normal axis: block start + surface sixteenths
		Vec3 normalAxis = absolute(end.facing());
		double planeCoordinate = normalAxis.dot(Vec3.atLowerCornerOf(end.min())) + end.surface() / 16.0;
		return inPlane.subtract(normalAxis.scale(normalAxis.dot(inPlane))).add(normalAxis.scale(planeCoordinate));
	}

	/** The end's blocks' bounding box. */
	public static AABB box(PortalEnd end) {
		Vec3 start = Vec3.atLowerCornerOf(end.min());
		Vec3 extent = absolute(end.right()).scale(end.width()).add(absolute(end.up()).scale(end.height())).add(absolute(end.facing()));
		return new AABB(start, start.add(extent));
	}

	public boolean horizontal() {
		return this.sourceEnd.facing().getAxis() == Direction.Axis.Y;
	}

	/** Rotates a direction (a velocity, a look vector) from the source side to the destination side. */
	public Vec3 rotate(Vec3 v) {
		Vector3d out = this.rotation.transform(new Vector3d(v.x, v.y, v.z));
		return new Vec3(out.x, out.y, out.z);
	}

	public Vec3 toDest(Vec3 sourcePoint) {
		return this.destCenter.add(this.rotate(sourcePoint.subtract(this.sourceCenter)));
	}

	/** A camera's orientation (view to world) carried through: roll included, so the view lines up exactly. */
	public Quaternionf toDest(Quaternionf orientation) {
		Quaterniond turn = new Quaterniond().setFromNormalized(this.rotation);
		Quaterniond carried = turn.mul(new Quaterniond(orientation.x, orientation.y, orientation.z, orientation.w));
		return new Quaternionf((float) carried.x, (float) carried.y, (float) carried.z, (float) carried.w);
	}

	/**
	 * A player's look direction carried through, as {yaw, pitch}. Players can't roll, so a
	 * portal pair that would roll the view (e.g. wall to ceiling) keeps the look direction and
	 * levels the horizon.
	 */
	public float[] toDestRotation(float yRot, float xRot) {
		Vec3 look = Vec3.directionFromRotation(xRot, yRot);
		Vec3 carried = this.rotate(look);
		float yaw = (float) Math.toDegrees(Math.atan2(-carried.x, carried.z));
		float pitch = (float) Math.toDegrees(Math.asin(Math.clamp(-carried.y, -1.0, 1.0)));
		return new float[] {yaw, pitch};
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
		return Math.abs(local.dot(unit(this.sourceEnd.right()))) <= this.sourceEnd.width() / 2.0
				&& Math.abs(local.dot(unit(this.sourceEnd.up()))) <= this.sourceEnd.height() / 2.0;
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
		Vec3 along = unit(this.sourceEnd.right()).scale(this.sourceEnd.width() / 2.0 + margin);
		Vec3 across = unit(this.sourceEnd.up()).scale(this.sourceEnd.height() / 2.0 + margin);
		Vec3 c = this.sourceCenter.add(shift);
		return new Vec3[] {
				c.subtract(along).subtract(across),
				c.add(along).subtract(across),
				c.add(along).add(across),
				c.subtract(along).add(across)
		};
	}

	/** The source's block positions (for hiding its fill). */
	public Iterable<BlockPos> sourceBlocks() {
		return this.sourceEnd.blocks();
	}
}
