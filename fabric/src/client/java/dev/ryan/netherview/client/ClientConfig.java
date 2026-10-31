package dev.ryan.netherview.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client rendering budget, from {@code config/netherview-client.json} (written with
 * defaults on first start). Each live portal is a full extra level render, so these trade
 * frame time for how many portals show the other side.
 */
public final class ClientConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("netherview");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ClientConfig instance;

	/** Portals rendered live each frame (nearest visible first). Others within range show a flat fog-coloured card. */
	public int maxLivePortals = 2;
	/** Portals farther than this (blocks) never get a live view, only the card. */
	public double liveViewDistance = 48.0;
	/** Render distance (chunks) used through portals; capped at the game's own render distance. */
	public int remoteRenderDistance = 8;
	/** Portals wider or taller than this (portal blocks) are left as vanilla portals on this client. */
	public int maxPortalSize = 21;

	/** {@link #maxLivePortals}, unless a perf-mode dev override is set. */
	public int effectiveMaxLivePortals() {
		return DevToggles.intValue("maxLivePortals", this.maxLivePortals);
	}

	/** {@link #liveViewDistance}, unless a perf-mode dev override is set. */
	public double effectiveLiveViewDistance() {
		return DevToggles.intValue("liveViewDistance", (int) this.liveViewDistance);
	}

	/** {@link #remoteRenderDistance}, unless a perf-mode dev override is set. */
	public int effectiveRemoteRenderDistance() {
		return DevToggles.intValue("remoteRenderDistance", this.remoteRenderDistance);
	}

	public static ClientConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static ClientConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("netherview-client.json");
		ClientConfig config = new ClientConfig();
		try {
			if (Files.exists(file)) {
				try (Reader reader = Files.newBufferedReader(file)) {
					ClientConfig loaded = GSON.fromJson(reader, ClientConfig.class);
					if (loaded != null) {
						config = loaded;
					}
				}
			}
			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException | JsonParseException e) {
			LOGGER.warn("Couldn't read {}, using defaults: {}", file, e.toString());
		}
		config.maxLivePortals = Math.clamp(config.maxLivePortals, 0, 8);
		config.liveViewDistance = Math.clamp(config.liveViewDistance, 0.0, 256.0);
		config.remoteRenderDistance = Math.clamp(config.remoteRenderDistance, 2, 32);
		config.maxPortalSize = Math.clamp(config.maxPortalSize, 2, 21);
		return config;
	}
}
