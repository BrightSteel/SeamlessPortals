package dev.ryan.seamlessportals.api;

import dev.ryan.seamlessportals.net.PortalEnd;
import dev.ryan.seamlessportals.net.PortalInsets;
import dev.ryan.seamlessportals.server.CustomPortal;
import dev.ryan.seamlessportals.server.CustomPortals;
import dev.ryan.seamlessportals.server.PortalAdmin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * SeamlessPortals's custom portals, for other mods on the server (a dedicated server or
 * singleplayer's): create, move, link and delete them, and look them up.
 *
 * <p>Call it on the server thread. Every change works like the matching {@code /sportal}
 * command (without its permission check): it's saved, it fires {@link SeamlessPortalsEvents} with no
 * player, and it throws {@link PortalException} when it can't be done (or a listener stops
 * it). Portals you get are snapshots.
 */
public final class SeamlessPortalsApi {
	private SeamlessPortalsApi() {
	}

	public static Collection<Portal> all() {
		return CustomPortals.all().stream().map(SeamlessPortalsApi::toApi).toList();
	}

	public static Optional<Portal> get(String name) {
		return Optional.ofNullable(CustomPortals.get(name)).map(SeamlessPortalsApi::toApi);
	}

	/** The portal whose opening includes this block. */
	public static Optional<Portal> at(Level level, BlockPos pos) {
		return Optional.ofNullable(CustomPortals.at(level, pos)).map(SeamlessPortalsApi::toApi);
	}

	/**
	 * Fills the opening between two corner blocks (one block thick along {@code facing}, at most
	 * 21×21, empty) and makes it a portal leading nowhere yet, private. {@code up} is which way
	 * is up in the opening (null: up for a wall, south for a floor or ceiling) and {@code fill}
	 * may be null for the default: nether portal for a wall, end portal for a floor or ceiling.
	 */
	public static Portal create(ServerLevel level, String name, BlockPos corner1, BlockPos corner2, Direction facing, Direction up, PortalFill fill) {
		return change(name, () -> PortalAdmin.createAt(level, name, corner1, corner2, facing, up, fill == null ? null : fill.name().toLowerCase(Locale.ROOT)));
	}

	/**
	 * Moves a portal to another opening, in any level: its blocks are cleared where it was and
	 * placed where it goes, and it keeps its name, fill, links and access. {@code up} may be null.
	 */
	public static Portal move(String name, ServerLevel level, BlockPos corner1, BlockPos corner2, Direction facing, Direction up) {
		return change(name, () -> PortalAdmin.move(level.getServer(), name, level, corner1, corner2, facing, up));
	}

	/** Clears a portal's blocks and forgets it (its partner, if linked, then leads nowhere). */
	public static void delete(MinecraftServer server, String name) {
		run(() -> PortalAdmin.delete(server, name));
	}

	/** Links two portals both ways (unlinking whatever either was linked to). Returns {@code a}. */
	public static Portal link(String a, String b) {
		return change(a, () -> PortalAdmin.link(a, b));
	}

	/** Makes a portal lead one-way to {@code position} in {@code level}, arriving facing {@code yRot} and {@code xRot}. */
	public static Portal setTarget(String name, ServerLevel level, Vec3 position, float yRot, float xRot) {
		return change(name, () -> PortalAdmin.targetAt(name, level, position.x, position.y, position.z, yRot, xRot));
	}

	/** Makes a portal lead one-way to a new random safe spot in its own world each trip. */
	public static Portal setRandomTarget(String name) {
		return change(name, () -> PortalAdmin.targetRandom(name));
	}

	/** Makes a portal lead nowhere. */
	public static Portal unlink(String name) {
		return change(name, () -> PortalAdmin.unlink(name));
	}

	public static Portal setFill(MinecraftServer server, String name, PortalFill fill) {
		return change(name, () -> PortalAdmin.fill(server, name, fill.name().toLowerCase(Locale.ROOT)));
	}

	public static Portal setAccess(String name, PortalAccess access) {
		return change(name, () -> PortalAdmin.setPublic(name, CustomPortal.Access.valueOf(access.name()).setting()));
	}

	/**
	 * Where the opening's plane sits in its row of blocks, in sixteenths of a block along the
	 * facing axis (0 = the side toward lower coordinates, 8 = the middle, the default; 16 = the
	 * other side): set back behind something in the opening, or level with a surface.
	 */
	public static Portal setSurface(String name, int sixteenths) {
		return change(name, () -> PortalAdmin.setSurface(name, sixteenths));
	}

	/**
	 * Pulls the opening in from its blocks' edges, in sixteenths of a block on each side as seen
	 * from its front (left and right along its width, bottom and top along its height): for an
	 * opening narrower than whole blocks, such as a doorway in a model. Moving a portal resets them.
	 */
	public static Portal setInsets(String name, int left, int right, int bottom, int top) {
		return change(name, () -> PortalAdmin.setInsets(name, new PortalInsets(left, right, bottom, top)));
	}

	/**
	 * Makes the portal work only from its front: what goes in from behind just passes the
	 * opening by (say a doorway you may step out of backward, or land behind).
	 */
	public static Portal setFrontOnly(String name, boolean frontOnly) {
		return change(name, () -> PortalAdmin.setFrontOnly(name, frontOnly));
	}

	/**
	 * Makes the portal two-faced: gone into from behind it leads where it does from the front,
	 * out of the far side's front (a doorway standing free, with nothing behind it), and it's
	 * seen through the same way from both sides. Each face then works only from its own side.
	 */
	public static Portal setTwoFaced(String name, boolean twoFaced) {
		return change(name, () -> PortalAdmin.setTwoFaced(name, twoFaced));
	}

	/** The API's view of the server half's portal record. */
	public static Portal toApi(CustomPortal portal) {
		PortalEnd end = portal.end();
		BlockPos max = end.min();
		for (BlockPos pos : end.blocks()) {
			max = BlockPos.max(max, pos);
		}
		return new Portal(portal.name(), level(portal.world()), end.min(), max.immutable(), end.width(), end.height(), end.facing(), end.up(),
				PortalFill.valueOf(end.fill().name()), PortalAccess.valueOf(portal.access().name()), switch (portal.destination()) {
					case CustomPortal.Destination.None() -> new PortalDestination.None();
					case CustomPortal.Destination.Portal(String partner) -> new PortalDestination.Linked(partner);
					case CustomPortal.Destination.Target(String world, double x, double y, double z, float yaw, float pitch) ->
							new PortalDestination.Target(level(world), new Vec3(x, y, z), yaw, pitch);
					case CustomPortal.Destination.Random() -> new PortalDestination.Random();
				});
	}

	private static ResourceKey<Level> level(String dimension) {
		return ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
	}

	private static Portal change(String name, Supplier<String> operation) {
		run(operation);
		return toApi(CustomPortals.get(name));
	}

	private static void run(Supplier<String> operation) {
		try {
			operation.get();
		} catch (IllegalArgumentException e) {
			throw new PortalException(e.getMessage());
		}
	}
}
