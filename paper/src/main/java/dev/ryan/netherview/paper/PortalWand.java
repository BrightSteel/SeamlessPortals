package dev.ryan.netherview.paper;

import dev.ryan.netherview.server.PortalWands;
import net.minecraft.core.BlockPos;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Passes portal wand clicks to {@link PortalWands} (which also draws the outlines). */
final class PortalWand implements Listener {
	@EventHandler
	public void onClick(PlayerInteractEvent event) {
		Block block = event.getClickedBlock();
		if (event.getHand() != EquipmentSlot.HAND || block == null || event.getItem() == null
				|| event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
			return;
		}
		if (PortalWands.click(((CraftPlayer) event.getPlayer()).getHandle(), CraftItemStack.asNMSCopy(event.getItem()),
				new BlockPos(block.getX(), block.getY(), block.getZ()), event.getAction() == Action.LEFT_CLICK_BLOCK)) {
			event.setCancelled(true);
		}
	}
}
