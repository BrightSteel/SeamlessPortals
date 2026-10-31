package dev.ryan.netherview.client;

/** Whether the client was started with {@code -Dnetherview.perf=true} (development measurements). */
public final class PerfMode {
	public static final boolean ENABLED = PerfLog.ENABLED;

	private PerfMode() {
	}
}
