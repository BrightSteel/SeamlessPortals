package dev.ryan.seamlessportals.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Development aid, only active with {@code -Dseamlessportals.perf=true}: switches re-read once a
 * second from {@code seamlessportals-dev.properties} in the game directory, for isolating the
 * cost of each rendering step without restarting.
 */
final class DevToggles {
	private static final long POLL_MS = 1000L;
	private static long lastPoll;
	private static Properties values = new Properties();

	private DevToggles() {
	}

	static boolean on(String key) {
		return Boolean.parseBoolean(value(key, "false"));
	}

	/** An integer override, or {@code fallback} when not set (or not in perf mode). */
	static int intValue(String key, int fallback) {
		try {
			return Integer.parseInt(value(key, String.valueOf(fallback)).trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static String value(String key, String fallback) {
		if (!PerfLog.ENABLED) {
			return fallback;
		}
		long now = Util.getMillis();
		if (now - lastPoll > POLL_MS) {
			lastPoll = now;
			Properties next = new Properties();
			Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("seamlessportals-dev.properties");
			if (Files.exists(file)) {
				try (Reader reader = Files.newBufferedReader(file)) {
					next.load(reader);
				} catch (IOException ignored) {
				}
			}
			values = next;
		}
		return values.getProperty(key, fallback);
	}
}
