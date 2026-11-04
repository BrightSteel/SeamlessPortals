package dev.ryan.seamlessportals.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.Lightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The remote-dimension pass reuses the game renderer, so for its duration the
 * lightmap it hands to {@code LevelRenderer} is swapped out (and the render target, through
 * {@code MainRenderTargetAccessor}).
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Accessor("lightmap")
	Lightmap seamlessportals$getLightmap();

	@Mutable
	@Accessor("lightmap")
	void seamlessportals$setLightmap(Lightmap lightmap);

	@Accessor("fogRenderer")
	FogRenderer seamlessportals$getFogRenderer();

	@Mutable
	@Accessor("fogRenderer")
	void seamlessportals$setFogRenderer(FogRenderer fog);

	@Accessor("mainCamera")
	Camera seamlessportals$getMainCamera();

	@Mutable
	@Accessor("mainCamera")
	void seamlessportals$setMainCamera(Camera camera);

	@Accessor("globalSettingsUniform")
	GlobalSettingsUniform seamlessportals$getGlobalSettingsUniform();
}
