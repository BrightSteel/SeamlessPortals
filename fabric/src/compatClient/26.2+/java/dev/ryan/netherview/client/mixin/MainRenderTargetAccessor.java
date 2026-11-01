package dev.ryan.netherview.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The target the level is drawn into; the game renderer owns it (26.2+). */
@Mixin(GameRenderer.class)
public interface MainRenderTargetAccessor {
	@Mutable
	@Accessor("mainRenderTarget")
	void netherview$setMainRenderTarget(RenderTarget target);
}
