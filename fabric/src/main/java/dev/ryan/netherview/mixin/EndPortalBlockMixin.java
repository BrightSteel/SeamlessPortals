package dev.ryan.netherview.mixin;

import dev.ryan.netherview.server.CustomPortals;
import dev.ryan.netherview.server.PortalViewServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EndPortalBlock.class)
public abstract class EndPortalBlockMixin {
	/** Players falling into a linked end portal travel when their feet cross its surface (see PortalViewServer), not on contact.
	 * Custom portals move things themselves (CustomPortalTravel), so the fill block never acts. */
	@Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
	private void netherview$crossInsteadOfTouch(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier, boolean isPrecise, CallbackInfo ci) {
		if (entity instanceof ServerPlayer player && PortalViewServer.handlesTravel(player, pos) || CustomPortals.at(level, pos) != null) {
			ci.cancel();
		}
	}
}
