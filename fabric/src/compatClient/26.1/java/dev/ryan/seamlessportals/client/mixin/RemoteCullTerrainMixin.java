package dev.ryan.seamlessportals.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryan.seamlessportals.client.ClientConfig;
import dev.ryan.seamlessportals.client.RemoteDimensions;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.1: remote views in the level renderer's own culling. Sodium replaces cullTerrain, so with it
 * installed this isn't applied (see ClientMixinPlugin; SodiumRemoteViewMixin does the same there).
 */
@Mixin(LevelRenderer.class)
public abstract class RemoteCullTerrainMixin {
	/** No sliding of the culling frustum for remote views (see SectionOcclusionGraphMixin). */
	@WrapOperation(
			method = "cullTerrain",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;offsetFrustum(Lnet/minecraft/client/renderer/culling/Frustum;)Lnet/minecraft/client/renderer/culling/Frustum;"))
	private Frustum seamlessportals$noSlideForRemoteViews(Frustum frustum, Operation<Frustum> original) {
		return RemoteDimensions.isRemote(this) ? new Frustum(frustum) : original.call(frustum);
	}

	/** Remote views cull to their own render distance (see RemoteRenderDistanceMixin). */
	@WrapOperation(
			method = "cullTerrain",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I"))
	private int seamlessportals$remoteRenderDistance(Options options, Operation<Integer> original) {
		int distance = original.call(options);
		return RemoteDimensions.isRemote(this)
				? Math.min(distance, ClientConfig.get().effectiveRemoteRenderDistance())
				: distance;
	}
}
