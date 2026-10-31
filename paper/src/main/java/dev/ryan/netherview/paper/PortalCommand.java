package dev.ryan.netherview.paper;

import dev.ryan.netherview.PortalGeometry;
import dev.ryan.netherview.net.PortalEnd;
import dev.ryan.netherview.net.PortalFill;
import dev.ryan.netherview.server.CustomPortal;
import dev.ryan.netherview.server.CustomPortalBlocks;
import dev.ryan.netherview.server.CustomPortals;
import dev.ryan.netherview.server.RandomDestinations;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code /nvportal}: define and manage custom portals.
 *
 * <pre>
 * wand                                         get the portal wand
 * create &lt;name&gt; [fill]                         from the wand selection (you stand on the front side)
 * createat &lt;name&gt; &lt;world&gt; &lt;x1 y1 z1&gt; &lt;x2 y2 z2&gt; &lt;facing&gt; [up] [fill]
 * link &lt;a&gt; &lt;b&gt;                                two-way pair
 * target &lt;name&gt; here | &lt;world&gt; &lt;x y z&gt; [yaw] [pitch]   one-way
 * target &lt;name&gt; random                       one-way to random spots in the portal's world
 * unlink &lt;name&gt; · fill &lt;name&gt; &lt;fill&gt; · public &lt;name&gt; &lt;true|false&gt;
 * delete &lt;name&gt; · list · info &lt;name&gt; · tp &lt;name&gt;
 * </pre>
 */
final class PortalCommand implements TabExecutor {
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
	private static final List<String> SUBCOMMANDS = List.of("wand", "create", "createat", "link", "target", "unlink", "fill", "public", "delete", "list", "info", "tp");
	private static final List<String> FILLS = Arrays.stream(PortalFill.values()).filter(f -> f != PortalFill.NONE).map(f -> f.name().toLowerCase(Locale.ROOT)).toList();
	private static final int MAX_SIZE = 21;

	private final PortalWand wand;

	PortalCommand(PortalWand wand) {
		this.wand = wand;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission("netherview.admin")) {
			return error(sender, "You don't have permission to manage portals.");
		}
		if (args.length == 0) {
			return this.help(sender);
		}
		try {
			return switch (args[0].toLowerCase(Locale.ROOT)) {
				case "wand" -> this.wand(sender);
				case "create" -> this.create(sender, args);
				case "createat" -> this.createAt(sender, args);
				case "link" -> this.link(sender, args);
				case "target" -> this.target(sender, args);
				case "unlink" -> this.unlink(sender, args);
				case "fill" -> this.fill(sender, args);
				case "public" -> this.setPublic(sender, args);
				case "delete" -> this.delete(sender, args);
				case "list" -> this.list(sender);
				case "info" -> this.info(sender, args);
				case "tp" -> this.tp(sender, args);
				default -> this.help(sender);
			};
		} catch (IllegalArgumentException e) {
			return error(sender, e.getMessage());
		}
	}

	private boolean help(CommandSender sender) {
		sender.sendMessage(Component.text("""
				/nvportal wand — get the portal wand (left/right click two corners)
				/nvportal create <name> [fill] — portal from the selection; you stand on its front
				/nvportal createat <name> <world> <x1 y1 z1> <x2 y2 z2> <facing> [up] [fill]
				/nvportal link <a> <b> — two-way pair
				/nvportal target <name> here | <world> <x y z> [yaw] [pitch] — one-way
				/nvportal target <name> random — one-way to a new random spot in its world each trip
				/nvportal unlink|delete|info|tp <name>, fill <name> <fill>, public <name> <true|false>, list
				Fills: """ + String.join(", ", FILLS), NamedTextColor.GRAY));
		return true;
	}

	private boolean wand(CommandSender sender) {
		Player player = player(sender);
		player.getInventory().addItem(this.wand.create());
		return ok(sender, "Here's the portal wand. Left click one corner, right click the opposite one, then /nvportal create <name>.");
	}

	private boolean create(CommandSender sender, String[] args) {
		Player player = player(sender);
		String name = newName(args, 1);
		PortalWand.Selection selection = this.wand.selection(player);
		if (selection == null || !selection.complete()) {
			throw new IllegalArgumentException("Select two corners with the wand first (/nvportal wand).");
		}
		if (!selection.world().equals(player.getWorld())) {
			throw new IllegalArgumentException("Stand in the same world as your selection (it decides which side is the front).");
		}
		BlockPos a = new BlockPos(selection.first().getX(), selection.first().getY(), selection.first().getZ());
		BlockPos b = new BlockPos(selection.second().getX(), selection.second().getY(), selection.second().getZ());
		BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		Location at = player.getLocation();
		Vec3 eye = new Vec3(at.getX(), at.getY() + player.getEyeHeight(), at.getZ());
		Vec3 center = new Vec3((min.getX() + max.getX() + 1) / 2.0, (min.getY() + max.getY() + 1) / 2.0, (min.getZ() + max.getZ() + 1) / 2.0);

		// The flat axis is the opening's normal; if the selection is flat in more than one
		// (a 1-wide portal), the one the player stands furthest along.
		Direction.Axis normal = null;
		double best = -1;
		for (Direction.Axis axis : Direction.Axis.values()) {
			if (axis.choose(min.getX(), min.getY(), min.getZ()) == axis.choose(max.getX(), max.getY(), max.getZ())) {
				double offset = Math.abs(axis.choose(eye.x, eye.y, eye.z) - axis.choose(center.x, center.y, center.z));
				if (offset > best) {
					best = offset;
					normal = axis;
				}
			}
		}
		if (normal == null) {
			throw new IllegalArgumentException("The corners must lie in one flat layer: same x, same y or same z.");
		}
		Direction facing = Direction.fromAxisAndDirection(normal,
				normal.choose(eye.x, eye.y, eye.z) >= normal.choose(center.x, center.y, center.z) ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
		// A floor or ceiling's "up" is the way the player is facing; a wall's is up.
		Direction up = normal == Direction.Axis.Y ? Direction.fromYRot(at.getYaw()) : Direction.UP;
		PortalFill fill = args.length > 2 ? fill(args[2]) : normal == Direction.Axis.Y ? PortalFill.END_PORTAL : PortalFill.NETHER_PORTAL;
		return this.build(sender, name, worldId(player.getWorld()), min, max, facing, up, fill);
	}

	private boolean createAt(CommandSender sender, String[] args) {
		if (args.length < 10) {
			throw new IllegalArgumentException("Usage: /nvportal createat <name> <world> <x1 y1 z1> <x2 y2 z2> <facing> [up] [fill]");
		}
		String name = newName(args, 1);
		World world = world(args[2]);
		BlockPos a = new BlockPos(integer(args[3]), integer(args[4]), integer(args[5]));
		BlockPos b = new BlockPos(integer(args[6]), integer(args[7]), integer(args[8]));
		Direction facing = direction(args[9]);
		Direction up = args.length > 10 && Direction.byName(args[10].toLowerCase(Locale.ROOT)) != null
				? direction(args[10]) : facing.getAxis() == Direction.Axis.Y ? Direction.SOUTH : Direction.UP;
		int fillArg = args.length > 10 && Direction.byName(args[10].toLowerCase(Locale.ROOT)) != null ? 11 : 10;
		PortalFill fill = args.length > fillArg ? fill(args[fillArg]) : facing.getAxis() == Direction.Axis.Y ? PortalFill.END_PORTAL : PortalFill.NETHER_PORTAL;
		BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		return this.build(sender, name, worldId(world), min, max, facing, up, fill);
	}

	private boolean build(CommandSender sender, String name, String worldId, BlockPos min, BlockPos max, Direction facing, Direction up, PortalFill fill) {
		if (up.getAxis() == facing.getAxis()) {
			throw new IllegalArgumentException("'up' must lie in the opening, not along its facing.");
		}
		if (facing.getAxis().choose(min.getX(), min.getY(), min.getZ()) != facing.getAxis().choose(max.getX(), max.getY(), max.getZ())) {
			throw new IllegalArgumentException("The opening must be one block thick along its facing (" + facing.getAxis() + ").");
		}
		PortalEnd shape = new PortalEnd(min, facing, up, 1, 1, 8, fill);
		int width = extent(min, max, shape.right().getAxis());
		int height = extent(min, max, up.getAxis());
		if (width > MAX_SIZE || height > MAX_SIZE) {
			throw new IllegalArgumentException("Portals can be at most " + MAX_SIZE + "×" + MAX_SIZE + " (this one is " + width + "×" + height + ").");
		}
		String fillProblem = CustomPortalBlocks.checkFill(fill, facing);
		if (fillProblem != null) {
			throw new IllegalArgumentException(fillProblem + ".");
		}
		PortalEnd end = new PortalEnd(min, facing, up, width, height, fill == PortalFill.END_PORTAL ? 12 : 8, fill);
		ServerLevel level = level(worldId);
		int blocked = CustomPortalBlocks.blockedCount(level, end);
		if (blocked > 0) {
			throw new IllegalArgumentException(blocked + " block(s) in the opening aren't empty; clear them first.");
		}
		CustomPortalBlocks.place(level, end);
		CustomPortals.put(new CustomPortal(name, worldId, end, new CustomPortal.Destination.None(), false));
		return ok(sender, "Created portal '" + name + "' (" + width + "×" + height + ", facing " + facing.getSerializedName() + ", "
				+ fill.name().toLowerCase(Locale.ROOT) + "). Link it with /nvportal link " + name + " <other> or /nvportal target " + name + " here.");
	}

	private boolean link(CommandSender sender, String[] args) {
		CustomPortal a = existing(args, 1);
		CustomPortal b = existing(args, 2);
		if (a.name().equals(b.name())) {
			throw new IllegalArgumentException("A portal can't be linked to itself.");
		}
		CustomPortals.link(a.name(), b.name());
		return ok(sender, "Linked '" + a.name() + "' ⇄ '" + b.name() + "'.");
	}

	private boolean target(CommandSender sender, String[] args) {
		CustomPortal portal = existing(args, 1);
		if (args.length == 3 && args[2].equalsIgnoreCase("random")) {
			CustomPortals.setDestination(portal.name(), new CustomPortal.Destination.Random());
			return ok(sender, "'" + portal.name() + "' now leads to random spots in " + portal.world() + ". It shows the next one once it's found.");
		}
		CustomPortal.Destination.Target target;
		if (args.length > 2 && args[2].equalsIgnoreCase("here")) {
			Player player = player(sender);
			Location at = player.getLocation();
			target = new CustomPortal.Destination.Target(worldId(player.getWorld()), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
		} else if (args.length >= 6) {
			target = new CustomPortal.Destination.Target(worldId(world(args[2])), number(args[3]), number(args[4]), number(args[5]),
					args.length > 6 ? (float) number(args[6]) : 0.0F, args.length > 7 ? (float) number(args[7]) : 0.0F);
		} else {
			throw new IllegalArgumentException("Usage: /nvportal target <name> here | random | <world> <x> <y> <z> [yaw] [pitch]");
		}
		CustomPortals.setDestination(portal.name(), target);
		return ok(sender, "'" + portal.name() + "' now leads one-way to " + describe(target) + ".");
	}

	private boolean unlink(CommandSender sender, String[] args) {
		CustomPortal portal = existing(args, 1);
		CustomPortals.setDestination(portal.name(), new CustomPortal.Destination.None());
		return ok(sender, "'" + portal.name() + "' no longer leads anywhere.");
	}

	private boolean fill(CommandSender sender, String[] args) {
		CustomPortal portal = existing(args, 1);
		if (args.length < 3) {
			throw new IllegalArgumentException("Usage: /nvportal fill <name> <" + String.join("|", FILLS) + ">");
		}
		PortalFill fill = fill(args[2]);
		String problem = CustomPortalBlocks.checkFill(fill, portal.end().facing());
		if (problem != null) {
			throw new IllegalArgumentException(problem + ".");
		}
		PortalEnd old = portal.end();
		PortalEnd end = new PortalEnd(old.min(), old.facing(), old.up(), old.width(), old.height(), fill == PortalFill.END_PORTAL ? 12 : 8, fill);
		ServerLevel level = level(portal.world());
		CustomPortalBlocks.clear(level, old);
		CustomPortalBlocks.place(level, end);
		CustomPortals.put(portal.withEnd(end));
		return ok(sender, "'" + portal.name() + "' is now filled with " + fill.name().toLowerCase(Locale.ROOT) + ".");
	}

	private boolean setPublic(CommandSender sender, String[] args) {
		CustomPortal portal = existing(args, 1);
		if (args.length < 3) {
			throw new IllegalArgumentException("Usage: /nvportal public <name> <true|false>");
		}
		boolean isPublic = Boolean.parseBoolean(args[2]);
		CustomPortals.put(portal.withPublic(isPublic));
		return ok(sender, "'" + portal.name() + "' is now " + (isPublic ? "public: anyone can use it." : "private: needs netherview.portal." + portal.name() + "."));
	}

	private boolean delete(CommandSender sender, String[] args) {
		CustomPortal portal = existing(args, 1);
		ServerLevel level = CustomPortals.level(MinecraftServer.getServer(), portal.world());
		if (level != null) {
			CustomPortalBlocks.clear(level, portal.end());
		}
		CustomPortals.remove(portal.name());
		return ok(sender, "Deleted '" + portal.name() + "'.");
	}

	private boolean list(CommandSender sender) {
		if (CustomPortals.all().isEmpty()) {
			return ok(sender, "No custom portals yet. Start with /nvportal wand.");
		}
		for (CustomPortal portal : CustomPortals.all()) {
			sender.sendMessage(Component.text(portal.name(), NamedTextColor.LIGHT_PURPLE)
					.append(Component.text(" — " + summary(portal), NamedTextColor.GRAY)));
		}
		return true;
	}

	private boolean info(CommandSender sender, String[] args) {
		CustomPortal portal = existing(args, 1);
		PortalEnd end = portal.end();
		sender.sendMessage(Component.text(portal.name(), NamedTextColor.LIGHT_PURPLE)
				.append(Component.text("\n world " + portal.world() + ", corner " + end.min().toShortString()
						+ "\n " + end.width() + "×" + end.height() + ", facing " + end.facing().getSerializedName() + ", up " + end.up().getSerializedName()
						+ "\n fill " + end.fill().name().toLowerCase(Locale.ROOT) + ", " + (portal.isPublic() ? "public" : "private (netherview.portal." + portal.name() + ")")
						+ "\n leads to " + describe(portal.destination()) + nextSpot(portal), NamedTextColor.GRAY)));
		return true;
	}

	private boolean tp(CommandSender sender, String[] args) {
		Player player = player(sender);
		CustomPortal portal = existing(args, 1);
		ServerLevel level = level(portal.world());
		Vec3 center = PortalGeometry.center(portal.end());
		Vec3 front = Vec3.atLowerCornerOf(portal.end().facing().getUnitVec3i());
		Vec3 spot = center.add(front.scale(2.0));
		if (portal.end().facing().getAxis() != Direction.Axis.Y) {
			spot = new Vec3(spot.x, portal.end().min().getY(), spot.z);
		}
		Direction toward = portal.end().facing().getAxis() == Direction.Axis.Y ? portal.end().up().getOpposite() : portal.end().facing().getOpposite();
		player.teleport(new Location(level.getWorld(), spot.x, spot.y, spot.z, toward.toYRot(), portal.end().facing() == Direction.UP ? 60.0F : 0.0F));
		return ok(sender, "Teleported to '" + portal.name() + "'.");
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private static String summary(CustomPortal portal) {
		return portal.end().width() + "×" + portal.end().height() + " " + portal.end().fill().name().toLowerCase(Locale.ROOT)
				+ " in " + portal.world() + " → " + describe(portal.destination()) + (portal.isPublic() ? ", public" : "");
	}

	private static String describe(CustomPortal.Destination destination) {
		return switch (destination) {
			case CustomPortal.Destination.Portal(String name) -> "'" + name + "' (two-way)";
			case CustomPortal.Destination.Target(String world, double x, double y, double z, float yaw, float pitch) ->
					String.format(Locale.ROOT, "%s %.1f %.1f %.1f (one-way)", world, x, y, z);
			case CustomPortal.Destination.Random() -> "random spots in its world (one-way)";
			case CustomPortal.Destination.None() -> "nowhere yet";
		};
	}

	private static String nextSpot(CustomPortal portal) {
		if (!(portal.destination() instanceof CustomPortal.Destination.Random)) {
			return "";
		}
		RandomDestinations.Spot spot = RandomDestinations.peek(portal.name());
		return spot == null
				? "\n no spot ready yet"
				: String.format(Locale.ROOT, "\n next %.1f %.1f %.1f, %d queued", spot.x(), spot.y(), spot.z(), RandomDestinations.queued(portal.name()));
	}

	private static int extent(BlockPos min, BlockPos max, Direction.Axis axis) {
		return axis.choose(max.getX(), max.getY(), max.getZ()) - axis.choose(min.getX(), min.getY(), min.getZ()) + 1;
	}

	private static String worldId(World world) {
		return ((CraftWorld) world).getHandle().dimension().identifier().toString();
	}

	private static ServerLevel level(String worldId) {
		ServerLevel level = CustomPortals.level(MinecraftServer.getServer(), worldId);
		if (level == null) {
			throw new IllegalArgumentException("World " + worldId + " isn't loaded.");
		}
		return level;
	}

	private static World world(String name) {
		World world = Bukkit.getWorld(name);
		if (world == null) {
			throw new IllegalArgumentException("No world named '" + name + "'.");
		}
		return world;
	}

	private static Player player(CommandSender sender) {
		if (sender instanceof Player player) {
			return player;
		}
		throw new IllegalArgumentException("Only players can do that (from the console use createat / target <name> <world> <x y z>).");
	}

	private static String newName(String[] args, int index) {
		if (args.length <= index || !NAME.matcher(args[index]).matches()) {
			throw new IllegalArgumentException("Give the portal a name: letters, digits, _ or -, up to 32 characters.");
		}
		if (CustomPortals.get(args[index]) != null) {
			throw new IllegalArgumentException("There's already a portal named '" + args[index] + "'.");
		}
		return args[index];
	}

	private static CustomPortal existing(String[] args, int index) {
		if (args.length <= index) {
			throw new IllegalArgumentException("Which portal? (/nvportal list)");
		}
		CustomPortal portal = CustomPortals.get(args[index]);
		if (portal == null) {
			throw new IllegalArgumentException("No portal named '" + args[index] + "'.");
		}
		return portal;
	}

	private static PortalFill fill(String name) {
		try {
			PortalFill fill = PortalFill.valueOf(name.toUpperCase(Locale.ROOT));
			if (fill != PortalFill.NONE) {
				return fill;
			}
		} catch (IllegalArgumentException ignored) {
		}
		throw new IllegalArgumentException("Unknown fill '" + name + "'; use one of " + String.join(", ", FILLS) + ".");
	}

	private static Direction direction(String name) {
		Direction direction = Direction.byName(name.toLowerCase(Locale.ROOT));
		if (direction == null) {
			throw new IllegalArgumentException("Unknown direction '" + name + "'; use north, south, east, west, up or down.");
		}
		return direction;
	}

	private static int integer(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("'" + value + "' isn't a whole number.");
		}
	}

	private static double number(String value) {
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("'" + value + "' isn't a number.");
		}
	}

	private static boolean ok(CommandSender sender, String message) {
		sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
		return true;
	}

	private static boolean error(CommandSender sender, String message) {
		sender.sendMessage(Component.text(message, NamedTextColor.RED));
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!sender.hasPermission("netherview.admin")) {
			return List.of();
		}
		List<String> names = CustomPortals.all().stream().map(CustomPortal::name).toList();
		List<String> worlds = Bukkit.getWorlds().stream().map(World::getName).toList();
		List<String> directions = Arrays.stream(Direction.values()).map(Direction::getSerializedName).toList();
		List<String> options = switch (args.length) {
			case 1 -> SUBCOMMANDS;
			case 2 -> switch (args[0].toLowerCase(Locale.ROOT)) {
				case "link", "target", "unlink", "fill", "public", "delete", "info", "tp" -> names;
				default -> List.of();
			};
			case 3 -> switch (args[0].toLowerCase(Locale.ROOT)) {
				case "create" -> FILLS;
				case "createat" -> worlds;
				case "link" -> names;
				case "target" -> concat(List.of("here", "random"), worlds);
				case "fill" -> FILLS;
				case "public" -> List.of("true", "false");
				default -> List.of();
			};
			case 10 -> args[0].equalsIgnoreCase("createat") ? directions : List.of();
			case 11 -> args[0].equalsIgnoreCase("createat") ? concat(directions, FILLS) : List.of();
			case 12 -> args[0].equalsIgnoreCase("createat") ? FILLS : List.of();
			default -> List.of();
		};
		String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
		return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
	}

	private static List<String> concat(List<String> a, List<String> b) {
		List<String> out = new ArrayList<>(a);
		out.addAll(b);
		return out;
	}
}
