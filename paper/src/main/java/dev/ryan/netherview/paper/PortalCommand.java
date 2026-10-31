package dev.ryan.netherview.paper;

import dev.ryan.netherview.server.CustomPortal;
import dev.ryan.netherview.server.CustomPortals;
import dev.ryan.netherview.server.PortalAdmin;
import dev.ryan.netherview.server.PortalWands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /nvportal}: define and manage custom portals. The work is {@link PortalAdmin}'s
 * (shared with the Fabric mod); this parses Bukkit-style arguments, where worlds are named
 * the Bukkit way ({@code world}, {@code world_nether}).
 *
 * <pre>
 * wand                                         get the portal wand
 * create &lt;name&gt; [fill]                         from the wand selection (you stand on the front side)
 * createat &lt;name&gt; &lt;world&gt; &lt;x1 y1 z1&gt; &lt;x2 y2 z2&gt; &lt;facing&gt; [up] [fill]
 * link &lt;a&gt; &lt;b&gt;                                two-way pair
 * target &lt;name&gt; here | &lt;world&gt; &lt;x y z&gt; [yaw] [pitch]   one-way
 * target &lt;name&gt; random                       one-way to random spots in the portal's world
 * unlink &lt;name&gt; · fill &lt;name&gt; &lt;fill&gt; · public &lt;name&gt; &lt;true|see|false&gt;
 * delete &lt;name&gt; · list · info &lt;name&gt; · tp &lt;name&gt;
 * </pre>
 */
final class PortalCommand implements TabExecutor {
	private static final List<String> SUBCOMMANDS = List.of("wand", "create", "createat", "link", "target", "unlink", "fill", "public", "delete", "list", "info", "tp");

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission("netherview.admin")) {
			return error(sender, "You don't have permission to manage portals.");
		}
		if (args.length == 0) {
			return this.help(sender);
		}
		MinecraftServer server = MinecraftServer.getServer();
		try {
			String message = switch (args[0].toLowerCase(Locale.ROOT)) {
				case "wand" -> {
					player(sender).getInventory().addItem(CraftItemStack.asBukkitCopy(PortalWands.create()));
					yield PortalAdmin.wandHelp();
				}
				case "create" -> PortalAdmin.create(handle(player(sender)), arg(args, 1), args.length > 2 ? args[2] : null);
				case "createat" -> this.createAt(args);
				case "link" -> PortalAdmin.link(arg(args, 1), arg(args, 2));
				case "target" -> this.target(sender, args);
				case "unlink" -> PortalAdmin.unlink(arg(args, 1));
				case "fill" -> {
					if (args.length < 3) {
						throw new IllegalArgumentException("Usage: /nvportal fill <name> <" + String.join("|", PortalAdmin.FILLS) + ">");
					}
					yield PortalAdmin.fill(server, args[1], args[2]);
				}
				case "public" -> {
					if (args.length < 3) {
						throw new IllegalArgumentException("Usage: /nvportal public <name> <true|see|false>");
					}
					yield PortalAdmin.setPublic(args[1], args[2]);
				}
				case "delete" -> PortalAdmin.delete(server, arg(args, 1));
				case "list" -> this.list(sender);
				case "info" -> {
					String body = PortalAdmin.info(arg(args, 1));
					sender.sendMessage(Component.text(args[1], NamedTextColor.LIGHT_PURPLE).append(Component.text("\n" + body, NamedTextColor.GRAY)));
					yield null;
				}
				case "tp" -> PortalAdmin.tp(server, handle(player(sender)), arg(args, 1));
				default -> {
					this.help(sender);
					yield null;
				}
			};
			return message == null || ok(sender, message);
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
				/nvportal unlink|delete|info|tp <name>, fill <name> <fill>, public <name> <true|see|false>, list
				Fills: """ + String.join(", ", PortalAdmin.FILLS), NamedTextColor.GRAY));
		return true;
	}

	private String createAt(String[] args) {
		if (args.length < 10) {
			throw new IllegalArgumentException("Usage: /nvportal createat <name> <world> <x1 y1 z1> <x2 y2 z2> <facing> [up] [fill]");
		}
		ServerLevel level = level(args[2]);
		BlockPos a = new BlockPos(integer(args[3]), integer(args[4]), integer(args[5]));
		BlockPos b = new BlockPos(integer(args[6]), integer(args[7]), integer(args[8]));
		Direction facing = PortalAdmin.direction(args[9]);
		boolean hasUp = args.length > 10 && Direction.byName(args[10].toLowerCase(Locale.ROOT)) != null;
		Direction up = hasUp ? PortalAdmin.direction(args[10]) : null;
		int fillArg = hasUp ? 11 : 10;
		return PortalAdmin.createAt(level, args[1], a, b, facing, up, args.length > fillArg ? args[fillArg] : null);
	}

	private String target(CommandSender sender, String[] args) {
		String name = arg(args, 1);
		if (args.length == 3 && args[2].equalsIgnoreCase("random")) {
			return PortalAdmin.targetRandom(name);
		}
		if (args.length > 2 && args[2].equalsIgnoreCase("here")) {
			return PortalAdmin.targetHere(name, handle(player(sender)));
		}
		if (args.length >= 6) {
			return PortalAdmin.targetAt(name, level(args[2]), number(args[3]), number(args[4]), number(args[5]),
					args.length > 6 ? (float) number(args[6]) : 0.0F, args.length > 7 ? (float) number(args[7]) : 0.0F);
		}
		throw new IllegalArgumentException("Usage: /nvportal target <name> here | random | <world> <x> <y> <z> [yaw] [pitch]");
	}

	private String list(CommandSender sender) {
		if (CustomPortals.all().isEmpty()) {
			return "No custom portals yet. Start with /nvportal wand.";
		}
		for (CustomPortal portal : CustomPortals.all()) {
			sender.sendMessage(Component.text(portal.name(), NamedTextColor.LIGHT_PURPLE)
					.append(Component.text(" — " + PortalAdmin.summary(portal), NamedTextColor.GRAY)));
		}
		return null;
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private static String arg(String[] args, int index) {
		return args.length > index ? args[index] : null;
	}

	private static ServerLevel level(String worldName) {
		World world = Bukkit.getWorld(worldName);
		if (world == null) {
			throw new IllegalArgumentException("No world named '" + worldName + "'.");
		}
		return ((CraftWorld) world).getHandle();
	}

	private static Player player(CommandSender sender) {
		if (sender instanceof Player player) {
			return player;
		}
		throw new IllegalArgumentException("Only players can do that (from the console use createat / target <name> <world> <x y z>).");
	}

	private static ServerPlayer handle(Player player) {
		return ((CraftPlayer) player).getHandle();
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
				case "create" -> PortalAdmin.FILLS;
				case "createat" -> worlds;
				case "link" -> names;
				case "target" -> concat(List.of("here", "random"), worlds);
				case "fill" -> PortalAdmin.FILLS;
				case "public" -> List.of("true", "see", "false");
				default -> List.of();
			};
			case 10 -> args[0].equalsIgnoreCase("createat") ? directions : List.of();
			case 11 -> args[0].equalsIgnoreCase("createat") ? concat(directions, PortalAdmin.FILLS) : List.of();
			case 12 -> args[0].equalsIgnoreCase("createat") ? PortalAdmin.FILLS : List.of();
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
