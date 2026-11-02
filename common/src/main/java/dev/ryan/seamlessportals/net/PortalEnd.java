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
 *       axis, in sixteenths (8 = the middle, as for nether portals; 12 = the end portal surface);</li>
 *   <li>{@code insets}: how far the opening is pulled in from its blocks' edges;</li>
 *   <li>{@code frontOnly}: only what goes in from its front goes through; from behind it's just
 *       an opening.</li>
 * </ul>
 *
 * <p>Wire format: long min, byte facing, byte up (3D data values), varint width, varint height,
 * byte surface, byte fill, then the insets as four bytes (left, right, bottom, top), then a
 * boolean frontOnly.</p>
 */
public record PortalEnd(BlockPos min, Direction facing, Direction up, int width, int height, int surface, PortalFill fill, PortalInsets insets,
		boolean frontOnly) {
	public static final StreamCodec<ByteBuf, PortalEnd> STREAM_CODEC = StreamCodec.of(PortalEnd::write, PortalEnd::read);

	/** An end whose opening fills its blocks. */
	public PortalEnd(BlockPos min, Direction facing, Direction up, int width, int height, int surface, PortalFill fill) {
		this(min, facing, up, width, height, surface, fill, PortalInsets.NONE, false);
	}

	private static void write(ByteBuf buf, PortalEnd end) {
		buf.writeLong(end.min.asLong());
		buf.writeByte(end.facing.get3DDataValue());
		buf.writeByte(end.up.get3DDataValue());
		VarInt.write(buf, end.width);
		VarInt.write(buf, end.height);
		buf.writeByte(end.surface);
		buf.writeByte(end.fill.ordinal());
		buf.writeByte(end.insets.left());
		buf.writeByte(end.insets.right());
		buf.writeByte(end.insets.bottom());
		buf.writeByte(end.insets.top());
		buf.writeBoolean(end.frontOnly);
	}

	private static PortalEnd read(ByteBuf buf) {
		return new PortalEnd(BlockPos.of(buf.readLong()), Direction.from3DDataValue(buf.readByte()), Direction.from3DDataValue(buf.readByte()),
				VarInt.read(buf), VarInt.read(buf), buf.readByte(), PortalFill.byId(buf.readByte()),
				new PortalInsets(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte()), buf.readBoolean());
	}

	/** The opening's "right": up × facing, so (right, up, facing) is a right-handed frame. */
	public Direction right() {
		Vec3i u = this.up.getUnitVec3i();
		Vec3i n = this.facing.getUnitVec3i();
		return Direction.getNearest(u.getY() * n.getZ() - u.getZ() * n.getY(), u.getZ() * n.getX() - u.getX() * n.getZ(),
				u.getX() * n.getY() - u.getY() * n.getX(), Direction.NORTH);
	}

	/** This end with its opening pulled in by {@code insets}. */
	public PortalEnd withInsets(PortalInsets insets) {
		return new PortalEnd(this.min, this.facing, this.up, this.width, this.height, this.surface, this.fill, insets, this.frontOnly);
	}

	/** This end going through only from its front ({@code frontOnly}), or from either side. */
	public PortalEnd withFrontOnly(boolean frontOnly) {
		return new PortalEnd(this.min, this.facing, this.up, this.width, this.height, this.surface, this.fill, this.insets, frontOnly);
	}

	/** This end with its plane at {@code surface} sixteenths. */
	public PortalEnd withSurface(int surface) {
		return new PortalEnd(this.min, this.facing, this.up, this.width, this.height, surface, this.fill, this.insets, this.frontOnly);
	}

	/** Half the opening's width along {@link #right()}, in blocks, insets taken off. */
	public double halfWidth() {
		return this.width / 2.0 - (this.insets.left() + this.insets.right()) / 32.0;
	}

	/** Half the opening's height along {@code up}, in blocks, insets taken off. */
	public double halfHeight() {
		return this.height / 2.0 - (this.insets.bottom() + this.insets.top()) / 32.0;
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
