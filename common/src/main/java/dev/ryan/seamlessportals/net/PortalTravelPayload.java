package dev.ryan.seamlessportals.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → client, sent immediately before the server moves the player through a linked
 * portal: the exact portal pair used. The respawn that follows is a portal walk-through,
 * so the client may hand over its preloaded view and keep its own position; any respawn
 * without this (a /tp, death, plugin teleport) is handled the vanilla way.
 *
 * <p>Wire format: one {@link PortalLink}.</p>
 */
public record PortalTravelPayload(PortalLink link) implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("seamlessportals", "travel");
	public static final Type<PortalTravelPayload> TYPE = new Type<>(ID);
	public static final StreamCodec<ByteBuf, PortalTravelPayload> STREAM_CODEC =
			PortalLink.STREAM_CODEC.map(PortalTravelPayload::new, PortalTravelPayload::link);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
