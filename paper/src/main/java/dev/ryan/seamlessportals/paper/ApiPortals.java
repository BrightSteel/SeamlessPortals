package dev.ryan.seamlessportals.paper;

import dev.ryan.seamlessportals.api.Portal;
import dev.ryan.seamlessportals.api.PortalAccess;
import dev.ryan.seamlessportals.api.PortalDestination;
import dev.ryan.seamlessportals.api.PortalException;
import dev.ryan.seamlessportals.api.PortalFill;
import dev.ryan.seamlessportals.net.PortalEnd;
import dev.ryan.seamlessportals.server.CustomPortal;
import dev.ryan.seamlessportals.server.CustomPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.CraftWorld;

/** Between the API's Bukkit-typed portals and the server half's own. */
final class ApiPortals {
	private ApiPortals() {
	}

	static Portal toApi(CustomPortal portal) {
		World world = world(portal.world());
		PortalEnd end = portal.end();
		BlockPos max = end.min();
		for (BlockPos pos : end.blocks()) {
			max = BlockPos.max(max, pos);
		}
		return new Portal(portal.name(), world, location(world, end.min()), location(world, max), end.width(), end.height(),
				BlockFace.valueOf(end.facing().name()), BlockFace.valueOf(end.up().name()),
				PortalFill.valueOf(end.fill().name()), PortalAccess.valueOf(portal.access().name()), destination(portal.destination()));
	}

	private static PortalDestination destination(CustomPortal.Destination destination) {
		return switch (destination) {
			case CustomPortal.Destination.None() -> new PortalDestination.None();
			case CustomPortal.Destination.Portal(String name) -> new PortalDestination.Linked(name);
			case CustomPortal.Destination.Target(String world, double x, double y, double z, float yaw, float pitch) ->
					new PortalDestination.Target(new Location(world(world), x, y, z, yaw, pitch));
			case CustomPortal.Destination.Random() -> new PortalDestination.Random();
		};
	}

	/** The Bukkit world for a dimension id, or null if it isn't loaded. */
	static World world(String dimension) {
		ServerLevel level = CustomPortals.level(MinecraftServer.getServer(), dimension);
		return level == null ? null : level.getWorld();
	}

	static ServerLevel level(Location location) {
		if (location.getWorld() == null) {
			throw new PortalException("The location needs a world.");
		}
		return ((CraftWorld) location.getWorld()).getHandle();
	}

	static BlockPos block(Location location) {
		return new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
	}

	/** A block face that's one of the six directions, or null for null. */
	static Direction direction(BlockFace face) {
		if (face == null) {
			return null;
		}
		try {
			return Direction.valueOf(face.name());
		} catch (IllegalArgumentException e) {
			throw new PortalException(face + " isn't one of north, south, east, west, up or down.");
		}
	}

	private static Location location(World world, BlockPos pos) {
		return new Location(world, pos.getX(), pos.getY(), pos.getZ());
	}
}
