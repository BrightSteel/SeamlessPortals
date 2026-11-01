package dev.ryan.seamlessportals.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryan.seamlessportals.client.PortalHiding;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
	/** Linked portals are drawn as a window, so their swirl is left out of the chunk mesh. Runs on mesh worker threads. */
	@WrapOperation(
			method = "compile",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
	private RenderShape seamlessportals$hideLinkedPortals(BlockState state, Operation<RenderShape> original,
			@Local(argsOnly = true) RenderSectionRegion region, @Local(ordinal = 2) BlockPos pos) {
		if (PortalHiding.shouldHide(((RenderSectionRegionAccessor) region).seamlessportals$getLevel(), state, pos)) {
			return RenderShape.INVISIBLE;
		}
		return original.call(state);
	}

	/** A water or lava fill is a fluid, meshed apart from block models; leave it out the same way. */
	@WrapOperation(
			method = "compile",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
	private void seamlessportals$hideLinkedFluids(FluidRenderer renderer, BlockAndTintGetter getter, BlockPos pos, FluidRenderer.Output output, BlockState state, FluidState fluid,
			Operation<Void> original, @Local(argsOnly = true) RenderSectionRegion region) {
		if (!PortalHiding.shouldHide(((RenderSectionRegionAccessor) region).seamlessportals$getLevel(), state, pos)) {
			original.call(renderer, getter, pos, output, state, fluid);
		}
	}
}
