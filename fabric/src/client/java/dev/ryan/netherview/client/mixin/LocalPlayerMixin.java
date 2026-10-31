package dev.ryan.netherview.client.mixin;

import dev.ryan.netherview.client.PortalHiding;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	/**
	 * A linked portal is an open window, so stepping into it shouldn't fade in the purple
	 * overlay, warp the view, or play the trigger sound. Holding the effect "inactive"
	 * keeps portalEffectIntensity at zero; unlinked portals behave as vanilla.
	 */
	@ModifyVariable(method = "handlePortalTransitionEffect", at = @At("HEAD"), argsOnly = true)
	private boolean netherview$noOverlayInLinkedPortal(boolean active) {
		return active && !PortalHiding.touchesLinkedPortal((LocalPlayer) (Object) this);
	}
}
