package dev.ryan.netherview.client;

import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * Each world's clocks as last sent while the player was in it, kept running. The client has a
 * single set of clocks, holding the current world's take on them, and a server may keep a
 * separate time per world (Paper does): in the Nether the overworld clock it sends is the
 * Nether's own count, so a view of the overworld from there would show the wrong time of day.
 */
public final class RemoteClocks {
	private static final Map<ResourceKey<Level>, ClientClockManager> CLOCKS = new HashMap<>();
	/** Set while a remote view's level is being built: its timelines take their clocks then, once. */
	private static ResourceKey<Level> constructing;

	private RemoteClocks() {
	}

	/** The server set the time (and maybe the clocks) while the player was in {@code dimension}. */
	public static void onSetTime(ResourceKey<Level> dimension, long gameTime, Map<Holder<WorldClock>, ClockNetworkState> updates) {
		CLOCKS.computeIfAbsent(dimension, key -> new ClientClockManager()).handleUpdates(gameTime, updates);
	}

	/** Every client tick, with the player's level's game time. */
	static void tick(long gameTime) {
		CLOCKS.values().forEach(clocks -> clocks.tick(gameTime));
	}

	/** A remote view's level of {@code dimension} is being built (see {@link #forRemote}); end with {@link #endConstructing}. */
	static void beginConstructing(ResourceKey<Level> dimension) {
		constructing = dimension;
	}

	static void endConstructing() {
		constructing = null;
	}

	/** The clocks a level of {@code dimension} should read, if a remote view's (null: the shared ones). */
	public static ClientClockManager forRemote(ResourceKey<Level> dimension, boolean remote) {
		if (constructing != null) {
			return CLOCKS.get(constructing);
		}
		return remote ? CLOCKS.get(dimension) : null;
	}

	static void clear() {
		CLOCKS.clear();
	}
}
