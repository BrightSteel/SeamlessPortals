package dev.ryan.netherview;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.ryan.netherview.server.CustomPortal;
import dev.ryan.netherview.server.CustomPortals;
import dev.ryan.netherview.server.PortalAdmin;
import dev.ryan.netherview.server.PortalWands;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code /nvportal} (alias {@code /nvp}) on Fabric, singleplayer or dedicated: the same
 * subcommands as the Paper plugin's, done by the shared {@link PortalAdmin}. Worlds are
 * dimension ids ({@code minecraft:the_nether}) instead of Bukkit world names.
 */
final class PortalCommand {
	private static final SuggestionProvider<CommandSourceStack> NAMES = (context, builder) ->
			SharedSuggestionProvider.suggest(CustomPortals.all().stream().map(CustomPortal::name), builder);
	private static final SuggestionProvider<CommandSourceStack> FILLS = (context, builder) ->
			SharedSuggestionProvider.suggest(PortalAdmin.FILLS, builder);
	private static final SuggestionProvider<CommandSourceStack> PUBLIC = (context, builder) ->
			SharedSuggestionProvider.suggest(List.of("true", "see", "false"), builder);
	private static final List<String> DIRECTIONS = Arrays.stream(Direction.values()).map(Direction::getSerializedName).toList();
	private static final SuggestionProvider<CommandSourceStack> DIRECTION_NAMES = (context, builder) ->
			SharedSuggestionProvider.suggest(DIRECTIONS, builder);
	private static final SuggestionProvider<CommandSourceStack> UP_OR_FILL = (context, builder) ->
			SharedSuggestionProvider.suggest(Stream.concat(DIRECTIONS.stream(), PortalAdmin.FILLS.stream()), builder);

	private PortalCommand() {
	}

	/** One action: returns the message to show, or null if it showed its own. */
	private interface Action {
		String run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException;
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("nvportal")
				.requires(PermissionPredicates.require(NetherView.id("admin"), PermissionLevel.GAMEMASTERS))
				.executes(context -> help(context.getSource()))
				.then(Commands.literal("help").executes(context -> help(context.getSource())))
				.then(Commands.literal("wand").executes(run(context -> {
					ServerPlayer player = context.getSource().getPlayerOrException();
					ItemStack wand = PortalWands.create();
					if (!player.getInventory().add(wand)) {
						// Full inventory: drop it at the player's feet, as /give does.
						ItemEntity drop = player.createItemStackToDrop(wand, false, false);
						if (drop != null) {
							drop.setNoPickUpDelay();
							drop.setTarget(player.getUUID());
							drop.level().addFreshEntity(drop);
						}
					}
					return PortalAdmin.wandHelp();
				})))
				.then(Commands.literal("create").then(name("name")
						.executes(run(context -> PortalAdmin.create(context.getSource().getPlayerOrException(), string(context, "name"), null)))
						.then(Commands.argument("fill", StringArgumentType.word()).suggests(FILLS)
								.executes(run(context -> PortalAdmin.create(context.getSource().getPlayerOrException(), string(context, "name"), string(context, "fill")))))))
				.then(Commands.literal("createat").then(name("name")
						.then(Commands.argument("world", DimensionArgument.dimension())
								.then(Commands.argument("from", BlockPosArgument.blockPos())
										.then(Commands.argument("to", BlockPosArgument.blockPos())
												.then(Commands.argument("facing", StringArgumentType.word()).suggests(DIRECTION_NAMES)
														.executes(run(context -> createAt(context, null, null)))
														.then(Commands.argument("upOrFill", StringArgumentType.word()).suggests(UP_OR_FILL)
																.executes(run(context -> {
																	String value = string(context, "upOrFill");
																	return Direction.byName(value.toLowerCase(Locale.ROOT)) != null
																			? createAt(context, value, null)
																			: createAt(context, null, value);
																}))
																.then(Commands.argument("fill", StringArgumentType.word()).suggests(FILLS)
																		.executes(run(context -> createAt(context, string(context, "upOrFill"), string(context, "fill"))))))))))))
				.then(Commands.literal("link").then(name("a").then(name("b")
						.executes(run(context -> PortalAdmin.link(string(context, "a"), string(context, "b")))))))
				.then(Commands.literal("target").then(name("name")
						.then(Commands.literal("here").executes(run(context -> PortalAdmin.targetHere(string(context, "name"), context.getSource().getPlayerOrException()))))
						.then(Commands.literal("random").executes(run(context -> PortalAdmin.targetRandom(string(context, "name")))))
						.then(Commands.argument("world", DimensionArgument.dimension())
								.then(Commands.argument("position", Vec3Argument.vec3())
										.executes(run(context -> targetAt(context, false)))
										.then(Commands.argument("rotation", RotationArgument.rotation())
												.executes(run(context -> targetAt(context, true))))))))
				.then(Commands.literal("unlink").then(name("name").executes(run(context -> PortalAdmin.unlink(string(context, "name"))))))
				.then(Commands.literal("fill").then(name("name").then(Commands.argument("fill", StringArgumentType.word()).suggests(FILLS)
						.executes(run(context -> PortalAdmin.fill(context.getSource().getServer(), string(context, "name"), string(context, "fill")))))))
				.then(Commands.literal("public").then(name("name").then(Commands.argument("public", StringArgumentType.word()).suggests(PUBLIC)
						.executes(run(context -> PortalAdmin.setPublic(string(context, "name"), string(context, "public")))))))
				.then(Commands.literal("delete").then(name("name").executes(run(context -> PortalAdmin.delete(context.getSource().getServer(), string(context, "name"))))))
				.then(Commands.literal("list").executes(run(PortalCommand::list)))
				.then(Commands.literal("info").then(name("name").executes(run(context -> {
					String body = PortalAdmin.info(string(context, "name"));
					context.getSource().sendSystemMessage(Component.literal(string(context, "name")).withStyle(ChatFormatting.LIGHT_PURPLE)
							.append(Component.literal("\n" + body).withStyle(ChatFormatting.GRAY)));
					return null;
				}))))
				.then(Commands.literal("tp").then(name("name").executes(run(context ->
						PortalAdmin.tp(context.getSource().getServer(), context.getSource().getPlayerOrException(), string(context, "name"))))));
		dispatcher.register(root);
		dispatcher.register(Commands.literal("nvp")
				.requires(PermissionPredicates.require(NetherView.id("admin"), PermissionLevel.GAMEMASTERS))
				.executes(context -> help(context.getSource()))
				.redirect(dispatcher.getRoot().getChild("nvportal")));
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> name(String argument) {
		return Commands.argument(argument, StringArgumentType.word()).suggests(NAMES);
	}

	private static String string(CommandContext<CommandSourceStack> context, String name) {
		return StringArgumentType.getString(context, name);
	}

	private static Command<CommandSourceStack> run(Action action) {
		return context -> {
			try {
				String message = action.run(context);
				if (message != null) {
					context.getSource().sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), false);
				}
				return Command.SINGLE_SUCCESS;
			} catch (IllegalArgumentException e) {
				context.getSource().sendFailure(Component.literal(e.getMessage()));
				return 0;
			}
		};
	}

	private static String createAt(CommandContext<CommandSourceStack> context, String up, String fill) throws CommandSyntaxException {
		ServerLevel level = DimensionArgument.getDimension(context, "world");
		return PortalAdmin.createAt(level, string(context, "name"),
				BlockPosArgument.getBlockPos(context, "from"), BlockPosArgument.getBlockPos(context, "to"),
				PortalAdmin.direction(string(context, "facing")), up == null ? null : PortalAdmin.direction(up), fill);
	}

	private static String targetAt(CommandContext<CommandSourceStack> context, boolean rotated) throws CommandSyntaxException {
		ServerLevel level = DimensionArgument.getDimension(context, "world");
		Vec3 position = Vec3Argument.getVec3(context, "position");
		Vec2 rotation = rotated ? RotationArgument.getRotation(context, "rotation").getRotation(context.getSource()) : Vec2.ZERO;
		return PortalAdmin.targetAt(string(context, "name"), level, position.x, position.y, position.z, rotation.y, rotation.x);
	}

	private static String list(CommandContext<CommandSourceStack> context) {
		if (CustomPortals.all().isEmpty()) {
			return "No custom portals yet. Start with /nvportal wand.";
		}
		for (CustomPortal portal : CustomPortals.all()) {
			context.getSource().sendSystemMessage(Component.literal(portal.name()).withStyle(ChatFormatting.LIGHT_PURPLE)
					.append(Component.literal(" — " + PortalAdmin.summary(portal)).withStyle(ChatFormatting.GRAY)));
		}
		return null;
	}

	private static int help(CommandSourceStack source) {
		source.sendSystemMessage(Component.literal("""
				/nvportal wand — get the portal wand (left/right click two corners)
				/nvportal create <name> [fill] — portal from the selection; you stand on its front
				/nvportal createat <name> <world> <x1 y1 z1> <x2 y2 z2> <facing> [up] [fill]
				/nvportal link <a> <b> — two-way pair
				/nvportal target <name> here | <world> <x y z> [yaw pitch] — one-way
				/nvportal target <name> random — one-way to a new random spot in its world each trip
				/nvportal unlink|delete|info|tp <name>, fill <name> <fill>, public <name> <true|see|false>, list
				Fills: """ + String.join(", ", PortalAdmin.FILLS)).withStyle(ChatFormatting.GRAY));
		return Command.SINGLE_SUCCESS;
	}
}
