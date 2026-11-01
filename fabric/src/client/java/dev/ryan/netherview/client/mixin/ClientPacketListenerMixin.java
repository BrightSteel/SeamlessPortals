package dev.ryan.netherview.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryan.netherview.client.DimensionHandoff;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.client.multiplayer.ClientLevel;
import dev.ryan.netherview.client.RemoteClocks;
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

	/**
	 * Some servers (Paper) unload the old level's chunks after the respawn into the new one, so
	 * the client applies those unloads to the new level, wiping the terrain a hand-off filled it
	 * with (and the ground under the player's feet). The server can't unload what it never sent
	 * in this level: ignore those.
	 */
	@Inject(
			method = "handleForgetLevelChunk",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER),
			cancellable = true)
	private void netherview$ignoreStaleUnload(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
		if (DimensionHandoff.isStaleUnload(packet.pos())) {
			ci.cancel();
		}
	}

	@Inject(
			method = "handleLevelChunkWithLight",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER),
			cancellable = true)
	private void netherview$noteServerChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
		if (DimensionHandoff.onServerChunk(packet)) {
			ci.cancel();
		}
	}

	@Inject(
			method = "handleAddEntity",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER),
			cancellable = true)
	private void netherview$noteServerEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
		if (DimensionHandoff.onServerEntity(packet, ((ClientPacketListenerAccessor) this).netherview$getLevel())) {
			ci.cancel();
		}
	}

	@Inject(
			method = "handleSetEntityPassengersPacket",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
	private void netherview$beforePassengers(ClientboundSetPassengersPacket packet, CallbackInfo ci) {
		DimensionHandoff.beforePassengers(packet);
	}

	@Inject(method = "handleSetEntityPassengersPacket", at = @At("TAIL"))
	private void netherview$afterPassengers(ClientboundSetPassengersPacket packet, CallbackInfo ci) {
		DimensionHandoff.afterPassengers();
	}

	@Inject(
			method = "handleRemoveEntities",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
	private void netherview$beforeRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
		DimensionHandoff.beforeRemoveEntities(packet);
	}

	@Inject(method = "handleRemoveEntities", at = @At("TAIL"))
	private void netherview$afterRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
		DimensionHandoff.afterRemoveEntities((ClientPacketListener) (Object) this, ((ClientPacketListenerAccessor) this).netherview$getLevel());
	}

	@Inject(method = "handleAddEntity", at = @At("TAIL"))
	private void netherview$afterServerEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
		DimensionHandoff.afterServerEntity(packet, ((ClientPacketListenerAccessor) this).netherview$getLevel());
	}

	@Inject(method = "handleSetTime", at = @At("TAIL"))
	private void netherview$rememberClocks(ClientboundSetTimePacket packet, CallbackInfo ci) {
		ClientLevel level = ((ClientPacketListenerAccessor) this).netherview$getLevel();
		if (level != null) {
			RemoteClocks.onSetTime(level.dimension(), packet.gameTime(), packet.clockUpdates());
		}
	}
}
