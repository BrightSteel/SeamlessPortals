package dev.ryan.seamlessportals.mixin;

import dev.ryan.seamlessportals.server.CustomPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Explosions leave custom portals standing. */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@Inject(method = "calculateExplodedPositions", at = @At("RETURN"))
	private void seamlessportals$sparePortals(CallbackInfoReturnable<List<BlockPos>> cir) {
		cir.getReturnValue().removeIf(pos -> CustomPortals.at(this.level, pos) != null);
	}
}
