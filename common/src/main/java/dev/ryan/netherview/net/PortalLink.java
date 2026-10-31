package dev.ryan.netherview.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One portal the player can see, paired with where it leads.
 *
 * <p>A portal is the rectangle of portal blocks: {@code min} is its lowest corner. For a
 * nether portal ({@code axis} X or Z) {@code width} runs along the axis and {@code height}
 * up Y; for an end portal ({@code axis} Y, lying flat) {@code width} runs along X and
 * {@code height} along Z. The destination is described the same way, in {@code destDimension}
 * (for an end portal it is a virtual opening just above where the player arrives).</p>
 *
 * <p>Wire format (kept to primitives so a non-Fabric server can write it):
 * long min, byte axis (0 = X, 1 = Z, 2 = Y), varint width, varint height, utf8 destDimension,
 * long destMin, byte destAxis, varint destWidth, varint destHeight.</p>
 */
public record PortalLink(
		BlockPos min, Direction.Axis axis, int width, int height,
		String destDimension, BlockPos destMin, Direction.Axis destAxis, int destWidth, int destHeight) {

	public static final StreamCodec<ByteBuf, PortalLink> STREAM_CODEC = StreamCodec.of(PortalLink::write, PortalLink::read);

	private static void write(ByteBuf buf, PortalLink link) {
		buf.writeLong(link.min.asLong());
		buf.writeByte(axisId(link.axis));
		VarInt.write(buf, link.width);
		VarInt.write(buf, link.height);
		ByteBufCodecs.STRING_UTF8.encode(buf, link.destDimension);
		buf.writeLong(link.destMin.asLong());
		buf.writeByte(axisId(link.destAxis));
		VarInt.write(buf, link.destWidth);
		VarInt.write(buf, link.destHeight);
	}

	private static PortalLink read(ByteBuf buf) {
		BlockPos min = BlockPos.of(buf.readLong());
		Direction.Axis axis = axisFromId(buf.readByte());
		int width = VarInt.read(buf);
		int height = VarInt.read(buf);
		String destDimension = ByteBufCodecs.STRING_UTF8.decode(buf);
		BlockPos destMin = BlockPos.of(buf.readLong());
		Direction.Axis destAxis = axisFromId(buf.readByte());
		int destWidth = VarInt.read(buf);
		int destHeight = VarInt.read(buf);
		return new PortalLink(min, axis, width, height, destDimension, destMin, destAxis, destWidth, destHeight);
	}

	private static int axisId(Direction.Axis axis) {
		return switch (axis) {
			case X -> 0;
			case Z -> 1;
			case Y -> 2;
		};
	}

	private static Direction.Axis axisFromId(int id) {
		return switch (id) {
			case 1 -> Direction.Axis.Z;
			case 2 -> Direction.Axis.Y;
			default -> Direction.Axis.X;
		};
	}

	/** Lets a plain {@link FriendlyByteBuf} writer (e.g. a plugin) produce the same bytes. */
	public void writeTo(FriendlyByteBuf buf) {
		write(buf, this);
	}
}
