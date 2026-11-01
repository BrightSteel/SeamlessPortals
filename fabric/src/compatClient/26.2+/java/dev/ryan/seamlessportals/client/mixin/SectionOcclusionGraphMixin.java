package dev.ryan.seamlessportals.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryan.seamlessportals.client.RemoteDimensions;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphMixin {
	/**
	 * Vanilla slides the culling frustum back along the view direction until an 8-block cube
	 * around the camera fits inside, so sections hugging the camera aren't culled. A portal
	 * view's frustum is narrowed to the portal's part of the screen and may not contain the
	 * view direction at all, so that slide would never end; and the sections hugging a portal
	 * view's camera are behind the portal plane, never seen. Remote views skip it.
	 */
	@WrapOperation(
			method = {"addSectionsInFrustum", "runPartialUpdate"},
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;offsetFrustum(Lnet/minecraft/client/renderer/culling/Frustum;)Lnet/minecraft/client/renderer/culling/Frustum;"),
			require = 2)
	private Frustum seamlessportals$noSlideForRemoteViews(Frustum frustum, Operation<Frustum> original) {
		return RemoteDimensions.isRemoteGraph((SectionOcclusionGraph) (Object) this) ? new Frustum(frustum) : original.call(frustum);
	}
}
