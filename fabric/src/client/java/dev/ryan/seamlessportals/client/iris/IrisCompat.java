package dev.ryan.seamlessportals.client.iris;

import dev.ryan.seamlessportals.client.RemoteDimensions;
import dev.ryan.seamlessportals.client.mixin.GameRendererAccessor;
import dev.ryan.seamlessportals.client.mixin.iris.ClientLevelConnectionAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Remote views with an Iris shader pack on. Iris picks the pipeline (the pack's program set
 * for a dimension) from {@code Minecraft.level} at the start of every level render, and
 * centres its shadow map on the game renderer's main camera: a view of the End would be drawn
 * with the overworld's sky, clouds and sun, shadowed as if from the player's spot. For a
 * view's draw both are the view's own ({@link #remoteDraw}), so Iris draws it with that
 * dimension's pipeline, which it keeps beside the player's own. The player's hand, which Iris
 * draws inside the level render, is left out of views.
 * <p>
 * Compiling a pipeline takes seconds, and Iris drops them all on every dimension change, which
 * a walk through a portal makes visible (no loading screen hides it) twice over: the player's
 * new pipeline, and the view back's. So pipelines are kept across dimension changes
 * ({@link #keepingPipelines}), and the overworld's, the nether's and the End's are built as
 * soon as the player joins (or the pack loads) ({@link #prebuild}), behind the loading screen.
 * Without Iris every call here only runs its action or does nothing; {@link IrisHooks} is the only class that names Iris's, and is loaded only with
 * it present.
 */
public final class IrisCompat {
	public static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("iris");

	private static boolean remoteDraw;
	private static boolean levelChanging;

	private IrisCompat() {
	}

	/** Runs a remote view's level render as one of {@code level}, seen from {@code camera}, if a shader pack is on. */
	public static void remoteDraw(ClientLevel level, Camera camera, Runnable action) {
		if (!PRESENT || !IrisHooks.shadersOn()) {
			action.run();
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		GameRendererAccessor gameRenderer = (GameRendererAccessor) minecraft.gameRenderer;
		ClientLevel savedLevel = minecraft.level;
		Camera savedCamera = gameRenderer.seamlessportals$getMainCamera();
		minecraft.level = level;
		gameRenderer.seamlessportals$setMainCamera(camera);
		remoteDraw = true;
		try {
			action.run();
		} finally {
			remoteDraw = false;
			minecraft.level = savedLevel;
			gameRenderer.seamlessportals$setMainCamera(savedCamera);
			// The player's pipeline current again, for whatever asks for it before the main render does.
			IrisHooks.restorePipeline();
		}
	}

	/** The game's level is about to change (Minecraft.setLevel). */
	public static void levelChanging() {
		levelChanging = PRESENT;
	}

	/** The game's level has changed: the pipelines missing are built now. */
	public static void levelChanged() {
		levelChanging = false;
		prebuild();
	}

	/** Whether Iris should keep its pipelines when it would drop them: during a level change, which only changes dimension. */
	public static boolean keepingPipelines() {
		return levelChanging;
	}

	/** Builds the pipelines of the vanilla dimensions this server has, if a shader pack is on (those already built are kept). */
	public static void prebuild() {
		Minecraft minecraft = Minecraft.getInstance();
		if (PRESENT && minecraft.level != null && IrisHooks.shadersOn()) {
			IrisHooks.prebuild(((ClientLevelConnectionAccessor) minecraft.level).seamlessportals$getConnection().levels());
		}
	}

	/**
	 * A pack (re)loaded, or shaders switched on or off: the views' terrain is rebuilt (its
	 * vertex format follows whether a pack is on, and Iris rebuilds only the game's), and with
	 * a pack on, the other dimensions' pipelines are built now.
	 */
	public static void irisReloaded() {
		RemoteDimensions.rebuildViews();
		prebuild();
	}

	/** Whether a remote view's level render is under way (no hand in it). */
	public static boolean inRemoteDraw() {
		return remoteDraw;
	}
}
