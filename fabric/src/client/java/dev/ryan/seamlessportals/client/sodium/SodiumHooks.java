package dev.ryan.seamlessportals.client.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkStatus;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import dev.ryan.seamlessportals.client.mixin.GameRendererAccessor;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Matrix4f;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;

/** The calls into Sodium itself; only {@link SodiumCompat} uses this, and only with Sodium present. */
final class SodiumHooks {
	private SodiumHooks() {
	}

	static Object worldRenderer(LevelRenderer renderer) {
		return ((LevelRendererExtension) renderer).sodium$getWorldRenderer();
	}

	/** Sodium keeps the level projection it last saw GameRenderer use (a Matrix4f it owns) and reads fog from GameRenderer's FogRenderer. */
	static void withProjectionAndFog(Matrix4f projection, FogRenderer fog, Runnable action) {
		GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;
		Matrix4f sodiumProjection = (Matrix4f) ((GameRendererStorage) gameRenderer).sodium$getProjectionMatrix();
		Matrix4f savedProjection = new Matrix4f(sodiumProjection);
		FogRenderer savedFog = ((GameRendererAccessor) gameRenderer).seamlessportals$getFogRenderer();
		sodiumProjection.set(projection);
		((GameRendererAccessor) gameRenderer).seamlessportals$setFogRenderer(fog);
		try {
			action.run();
		} finally {
			sodiumProjection.set(savedProjection);
			((GameRendererAccessor) gameRenderer).seamlessportals$setFogRenderer(savedFog);
		}
	}

	static void chunkLit(ClientLevel level, int x, int z, boolean lit) {
		if (lit) {
			ChunkTrackerHolder.get(level).onChunkStatusAdded(x, z, ChunkStatus.FLAG_HAS_LIGHT_DATA);
		} else {
			ChunkTrackerHolder.get(level).onChunkStatusRemoved(x, z, ChunkStatus.FLAG_HAS_LIGHT_DATA);
		}
	}
}
