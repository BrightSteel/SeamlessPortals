package dev.ryan.netherview.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryan.netherview.client.DimensionHandoff;
import dev.ryan.netherview.client.RemoteDimensions;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Right after a hand-off, the player's own entities show before their sections are meshed (see {@link DimensionHandoff#showUnmeshedEntities}). */
@Mixin(LevelExtractor.class)
public abstract class EntitySectionCullMixin {
	@WrapOperation(method = "isEntityVisible", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;)Z"))
	private boolean netherview$showUnmeshed(LevelRenderer renderer, BlockPos pos, Operation<Boolean> original) {
		return DimensionHandoff.showUnmeshedEntities() && !RemoteDimensions.isRemote(this) || original.call(renderer, pos);
	}
}
