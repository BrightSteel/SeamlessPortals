package dev.ryan.seamlessportals.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A portal the player can see, paired with where it leads: a {@code source} opening in the
 * player's world and a {@code dest} opening in {@code destDimension} (whose dimension type is
 * {@code destDimensionType}, so the client can recreate any world, including datapack and
 * Paper-created ones). Going in through the source's front brings you out of the destination's
 * front, with "up" kept as "up".
 *
 * <p>Wire format: source, utf8 destDimension, utf8 destDimensionType, dest.</p>
 */
public record PortalLink(PortalEnd source, String destDimension, String destDimensionType, PortalEnd dest) {
	public static final StreamCodec<ByteBuf, PortalLink> STREAM_CODEC = StreamCodec.composite(
			PortalEnd.STREAM_CODEC, PortalLink::source,
			ByteBufCodecs.STRING_UTF8, PortalLink::destDimension,
			ByteBufCodecs.STRING_UTF8, PortalLink::destDimensionType,
			PortalEnd.STREAM_CODEC, PortalLink::dest,
			PortalLink::new);

	public BlockPos min() {
		return this.source.min();
	}

	public Direction.Axis axis() {
		return this.source.axis();
	}

	public int width() {
		return this.source.width();
	}

	public int height() {
		return this.source.height();
	}

	/** The same link travelled the other way. */
	public PortalLink reversed(String sourceDimension, String sourceDimensionType) {
		return new PortalLink(this.dest, sourceDimension, sourceDimensionType, this.source);
	}
}
