package dev.ryan.seamlessportals.client.mixin.iris;

import dev.ryan.seamlessportals.client.iris.IrisCompat;
import net.irisshaders.iris.pathways.HandRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Iris draws the player's hand inside the level render; a remote view's has none. */
@Mixin(value = HandRenderer.class, remap = false)
public abstract class IrisHandMixin {
	@Inject(method = "canRender", at = @At("HEAD"), cancellable = true)
	private void seamlessportals$noHandInViews(Camera camera, GameRenderer gameRenderer, CallbackInfoReturnable<Boolean> cir) {
		if (IrisCompat.inRemoteDraw()) {
			cir.setReturnValue(false);
		}
	}
}
