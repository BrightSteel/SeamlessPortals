package dev.ryan.netherview.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryan.netherview.client.ClientConfig;
import dev.ryan.netherview.client.RemoteDimensions;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Remote views use their own (configurable, usually shorter) render distance: for the section
 * grid, for noticing a change of it, and for the world border. (26.1: all in the level renderer.)
 */
@Mixin(LevelRenderer.class)
public abstract class RemoteRenderDistanceMixin {
	@WrapOperation(
			method = {"allChanged", "cullTerrain", "extractLevel"},
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I"))
	private int netherview$remoteRenderDistance(Options options, Operation<Integer> original) {
		int distance = original.call(options);
		return RemoteDimensions.isRemote(this)
				? Math.min(distance, ClientConfig.get().effectiveRemoteRenderDistance())
				: distance;
	}
}
