package dev.ryan.netherview.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.ryan.netherview.client.DimensionHandoff;
import dev.ryan.netherview.client.PortalViewRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/**
	 * Render the far side of the portal once the level projection is final (bobbing and
	 * the portal-nausea warp included), so the offscreen image lines up pixel-for-pixel
	 * with the main view.
	 */
	@Inject(
			method = "renderLevel",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V", shift = At.Shift.AFTER))
	private void netherview$renderRemoteView(CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix) {
		PortalViewRenderer.renderRemoteView(projectionMatrix);
	}

	@Inject(method = "extractOptions", at = @At("TAIL"))
	private void netherview$noFadeAfterHandoff(CallbackInfo ci) {
		if (DimensionHandoff.suppressChunkFade()) {
			((GameRenderer) (Object) this).gameRenderState().optionsRenderState.chunkSectionFadeInTime = 0.0;
		}
	}

	/** Draw the portal window after the level, before the hand and screen effects. */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void netherview$compositePortals(CallbackInfo ci) {
		PortalViewRenderer.composite();
	}
}
