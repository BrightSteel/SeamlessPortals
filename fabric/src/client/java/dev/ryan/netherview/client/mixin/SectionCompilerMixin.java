package dev.ryan.netherview.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryan.netherview.client.PortalHiding;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
	/** Linked portals are drawn as a window, so their swirl is left out of the chunk mesh. Runs on mesh worker threads. */
	@WrapOperation(
			method = "compile",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
	private RenderShape netherview$hideLinkedPortals(BlockState state, Operation<RenderShape> original,
			@Local(argsOnly = true) RenderSectionRegion region, @Local(ordinal = 2) BlockPos pos) {
		if (PortalHiding.shouldHide(((RenderSectionRegionAccessor) region).netherview$getLevel(), state, pos)) {
			return RenderShape.INVISIBLE;
		}
		return original.call(state);
	}
}
