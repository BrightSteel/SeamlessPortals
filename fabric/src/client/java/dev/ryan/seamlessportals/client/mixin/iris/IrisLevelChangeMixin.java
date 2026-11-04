package dev.ryan.seamlessportals.client.mixin.iris;

import dev.ryan.seamlessportals.client.iris.IrisCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A level change (joining, or a dimension change): Iris keeps its pipelines, and gets the others built (see IrisCompat). */
@Mixin(Minecraft.class)
public abstract class IrisLevelChangeMixin {
	@Inject(method = "setLevel", at = @At("HEAD"))
	private void seamlessportals$levelChanging(ClientLevel level, CallbackInfo ci) {
		IrisCompat.levelChanging();
	}

	@Inject(method = "setLevel", at = @At("RETURN"))
	private void seamlessportals$levelChanged(ClientLevel level, CallbackInfo ci) {
		IrisCompat.levelChanged();
	}
}
