package dev.ryan.seamlessportals.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryan.seamlessportals.client.ClientConfig;
import dev.ryan.seamlessportals.client.RemoteDimensions;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class RemoteRenderDistanceMixin {
	/** A remote view's section grid is sized to its own render distance. */
	@WrapOperation(
			method = "invalidateCompiledGeometry",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I"))
	private int seamlessportals$remoteRenderDistance(Options options, Operation<Integer> original) {
		int distance = original.call(options);
		return RemoteDimensions.isRemote(this)
				? Math.min(distance, ClientConfig.get().effectiveRemoteRenderDistance())
				: distance;
	}
}
