package dev.ryan.seamlessportals.client.mixin;

import dev.ryan.seamlessportals.client.RemoteClocks;
import dev.ryan.seamlessportals.client.RemoteDimensions;
import net.minecraft.client.ClientClockManager;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A remote view of a world reads that world's own clocks (see {@link RemoteClocks}). */
@Mixin(ClientLevel.class)
public abstract class ClientLevelClockMixin {
	@Inject(method = "clockManager()Lnet/minecraft/client/ClientClockManager;", at = @At("HEAD"), cancellable = true)
	private void seamlessportals$ownClocks(CallbackInfoReturnable<ClientClockManager> cir) {
		ClientLevel level = (ClientLevel) (Object) this;
		ClientClockManager clocks = RemoteClocks.forRemote(level.dimension(), RemoteDimensions.isRemoteLevel(level));
		if (clocks != null) {
			cir.setReturnValue(clocks);
		}
	}
}
