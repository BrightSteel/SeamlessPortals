package dev.ryan.seamlessportals.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.ryan.seamlessportals.server.Renamed;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client rendering budget, from {@code config/seamlessportals-client.json} (written with
 * defaults on first start, and by {@link ClientConfigScreen}). Each live portal is a full extra
 * level render, so these trade frame time for how many portals show the other side.
 */
public final class ClientConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("seamlessportals");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ClientConfig instance;

	/** Portals rendered live each frame (nearest visible first). Others within range show a flat fog-coloured card. */
	public int maxLivePortals = 2;
	/** Portals farther than this (blocks) never get a live view, only the card. */
	public double liveViewDistance = 48.0;
	/** Render distance (chunks) used through portals; capped at the game's own render distance. */
	public int remoteRenderDistance = 8;
	/** The same, over views the server streams live (0 = a clear window, 1 = a normal portal). */
	public double streamedPortalOpacity = 0.0;
	/** Portals wider or taller than this (portal blocks) are left as vanilla portals on this client. */
	public int maxPortalSize = 21;
	/** Share of vanilla's swirl particles a linked portal keeps (1 = all, 0 = none); unlinked portals keep all. */
	public double portalParticles = 0.25;
	/**
	 * On servers without the SeamlessPortals server half: portals you've been through show the far
	 * side as you last saw it (no mobs).
	 */
	public boolean offlinePreviews = true;
	/** How opaque the portal's own surface (swirl, starfield) drawn over such a remembered view is (0 = a clear window, 1 = a normal portal). */
	public double offlinePortalOpacity = 0.45;
	/** Disk space for the remembered chunks (MB, per server); the least recently used go first. */
	public int offlineCacheMb = 256;

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

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("seamlessportals-client.json");
	}

	/** Write the current settings (as changed in the settings screen) back to the file. */
	public void save() {
		this.clamp();
		Path file = file();
		try (Writer writer = Files.newBufferedWriter(file)) {
			GSON.toJson(this, writer);
		} catch (IOException e) {
			LOGGER.warn("Couldn't save {}: {}", file, e.toString());
		}
	}

	private static ClientConfig load() {
		Renamed.carryOver(FabricLoader.getInstance().getConfigDir().resolve("netherview-client.json"), file());
		Path file = file();
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
		config.clamp();
		return config;
	}

	private void clamp() {
		this.maxLivePortals = Math.clamp(this.maxLivePortals, 0, 8);
		this.liveViewDistance = Math.clamp(this.liveViewDistance, 0.0, 256.0);
		this.remoteRenderDistance = Math.clamp(this.remoteRenderDistance, 2, 32);
		this.maxPortalSize = Math.clamp(this.maxPortalSize, 2, 21);
		this.portalParticles = Math.clamp(this.portalParticles, 0.0, 1.0);
		this.streamedPortalOpacity = Math.clamp(this.streamedPortalOpacity, 0.0, 1.0);
		this.offlinePortalOpacity = Math.clamp(this.offlinePortalOpacity, 0.0, 1.0);
		this.offlineCacheMb = Math.clamp(this.offlineCacheMb, 16, 8192);
	}
}
