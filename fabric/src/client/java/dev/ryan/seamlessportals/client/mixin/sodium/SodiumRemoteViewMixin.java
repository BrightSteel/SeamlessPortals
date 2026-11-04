package dev.ryan.seamlessportals.client.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryan.seamlessportals.client.ClientConfig;
import dev.ryan.seamlessportals.client.sodium.SodiumCompat;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** A remote view's Sodium chunk renderer. */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class SodiumRemoteViewMixin {
	/** Remote views use their own (configurable, usually shorter) render distance (see RemoteExtractMixin): Sodium's grid and culling too. */
	@WrapOperation(
			method = {"setupTerrain", "initRenderer"},
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I"))
	private int seamlessportals$remoteRenderDistance(Options options, Operation<Integer> original) {
		int distance = original.call(options);
		return SodiumCompat.isRemote(this)
				? Math.min(distance, ClientConfig.get().effectiveRemoteRenderDistance())
				: distance;
	}

	/**
	 * No occlusion culling for remote views. Sodium's search starts from the camera's section,
	 * and a view's camera is the player's carried through the portal: from across the room it
	 * stands well behind the far portal, inside whatever is there (often solid rock), where the
	 * search finds nothing. The view is already culled to the portal's part of the screen and
	 * a short distance, so what's left is small anyway.
	 */
	@ModifyVariable(method = "setupTerrain", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private boolean seamlessportals$noOcclusionCulling(boolean useOcclusionCulling) {
		return useOcclusionCulling && !SodiumCompat.isRemote(this);
	}
}
