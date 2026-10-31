package dev.ryan.netherview.client.mixin;

import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

/** Remote renderers are created after resource load, so their cloud texture is loaded by hand. */
@Mixin(CloudRenderer.class)
public interface CloudRendererInvoker {
	@Invoker("prepare")
	Optional<CloudRenderer.TextureData> netherview$prepare(ResourceManager manager, ProfilerFiller profiler);

	@Invoker("apply")
	void netherview$apply(Optional<CloudRenderer.TextureData> preparations, ResourceManager manager, ProfilerFiller profiler);
}
