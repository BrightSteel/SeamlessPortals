package dev.ryan.netherview;

import dev.ryan.netherview.net.PortalLinksPayload;
import dev.ryan.netherview.net.RemoteChunkPayload;
import dev.ryan.netherview.net.RemoteEntitiesPayload;
import dev.ryan.netherview.server.PayloadSender;
import dev.ryan.netherview.server.PortalViewServer;
import io.netty.buffer.ByteBuf;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public class NetherView implements ModInitializer {
	public static final String MOD_ID = "netherview";

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.clientboundPlay().register(PortalLinksPayload.TYPE, PortalLinksPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().registerLarge(RemoteChunkPayload.TYPE, RemoteChunkPayload.STREAM_CODEC, RemoteChunkPayload.MAX_SIZE);
		PayloadTypeRegistry.clientboundPlay().registerLarge(RemoteEntitiesPayload.TYPE, RemoteEntitiesPayload.STREAM_CODEC, RemoteEntitiesPayload.MAX_SIZE);

		PortalViewServer.init(new PayloadSender() {
			@Override
			public boolean canReceive(ServerPlayer player) {
				return ServerPlayNetworking.canSend(player, PortalLinksPayload.TYPE);
			}

			@Override
			public <T extends CustomPacketPayload> void send(ServerPlayer player, T payload, StreamCodec<ByteBuf, T> codec) {
				ServerPlayNetworking.send(player, payload);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(PortalViewServer::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PortalViewServer.forget(handler.getPlayer()));
	}
}
