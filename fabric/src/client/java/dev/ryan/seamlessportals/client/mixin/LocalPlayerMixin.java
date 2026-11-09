package dev.ryan.seamlessportals.client.mixin;

import dev.ryan.seamlessportals.client.PortalHiding;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.PortalProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	/**
	 * A linked portal is an open window, so stepping into it shouldn't fade in the purple
	 * overlay, warp the view, or play the trigger sound. Holding the effect "inactive"
	 * keeps portalEffectIntensity at zero; unlinked portals behave as vanilla. Inside a
	 * portal's only noticed as the player moves, a tick after the box may have left it, so
	 * the portal that noticed it counts too; and the notice is used up here, as vanilla does
	 * when it acts on it (left standing, it'd be read as being inside the next tick the
	 * player's touching no linked portal: one tick of it is the overlay at a fifth strength).
	 */
	@ModifyVariable(method = "handlePortalTransitionEffect", at = @At("HEAD"), argsOnly = true)
	private boolean seamlessportals$noOverlayInLinkedPortal(boolean active) {
		LocalPlayer player = (LocalPlayer) (Object) this;
		PortalProcessor process = player.portalProcess;
		if (!active || process == null || !PortalHiding.touchesLinkedPortal(player) && !PortalHiding.isLinkedPortal(process.getEntryPosition())) {
			return active;
		}
		process.setAsInsidePortalThisTick(false);
		return false;
	}
}
