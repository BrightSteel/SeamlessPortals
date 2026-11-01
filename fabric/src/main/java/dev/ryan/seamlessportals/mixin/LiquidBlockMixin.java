package dev.ryan.seamlessportals.mixin;

import dev.ryan.seamlessportals.server.CustomPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A water or lava fill can't be scooped up (buckets, dispensers) or turn into obsidian or cobblestone. */
@Mixin(LiquidBlock.class)
public abstract class LiquidBlockMixin {
	@Inject(method = "pickupBlock", at = @At("HEAD"), cancellable = true)
	private void seamlessportals$keepFill(LivingEntity user, LevelAccessor level, BlockPos pos, BlockState state, CallbackInfoReturnable<ItemStack> cir) {
		if (level instanceof Level real && CustomPortals.at(real, pos) != null) {
			cir.setReturnValue(ItemStack.EMPTY);
		}
	}

	@Inject(method = "shouldSpreadLiquid", at = @At("HEAD"), cancellable = true)
	private void seamlessportals$noForming(Level level, BlockPos pos, BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (CustomPortals.at(level, pos) != null) {
			cir.setReturnValue(true);
		}
	}
}
