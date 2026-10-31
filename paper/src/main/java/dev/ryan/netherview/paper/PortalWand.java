package dev.ryan.netherview.paper;

import dev.ryan.netherview.PortalGeometry;
import dev.ryan.netherview.server.CustomPortal;
import dev.ryan.netherview.server.CustomPortals;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The portal wand: a stick that marks two corners of a new portal (left click, right click),
 * like WorldEdit's. While held, the selection and nearby custom portals are outlined.
 */
final class PortalWand implements Listener {
	private static final double OUTLINE_STEP = 0.25;
	private static final double SHOW_PORTALS_WITHIN = 48.0;
	private static final Particle.DustOptions SELECTION = new Particle.DustOptions(Color.fromRGB(80, 220, 255), 0.7F);
	private static final Particle.DustOptions PORTAL = new Particle.DustOptions(Color.fromRGB(190, 90, 255), 0.6F);

	/** Two corners in one world; either may still be unset. */
	record Selection(World world, Block first, Block second) {
		boolean complete() {
			return this.first != null && this.second != null;
		}
	}

	private final NamespacedKey key;
	private final Map<UUID, Selection> selections = new HashMap<>();

	PortalWand(JavaPlugin plugin) {
		this.key = new NamespacedKey(plugin, "wand");
		plugin.getServer().getScheduler().runTaskTimer(plugin, this::drawOutlines, 10L, 10L);
	}

	ItemStack create() {
		ItemStack stick = new ItemStack(Material.STICK);
		ItemMeta meta = stick.getItemMeta();
		meta.displayName(Component.text("Portal Wand", NamedTextColor.LIGHT_PURPLE));
		meta.lore(List.of(
				Component.text("Left click: first corner", NamedTextColor.GRAY),
				Component.text("Right click: second corner", NamedTextColor.GRAY),
				Component.text("Then /nvportal create <name> [fill]", NamedTextColor.GRAY)));
		meta.getPersistentDataContainer().set(this.key, PersistentDataType.BYTE, (byte) 1);
		stick.setItemMeta(meta);
		return stick;
	}

	boolean isWand(ItemStack item) {
		return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(this.key, PersistentDataType.BYTE);
	}

	Selection selection(Player player) {
		return this.selections.get(player.getUniqueId());
	}

	@EventHandler
	public void onClick(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND || !this.isWand(event.getItem()) || event.getClickedBlock() == null
				|| !event.getPlayer().hasPermission("netherview.admin")) {
			return;
		}
		boolean first = event.getAction() == Action.LEFT_CLICK_BLOCK;
		if (!first && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
			return;
		}
		event.setCancelled(true);
		Player player = event.getPlayer();
		Block block = event.getClickedBlock();
		Selection old = this.selections.get(player.getUniqueId());
		if (old == null || !old.world().equals(block.getWorld())) {
			old = new Selection(block.getWorld(), null, null);
		}
		Selection updated = first ? new Selection(block.getWorld(), block, old.second()) : new Selection(block.getWorld(), old.first(), block);
		this.selections.put(player.getUniqueId(), updated);
		Component message = Component.text((first ? "First" : "Second") + " corner set to " + block.getX() + " " + block.getY() + " " + block.getZ(), NamedTextColor.AQUA);
		if (updated.complete()) {
			int dx = Math.abs(updated.first().getX() - updated.second().getX()) + 1;
			int dy = Math.abs(updated.first().getY() - updated.second().getY()) + 1;
			int dz = Math.abs(updated.first().getZ() - updated.second().getZ()) + 1;
			message = message.append(Component.text(" (" + dx + "×" + dy + "×" + dz + ")", NamedTextColor.GRAY));
		}
		player.sendMessage(message);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		this.selections.remove(event.getPlayer().getUniqueId());
	}

	private void drawOutlines() {
		for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
			if (!this.isWand(player.getInventory().getItemInMainHand()) || !player.hasPermission("netherview.admin")) {
				continue;
			}
			Selection selection = this.selections.get(player.getUniqueId());
			if (selection != null && selection.world().equals(player.getWorld())) {
				Block a = selection.first() != null ? selection.first() : selection.second();
				Block b = selection.second() != null ? selection.second() : selection.first();
				if (a != null) {
					outline(player, new AABB(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
							Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1), SELECTION);
				}
			}
			ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
			Location at = player.getLocation();
			for (CustomPortal portal : CustomPortals.near(level, new net.minecraft.world.phys.Vec3(at.getX(), at.getY(), at.getZ()), SHOW_PORTALS_WITHIN)) {
				outline(player, PortalGeometry.box(portal.end()), PORTAL);
			}
		}
	}

	/** Particles along a box's twelve edges, visible to this player only. */
	private static void outline(Player player, AABB box, Particle.DustOptions dust) {
		double[] xs = {box.minX, box.maxX};
		double[] ys = {box.minY, box.maxY};
		double[] zs = {box.minZ, box.maxZ};
		for (double y : ys) {
			for (double z : zs) {
				line(player, box.minX, y, z, box.maxX, y, z, dust);
			}
		}
		for (double x : xs) {
			for (double z : zs) {
				line(player, x, box.minY, z, x, box.maxY, z, dust);
			}
		}
		for (double x : xs) {
			for (double y : ys) {
				line(player, x, y, box.minZ, x, y, box.maxZ, dust);
			}
		}
	}

	private static void line(Player player, double x0, double y0, double z0, double x1, double y1, double z1, Particle.DustOptions dust) {
		double length = Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0));
		int steps = Math.max(1, (int) Math.ceil(length / OUTLINE_STEP));
		for (int i = 0; i <= steps; i++) {
			double t = (double) i / steps;
			player.spawnParticle(Particle.DUST, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t, 1, 0, 0, 0, 0, dust);
		}
	}
}
