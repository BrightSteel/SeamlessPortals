package dev.ryan.seamlessportals.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sodium finds a renderer's chunk renderer through the game's (see SodiumCompat#asCurrent). */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	@Mutable
	@Accessor("levelRenderer")
	void seamlessportals$setLevelRenderer(LevelRenderer renderer);
}
