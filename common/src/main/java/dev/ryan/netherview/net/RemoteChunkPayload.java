package dev.ryan.netherview.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → client: one chunk of a dimension the player is not in.
 *
 * <p>{@code chunkPacket} is a vanilla {@code ClientboundLevelChunkWithLightPacket} encoded
 * with its own stream codec, so the client can load it exactly like a normal chunk.</p>
 *
 * <p>Wire format: utf8 dimension, varint length + packet bytes.</p>
 */
public record RemoteChunkPayload(String dimension, byte[] chunkPacket) implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("netherview", "chunk");
	public static final Type<RemoteChunkPayload> TYPE = new Type<>(ID);
	public static final int MAX_SIZE = 8 * 1024 * 1024;
	public static final StreamCodec<ByteBuf, RemoteChunkPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, RemoteChunkPayload::dimension,
			ByteBufCodecs.byteArray(MAX_SIZE), RemoteChunkPayload::chunkPacket,
			RemoteChunkPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
