package dev.ryan.seamlessportals.client;

/** Whether the client was started with {@code -Dseamlessportals.perf=true} (development measurements). */
public final class PerfMode {
	public static final boolean ENABLED = PerfLog.ENABLED;

	private PerfMode() {
	}
}
