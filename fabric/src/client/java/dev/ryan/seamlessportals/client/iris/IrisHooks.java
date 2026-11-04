package dev.ryan.seamlessportals.client.iris;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/** Iris's side of {@link IrisCompat}; loaded only with Iris present. */
final class IrisHooks {
	private static final Logger LOGGER = LoggerFactory.getLogger("seamlessportals");
	/**
	 * The pipelines worth building ahead: Iris keys a level's by its id when the pack names it,
	 * else by its sky (overworld or End), so these three also serve most modded dimensions.
	 */
	private static final Map<ResourceKey<Level>, NamespacedId> VANILLA = Map.of(
			Level.OVERWORLD, DimensionId.OVERWORLD,
			Level.NETHER, DimensionId.NETHER,
			Level.END, DimensionId.END);

	private IrisHooks() {
	}

	static boolean shadersOn() {
		return IrisApi.getInstance().isShaderPackInUse();
	}

	static void restorePipeline() {
		Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimension());
	}

	static void prebuild(Set<ResourceKey<Level>> levels) {
		long start = System.nanoTime();
		boolean built = false;
		for (Map.Entry<ResourceKey<Level>, NamespacedId> entry : VANILLA.entrySet()) {
			if (levels.contains(entry.getKey())) {
				Iris.getPipelineManager().preparePipeline(entry.getValue());
				built = true;
			}
		}
		if (built) {
			restorePipeline();
			LOGGER.debug("Iris pipelines ready in {} ms", (System.nanoTime() - start) / 1_000_000);
		}
	}
}
