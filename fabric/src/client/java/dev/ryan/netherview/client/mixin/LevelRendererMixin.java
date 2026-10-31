package dev.ryan.netherview.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryan.netherview.client.ClientConfig;
import dev.ryan.netherview.client.RemoteDimensions;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	/** A remote view's section grid is sized to its own render distance. */
	@WrapOperation(
			method = "invalidateCompiledGeometry",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I"))
	private int netherview$remoteRenderDistance(Options options, Operation<Integer> original) {
		int distance = original.call(options);
		return RemoteDimensions.isRemoteRenderer((LevelRenderer) (Object) this)
				? Math.min(distance, ClientConfig.get().effectiveRemoteRenderDistance())
				: distance;
	}
}
