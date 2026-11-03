package dev.ryan.seamlessportals.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.ryan.seamlessportals.client.RemoteDimensions;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Portal views' terrain appears at once instead of fading in: a view's drawn only once what's
 * around its far end is meshed (PortalViewRenderer#ready), and fading in then would only show
 * it see-through at first (walls missing, the sky behind). (26.3: read from the render state as sections are drawn.)
 */
@Mixin(LevelRenderer.class)
public abstract class RemoteChunkFadeMixin {
	@ModifyExpressionValue(method = "extractSectionDrawGroups", at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/renderer/state/OptionsRenderState;chunkSectionFadeInTime:D"))
	private double seamlessportals$noFadeInViews(double fadeSeconds) {
		return RemoteDimensions.isRemote(this) ? 0.0 : fadeSeconds;
	}
}
