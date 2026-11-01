package dev.ryan.netherview.compat;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** The few calls that differ between Minecraft versions; one copy per version under fabric/src/compat. (26.2) */
public final class Versioned {
	private Versioned() {
	}

	/** Puts the item in the player's inventory, or drops it at their feet when it's full, as /give does. */
	public static void give(ServerPlayer player, ItemStack stack) {
		if (player.getInventory().add(stack)) {
			return;
		}
		ItemEntity drop = player.drop(stack, false);
		if (drop != null) {
			drop.setNoPickUpDelay();
			drop.setTarget(player.getUUID());
		}
	}
}
