package dev.ryan.netherview.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderSectionRegion.class)
public interface RenderSectionRegionAccessor {
	@Accessor("level")
	ClientLevel netherview$getLevel();
}
