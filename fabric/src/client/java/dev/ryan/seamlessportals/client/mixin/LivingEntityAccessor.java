package dev.ryan.seamlessportals.client.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The attack and item-swap timers, carried over to the new player object after a portal hand-off. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
	@Accessor("attackStrengthTicker")
	int seamlessportals$getAttackStrengthTicker();

	@Accessor("attackStrengthTicker")
	void seamlessportals$setAttackStrengthTicker(int ticks);

	@Accessor("itemSwapTicker")
	int seamlessportals$getItemSwapTicker();

	@Accessor("itemSwapTicker")
	void seamlessportals$setItemSwapTicker(int ticks);
}
