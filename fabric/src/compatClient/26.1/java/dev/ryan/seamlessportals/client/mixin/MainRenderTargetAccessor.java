package dev.ryan.seamlessportals.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The target the level is drawn into; Minecraft owns it in 26.1. */
@Mixin(Minecraft.class)
public interface MainRenderTargetAccessor {
	@Mutable
	@Accessor("mainRenderTarget")
	void seamlessportals$setMainRenderTarget(RenderTarget target);
}
