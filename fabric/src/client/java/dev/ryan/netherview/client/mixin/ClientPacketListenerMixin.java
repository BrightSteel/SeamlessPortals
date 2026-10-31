package dev.ryan.netherview.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryan.netherview.client.DimensionHandoff;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	/** After the thread hop, so this runs once and on the main thread. */
	@Inject(
			method = "handleRespawn",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
	private void netherview$snapshotRemoteDimension(ClientboundRespawnPacket packet, CallbackInfo ci) {
		DimensionHandoff.beforeRespawn(packet.commonPlayerSpawnInfo().dimension());
	}

	@Inject(method = "handleRespawn", at = @At("TAIL"))
	private void netherview$prefillNewLevel(ClientboundRespawnPacket packet, CallbackInfo ci) {
		DimensionHandoff.afterRespawn((ClientPacketListener) (Object) this);
	}

	@Inject(
			method = "handleMovePlayer",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
	private void netherview$rememberClientPosition(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		DimensionHandoff.beforeServerPlacement((ClientPacketListener) (Object) this);
	}

	@Inject(method = "handleMovePlayer", at = @At("TAIL"))
	private void netherview$keepClientPosition(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		DimensionHandoff.afterServerPlacement();
	}

	/** The load tracker still runs (and unfreezes the player when ready); only the screen is skipped. */
	@WrapOperation(
			method = "startWaitingForNewLevel",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setScreenAndShow(Lnet/minecraft/client/gui/screens/Screen;)V"))
	private void netherview$skipLoadingScreen(Minecraft minecraft, Screen screen, Operation<Void> original) {
		if (!DimensionHandoff.skipLoadingScreen()) {
			original.call(minecraft, screen);
		}
	}
}
