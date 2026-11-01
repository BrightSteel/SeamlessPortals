package dev.ryan.netherview.client.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The attack and item-swap timers, carried over to the new player object after a portal hand-off. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
	@Accessor("attackStrengthTicker")
	int netherview$getAttackStrengthTicker();

	@Accessor("attackStrengthTicker")
	void netherview$setAttackStrengthTicker(int ticks);

	@Accessor("itemSwapTicker")
	int netherview$getItemSwapTicker();

	@Accessor("itemSwapTicker")
	void netherview$setItemSwapTicker(int ticks);
}
