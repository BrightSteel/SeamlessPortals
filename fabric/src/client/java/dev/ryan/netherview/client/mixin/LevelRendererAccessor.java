package dev.ryan.netherview.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A second LevelRenderer would otherwise share the game's LevelRenderState. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
	@Mutable
	@Accessor("levelRenderState")
	void netherview$setLevelRenderState(LevelRenderState state);
}
