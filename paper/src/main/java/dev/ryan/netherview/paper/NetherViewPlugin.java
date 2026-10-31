package dev.ryan.netherview.paper;

import dev.ryan.netherview.net.PortalLinksPayload;
import dev.ryan.netherview.net.RemoteChunkPayload;
import dev.ryan.netherview.net.RemoteEntitiesPayload;
import dev.ryan.netherview.server.PayloadSender;
import dev.ryan.netherview.server.PortalViewServer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper side of NetherView: runs the shared server logic every tick and delivers its
 * payloads as plugin messages. The bytes on each channel are exactly what the Fabric mod's
 * own server half sends, so the client mod handles both the same way.
 */
public final class NetherViewPlugin extends JavaPlugin implements Listener {
	private static final String[] CHANNELS = {
			PortalLinksPayload.ID.toString(),
			RemoteChunkPayload.ID.toString(),
			RemoteEntitiesPayload.ID.toString()
	};

	@Override
	public void onEnable() {
		for (String channel : CHANNELS) {
			this.getServer().getMessenger().registerOutgoingPluginChannel(this, channel);
		}
		PortalViewServer.init(new PluginMessageSender());
		this.getServer().getPluginManager().registerEvents(this, this);
		this.getServer().getScheduler().runTaskTimer(this, () -> PortalViewServer.tick(MinecraftServer.getServer()), 1L, 1L);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		PortalViewServer.forget(((CraftPlayer) event.getPlayer()).getHandle());
	}

	/** Linked portals are crossed by walking through their plane; vanilla's touch-to-teleport is skipped for mod clients. */
	@EventHandler(ignoreCancelled = true)
	public void onPortal(PlayerPortalEvent event) {
		if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
				&& PortalViewServer.handlesTravel(((CraftPlayer) event.getPlayer()).getHandle())) {
			event.setCancelled(true);
		}
	}

	private final class PluginMessageSender implements PayloadSender {
		@Override
		public boolean canReceive(ServerPlayer player) {
			return player.getBukkitEntity().getListeningPluginChannels().contains(PortalLinksPayload.ID.toString());
		}

		@Override
		public <T extends CustomPacketPayload> void send(ServerPlayer player, T payload, StreamCodec<ByteBuf, T> codec) {
			ByteBuf buf = Unpooled.buffer();
			try {
				codec.encode(buf, payload);
				byte[] bytes = new byte[buf.readableBytes()];
				buf.readBytes(bytes);
				player.getBukkitEntity().sendPluginMessage(NetherViewPlugin.this, payload.type().id().toString(), bytes);
			} finally {
				buf.release();
			}
		}
	}
}
