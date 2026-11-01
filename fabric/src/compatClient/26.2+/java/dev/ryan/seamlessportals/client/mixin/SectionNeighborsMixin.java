package dev.ryan.seamlessportals.client.mixin;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;

/** Nothing to change in 26.2+: a section is culled and meshed once its own chunk is in (see the 26.1 version). */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class SectionNeighborsMixin {
}
