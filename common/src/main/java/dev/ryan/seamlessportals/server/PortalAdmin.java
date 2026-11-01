package dev.ryan.seamlessportals.server;

import dev.ryan.seamlessportals.PortalGeometry;
import dev.ryan.seamlessportals.net.PortalEnd;
import dev.ryan.seamlessportals.net.PortalFill;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What {@code /sportal} does, shared by the Paper plugin and the Fabric mod so both behave
 * the same: each operation checks its input, changes {@link CustomPortals}, and returns the
 * message to show. Bad input throws {@link IllegalArgumentException} with the message to show
 * instead. Parsing arguments is left to each platform's command.
 */
public final class PortalAdmin {
	public static final int MAX_SIZE = 21;
	public static final List<String> FILLS = Arrays.stream(PortalFill.values()).filter(f -> f != PortalFill.NONE).map(f -> f.name().toLowerCase(Locale.ROOT)).toList();
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
	private static PortalHooks hooks = PortalHooks.NONE;
	/** The player whose command is running (see {@link #setActor}), or null. */
	private static ServerPlayer actor;

	private PortalAdmin() {
	}

	/** The platform's say on what's created, changed, deleted and travelled through (see {@link PortalHooks}). */
	public static void setHooks(PortalHooks portalHooks) {
		hooks = portalHooks;
	}

	public static PortalHooks hooks() {
		return hooks;
	}

	/**
	 * Who the operations that follow are done for, as the hooks see it: the player running a
	 * command, or null (the console, another plugin). Commands set it and clear it afterwards.
	 */
	public static void setActor(ServerPlayer player) {
		actor = player;
	}

	public static String wandHelp() {
		return "Here's the portal wand. Left click one corner, right click the opposite one, then /sportal create <name>.";
	}

	/** From the player's wand selection: the flat axis is the normal, the side the player is on the front. */
	public static String create(ServerPlayer player, String name, String fillName) {
		checkNewName(name);
		PortalWands.Selection selection = PortalWands.selection(player);
		if (selection == null || !selection.complete()) {
			throw new IllegalArgumentException("Select two corners with the wand first (/sportal wand).");
		}
		String worldId = player.level().dimension().identifier().toString();
		if (!selection.world().equals(worldId)) {
			throw new IllegalArgumentException("Stand in the same world as your selection (it decides which side is the front).");
		}
		BlockPos a = selection.first();
		BlockPos b = selection.second();
		BlockPos min = BlockPos.min(a, b);
		BlockPos max = BlockPos.max(a, b);
		Vec3 eye = player.getEyePosition();
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
		Direction up = normal == Direction.Axis.Y ? Direction.fromYRot(player.getYRot()) : Direction.UP;
		PortalFill fill = fillName != null ? fill(fillName) : defaultFill(facing);
		return build(player.level(), name, min, max, facing, up, fill);
	}

	/** From coordinates; {@code up} and {@code fillName} may be null for the defaults. */
	public static String createAt(ServerLevel level, String name, BlockPos a, BlockPos b, Direction facing, Direction up, String fillName) {
		checkNewName(name);
		Direction realUp = up != null ? up : facing.getAxis() == Direction.Axis.Y ? Direction.SOUTH : Direction.UP;
		PortalFill fill = fillName != null ? fill(fillName) : defaultFill(facing);
		return build(level, name, BlockPos.min(a, b), BlockPos.max(a, b), facing, realUp, fill);
	}

	private static PortalFill defaultFill(Direction facing) {
		return facing.getAxis() == Direction.Axis.Y ? PortalFill.END_PORTAL : PortalFill.NETHER_PORTAL;
	}

	private static String build(ServerLevel level, String name, BlockPos min, BlockPos max, Direction facing, Direction up, PortalFill fill) {
		PortalEnd end = opening(level, min, max, facing, up, fill, null);
		CustomPortal portal = new CustomPortal(name, level.dimension().identifier().toString(), end, new CustomPortal.Destination.None(), CustomPortal.Access.PRIVATE);
		if (!hooks.creating(portal, actor)) {
			throw stopped();
		}
		CustomPortalBlocks.place(level, end);
		CustomPortals.put(portal);
		return "Created portal '" + name + "' (" + end.width() + "×" + end.height() + ", facing " + facing.getSerializedName() + ", "
				+ fill.name().toLowerCase(Locale.ROOT) + "). Link it with /sportal link " + name + " <other> or /sportal target " + name + " here.";
	}

	/**
	 * The opening from {@code min} to {@code max}, checked: one block thick, not too big, a fill
	 * that works facing that way, and nothing in the way (but {@code moving}'s own blocks, which
	 * it leaves).
	 */
	private static PortalEnd opening(ServerLevel level, BlockPos min, BlockPos max, Direction facing, Direction up, PortalFill fill, CustomPortal moving) {
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
		PortalEnd end = new PortalEnd(min, facing, up, width, height, surface(fill), fill);
		int blocked = CustomPortalBlocks.blockedCount(level, end,
				moving != null && moving.world().equals(level.dimension().identifier().toString()) ? moving.end() : null);
		if (blocked > 0) {
			throw new IllegalArgumentException(blocked + " block(s) in the opening aren't empty; clear them first.");
		}
		return end;
	}

	/**
	 * Moves a portal to a new opening (in any world), keeping its name, fill, links and who may
	 * use it: its blocks are cleared where it was and placed where it goes. {@code up} may be
	 * null for the default.
	 */
	public static String move(MinecraftServer server, String name, ServerLevel level, BlockPos a, BlockPos b, Direction facing, Direction up) {
		CustomPortal portal = existing(name);
		Direction realUp = up != null ? up : facing.getAxis() == Direction.Axis.Y ? Direction.SOUTH : Direction.UP;
		PortalEnd end = opening(level, BlockPos.min(a, b), BlockPos.max(a, b), facing, realUp, portal.end().fill(), portal);
		CustomPortal moved = new CustomPortal(portal.name(), level.dimension().identifier().toString(), end, portal.destination(), portal.access());
		change(portal, moved);
		ServerLevel from = CustomPortals.level(server, portal.world());
		if (from != null) {
			CustomPortalBlocks.clear(from, portal.end());
		}
		CustomPortalBlocks.place(level, end);
		CustomPortals.put(moved);
		return "Moved '" + portal.name() + "' to " + level.dimension().identifier() + " " + end.min().toShortString() + ".";
	}

	/** The hooks' say on {@code before} becoming {@code after}; throws if they stop it. */
	private static void change(CustomPortal before, CustomPortal after) {
		if (!hooks.changing(before, after, actor)) {
			throw stopped();
		}
	}

	private static IllegalArgumentException stopped() {
		return new IllegalArgumentException("Another plugin stopped that.");
	}

	public static String link(String nameA, String nameB) {
		CustomPortal a = existing(nameA);
		CustomPortal b = existing(nameB);
		if (a.name().equals(b.name())) {
			throw new IllegalArgumentException("A portal can't be linked to itself.");
		}
		change(a, a.withDestination(new CustomPortal.Destination.Portal(b.name())));
		change(b, b.withDestination(new CustomPortal.Destination.Portal(a.name())));
		CustomPortals.link(a.name(), b.name());
		return "Linked '" + a.name() + "' ⇄ '" + b.name() + "'.";
	}

	public static String targetHere(String name, ServerPlayer player) {
		return targetAt(name, player.level(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
	}

	public static String targetAt(String name, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
		CustomPortal portal = existing(name);
		CustomPortal.Destination.Target target = new CustomPortal.Destination.Target(level.dimension().identifier().toString(), x, y, z, yaw, pitch);
		change(portal, portal.withDestination(target));
		CustomPortals.setDestination(portal.name(), target);
		return "'" + portal.name() + "' now leads one-way to " + describe(target) + ".";
	}

	public static String targetRandom(String name) {
		CustomPortal portal = existing(name);
		change(portal, portal.withDestination(new CustomPortal.Destination.Random()));
		CustomPortals.setDestination(portal.name(), new CustomPortal.Destination.Random());
		return "'" + portal.name() + "' now leads to random spots in " + portal.world() + ". It shows the next one once it's found.";
	}

	public static String unlink(String name) {
		CustomPortal portal = existing(name);
		change(portal, portal.withDestination(new CustomPortal.Destination.None()));
		CustomPortals.setDestination(portal.name(), new CustomPortal.Destination.None());
		return "'" + portal.name() + "' no longer leads anywhere.";
	}

	public static String fill(MinecraftServer server, String name, String fillName) {
		CustomPortal portal = existing(name);
		PortalFill fill = fill(fillName);
		String problem = CustomPortalBlocks.checkFill(fill, portal.end().facing());
		if (problem != null) {
			throw new IllegalArgumentException(problem + ".");
		}
		PortalEnd old = portal.end();
		PortalEnd end = new PortalEnd(old.min(), old.facing(), old.up(), old.width(), old.height(), surface(fill), fill);
		ServerLevel level = level(server, portal.world());
		change(portal, portal.withEnd(end));
		CustomPortalBlocks.clear(level, old);
		CustomPortalBlocks.place(level, end);
		CustomPortals.put(portal.withEnd(end));
		return "'" + portal.name() + "' is now filled with " + fill.name().toLowerCase(Locale.ROOT) + ".";
	}

	/** {@code value}: true (anyone can use it), see (anyone can see through it) or false. */
	public static String setPublic(String name, String value) {
		CustomPortal portal = existing(name);
		CustomPortal.Access access = CustomPortal.Access.parse(value);
		change(portal, portal.withAccess(access));
		CustomPortals.put(portal.withAccess(access));
		return "'" + portal.name() + "' is now " + describe(access, portal.name()) + ".";
	}

	public static String delete(MinecraftServer server, String name) {
		CustomPortal portal = existing(name);
		if (!hooks.deleting(portal, actor)) {
			throw stopped();
		}
		ServerLevel level = CustomPortals.level(server, portal.world());
		if (level != null) {
			CustomPortalBlocks.clear(level, portal.end());
		}
		CustomPortals.remove(portal.name());
		return "Deleted '" + portal.name() + "'.";
	}

	/** The body of {@code info} (the platform shows the name above it). */
	public static String info(String name) {
		CustomPortal portal = existing(name);
		PortalEnd end = portal.end();
		return " world " + portal.world() + ", corner " + end.min().toShortString()
				+ "\n " + end.width() + "×" + end.height() + ", facing " + end.facing().getSerializedName() + ", up " + end.up().getSerializedName()
				+ "\n fill " + end.fill().name().toLowerCase(Locale.ROOT) + ", " + describe(portal.access(), portal.name())
				+ "\n leads to " + describe(portal.destination()) + nextSpot(portal);
	}

	/** Puts the player two blocks in front of the portal, looking at it. */
	public static String tp(MinecraftServer server, ServerPlayer player, String name) {
		CustomPortal portal = existing(name);
		ServerLevel level = level(server, portal.world());
		Vec3 center = PortalGeometry.center(portal.end());
		Vec3 front = Vec3.atLowerCornerOf(portal.end().facing().getUnitVec3i());
		Vec3 spot = center.add(front.scale(2.0));
		if (portal.end().facing().getAxis() != Direction.Axis.Y) {
			spot = new Vec3(spot.x, portal.end().min().getY(), spot.z);
		}
		Direction toward = portal.end().facing().getAxis() == Direction.Axis.Y ? portal.end().up().getOpposite() : portal.end().facing().getOpposite();
		player.teleport(new TeleportTransition(level, spot, Vec3.ZERO, toward.toYRot(), portal.end().facing() == Direction.UP ? 60.0F : 0.0F, TeleportTransition.DO_NOTHING));
		return "Teleported to '" + portal.name() + "'.";
	}

	public static String summary(CustomPortal portal) {
		return portal.end().width() + "×" + portal.end().height() + " " + portal.end().fill().name().toLowerCase(Locale.ROOT)
				+ " in " + portal.world() + " → " + describe(portal.destination()) + switch (portal.access()) {
					case USE -> ", public";
					case SEE -> ", public view";
					case PRIVATE -> "";
				};
	}

	private static String describe(CustomPortal.Access access, String name) {
		String node = name.toLowerCase(Locale.ROOT);
		return switch (access) {
			case USE -> "public: anyone can see through it and use it";
			case SEE -> "public to look through: using it needs seamlessportals.use." + node;
			case PRIVATE -> "private: seeing through it needs seamlessportals.see." + node + ", using it seamlessportals.use." + node;
		};
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

	// ── checks ──────────────────────────────────────────────────────────────

	private static void checkNewName(String name) {
		if (name == null || !NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("Give the portal a name: letters, digits, _ or -, up to 32 characters.");
		}
		if (CustomPortals.get(name) != null) {
			throw new IllegalArgumentException("There's already a portal named '" + name + "'.");
		}
	}

	public static CustomPortal existing(String name) {
		if (name == null) {
			throw new IllegalArgumentException("Which portal? (/sportal list)");
		}
		CustomPortal portal = CustomPortals.get(name);
		if (portal == null) {
			throw new IllegalArgumentException("No portal named '" + name + "'.");
		}
		return portal;
	}

	public static PortalFill fill(String name) {
		try {
			PortalFill fill = PortalFill.valueOf(name.toUpperCase(Locale.ROOT));
			if (fill != PortalFill.NONE) {
				return fill;
			}
		} catch (IllegalArgumentException ignored) {
		}
		throw new IllegalArgumentException("Unknown fill '" + name + "'; use one of " + String.join(", ", FILLS) + ".");
	}

	public static Direction direction(String name) {
		Direction direction = Direction.byName(name.toLowerCase(Locale.ROOT));
		if (direction == null) {
			throw new IllegalArgumentException("Unknown direction '" + name + "'; use north, south, east, west, up or down.");
		}
		return direction;
	}

	private static ServerLevel level(MinecraftServer server, String worldId) {
		ServerLevel level = CustomPortals.level(server, worldId);
		if (level == null) {
			throw new IllegalArgumentException("World " + worldId + " isn't loaded.");
		}
		return level;
	}

	private static int surface(PortalFill fill) {
		return fill == PortalFill.END_PORTAL ? 12 : 8;
	}

	private static int extent(BlockPos min, BlockPos max, Direction.Axis axis) {
		return axis.choose(max.getX(), max.getY(), max.getZ()) - axis.choose(min.getX(), min.getY(), min.getZ()) + 1;
	}
}
