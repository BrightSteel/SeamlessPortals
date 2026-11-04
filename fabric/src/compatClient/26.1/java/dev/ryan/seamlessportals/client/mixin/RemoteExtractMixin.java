package dev.ryan.seamlessportals.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.ryan.seamlessportals.client.RemoteDimensions;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 26.1: the level renderer extracts and culls itself (later versions have a LevelExtractor). */
@Mixin(LevelRenderer.class)
public abstract class RemoteExtractMixin {
	/** The particle engine belongs to the player's level; keep its particles out of remote views. */
	@WrapWithCondition(
			method = "extractLevel",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;extract(Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)V"))
	private boolean seamlessportals$skipParticlesForRemote(ParticleEngine engine, ParticlesRenderState state, Frustum frustum, Camera camera, float partialTicks) {
		return !RemoteDimensions.isRemote(this);
	}
}
