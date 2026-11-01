package dev.ryan.seamlessportals.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;

/**
 * One end of a portal link: a flat rectangle of blocks.
 *
 * <ul>
 *   <li>{@code facing}: the side players come out of (and see the far side from);</li>
 *   <li>{@code up}: the opening's "up", in its plane (for a wall, UP; for a floor, any
 *       horizontal direction);</li>
 *   <li>{@code width} blocks along {@link #right()}, {@code height} along {@code up};
 *       {@code min} is the rectangle's lowest corner;</li>
 *   <li>{@code surface}: where the opening's plane sits within its blocks along {@code facing}'s
 *       axis, in sixteenths (8 = the middle, as for nether portals; 12 = the end portal surface).</li>
 * </ul>
 *
 * <p>Wire format: long min, byte facing, byte up (3D data values), varint width, varint height,
 * byte surface, byte fill.</p>
 */
public record PortalEnd(BlockPos min, Direction facing, Direction up, int width, int height, int surface, PortalFill fill) {
	public static final StreamCodec<ByteBuf, PortalEnd> STREAM_CODEC = StreamCodec.of(PortalEnd::write, PortalEnd::read);

	private static void write(ByteBuf buf, PortalEnd end) {
		buf.writeLong(end.min.asLong());
		buf.writeByte(end.facing.get3DDataValue());
		buf.writeByte(end.up.get3DDataValue());
		VarInt.write(buf, end.width);
		VarInt.write(buf, end.height);
		buf.writeByte(end.surface);
		buf.writeByte(end.fill.ordinal());
	}

	private static PortalEnd read(ByteBuf buf) {
		return new PortalEnd(BlockPos.of(buf.readLong()), Direction.from3DDataValue(buf.readByte()), Direction.from3DDataValue(buf.readByte()),
				VarInt.read(buf), VarInt.read(buf), buf.readByte(), PortalFill.byId(buf.readByte()));
	}

	/** The opening's "right": up × facing, so (right, up, facing) is a right-handed frame. */
	public Direction right() {
		Vec3i u = this.up.getUnitVec3i();
		Vec3i n = this.facing.getUnitVec3i();
		return Direction.getNearest(u.getY() * n.getZ() - u.getZ() * n.getY(), u.getZ() * n.getX() - u.getX() * n.getZ(),
				u.getX() * n.getY() - u.getY() * n.getX(), Direction.NORTH);
	}

	/** The axis a vanilla portal of this shape would have: its width axis, or Y when lying flat. */
	public Direction.Axis axis() {
		return this.facing.getAxis() == Direction.Axis.Y ? Direction.Axis.Y : this.right().getAxis();
	}

	/** Every block position of the rectangle. */
	public Iterable<BlockPos> blocks() {
		Vec3i r = absolute(this.right());
		Vec3i u = absolute(this.up);
		BlockPos max = this.min.offset(r.getX() * (this.width - 1) + u.getX() * (this.height - 1),
				r.getY() * (this.width - 1) + u.getY() * (this.height - 1), r.getZ() * (this.width - 1) + u.getZ() * (this.height - 1));
		return BlockPos.betweenClosed(this.min, max);
	}

	public boolean contains(BlockPos pos) {
		for (BlockPos block : this.blocks()) {
			if (block.equals(pos)) {
				return true;
			}
		}
		return false;
	}

	static Vec3i absolute(Direction direction) {
		Vec3i v = direction.getUnitVec3i();
		return new Vec3i(Math.abs(v.getX()), Math.abs(v.getY()), Math.abs(v.getZ()));
	}

	/** A vanilla nether portal of {@code axis}, facing the positive side of its normal axis. */
	public static PortalEnd netherPortal(BlockPos min, Direction.Axis axis, int width, int height, PortalFill fill) {
		return new PortalEnd(min, axis == Direction.Axis.X ? Direction.SOUTH : Direction.EAST, Direction.UP, width, height, 8, fill);
	}

	/** A flat end portal, facing up, with "up" toward south (+Z). */
	public static PortalEnd endPortal(BlockPos min, int width, int height, PortalFill fill) {
		return new PortalEnd(min, Direction.UP, Direction.SOUTH, width, height, 12, fill);
	}
}
