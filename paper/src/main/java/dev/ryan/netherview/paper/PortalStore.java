package dev.ryan.netherview.paper;

import dev.ryan.netherview.net.PortalEnd;
import dev.ryan.netherview.net.PortalFill;
import dev.ryan.netherview.server.CustomPortal;
import dev.ryan.netherview.server.CustomPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Custom portals in {@code plugins/NetherView/portals.yml}. */
final class PortalStore {
	private final JavaPlugin plugin;
	private final File file;

	PortalStore(JavaPlugin plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "portals.yml");
	}

	void load() {
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(this.file);
		List<CustomPortal> portals = new ArrayList<>();
		ConfigurationSection root = yaml.getConfigurationSection("portals");
		if (root != null) {
			for (String name : root.getKeys(false)) {
				ConfigurationSection section = root.getConfigurationSection(name);
				try {
					portals.add(read(name, section));
				} catch (RuntimeException e) {
					this.plugin.getLogger().warning("Skipping portal '" + name + "' in portals.yml: " + e.getMessage());
				}
			}
		}
		CustomPortals.load(portals);
	}

	void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		for (CustomPortal portal : CustomPortals.all()) {
			ConfigurationSection section = yaml.createSection("portals." + portal.name());
			PortalEnd end = portal.end();
			section.set("world", portal.world());
			section.set("min", List.of(end.min().getX(), end.min().getY(), end.min().getZ()));
			section.set("facing", end.facing().getSerializedName());
			section.set("up", end.up().getSerializedName());
			section.set("width", end.width());
			section.set("height", end.height());
			section.set("surface", end.surface());
			section.set("fill", end.fill().name().toLowerCase(Locale.ROOT));
			section.set("public", portal.isPublic());
			switch (portal.destination()) {
				case CustomPortal.Destination.Portal(String partner) -> section.set("destination.portal", partner);
				case CustomPortal.Destination.Target(String world, double x, double y, double z, float yaw, float pitch) -> {
					section.set("destination.world", world);
					section.set("destination.position", List.of(x, y, z));
					section.set("destination.yaw", yaw);
					section.set("destination.pitch", pitch);
				}
				case CustomPortal.Destination.None() -> {
				}
			}
		}
		try {
			this.file.getParentFile().mkdirs();
			yaml.save(this.file);
		} catch (IOException e) {
			this.plugin.getLogger().severe("Couldn't save portals.yml: " + e.getMessage());
		}
	}

	private static CustomPortal read(String name, ConfigurationSection section) {
		List<Integer> min = section.getIntegerList("min");
		PortalEnd end = new PortalEnd(new BlockPos(min.get(0), min.get(1), min.get(2)),
				direction(section.getString("facing")), direction(section.getString("up")),
				section.getInt("width"), section.getInt("height"), section.getInt("surface", 8),
				PortalFill.valueOf(section.getString("fill", "nether_portal").toUpperCase(Locale.ROOT)));
		CustomPortal.Destination destination = new CustomPortal.Destination.None();
		if (section.isString("destination.portal")) {
			destination = new CustomPortal.Destination.Portal(section.getString("destination.portal"));
		} else if (section.isString("destination.world")) {
			List<Double> pos = section.getDoubleList("destination.position");
			destination = new CustomPortal.Destination.Target(section.getString("destination.world"), pos.get(0), pos.get(1), pos.get(2),
					(float) section.getDouble("destination.yaw"), (float) section.getDouble("destination.pitch"));
		}
		return new CustomPortal(name, section.getString("world"), end, destination, section.getBoolean("public"));
	}

	private static Direction direction(String name) {
		Direction direction = Direction.byName(name);
		if (direction == null) {
			throw new IllegalArgumentException("unknown direction " + name);
		}
		return direction;
	}
}
