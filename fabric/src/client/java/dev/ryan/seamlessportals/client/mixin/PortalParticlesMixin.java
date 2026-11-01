package dev.ryan.seamlessportals.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryan.seamlessportals.client.ClientConfig;
import dev.ryan.seamlessportals.client.PortalHiding;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Linked portals show the other side, so their particle swirl is mostly noise in front of
 * the view: they keep {@link ClientConfig#portalParticles} of vanilla's particles (none at 0).
 * Unlinked portals are left as they are.
 */
@Mixin({NetherPortalBlock.class, EndGatewayBlock.class})
public abstract class PortalParticlesMixin {
	@WrapWithCondition(
			method = "animateTick",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
	private boolean seamlessportals$fewerParticlesOnLinkedPortals(Level level, ParticleOptions particle, double x, double y, double z, double xa, double ya, double za,
			@Local(argsOnly = true) BlockState state, @Local(argsOnly = true) BlockPos pos, @Local(argsOnly = true) RandomSource random) {
		if (!(level instanceof ClientLevel clientLevel) || !PortalHiding.shouldHide(clientLevel, state, pos)) {
			return true;
		}
		double share = ClientConfig.get().portalParticles;
		return share >= 1.0 || random.nextDouble() < share;
	}
}
