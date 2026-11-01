package dev.ryan.seamlessportals.client;

import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Development aid, off unless {@code -Dseamlessportals.perf=true}: every few seconds, logs frame
 * times and how much of each frame went to remote (through-portal) passes. Remote time is
 * CPU-side (submission); GPU cost shows up in the frame time.
 */
final class PerfLog {
	static final boolean ENABLED = Boolean.getBoolean("seamlessportals.perf");
	private static final Logger LOGGER = LoggerFactory.getLogger("seamlessportals");
	private static final long WINDOW_MS = 5000L;

	private static long[] frameNanos = new long[4096];
	private static int frames;
	private static long lastFrame;
	private static long windowStart;
	private static long remoteNanos;
	private static int remotePasses;

	private PerfLog() {
	}

	static void frameStart() {
		if (!ENABLED) {
			return;
		}
		long now = System.nanoTime();
		if (lastFrame != 0 && frames < frameNanos.length) {
			frameNanos[frames++] = now - lastFrame;
		}
		lastFrame = now;
		long nowMs = Util.getMillis();
		if (windowStart == 0) {
			windowStart = nowMs;
		} else if (nowMs - windowStart >= WINDOW_MS && frames > 0) {
			long[] sorted = Arrays.copyOf(frameNanos, frames);
			Arrays.sort(sorted);
			double avg = Arrays.stream(sorted).average().orElse(0) / 1e6;
			LOGGER.info("[perf] {} fps | frame avg {} ms p95 {} ms max {} ms | live portals/frame {} | remote cpu {} ms/frame",
					Math.round(frames * 1000.0 / (nowMs - windowStart)),
					String.format("%.2f", avg),
					String.format("%.2f", sorted[(int) (frames * 0.95)] / 1e6),
					String.format("%.2f", sorted[frames - 1] / 1e6),
					String.format("%.2f", remotePasses / (double) frames),
					String.format("%.2f", remoteNanos / 1e6 / frames));
			frames = 0;
			remoteNanos = 0;
			remotePasses = 0;
			windowStart = nowMs;
		}
	}

	static void remotePass(long nanos) {
		if (ENABLED) {
			remoteNanos += nanos;
			remotePasses++;
		}
	}
}
