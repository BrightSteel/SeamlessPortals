package dev.ryan.seamlessportals.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryan.seamlessportals.client.DimensionHandoff;
import dev.ryan.seamlessportals.client.RemoteDimensions;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Right after a hand-off, entities show before their sections are meshed (see {@link
 * DimensionHandoff#showUnmeshedEntities}). And in portal views, as soon as their sections are
 * meshed, not once they've faded partly in: a view's drawn only once what's around its far end
 * is meshed (PortalViewRenderer#ready), and its mobs and displays must be there with it.
 */
@Mixin(LevelExtractor.class)
public abstract class EntitySectionCullMixin {
	@WrapOperation(method = "isEntityVisible", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;J)Z"))
	private boolean seamlessportals$showUnmeshed(LevelRenderer renderer, BlockPos pos, long fadeDuration, Operation<Boolean> original) {
		return DimensionHandoff.showUnmeshedEntities() || original.call(renderer, pos, RemoteDimensions.isRemote(renderer) ? 0L : fadeDuration);
	}
}
