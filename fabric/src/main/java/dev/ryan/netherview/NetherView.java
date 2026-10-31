package dev.ryan.netherview;

import dev.ryan.netherview.net.PortalLinksPayload;
import dev.ryan.netherview.net.PortalTravelPayload;
import dev.ryan.netherview.net.RemoteChunkPayload;
import dev.ryan.netherview.net.RemoteEntitiesPayload;
import dev.ryan.netherview.server.PayloadSender;
import dev.ryan.netherview.server.PortalViewServer;
import dev.ryan.netherview.server.ServerConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
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
	private static final Logger LOGGER = LoggerFactory.getLogger(NetherView.MOD_ID);

	public static final String MOD_ID = "netherview";

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.clientboundPlay().register(PortalLinksPayload.TYPE, PortalLinksPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PortalTravelPayload.TYPE, PortalTravelPayload.STREAM_CODEC);
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
		}, loadServerConfig());
		ServerTickEvents.END_SERVER_TICK.register(PortalViewServer::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PortalViewServer.forget(handler.getPlayer()));
	}

	/** Singleplayer only (the mod is client-side); servers use the Paper plugin's config.yml. */
	private static ServerConfig loadServerConfig() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("netherview-server.properties");
		ServerConfig defaults = ServerConfig.DEFAULTS;
		Properties properties = new Properties();
		try {
			if (Files.exists(file)) {
				try (Reader reader = Files.newBufferedReader(file)) {
					properties.load(reader);
				}
			} else {
				properties.setProperty("scanRadius", String.valueOf(defaults.scanRadius()));
				properties.setProperty("streamedDestinations", String.valueOf(defaults.streamedDestinations()));
				properties.setProperty("viewRadiusChunks", String.valueOf(defaults.viewRadiusChunks()));
				properties.setProperty("chunksPerScan", String.valueOf(defaults.chunksPerScan()));
				properties.setProperty("entityRadius", String.valueOf(defaults.entityRadius()));
				properties.setProperty("maxPortalSize", String.valueOf(defaults.maxPortalSize()));
				properties.setProperty("tickDestination", String.valueOf(defaults.tickDestination()));
				properties.setProperty("seamlessTravel", String.valueOf(defaults.seamlessTravel()));
				try (Writer writer = Files.newBufferedWriter(file)) {
					properties.store(writer, "NetherView singleplayer server settings (see ServerConfig for what each does)");
				}
			}
			return new ServerConfig(
					Integer.parseInt(properties.getProperty("scanRadius", String.valueOf(defaults.scanRadius()))),
					Integer.parseInt(properties.getProperty("streamedDestinations", String.valueOf(defaults.streamedDestinations()))),
					Integer.parseInt(properties.getProperty("viewRadiusChunks", String.valueOf(defaults.viewRadiusChunks()))),
					Integer.parseInt(properties.getProperty("chunksPerScan", String.valueOf(defaults.chunksPerScan()))),
					Double.parseDouble(properties.getProperty("entityRadius", String.valueOf(defaults.entityRadius()))),
					Integer.parseInt(properties.getProperty("maxPortalSize", String.valueOf(defaults.maxPortalSize()))),
					Boolean.parseBoolean(properties.getProperty("tickDestination", String.valueOf(defaults.tickDestination()))),
					Boolean.parseBoolean(properties.getProperty("seamlessTravel", String.valueOf(defaults.seamlessTravel()))));
		} catch (IOException | NumberFormatException e) {
			LOGGER.warn("Couldn't read {}, using defaults: {}", file, e.toString());
			return defaults;
		}
	}
}
