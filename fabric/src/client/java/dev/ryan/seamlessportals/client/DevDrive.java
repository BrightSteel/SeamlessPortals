package dev.ryan.seamlessportals.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.ryan.seamlessportals.compat.ClientVersioned;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Development aid, off unless {@code -Dseamlessportals.devCapture=true}: walks the player by a
 * script, for testing crossings as a player makes them (the real movement keys, not teleports),
 * and records every tick's frame meanwhile. A script is a file {@code devdrive.txt} in the game
 * directory, taken (and deleted) as soon as it appears, one step a line:
 * <ul>
 *   <li>{@code look <yaw> <pitch>}: face that way;</li>
 *   <li>{@code step <ticks> <forward> <strafe> <yaw per tick> <pitch per tick>}: hold the
 *       movement keys (forward and strafe -1, 0 or 1) and turn, for that many ticks;</li>
 *   <li>{@code record <name>} / {@code stop}: save each tick's frame as
 *       {@code drive_<name>_<n>.png}, or stop;</li>
 *   <li>{@code done <name>}: writes {@code drive_<name>.done} when it's reached.</li>
 * </ul>
 */
final class DevDrive {
	private static final Path SCRIPT = Path.of("devdrive.txt");
	private static final ArrayDeque<String[]> STEPS = new ArrayDeque<>();
	private static String[] current;
	private static int left;
	private static String recording;
	private static int frame;
	private static boolean frameDue;
	/** Whether the script's holding the movement keys. */
	private static boolean driving;

	private DevDrive() {
	}

	static void tick(Minecraft minecraft) {
		if (!DevCapture.ENABLED) {
			return;
		}
		try {
			drive(minecraft);
		} catch (RuntimeException e) {
			// A dev aid must never take the game down: drop the script.
			STEPS.clear();
			current = null;
			recording = null;
		}
	}

	private static void drive(Minecraft minecraft) {
		Path script = minecraft.gameDirectory.toPath().resolve(SCRIPT);
		if (Files.exists(script)) {
			try {
				List<String> lines = Files.readAllLines(script);
				Files.delete(script);
				for (String line : lines) {
					if (!line.isBlank()) {
						STEPS.add(line.trim().split("\\s+"));
					}
				}
			} catch (IOException ignored) {
			}
		}
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return;
		}
		frameDue = recording != null;
		while (current == null && !STEPS.isEmpty()) {
			String[] step = STEPS.poll();
			switch (step[0]) {
				case "look" -> {
					player.setYRot(Float.parseFloat(step[1]));
					player.setXRot(Float.parseFloat(step[2]));
				}
				case "record" -> {
					recording = step[1];
					frame = 0;
				}
				case "stop" -> recording = null;
				case "done" -> {
					try {
						Files.writeString(minecraft.gameDirectory.toPath().resolve("drive_" + step[1] + ".done"), "");
					} catch (IOException ignored) {
					}
				}
				case "step" -> {
					current = step;
					left = Integer.parseInt(step[1]);
				}
				default -> {
				}
			}
		}
		if (current == null) {
			// Let go of the keys once a script's done, and only then: otherwise they're the player's.
			if (driving) {
				keys(minecraft, 0.0F, 0.0F);
				driving = false;
			}
			return;
		}
		driving = true;
		keys(minecraft, Float.parseFloat(current[2]), Float.parseFloat(current[3]));
		player.setYRot(player.getYRot() + Float.parseFloat(current[4]));
		player.setXRot(Math.clamp(player.getXRot() + Float.parseFloat(current[5]), -90.0F, 90.0F));
		if (--left <= 0) {
			current = null;
		}
	}

	private static void keys(Minecraft minecraft, float forward, float strafe) {
		minecraft.options.keyUp.setDown(forward > 0);
		minecraft.options.keyDown.setDown(forward < 0);
		minecraft.options.keyLeft.setDown(strafe > 0);
		minecraft.options.keyRight.setDown(strafe < 0);
	}

	/** After the frame's level and portals are drawn: saves it if recording (once a tick). */
	static void frame(Minecraft minecraft) {
		if (recording == null || !frameDue) {
			return;
		}
		frameDue = false;
		RenderTarget target = ClientVersioned.mainRenderTarget();
		try {
			Screenshot.grab(minecraft.gameDirectory, "drive_" + recording + "_" + String.format("%03d", frame++) + ".png", target, 2, message -> {
			});
		} catch (RuntimeException ignored) {
		}
	}
}
