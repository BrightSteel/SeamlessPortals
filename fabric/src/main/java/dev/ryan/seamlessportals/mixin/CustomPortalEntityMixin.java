package dev.ryan.seamlessportals.mixin;

import dev.ryan.seamlessportals.server.CustomPortals;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A lava fill sends things on, it doesn't burn them (CustomPortalTravel moves them on contact). */
@Mixin(Entity.class)
public abstract class CustomPortalEntityMixin {
	@Inject(method = {"lavaHurt", "lavaIgnite"}, at = @At("HEAD"), cancellable = true)
	private void seamlessportals$noBurnInPortal(CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (!self.level().isClientSide() && CustomPortals.touches(self)) {
			ci.cancel();
		}
	}
}
