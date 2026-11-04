package dev.ryan.seamlessportals.client.sodium;

import dev.ryan.seamlessportals.client.mixin.MinecraftAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Remote views with Sodium installed. Sodium gives each LevelRenderer its own chunk renderer
 * but finds it through {@code Minecraft.levelRenderer} (a level extractor binds to that one,
 * whichever renderer it belongs to), and only meshes a chunk once ClientPacketListener has
 * applied its light. A remote view's renderer is made the game's for the length of its own
 * calls ({@link #asCurrent}), and its chunks are reported lit by hand ({@link #chunkLit}).
 * Sodium also draws terrain with the projection and fog it takes from the game renderer; a
 * view's pass hands it the view's own ({@link #remotePass}), oblique near plane included.
 * Without Sodium every call here does nothing; {@link SodiumHooks} is the only class that
 * names Sodium's, and is loaded only with it present.
 */
public final class SodiumCompat {
	public static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("sodium");

	/** Remote views' Sodium chunk renderers (read on the render thread only). */
	private static final Set<Object> REMOTE = Collections.newSetFromMap(new WeakHashMap<>());

	private SodiumCompat() {
	}

	/** Runs {@code action} with {@code renderer} as the game's level renderer, if Sodium is present (as it is, as is, otherwise). */
	public static void asCurrent(LevelRenderer renderer, Runnable action) {
		if (!PRESENT) {
			action.run();
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		LevelRenderer saved = minecraft.levelRenderer;
		if (saved == renderer) {
			action.run();
			return;
		}
		((MinecraftAccessor) minecraft).seamlessportals$setLevelRenderer(renderer);
		try {
			action.run();
		} finally {
			((MinecraftAccessor) minecraft).seamlessportals$setLevelRenderer(saved);
		}
	}

	/**
	 * Runs part of a remote view's pass (its culling or drawing): as {@link #asCurrent}, with
	 * the view's projection and fog standing in for the game renderer's where Sodium reads them.
	 */
	public static void remotePass(LevelRenderer renderer, Matrix4f projection, FogRenderer fog, Runnable action) {
		if (!PRESENT) {
			action.run();
			return;
		}
		asCurrent(renderer, () -> SodiumHooks.withProjectionAndFog(projection, fog, action));
	}

	/** Marks a remote view's renderer, so its Sodium renderer keeps to the views' render distance. */
	public static @Nullable Object registerRemote(LevelRenderer renderer) {
		if (!PRESENT) {
			return null;
		}
		Object sodium = SodiumHooks.worldRenderer(renderer);
		REMOTE.add(sodium);
		return sodium;
	}

	public static void unregisterRemote(@Nullable Object sodiumRenderer) {
		if (sodiumRenderer != null) {
			REMOTE.remove(sodiumRenderer);
		}
	}

	/** Whether a Sodium chunk renderer (as a mixin sees it) is a remote view's. */
	public static boolean isRemote(Object sodiumRenderer) {
		return REMOTE.contains(sodiumRenderer);
	}

	/** The chunk's light is in (as ClientPacketListener would report it): Sodium may mesh it. */
	public static void chunkLit(ClientLevel level, int x, int z) {
		if (PRESENT) {
			SodiumHooks.chunkLit(level, x, z, true);
		}
	}

	/** The chunk's (re)loaded and its light is yet to come. */
	public static void chunkUnlit(ClientLevel level, int x, int z) {
		if (PRESENT) {
			SodiumHooks.chunkLit(level, x, z, false);
		}
	}
}
