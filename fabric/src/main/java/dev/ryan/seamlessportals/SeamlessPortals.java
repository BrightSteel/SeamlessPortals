package dev.ryan.seamlessportals;

import dev.ryan.seamlessportals.net.PortalLinksPayload;
import dev.ryan.seamlessportals.net.PortalTravelPayload;
import dev.ryan.seamlessportals.net.RemoteChunkPayload;
import dev.ryan.seamlessportals.net.RemoteEntitiesPayload;
import dev.ryan.seamlessportals.server.PayloadSender;
import dev.ryan.seamlessportals.server.PortalViewServer;
import dev.ryan.seamlessportals.server.Renamed;
import dev.ryan.seamlessportals.server.ServerConfig;
import dev.ryan.seamlessportals.server.StarfieldPack;
import dev.ryan.seamlessportals.server.StarfieldPanels;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import dev.ryan.seamlessportals.server.CustomPortals;
import dev.ryan.seamlessportals.server.PortalAdmin;
import dev.ryan.seamlessportals.server.PortalWands;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import io.netty.buffer.ByteBuf;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public class SeamlessPortals implements ModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger(SeamlessPortals.MOD_ID);

	public static final String MOD_ID = "seamlessportals";

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

		// Custom portals, as on Paper: permission nodes seamlessportals.admin, seamlessportals.use.<name>
		// and seamlessportals.see.<name> (through Fabric's permission API, so a permissions mod can
		// grant them), falling back to op level 2.
		PortalWands.init(player -> player.checkPermission(id("admin"), PermissionLevel.GAMEMASTERS));
		PortalAdmin.setHooks(new FabricPortalHooks());
		CustomPortals.init((player, node) -> !node.endsWith("*") && player.checkPermission(id(node), PermissionLevel.GAMEMASTERS),
				PortalStore::save);
		ServerLifecycleEvents.SERVER_STARTED.register(PortalStore::load);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> writePack());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> StarfieldPanels.clear());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PortalStore.unload());
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> PortalCommand.register(dispatcher));
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
				player instanceof ServerPlayer serverPlayer && PortalWands.click(serverPlayer, player.getItemInHand(hand), pos, true)
						? InteractionResult.FAIL : InteractionResult.PASS);
		UseBlockCallback.EVENT.register((player, level, hand, hit) ->
				hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer serverPlayer
						&& PortalWands.click(serverPlayer, player.getItemInHand(hand), hit.getBlockPos(), false)
						? InteractionResult.FAIL : InteractionResult.PASS);
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (CustomPortals.at(level, pos) == null) {
				return true;
			}
			if (player instanceof ServerPlayer serverPlayer && PortalWands.isAdmin(serverPlayer)) {
				serverPlayer.sendOverlayMessage(Component.literal("That's a custom portal: remove it with /sportal delete <name>").withStyle(ChatFormatting.LIGHT_PURPLE));
			}
			return false;
		});
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** Singleplayer or a Fabric server; Paper servers use the plugin's config.yml. */
	private static ServerConfig loadServerConfig() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("seamlessportals-server.properties");
		Renamed.carryOver(FabricLoader.getInstance().getConfigDir().resolve("netherview-server.properties"), file);
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
				properties.setProperty("maxPortals", String.valueOf(defaults.maxPortals()));
				try (Writer writer = Files.newBufferedWriter(file)) {
					properties.store(writer, "SeamlessPortals server settings (see ServerConfig for what each does)");
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
					Boolean.parseBoolean(properties.getProperty("seamlessTravel", String.valueOf(defaults.seamlessTravel()))),
					Integer.parseInt(properties.getProperty("maxPortals", String.valueOf(defaults.maxPortals()))));
		} catch (IOException | NumberFormatException e) {
			LOGGER.warn("Couldn't read {}, using defaults: {}", file, e.toString());
			return defaults;
		}
	}

	/** The resource pack starfield portals need, for players without the mod: for the server's pack (see README). */
	private static void writePack() {
		Path pack = FabricLoader.getInstance().getConfigDir().resolve("seamlessportals").resolve("resource pack.zip");
		try {
			if (StarfieldPack.write(pack)) {
				LOGGER.info("Wrote the SeamlessPortals resource pack (for starfield portals) to {}: add it to the server's resource packs.", pack);
			}
		} catch (IOException e) {
			LOGGER.warn("Couldn't write the SeamlessPortals resource pack: {}", e.getMessage());
		}
	}

}
