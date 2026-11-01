package dev.ryan.seamlessportals.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.ryan.seamlessportals.client.DimensionHandoff;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Right after a hand-off sections appear at once instead of fading in (26.1: read from the options while compiling). */
@Mixin(LevelRenderer.class)
public abstract class ChunkFadeMixin {
	/** The fade duration in ms, {@code Mth.floor(chunkSectionFadeInTime * 1000)}. */
	@ModifyExpressionValue(method = "compileSections", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;floor(D)I"))
	private int seamlessportals$noFadeAfterHandoff(int fadeMillis) {
		return DimensionHandoff.suppressChunkFade() ? 0 : fadeMillis;
	}
}
