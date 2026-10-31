package dev.ryan.netherview.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Development aid, off unless {@code -Dnetherview.devCapture=true}: every few seconds in a
 * world, saves the main view and the last remote (offscreen) view to run/screenshots.
 */
final class DevCapture {
	static final boolean ENABLED = Boolean.getBoolean("netherview.devCapture");
	private static final int INTERVAL_TICKS = 60;
	private static int ticks;
	private static int shot;

	private DevCapture() {
	}

	private static int burstFrames;
	private static int burstTrip;

	/** Capture the next frames after a portal hand-off. */
	static void startBurst() {
		if (ENABLED) {
			burstFrames = 15;
			burstTrip++;
		}
	}

	/** Called once per frame after the level and portal windows are drawn (before the HUD). */
	static void frame(Minecraft minecraft) {
		if (!ENABLED || burstFrames <= 0 || minecraft.player == null) {
			return;
		}
		int frame = 15 - burstFrames--;
		grab(minecraft, "arrival_" + burstTrip + "_" + frame + ".png", minecraft.gameRenderer.mainRenderTarget());
	}

	static void tick(Minecraft minecraft) {
		if (!ENABLED || minecraft.level == null || minecraft.player == null || ++ticks % INTERVAL_TICKS != 0) {
			return;
		}
		int n = shot++;
		grab(minecraft, "nv_" + n + "_main.png", minecraft.gameRenderer.mainRenderTarget());
		RenderTarget remote = PortalViewRenderer.lastRemoteTarget();
		if (remote != null) {
			grab(minecraft, "nv_" + n + "_remote.png", remote);
		}
	}

	/** A dev aid must never take the game down; skip frames whose target isn't ready. */
	private static void grab(Minecraft minecraft, String name, RenderTarget target) {
		try {
			Screenshot.grab(minecraft.gameDirectory, name, target, 2, message -> {
			});
		} catch (RuntimeException ignored) {
		}
	}
}
