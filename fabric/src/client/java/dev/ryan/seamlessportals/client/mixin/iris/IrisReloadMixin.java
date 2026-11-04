package dev.ryan.seamlessportals.client.mixin.iris;

import dev.ryan.seamlessportals.client.iris.IrisCompat;
import net.irisshaders.iris.Iris;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A pack (re)loaded, or shaders switched on or off mid-game (see IrisCompat#irisReloaded). */
@Mixin(value = Iris.class, remap = false)
public abstract class IrisReloadMixin {
	@Inject(method = "reload", at = @At("RETURN"))
	private static void seamlessportals$reloaded(CallbackInfo ci) {
		IrisCompat.irisReloaded();
	}
}
