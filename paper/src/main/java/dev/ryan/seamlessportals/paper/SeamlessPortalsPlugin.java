package dev.ryan.seamlessportals.paper;

import dev.ryan.seamlessportals.api.SeamlessPortalsApi;
import dev.ryan.seamlessportals.net.PortalLinksPayload;
import dev.ryan.seamlessportals.net.PortalTravelPayload;
import dev.ryan.seamlessportals.net.RemoteChunkPayload;
import dev.ryan.seamlessportals.net.RemoteEntitiesPayload;
import dev.ryan.seamlessportals.server.CustomPortals;
import dev.ryan.seamlessportals.server.PayloadSender;
import dev.ryan.seamlessportals.server.PortalAdmin;
import dev.ryan.seamlessportals.server.PortalHooks;
import dev.ryan.seamlessportals.server.PortalViewServer;
import dev.ryan.seamlessportals.server.PortalWands;
import dev.ryan.seamlessportals.server.Renamed;
import dev.ryan.seamlessportals.server.ServerConfig;
import dev.ryan.seamlessportals.server.StarfieldPack;
import dev.ryan.seamlessportals.server.StarfieldPanels;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.event.entity.EntityInsideBlockEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Paper side of SeamlessPortals: runs the shared server logic every tick and delivers its
 * payloads as plugin messages. The bytes on each channel are exactly what the Fabric mod's
 * own server half sends, so the client mod handles both the same way.
 */
public final class SeamlessPortalsPlugin extends JavaPlugin implements Listener {
	private static final String[] CHANNELS = {
			PortalLinksPayload.ID.toString(),
			PortalTravelPayload.ID.toString(),
			RemoteChunkPayload.ID.toString(),
			RemoteEntitiesPayload.ID.toString()
	};

	@Override
	public void onEnable() {
		Renamed.carryOver(this.getDataFolder().toPath().resolveSibling("NetherView"), this.getDataFolder().toPath());
		for (String channel : CHANNELS) {
			this.getServer().getMessenger().registerOutgoingPluginChannel(this, channel);
		}
		this.saveDefaultConfig();
		ServerConfig defaults = ServerConfig.DEFAULTS;
		PortalViewServer.init(new PluginMessageSender(), new ServerConfig(
				this.getConfig().getInt("scan-radius", defaults.scanRadius()),
				this.getConfig().getInt("streamed-destinations", defaults.streamedDestinations()),
				this.getConfig().getInt("view-radius-chunks", defaults.viewRadiusChunks()),
				this.getConfig().getInt("chunks-per-scan", defaults.chunksPerScan()),
				this.getConfig().getDouble("entity-radius", defaults.entityRadius()),
				this.getConfig().getInt("max-portal-size", defaults.maxPortalSize()),
				this.getConfig().getBoolean("tick-destination", defaults.tickDestination()),
				this.getConfig().getBoolean("seamless-travel", defaults.seamlessTravel()),
				this.getConfig().getInt("max-portals", defaults.maxPortals())));
		this.writePack();
		PortalStore store = new PortalStore(this);
		CustomPortals.init((player, node) -> player.getBukkitEntity().hasPermission("seamlessportals." + node), store::save);
		store.load();
		PortalWands.init(player -> player.getBukkitEntity().hasPermission("seamlessportals.admin"));
		PortalAdmin.setHooks(new PaperPortalHooks());
		this.getServer().getServicesManager().register(SeamlessPortalsApi.class, new PaperPortalApi(), this, ServicePriority.Normal);
		PortalCommand command = new PortalCommand();
		this.getCommand("seamlessportals").setExecutor(command);
		this.getCommand("seamlessportals").setTabCompleter(command);
		this.getServer().getPluginManager().registerEvents(this, this);
		this.getServer().getPluginManager().registerEvents(new PortalWand(), this);
		this.getServer().getPluginManager().registerEvents(new PortalProtection(), this);
		this.getServer().getScheduler().runTaskTimer(this, () -> PortalViewServer.tick(MinecraftServer.getServer()), 1L, 1L);
	}

	@Override
	public void onDisable() {
		StarfieldPanels.clear();
		this.getServer().getServicesManager().unregisterAll(this);
		PortalAdmin.setHooks(PortalHooks.NONE);
	}

	/** The resource pack starfield portals need, for players without the mod: for the server's pack (see README). */
	private void writePack() {
		Path pack = this.getDataFolder().toPath().resolve("resource pack.zip");
		try {
			if (StarfieldPack.write(pack)) {
				this.getLogger().info("Wrote the SeamlessPortals resource pack (for starfield portals) to " + pack + ": add it to the server's resource packs.");
			}
		} catch (IOException e) {
			this.getLogger().warning("Couldn't write the SeamlessPortals resource pack: " + e.getMessage());
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		PortalViewServer.forget(((CraftPlayer) event.getPlayer()).getHandle());
	}

	/** Linked portals are crossed by going through their plane; vanilla's touch-to-teleport is skipped for mod clients. */
	@EventHandler(ignoreCancelled = true)
	public void onPortal(PlayerPortalEvent event) {
		PlayerTeleportEvent.TeleportCause cause = event.getCause();
		if ((cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL || cause == PlayerTeleportEvent.TeleportCause.END_PORTAL)
				&& PortalViewServer.handlesTravel(((CraftPlayer) event.getPlayer()).getHandle())) {
			event.setCancelled(true);
		}
	}

	/** The same for a vehicle carrying such a player: vanilla would send it on touch, before its rider reaches the plane. */
	@EventHandler(ignoreCancelled = true)
	public void onVehicleInPortal(EntityInsideBlockEvent event) {
		Material type = event.getBlock().getType();
		if ((type == Material.NETHER_PORTAL || type == Material.END_PORTAL) && !(event.getEntity() instanceof Player)
				&& !event.getEntity().getPassengers().isEmpty()) {
			Block block = event.getBlock();
			if (PortalViewServer.handlesTravel(((CraftEntity) event.getEntity()).getHandle(), new BlockPos(block.getX(), block.getY(), block.getZ()))) {
				event.setCancelled(true);
			}
		}
	}

	/**
	 * Development aid: players named in {@code -Dseamlessportals.devVanilla=<name,name>} are
	 * treated as not having the client mod, to see what such players see with a modded client.
	 */
	private static final java.util.Set<String> DEV_VANILLA = java.util.Set.of(System.getProperty("seamlessportals.devVanilla", "").split(","));

	private final class PluginMessageSender implements PayloadSender {
		@Override
		public boolean canReceive(ServerPlayer player) {
			return player.getBukkitEntity().getListeningPluginChannels().contains(PortalLinksPayload.ID.toString())
					&& !DEV_VANILLA.contains(player.getBukkitEntity().getName());
		}

		@Override
		public <T extends CustomPacketPayload> void send(ServerPlayer player, T payload, StreamCodec<ByteBuf, T> codec) {
			ByteBuf buf = Unpooled.buffer();
			try {
				codec.encode(buf, payload);
				byte[] bytes = new byte[buf.readableBytes()];
				buf.readBytes(bytes);
				player.getBukkitEntity().sendPluginMessage(SeamlessPortalsPlugin.this, payload.type().id().toString(), bytes);
			} finally {
				buf.release();
			}
		}
	}
}
