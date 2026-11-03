package dev.ryan.seamlessportals.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.ryan.seamlessportals.client.RemoteDimensions;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Portal views' terrain appears at once instead of fading in: a view's drawn only once what's
 * around its far end is meshed (PortalViewRenderer#ready), and fading in then would only show
 * it see-through at first (walls missing, the sky behind). (26.1: read from the options while compiling, in ms.)
 */
@Mixin(LevelRenderer.class)
public abstract class RemoteChunkFadeMixin {
	@ModifyExpressionValue(method = "compileSections", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;floor(D)I"))
	private int seamlessportals$noFadeInViews(int fadeMillis) {
		return RemoteDimensions.isRemote(this) ? 0 : fadeMillis;
	}
}
