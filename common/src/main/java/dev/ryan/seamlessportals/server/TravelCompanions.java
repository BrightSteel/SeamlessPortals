package dev.ryan.seamlessportals.server;

import dev.ryan.seamlessportals.PortalGeometry;
import dev.ryan.seamlessportals.net.PortalLink;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Whatever goes through a portal with a player: the vehicle they ride (with everyone on it,
 * moved together, as vanilla moves a vehicle and its passengers) and the animals on their
 * leads, which vanilla would leave behind on the old side.
 */
final class TravelCompanions {
	/** How far in front of the far opening a led animal is set down, if the transform would leave it behind it. */
	private static final double LEASHED_EXIT_CLEARANCE = 0.6;

	private TravelCompanions() {
	}

	/** The players riding on (or being) {@code root}. */
	static List<ServerPlayer> riders(Entity root) {
		List<ServerPlayer> riders = new ArrayList<>();
		if (root instanceof ServerPlayer player) {
			riders.add(player);
		}
		for (Entity passenger : root.getIndirectPassengers()) {
			if (passenger instanceof ServerPlayer player) {
				riders.add(player);
			}
		}
		return riders;
	}

	/** Animals (and boats) on these players' leads, not riding along already. */
	static List<Entity> leashedTo(List<ServerPlayer> players, Entity root) {
		List<Entity> leashed = new ArrayList<>();
		for (ServerPlayer player : players) {
			for (Leashable leashable : Leashable.leashableLeashedTo(player)) {
				if (leashable instanceof Entity entity && entity.getRootVehicle() != root && !leashed.contains(entity)) {
					leashed.add(entity);
				}
			}
		}
		return leashed;
	}

	/**
	 * Moves {@code root} (a player, or the vehicle a player rides, with all its passengers)
	 * through {@code link} to {@code destPos}, and the animals its riders lead after it: through
	 * the custom portal {@code portal}, or a nether or end portal if that's null. Every modded
	 * rider is told first, so their client hands its view over. The platform's hooks may stop
	 * the trip or send it elsewhere (then it's a plain teleport: the view showed the old
	 * destination). Returns the moved root, or null.
	 */
	/** Highest a traveller is lifted out of a floor it would arrive in (blocks): a step's height. */
	private static final double MAX_LIFT = 0.6;
	private static final double LIFT_STEP = 1.0 / 64.0;

	/**
	 * {@code pos}, or as little above it as clears the entity of blocks there, up to a step: the
	 * far side's floor needn't be level with the near side's ground (a portal whose bottom edge
	 * is a little above its ground, linked to one level with its floor), and arriving inside the
	 * floor would drop it through.
	 */
	private static Vec3 clear(ServerLevel dest, Entity entity, Vec3 pos) {
		for (double lift = 0.0; lift <= MAX_LIFT; lift += LIFT_STEP) {
			Vec3 at = pos.add(0.0, lift, 0.0);
			if (dest.noCollision(entity, entity.getDimensions(entity.getPose()).makeBoundingBox(at))) {
				return at;
			}
		}
		return pos;
	}

	static Entity travel(ServerLevel dest, Entity root, CustomPortal portal, PortalLink link, PortalGeometry geometry, Vec3 destPos,
			TeleportTransition.PostTeleportTransition after) {
		destPos = clear(dest, root, destPos);
		Vec3 velocity = geometry.rotate(root.getDeltaMovement());
		float[] rotation = geometry.toDestRotation(root.getYRot(), root.getXRot());
		PortalHooks.Arrival planned = new PortalHooks.Arrival(dest, destPos, rotation[0], rotation[1]);
		PortalHooks.Arrival arrival = PortalAdmin.hooks().travelling(root, portal, planned);
		if (arrival == null) {
			return null;
		}
		boolean redirected = !arrival.equals(planned);
		List<ServerPlayer> riders = riders(root);
		List<Entity> leashed = leashedTo(riders, root);
		if (!redirected) {
			for (ServerPlayer rider : riders) {
				PortalViewServer.announceTravel(rider, link);
			}
		}
		Entity moved = root.teleport(new TeleportTransition(arrival.level(), arrival.position(), redirected ? Vec3.ZERO : velocity,
				arrival.yRot(), arrival.xRot(), after));
		if (moved == null) {
			return null;
		}
		// Arriving just past the far plane may still overlap its portal blocks: the cooldown
		// keeps vanilla from sending anything straight back.
		moved.setPortalCooldown();
		for (Entity passenger : moved.getIndirectPassengers()) {
			passenger.setPortalCooldown();
		}
		for (Entity animal : leashed) {
			if (redirected) {
				followRedirect(arrival, animal);
			} else {
				bringLeashed(arrival.level(), animal, geometry);
			}
		}
		return moved;
	}

	/** A led animal after a redirected trip: set down where its holder went, on the lead again. */
	private static void followRedirect(PortalHooks.Arrival arrival, Entity animal) {
		Entity holder = ((Leashable) animal).getLeashHolder();
		Entity moved = animal.teleport(new TeleportTransition(arrival.level(), arrival.position(), Vec3.ZERO, animal.getYRot(), animal.getXRot(),
				TeleportTransition.PLACE_PORTAL_TICKET));
		if (moved instanceof Leashable leashable && holder != null && holder.isAlive() && holder.level() == moved.level()) {
			leashable.setLeashedTo(holder, true);
		}
	}

	/** A led animal follows its holder: to the same place relative to the far opening, but in front of it, and on the lead again. */
	private static void bringLeashed(ServerLevel dest, Entity animal, PortalGeometry geometry) {
		Entity holder = ((Leashable) animal).getLeashHolder();
		if (holder == null) {
			return;
		}
		Vec3 pos = geometry.toDest(animal.position());
		// Out of the far side the way its holder (moved already) came out.
		Vec3 normal = geometry.destNormal();
		if (holder.level() == dest && holder.position().subtract(geometry.destCenter()).dot(normal) < 0.0) {
			normal = normal.scale(-1.0);
		}
		double ahead = pos.subtract(geometry.destCenter()).dot(normal);
		if (ahead < LEASHED_EXIT_CLEARANCE) {
			pos = pos.add(normal.scale(LEASHED_EXIT_CLEARANCE - ahead));
		}
		// Walking up behind its holder, it may be beside the opening rather than in line with it:
		// across the opening, keep it inside, or it lands in the frame.
		AABB opening = PortalGeometry.box(geometry.destEnd());
		double halfWidth = animal.getBbWidth() / 2.0;
		pos = new Vec3(
				Math.abs(normal.x) > 0.5 ? pos.x : clampWithin(pos.x, opening.minX + halfWidth, opening.maxX - halfWidth),
				Math.abs(normal.y) > 0.5 ? pos.y : clampWithin(pos.y, opening.minY, opening.maxY - animal.getBbHeight()),
				Math.abs(normal.z) > 0.5 ? pos.z : clampWithin(pos.z, opening.minZ + halfWidth, opening.maxZ - halfWidth));
		Vec3 velocity = geometry.rotate(animal.getDeltaMovement());
		float[] rotation = geometry.toDestRotation(animal.getYRot(), animal.getXRot());
		Entity moved = animal.teleport(new TeleportTransition(dest, pos, velocity, rotation[0], rotation[1], TeleportTransition.PLACE_PORTAL_TICKET));
		if (moved == null) {
			return;
		}
		moved.setPortalCooldown();
		if (moved instanceof Leashable leashable && holder.isAlive() && holder.level() == moved.level()) {
			leashable.setLeashedTo(holder, true);
		}
	}

	/** {@code value} within [min, max], or the middle if that range is empty (the animal is wider than the opening). */
	private static double clampWithin(double value, double min, double max) {
		return min <= max ? Mth.clamp(value, min, max) : (min + max) / 2.0;
	}
}
