package dev.ryan.seamlessportals.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryan.seamlessportals.client.DimensionHandoff;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Right after a hand-off, entities show before their sections are meshed (see {@link DimensionHandoff#showUnmeshedEntities}). */
@Mixin(LevelExtractor.class)
public abstract class EntitySectionCullMixin {
	@WrapOperation(method = "isEntityVisible", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;J)Z"))
	private boolean seamlessportals$showUnmeshed(LevelRenderer renderer, BlockPos pos, long fadeDuration, Operation<Boolean> original) {
		return DimensionHandoff.showUnmeshedEntities() || original.call(renderer, pos, fadeDuration);
	}
}
