package dev.ryan.netherview.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryan.netherview.client.ClientConfig;
import dev.ryan.netherview.client.RemoteDimensions;
import net.minecraft.client.Options;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelExtractor.class)
public abstract class RemoteExtractMixin {
	/** The particle engine belongs to the player's level; keep its particles out of remote views. */
	@WrapWithCondition(
			method = "extract",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;extract(Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)V"))
	private boolean netherview$skipParticlesForRemote(ParticleEngine engine, ParticlesRenderState state, Frustum frustum, Camera camera, float partialTicks) {
		return !RemoteDimensions.isRemote(this);
	}

	/** Remote views use their own (configurable, usually shorter) render distance. */
	@WrapOperation(
			method = {"extract", "allChanged"},
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I"))
	private int netherview$remoteRenderDistance(Options options, Operation<Integer> original) {
		int distance = original.call(options);
		return RemoteDimensions.isRemote(this)
				? Math.min(distance, ClientConfig.get().effectiveRemoteRenderDistance())
				: distance;
	}
}
