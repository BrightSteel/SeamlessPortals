package dev.ryan.netherview.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/** Server → client: the full set of portals near the player and where each one leads. */
public record PortalLinksPayload(List<PortalLink> links) implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("netherview", "links");
	public static final Type<PortalLinksPayload> TYPE = new Type<>(ID);
	public static final StreamCodec<ByteBuf, PortalLinksPayload> STREAM_CODEC =
			PortalLink.STREAM_CODEC.apply(ByteBufCodecs.list()).map(PortalLinksPayload::new, PortalLinksPayload::links);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
