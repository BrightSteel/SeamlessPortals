package dev.ryan.seamlessportals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryan.seamlessportals.net.PortalEnd;
import dev.ryan.seamlessportals.net.PortalFill;
import dev.ryan.seamlessportals.net.PortalInsets;
import dev.ryan.seamlessportals.server.CustomPortal;
import dev.ryan.seamlessportals.server.CustomPortals;
import dev.ryan.seamlessportals.server.Renamed;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Custom portals in {@code <world>/seamlessportals/portals.json}: they belong to the save (or the
 * dedicated server's world), like the Paper plugin's portals.yml belongs to its server. The
 * fields are the same as portals.yml's.
 */
final class PortalStore {
	private static final Logger LOGGER = LoggerFactory.getLogger(SeamlessPortals.MOD_ID);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static Path file;

	private PortalStore() {
	}

	static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("seamlessportals").resolve("portals.json");
		Renamed.carryOver(server.getWorldPath(LevelResource.ROOT).resolve("netherview"), file.getParent());
		List<CustomPortal> portals = new ArrayList<>();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
				for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("portals").entrySet()) {
					try {
						portals.add(read(entry.getKey(), entry.getValue().getAsJsonObject()));
					} catch (RuntimeException e) {
						LOGGER.warn("Skipping portal '{}' in {}: {}", entry.getKey(), file, e.toString());
					}
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.error("Couldn't read {}: {}", file, e.toString());
			}
		}
		CustomPortals.load(portals);
	}

	/** The server stopped (singleplayer can open another world next): forget this one's portals. */
	static void unload() {
		file = null;
		CustomPortals.load(List.of());
	}

	static void save() {
		if (file == null) {
			return;
		}
		JsonObject all = new JsonObject();
		for (CustomPortal portal : CustomPortals.all()) {
			all.add(portal.name(), write(portal));
		}
		JsonObject root = new JsonObject();
		root.add("portals", all);
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(root, writer);
			}
		} catch (IOException e) {
			LOGGER.error("Couldn't save {}: {}", file, e.toString());
		}
	}

	private static JsonObject write(CustomPortal portal) {
		PortalEnd end = portal.end();
		JsonObject json = new JsonObject();
		json.addProperty("world", portal.world());
		json.add("min", array(end.min().getX(), end.min().getY(), end.min().getZ()));
		json.addProperty("facing", end.facing().getSerializedName());
		json.addProperty("up", end.up().getSerializedName());
		json.addProperty("width", end.width());
		json.addProperty("height", end.height());
		json.addProperty("surface", end.surface());
		if (end.frontOnly()) {
			json.addProperty("frontOnly", true);
		}
		if (!end.insets().isNone()) {
			json.add("insets", array(end.insets().left(), end.insets().right(), end.insets().bottom(), end.insets().top()));
		}
		json.addProperty("fill", end.fill().name().toLowerCase(Locale.ROOT));
		json.addProperty("public", portal.access().setting());
		JsonObject destination = new JsonObject();
		switch (portal.destination()) {
			case CustomPortal.Destination.Portal(String partner) -> destination.addProperty("portal", partner);
			case CustomPortal.Destination.Target(String world, double x, double y, double z, float yaw, float pitch) -> {
				destination.addProperty("world", world);
				destination.add("position", array(x, y, z));
				destination.addProperty("yaw", yaw);
				destination.addProperty("pitch", pitch);
			}
			case CustomPortal.Destination.Random() -> destination.addProperty("random", true);
			case CustomPortal.Destination.None() -> {
			}
		}
		json.add("destination", destination);
		return json;
	}

	private static CustomPortal read(String name, JsonObject json) {
		JsonArray min = json.getAsJsonArray("min");
		PortalEnd end = new PortalEnd(new BlockPos(min.get(0).getAsInt(), min.get(1).getAsInt(), min.get(2).getAsInt()),
				direction(json.get("facing").getAsString()), direction(json.get("up").getAsString()),
				json.get("width").getAsInt(), json.get("height").getAsInt(),
				json.has("surface") ? json.get("surface").getAsInt() : 8,
				PortalFill.valueOf(json.has("fill") ? json.get("fill").getAsString().toUpperCase(Locale.ROOT) : "NETHER_PORTAL"));
		end = end.withFrontOnly(json.has("frontOnly") && json.get("frontOnly").getAsBoolean());
		if (json.has("insets")) {
			JsonArray insets = json.getAsJsonArray("insets");
			end = end.withInsets(new PortalInsets(insets.get(0).getAsInt(), insets.get(1).getAsInt(), insets.get(2).getAsInt(), insets.get(3).getAsInt()));
		}
		CustomPortal.Destination destination = new CustomPortal.Destination.None();
		JsonObject dest = json.has("destination") ? json.getAsJsonObject("destination") : new JsonObject();
		if (dest.has("portal")) {
			destination = new CustomPortal.Destination.Portal(dest.get("portal").getAsString());
		} else if (dest.has("random") && dest.get("random").getAsBoolean()) {
			destination = new CustomPortal.Destination.Random();
		} else if (dest.has("world")) {
			JsonArray pos = dest.getAsJsonArray("position");
			destination = new CustomPortal.Destination.Target(dest.get("world").getAsString(),
					pos.get(0).getAsDouble(), pos.get(1).getAsDouble(), pos.get(2).getAsDouble(),
					dest.has("yaw") ? dest.get("yaw").getAsFloat() : 0.0F, dest.has("pitch") ? dest.get("pitch").getAsFloat() : 0.0F);
		}
		return new CustomPortal(name, json.get("world").getAsString(), end, destination, CustomPortal.Access.parse(json.has("public") ? json.get("public").getAsString() : "false"));
	}

	private static JsonArray array(Number... values) {
		JsonArray array = new JsonArray();
		for (Number value : values) {
			array.add(value);
		}
		return array;
	}

	private static Direction direction(String name) {
		Direction direction = Direction.byName(name);
		if (direction == null) {
			throw new IllegalArgumentException("unknown direction " + name);
		}
		return direction;
	}
}
