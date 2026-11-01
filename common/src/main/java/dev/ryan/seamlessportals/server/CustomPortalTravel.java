package dev.ryan.seamlessportals.server;

import dev.ryan.seamlessportals.PortalGeometry;
import dev.ryan.seamlessportals.net.PortalFill;
import dev.ryan.seamlessportals.net.PortalLink;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Moves anything that goes through a custom portal: players with or without the client mod,
 * mobs, items, projectiles, vehicles with their riders. Vanilla's own handling of the fill
 * block (nether portal timer, end portal, gateway) is cancelled by the platform for custom
 * portals, so this is the only way through.
 */
final class CustomPortalTravel {
	/** How far around an opening entities are watched. */
	private static final double WATCH_MARGIN = 3.0;
	/** Largest movement in one tick still treated as moving rather than teleporting. */
	private static final double MAX_STEP = 4.0;
	/** Ticks after a trip during which an entity isn't sent again. */
	private static final int COOLDOWN_TICKS = 10;
	/** How far in front of the far opening something is set down after a contact trip (lava). */
	private static final double CONTACT_EXIT_CLEARANCE = 0.6;

	/** Per portal (portals close together watch the same entities), each entity's point last tick. */
	private static final Map<String, Map<UUID, Vec3>> LAST = new HashMap<>();
	private static final Map<UUID, Integer> COOLDOWN = new HashMap<>();

	private CustomPortalTravel() {
	}

	static void tick(MinecraftServer server) {
		COOLDOWN.replaceAll((id, ticks) -> ticks - 1);
		COOLDOWN.values().removeIf(ticks -> ticks <= 0);
		if (CustomPortals.all().isEmpty()) {
			LAST.clear();
			return;
		}
		Set<String> portals = new HashSet<>();
		for (CustomPortal portal : CustomPortals.all()) {
			portals.add(portal.name());
			ServerLevel level = CustomPortals.level(server, portal.world());
			PortalLink link = level == null ? null : CustomPortals.link(server, portal);
			if (link == null) {
				continue;
			}
			PortalGeometry geometry = PortalGeometry.of(link);
			AABB opening = PortalGeometry.box(portal.end());
			boolean onContact = portal.end().fill() == PortalFill.LAVA;
			// Lava hurts on touch, so it sends on touch: against the whole fill blocks, not the plane.
			AABB fillBlocks = onContact ? fillBox(portal) : opening;
			boolean playerSent = false;
			Map<UUID, Vec3> lastPoints = LAST.computeIfAbsent(portal.name(), n -> new HashMap<>());
			Set<UUID> seen = new HashSet<>();
			for (Entity entity : level.getEntities((Entity) null, opening.inflate(WATCH_MARGIN), e -> e.isAlive() && !e.isPassenger())) {
				UUID id = entity.getUUID();
				seen.add(id);
				Vec3 point = referencePoint(entity, geometry);
				Vec3 last = lastPoints.put(id, point);
				if (COOLDOWN.containsKey(id)) {
					continue;
				}
				boolean through = onContact
						? entity.getBoundingBox().intersects(fillBlocks)
						: last != null && last.distanceToSqr(point) <= MAX_STEP * MAX_STEP && geometry.crossesSource(last, point);
				if (!through || entity instanceof ServerPlayer player && !CustomPortals.canUse(player, portal)) {
					continue;
				}
				playerSent |= entity instanceof ServerPlayer || entity.hasPassenger(passenger -> passenger instanceof ServerPlayer);
				send(server, entity, portal, link, geometry, onContact);
			}
			lastPoints.keySet().retainAll(seen);
			if (playerSent && portal.destination() instanceof CustomPortal.Destination.Random) {
				// Only a player's trip uses up the spot (mobs and items just follow it there);
				// everything that went through this tick shares it.
				RandomDestinations.advance(portal.name());
			}
		}
		LAST.keySet().retainAll(portals);
	}

	private static AABB fillBox(CustomPortal portal) {
		AABB box = null;
		for (BlockPos pos : portal.end().blocks()) {
			box = box == null ? new AABB(pos) : box.minmax(new AABB(pos));
		}
		return box;
	}

	/** A player's eye at a wall portal (what they see through), feet at a floor portal (what falls in), else the middle of the entity. */
	private static Vec3 referencePoint(Entity entity, PortalGeometry geometry) {
		if (entity instanceof ServerPlayer player) {
			return geometry.horizontal() ? player.position() : player.getEyePosition();
		}
		return entity.getBoundingBox().getCenter();
	}

	private static void send(MinecraftServer server, Entity entity, CustomPortal portal, PortalLink link, PortalGeometry geometry, boolean onContact) {
		ServerLevel dest = CustomPortals.level(server, link.destDimension());
		if (dest == null) {
			return;
		}
		Vec3 destPos = geometry.toDest(entity.position());
		if (onContact) {
			// Touching the near side maps to just behind the far opening; set it down in front.
			double ahead = destPos.subtract(geometry.destCenter()).dot(geometry.destNormal());
			if (ahead < CONTACT_EXIT_CLEARANCE) {
				destPos = destPos.add(geometry.destNormal().scale(CONTACT_EXIT_CLEARANCE - ahead));
			}
		}
		COOLDOWN.put(entity.getUUID(), COOLDOWN_TICKS);
		LAST.values().forEach(points -> points.remove(entity.getUUID()));
		boolean withPlayer = !TravelCompanions.riders(entity).isEmpty();
		Entity moved = TravelCompanions.travel(dest, entity, portal, link, geometry, destPos,
				withPlayer ? TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET) : TeleportTransition.PLACE_PORTAL_TICKET);
		if (moved != null) {
			moved.clearFire();
			COOLDOWN.put(moved.getUUID(), COOLDOWN_TICKS);
		}
	}
}
