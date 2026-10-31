package dev.ryan.netherview.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server → client: entity updates for a dimension the player is not in.
 *
 * <p>Each entry is one vanilla clientbound game packet (add entity, entity data, position
 * sync, ...) encoded with the game protocol's own codec, packet id included. The client
 * decodes them and runs vanilla's handlers against the remote level.</p>
 *
 * <p>Wire format: utf8 dimension, varint count, then per packet varint length + bytes.</p>
 */
public record RemoteEntitiesPayload(String dimension, List<byte[]> packets) implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("netherview", "entities");
	public static final Type<RemoteEntitiesPayload> TYPE = new Type<>(ID);
	public static final int MAX_SIZE = 4 * 1024 * 1024;
	public static final StreamCodec<ByteBuf, RemoteEntitiesPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, RemoteEntitiesPayload::dimension,
			ByteBufCodecs.byteArray(MAX_SIZE).apply(ByteBufCodecs.list()), RemoteEntitiesPayload::packets,
			RemoteEntitiesPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
