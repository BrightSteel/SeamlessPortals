package dev.ryan.seamlessportals.mixin;

import dev.ryan.seamlessportals.server.CustomPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Nothing is poured into a custom portal. */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
	@Inject(method = "emptyContents", at = @At("HEAD"), cancellable = true)
	private void seamlessportals$noPouringIn(LivingEntity user, Level level, BlockPos pos, BlockHitResult hitResult, CallbackInfoReturnable<Boolean> cir) {
		if (CustomPortals.at(level, pos) != null) {
			cir.setReturnValue(false);
		}
	}
}
