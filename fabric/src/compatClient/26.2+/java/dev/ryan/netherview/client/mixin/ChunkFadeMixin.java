package dev.ryan.netherview.client.mixin;

import dev.ryan.netherview.client.DimensionHandoff;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right after a hand-off sections appear at once instead of fading in (26.2+: an option in the render state). */
@Mixin(GameRenderer.class)
public abstract class ChunkFadeMixin {
	@Inject(method = "extractOptions", at = @At("TAIL"))
	private void netherview$noFadeAfterHandoff(CallbackInfo ci) {
		if (DimensionHandoff.suppressChunkFade()) {
			((GameRenderer) (Object) this).gameRenderState().optionsRenderState.chunkSectionFadeInTime = 0.0;
		}
	}
}
