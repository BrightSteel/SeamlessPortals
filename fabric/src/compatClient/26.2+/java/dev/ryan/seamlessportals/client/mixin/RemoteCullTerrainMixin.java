package dev.ryan.seamlessportals.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Nothing to change in 26.2+: culling is in SectionOcclusionGraph and the LevelExtractor (see SectionOcclusionGraphMixin, RemoteExtractMixin and the 26.1 version). */
@Mixin(LevelRenderer.class)
public abstract class RemoteCullTerrainMixin {
}
