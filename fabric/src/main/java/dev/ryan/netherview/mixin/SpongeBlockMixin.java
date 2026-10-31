package dev.ryan.netherview.mixin;

import dev.ryan.netherview.server.CustomPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SpongeBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A sponge doesn't soak up a water fill (the check run on each block the sponge reaches). */
@Mixin(SpongeBlock.class)
public abstract class SpongeBlockMixin {
	@Inject(method = "lambda$removeWaterBreadthFirstSearch$1", at = @At("HEAD"), cancellable = true)
	private static void netherview$skipFill(BlockPos startPos, Level level, BlockPos pos, CallbackInfoReturnable<BlockPos.TraversalNodeStatus> cir) {
		if (CustomPortals.at(level, pos) != null) {
			cir.setReturnValue(BlockPos.TraversalNodeStatus.SKIP);
		}
	}
}
