package dev.ryan.seamlessportals.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.ryan.seamlessportals.client.PerfMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Development aid: in perf mode, measurements run with no input, so the AFK frame cap is off. */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
	@Inject(method = "getThrottleReason", at = @At("HEAD"), cancellable = true)
	private void seamlessportals$noAfkThrottleInPerfMode(CallbackInfoReturnable<FramerateLimitTracker.FramerateThrottleReason> cir) {
		if (PerfMode.ENABLED) {
			cir.setReturnValue(FramerateLimitTracker.FramerateThrottleReason.NONE);
		}
	}
}
