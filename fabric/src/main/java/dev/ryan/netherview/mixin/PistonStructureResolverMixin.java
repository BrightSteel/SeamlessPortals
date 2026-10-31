package dev.ryan.netherview.mixin;

import dev.ryan.netherview.server.CustomPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** A piston can't move a custom portal's blocks, push anything into one, or break one. */
@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureResolverMixin {
	@Shadow
	@Final
	private Level level;
	@Shadow
	@Final
	private Direction pushDirection;
	@Shadow
	@Final
	private List<BlockPos> toPush;
	@Shadow
	@Final
	private List<BlockPos> toDestroy;

	@Inject(method = "resolve", at = @At("RETURN"), cancellable = true)
	private void netherview$blockedByPortal(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()) {
			return;
		}
		for (BlockPos pos : this.toPush) {
			if (CustomPortals.at(this.level, pos) != null || CustomPortals.at(this.level, pos.relative(this.pushDirection)) != null) {
				cir.setReturnValue(false);
				return;
			}
		}
		for (BlockPos pos : this.toDestroy) {
			if (CustomPortals.at(this.level, pos) != null) {
				cir.setReturnValue(false);
				return;
			}
		}
	}
}
