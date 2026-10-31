package dev.ryan.netherview.server;

import dev.ryan.netherview.PortalGeometry;
import dev.ryan.netherview.net.PortalEnd;
import dev.ryan.netherview.net.PortalFill;
import dev.ryan.netherview.net.PortalLink;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * The registry of admin-defined portals. Platforms load it from their storage and save it
 * whenever {@link #onChange} fires; everything else (views, travel, protecting the fill)
 * reads it from here.
 */
public final class CustomPortals {
	private static final Map<String, CustomPortal> PORTALS = new LinkedHashMap<>();
	/** world → block → portal name, for "is this block part of a custom portal" checks. */
	private static Map<String, Map<BlockPos, String>> blocks = Map.of();
	private static Runnable onChange = () -> {
	};
	/** Whether a player has a permission node, given without its {@code netherview.} prefix; the platform decides. */
	private static BiPredicate<ServerPlayer, String> permission = (player, node) -> true;

	private CustomPortals() {
	}

	public static void init(BiPredicate<ServerPlayer, String> permissionCheck, Runnable changed) {
		permission = permissionCheck;
		onChange = changed;
	}

	/** Replaces everything (loading from storage); doesn't fire {@link #onChange}. */
	public static void load(Collection<CustomPortal> portals) {
		PORTALS.clear();
		RandomDestinations.clear();
		portals.forEach(portal -> PORTALS.put(portal.name(), portal));
		reindex();
	}

	public static Collection<CustomPortal> all() {
		return List.copyOf(PORTALS.values());
	}

	public static CustomPortal get(String name) {
		return PORTALS.get(name);
	}

	public static void put(CustomPortal portal) {
		PORTALS.put(portal.name(), portal);
		reindex();
		onChange.run();
	}

	public static void remove(String name) {
		CustomPortal removed = PORTALS.remove(name);
		if (removed != null && removed.destination() instanceof CustomPortal.Destination.Portal(String partner)) {
			CustomPortal other = PORTALS.get(partner);
			if (other != null && other.destination() instanceof CustomPortal.Destination.Portal(String back) && back.equals(name)) {
				PORTALS.put(partner, other.withDestination(new CustomPortal.Destination.None()));
			}
		}
		reindex();
		onChange.run();
	}

	/** Pairs two portals both ways, unpairing whatever either was paired with before. */
	public static void link(String a, String b) {
		unpairPartner(a);
		unpairPartner(b);
		PORTALS.computeIfPresent(a, (n, portal) -> portal.withDestination(new CustomPortal.Destination.Portal(b)));
		PORTALS.computeIfPresent(b, (n, portal) -> portal.withDestination(new CustomPortal.Destination.Portal(a)));
		onChange.run();
	}

	/** Sets a portal's destination (one-way target or none), unpairing its old partner. */
	public static void setDestination(String name, CustomPortal.Destination destination) {
		unpairPartner(name);
		PORTALS.computeIfPresent(name, (n, portal) -> portal.withDestination(destination));
		onChange.run();
	}

	private static void unpairPartner(String name) {
		CustomPortal portal = PORTALS.get(name);
		if (portal != null && portal.destination() instanceof CustomPortal.Destination.Portal(String partner)) {
			CustomPortal other = PORTALS.get(partner);
			if (other != null && other.destination() instanceof CustomPortal.Destination.Portal(String back) && back.equals(name)) {
				PORTALS.put(partner, other.withDestination(new CustomPortal.Destination.None()));
			}
			PORTALS.put(name, portal.withDestination(new CustomPortal.Destination.None()));
		}
	}

	private static void reindex() {
		Map<String, Map<BlockPos, String>> index = new HashMap<>();
		for (CustomPortal portal : PORTALS.values()) {
			Map<BlockPos, String> world = index.computeIfAbsent(portal.world(), w -> new HashMap<>());
			for (BlockPos pos : portal.end().blocks()) {
				world.put(pos.immutable(), portal.name());
			}
		}
		blocks = index;
	}

	/** The custom portal occupying this block, if any. */
	public static CustomPortal at(Level level, BlockPos pos) {
		Map<BlockPos, String> world = blocks.get(level.dimension().identifier().toString());
		String name = world == null ? null : world.get(pos);
		return name == null ? null : PORTALS.get(name);
	}

	/** Whether the entity's box overlaps a custom portal block. */
	public static boolean touches(Entity entity) {
		Map<BlockPos, String> world = blocks.get(entity.level().dimension().identifier().toString());
		if (world == null) {
			return false;
		}
		AABB box = entity.getBoundingBox();
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			if (world.containsKey(pos)) {
				return true;
			}
		}
		return false;
	}

	/** May go through it: {@code netherview.use.<name>} or {@code netherview.use.*}, unless everyone may. */
	public static boolean canUse(ServerPlayer player, CustomPortal portal) {
		return portal.access() == CustomPortal.Access.USE || has(player, "use", portal);
	}

	/** May see through it (otherwise it's just its fill): using it, or {@code netherview.see.<name>} or {@code netherview.see.*}. */
	public static boolean canSee(ServerPlayer player, CustomPortal portal) {
		return portal.access() != CustomPortal.Access.PRIVATE || canUse(player, portal) || has(player, "see", portal);
	}

	private static boolean has(ServerPlayer player, String kind, CustomPortal portal) {
		return permission.test(player, kind + "." + portal.name().toLowerCase(Locale.ROOT)) || permission.test(player, kind + ".*");
	}

	/** Portals in {@code level} whose opening is within {@code radius} of {@code center}. */
	public static List<CustomPortal> near(ServerLevel level, Vec3 center, double radius) {
		String world = level.dimension().identifier().toString();
		List<CustomPortal> out = new ArrayList<>();
		for (CustomPortal portal : PORTALS.values()) {
			if (portal.world().equals(world) && PortalGeometry.box(portal.end()).inflate(radius).contains(center)) {
				out.add(portal);
			}
		}
		return out;
	}

	public static ServerLevel level(MinecraftServer server, String world) {
		return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(world)));
	}

	/**
	 * The link a portal stands for, or null if it leads nowhere usable (not linked, or its
	 * destination's world isn't loaded). A pair links to the other portal's opening; a target
	 * to a virtual upright opening of the same size standing at the target, facing its yaw
	 * (to the nearest side: portal frames are block-aligned). A random portal is a target at
	 * the head of its queue, or null until one is ready.
	 */
	public static PortalLink link(MinecraftServer server, CustomPortal portal) {
		switch (portal.destination()) {
			case CustomPortal.Destination.Portal(String partnerName) -> {
				CustomPortal partner = PORTALS.get(partnerName);
				ServerLevel dest = partner == null ? null : level(server, partner.world());
				return dest == null ? null : new PortalLink(portal.end(), partner.world(), PortalViewServer.dimensionTypeId(dest), partner.end());
			}
			case CustomPortal.Destination.Target(String world, double x, double y, double z, float yaw, float pitch) -> {
				return targetLink(server, portal, world, x, y, z, yaw);
			}
			case CustomPortal.Destination.Random() -> {
				RandomDestinations.Spot spot = RandomDestinations.peek(portal.name());
				return spot == null ? null : targetLink(server, portal, portal.world(), spot.x(), spot.y(), spot.z(), spot.yaw());
			}
			case CustomPortal.Destination.None() -> {
				return null;
			}
		}
	}

	private static PortalLink targetLink(MinecraftServer server, CustomPortal portal, String world, double x, double y, double z, float yaw) {
		ServerLevel dest = level(server, world);
		if (dest == null) {
			return null;
		}
		Direction facing = Direction.fromYRot(yaw);
		int width = portal.end().width();
		int height = portal.end().height();
		Direction right = new PortalEnd(BlockPos.ZERO, facing, Direction.UP, width, height, 8, PortalFill.NONE).right();
		// Centred on the target along its width, standing on the target's block.
		BlockPos targetBlock = BlockPos.containing(x, y, z);
		int start = (int) Math.floor((right.getAxis() == Direction.Axis.X ? x : z) - width / 2.0 + 0.5);
		BlockPos min = right.getAxis() == Direction.Axis.X
				? new BlockPos(start, targetBlock.getY(), targetBlock.getZ())
				: new BlockPos(targetBlock.getX(), targetBlock.getY(), start);
		return new PortalLink(portal.end(), world, PortalViewServer.dimensionTypeId(dest),
				new PortalEnd(min, facing, Direction.UP, width, height, 8, PortalFill.NONE));
	}
}
