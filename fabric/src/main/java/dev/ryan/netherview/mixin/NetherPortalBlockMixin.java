package dev.ryan.netherview.mixin;

import dev.ryan.netherview.server.CustomPortals;
import dev.ryan.netherview.server.PortalViewServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {
	/** Players who see through this portal travel when they cross its plane (see PortalViewServer), not on contact.
	 * Custom portals move things themselves (CustomPortalTravel), so the fill block never acts. */
	@Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
	private void netherview$crossInsteadOfTouch(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier, boolean isPrecise, CallbackInfo ci) {
		if (entity instanceof ServerPlayer player && PortalViewServer.handlesTravel(player, pos) || CustomPortals.at(level, pos) != null) {
			ci.cancel();
		}
	}

	/** No zombified piglins out of a custom portal. */
	@Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
	private void netherview$noCustomSpawns(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
		if (CustomPortals.at(level, pos) != null) {
			ci.cancel();
		}
	}

	/** A custom nether portal needs no obsidian frame: it doesn't break itself when a neighbour changes. */
	@Inject(method = "updateShape", at = @At("HEAD"), cancellable = true)
	private void netherview$framelessCustom(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction directionToNeighbour,
			BlockPos neighbourPos, BlockState neighbourState, RandomSource random, CallbackInfoReturnable<BlockState> cir) {
		if (level instanceof Level real && CustomPortals.at(real, pos) != null) {
			cir.setReturnValue(state);
		}
	}
}
