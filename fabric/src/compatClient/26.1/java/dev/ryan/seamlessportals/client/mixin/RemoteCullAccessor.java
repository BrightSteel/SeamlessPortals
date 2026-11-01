package dev.ryan.seamlessportals.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The renderer re-culls sections only when the camera turns. A portal view's frustum also
 * changes shape as the player moves (it is narrowed to the portal on screen), so it is
 * made to re-cull every frame. (26.1: the level renderer culls; later versions' extractor does.)
 */
@Mixin(LevelRenderer.class)
public interface RemoteCullAccessor {
	@Accessor("prevCamRotX")
	void seamlessportals$setPrevCamRotX(double value);
}
