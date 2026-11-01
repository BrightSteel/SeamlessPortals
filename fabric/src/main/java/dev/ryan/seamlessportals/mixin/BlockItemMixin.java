package dev.ryan.seamlessportals.mixin;

import dev.ryan.seamlessportals.server.CustomPortals;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Blocks can't be placed into a custom portal (water and lava fills are replaceable). */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@Inject(method = "place", at = @At("HEAD"), cancellable = true)
	private void seamlessportals$noBuildingIn(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (CustomPortals.at(context.getLevel(), context.getClickedPos()) != null) {
			cir.setReturnValue(InteractionResult.FAIL);
		}
	}
}
