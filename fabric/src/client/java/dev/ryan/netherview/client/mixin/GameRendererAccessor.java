package dev.ryan.netherview.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.Lightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The remote-dimension pass reuses the game renderer, so for its duration the
 * render target and lightmap it hands to {@code LevelRenderer} are swapped out.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Mutable
	@Accessor("mainRenderTarget")
	void netherview$setMainRenderTarget(RenderTarget target);

	@Accessor("lightmap")
	Lightmap netherview$getLightmap();

	@Mutable
	@Accessor("lightmap")
	void netherview$setLightmap(Lightmap lightmap);

	@Accessor("globalSettingsUniform")
	GlobalSettingsUniform netherview$getGlobalSettingsUniform();
}
