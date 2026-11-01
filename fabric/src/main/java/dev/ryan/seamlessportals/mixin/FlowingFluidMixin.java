package dev.ryan.seamlessportals.mixin;

import dev.ryan.seamlessportals.server.CustomPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Water and lava fills don't spread or drain, and nothing flows into a portal. */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void seamlessportals$stillFill(ServerLevel level, BlockPos pos, BlockState blockState, FluidState fluidState, CallbackInfo ci) {
		if (CustomPortals.at(level, pos) != null) {
			ci.cancel();
		}
	}

	@Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
	private void seamlessportals$noFlowIn(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState target, CallbackInfo ci) {
		if (level instanceof Level real && CustomPortals.at(real, pos) != null) {
			ci.cancel();
		}
	}
}
