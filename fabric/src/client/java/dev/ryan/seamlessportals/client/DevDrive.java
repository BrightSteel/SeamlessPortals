package dev.ryan.seamlessportals.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.ryan.seamlessportals.compat.ClientVersioned;
import net.minecraft.client.KeyMapping;
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
 *   <li>{@code respawn}: respawns, if dead;</li>
 *   <li>{@code use}: right-clicks (as the use key, if it's the default one);</li>
 *   <li>{@code command <command>}: runs the command (no slash) as the player;</li>
 *   <li>{@code textclick <x> <y>}: clicks the text there (a dialog's, say) straight through its widget;</li>
 *   <li>{@code press <label>}: presses the button with that label on the screen showing;</li>
 *   <li>{@code shot <name>}: saves the last frame whole, menus and all, as {@code drive_<name>.png};</li>
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
				case "respawn" -> player.respawn();
				case "use" -> KeyMapping.click(minecraft.options.keyUse.getDefaultKey());
				case "command" -> player.connection.sendCommand(String.join(" ", java.util.Arrays.copyOfRange(step, 1, step.length)));
				case "textclick" -> textClick(minecraft, Double.parseDouble(step[1]), Double.parseDouble(step[2]));
				case "press" -> press(minecraft, String.join(" ", java.util.Arrays.copyOfRange(step, 1, step.length)));
				case "shot" -> {
					// Between frames, as F2 takes one: the last frame whole, menus and all.
					try {
						Screenshot.grab(minecraft.gameDirectory, "drive_" + step[1] + ".png", ClientVersioned.mainRenderTarget(), 1, message -> {
						});
					} catch (RuntimeException ignored) {
					}
				}
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

	/** Presses the button on the screen showing whose label is {@code label} (the press method differs between versions). */
	private static void press(Minecraft minecraft, String label) {
		net.minecraft.client.gui.screens.Screen screen = screen(minecraft);
		if (screen != null) {
			press(screen.children(), label);
		}
	}

	/** Looks through {@code children}, and theirs (a dialog's buttons are in a scrolling list), for the button. */
	private static boolean press(List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children, String label) {
		for (var child : children) {
			if (child instanceof net.minecraft.client.gui.components.AbstractButton button && button.getMessage().getString().equals(label)) {
				for (java.lang.reflect.Method method : button.getClass().getMethods()) {
					if (method.getName().equals("onPress") && method.getParameterCount() <= 1) {
						try {
							method.invoke(button, method.getParameterCount() == 0 ? new Object[0] : new Object[] {null});
						} catch (ReflectiveOperationException ignored) {
						}
						return true;
					}
				}
			}
			if (child instanceof net.minecraft.client.gui.components.events.ContainerEventHandler container && press(container.children(), label)) {
				return true;
			}
		}
		return false;
	}

	/** Clicks whatever text widget is at {@code fx}, {@code fy} (fractions of the screen), calling its own click handling. */
	private static void textClick(Minecraft minecraft, double fx, double fy) {
		net.minecraft.client.gui.screens.Screen screen = screen(minecraft);
		if (screen != null) {
			textClick(screen.children(), fx * screen.width, fy * screen.height);
		}
	}

	private static void textClick(List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children, double x, double y) {
		for (var child : children) {
			if (child instanceof net.minecraft.client.gui.components.AbstractStringWidget text && text.isMouseOver(x, y)) {
				for (java.lang.reflect.Method method : text.getClass().getMethods()) {
					if (method.getName().equals("onClick") && method.getParameterCount() == 2) {
						try {
							java.lang.reflect.Constructor<?> event = method.getParameterTypes()[0].getConstructors()[0];
							Object info = event.getParameterTypes()[2].getConstructors()[0].newInstance(0, 0);
							method.invoke(text, event.newInstance(x, y, info), false);
						} catch (ReflectiveOperationException e) {
							System.out.println("[devdrive] textclick: " + e);
						}
						return;
					}
				}
			}
			if (child instanceof net.minecraft.client.gui.components.events.ContainerEventHandler container) {
				textClick(container.children(), x, y);
			}
		}
	}

	/** The screen showing: where it's kept differs between versions (a dev aid, so found by reflection). */
	private static net.minecraft.client.gui.screens.Screen screen(Minecraft minecraft) {
		try {
			Object gui = Minecraft.class.getField("gui").get(minecraft);
			return (net.minecraft.client.gui.screens.Screen) gui.getClass().getMethod("screen").invoke(gui);
		} catch (ReflectiveOperationException e) {
			try {
				return (net.minecraft.client.gui.screens.Screen) Minecraft.class.getField("screen").get(minecraft);
			} catch (ReflectiveOperationException ignored) {
				return null;
			}
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
