package dev.ryan.seamlessportals.client.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The held item as of the last tick. A new player object starts it empty, so its first tick
 * sees an item change and resets the attack timer (the arm dips); carried over with it.
 */
@Mixin(Player.class)
public interface PlayerAccessor {
	@Accessor("lastItemInMainHand")
	ItemStack seamlessportals$getLastItemInMainHand();

	@Accessor("lastItemInMainHand")
	void seamlessportals$setLastItemInMainHand(ItemStack stack);
}
