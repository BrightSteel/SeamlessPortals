package dev.ryan.seamlessportals.client.mixin.iris;

import dev.ryan.seamlessportals.client.iris.IrisCompat;
import net.irisshaders.iris.pipeline.PipelineManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris drops every compiled pipeline when the player changes dimension, and compiling one
 * takes seconds: behind a loading screen that goes unseen, but a walk through a portal has
 * none, and the view back needs the pipeline just dropped. Kept across a dimension change
 * (only); leaving the world or reloading the pack still drops them.
 */
@Mixin(value = PipelineManager.class, remap = false)
public abstract class IrisKeepPipelinesMixin {
	@Inject(method = "destroyPipeline", at = @At("HEAD"), cancellable = true)
	private void seamlessportals$keepAcrossDimensions(CallbackInfo ci) {
		if (IrisCompat.keepingPipelines()) {
			ci.cancel();
		}
	}
}
