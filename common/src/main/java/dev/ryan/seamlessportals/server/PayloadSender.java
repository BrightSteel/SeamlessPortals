package dev.ryan.seamlessportals.server;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * How the server half reaches a client: Fabric's networking API in the mod, plugin
 * messages on Paper. Both put the same bytes (the payload's stream codec output) on the
 * same channel, so the client can't tell them apart.
 */
public interface PayloadSender {
	/** Whether this player runs the SeamlessPortals client mod. */
	boolean canReceive(ServerPlayer player);

	<T extends CustomPacketPayload> void send(ServerPlayer player, T payload, StreamCodec<ByteBuf, T> codec);
}
