package dev.ryan.netherview.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** 26.1 keeps the section dispatcher across a change of world already: nothing to do (see the 26.2+ version). */
@Mixin(LevelRenderer.class)
public abstract class KeepTerrainBuffersMixin {
}
