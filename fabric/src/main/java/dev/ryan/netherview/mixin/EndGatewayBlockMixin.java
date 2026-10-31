package dev.ryan.netherview.mixin;

import dev.ryan.netherview.server.CustomPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EndGatewayBlock.class)
public abstract class EndGatewayBlockMixin {
	/** A gateway used as a custom portal's fill doesn't teleport; CustomPortalTravel does. */
	@Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
	private void netherview$customFill(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier, boolean isPrecise, CallbackInfo ci) {
		if (CustomPortals.at(level, pos) != null) {
			ci.cancel();
		}
	}
}
