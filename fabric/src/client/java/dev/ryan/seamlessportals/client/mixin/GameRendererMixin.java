package dev.ryan.seamlessportals.client.mixin;

import dev.ryan.seamlessportals.compat.ClientVersioned;
import dev.ryan.seamlessportals.client.PortalViewRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
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
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V", ordinal = 0, shift = At.Shift.AFTER))
	private void seamlessportals$renderRemoteView(CallbackInfo ci) {
		PortalViewRenderer.renderRemoteView(this.seamlessportals$projectionMatrix);
	}

	@Unique
	private Matrix4f seamlessportals$projectionMatrix;

	/**
	 * The level's projection matrix, as it's turned into the projection buffer just before the
	 * call above. (Taken here rather than as a local: without a local variable table the
	 * matrix's place among the method's locals differs between versions.)
	 */
	@ModifyArg(
			method = "renderLevel",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private Matrix4f seamlessportals$captureProjection(Matrix4f projectionMatrix) {
		this.seamlessportals$projectionMatrix = projectionMatrix;
		return projectionMatrix;
	}

	/** Draw the portal window after the level, before the hand and screen effects. */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = ClientVersioned.AFTER_LEVEL_RENDER))
	private void seamlessportals$compositePortals(CallbackInfo ci) {
		PortalViewRenderer.composite();
	}
}
