package dev.ryan.seamlessportals.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Hooks into SeamlessPortals's custom portals and trips, on the server. Create, change and delete
 * fire for {@code /sportal} commands and for {@link SeamlessPortalsApi} calls alike; the player
 * is the one running the command, or null (the console, or a mod through the API).
 */
public final class SeamlessPortalsEvents {
	/** A custom portal is about to be created (its blocks aren't placed yet). Return false to stop it. */
	public static final Event<Create> CREATE = EventFactory.createArrayBacked(Create.class, listeners -> (portal, player) -> {
		for (Create listener : listeners) {
			if (!listener.allowCreate(portal, player)) {
				return false;
			}
		}
		return true;
	});

	/**
	 * A custom portal is about to change: moved, refilled, linked, unlinked, retargeted, or made
	 * public or private (linking two fires once for each). Return false to stop it.
	 */
	public static final Event<Change> CHANGE = EventFactory.createArrayBacked(Change.class, listeners -> (previous, portal, player) -> {
		for (Change listener : listeners) {
			if (!listener.allowChange(previous, portal, player)) {
				return false;
			}
		}
		return true;
	});

	/** A custom portal is about to be deleted. Return false to stop it. */
	public static final Event<Delete> DELETE = EventFactory.createArrayBacked(Delete.class, listeners -> (portal, player) -> {
		for (Delete listener : listeners) {
			if (!listener.allowDelete(portal, player)) {
				return false;
			}
		}
		return true;
	});

	/**
	 * SeamlessPortals is about to move something through a portal: anything through a custom portal,
	 * or a player with the mod (and what they ride or lead) walking through a nether or end
	 * portal. Trips left to vanilla don't fire it. Each listener gets the arrival the one before
	 * returned: return it to go ahead, another to send the trip there instead (then it's an
	 * ordinary teleport, not a seamless crossing), or null to keep it on this side.
	 */
	public static final Event<Travel> TRAVEL = EventFactory.createArrayBacked(Travel.class, listeners -> (entity, portal, arrival) -> {
		PortalArrival current = arrival;
		for (Travel listener : listeners) {
			current = listener.onTravel(entity, portal, current);
			if (current == null) {
				return null;
			}
		}
		return current;
	});

	private SeamlessPortalsEvents() {
	}

	@FunctionalInterface
	public interface Create {
		boolean allowCreate(Portal portal, ServerPlayer player);
	}

	@FunctionalInterface
	public interface Change {
		boolean allowChange(Portal previous, Portal portal, ServerPlayer player);
	}

	@FunctionalInterface
	public interface Delete {
		boolean allowDelete(Portal portal, ServerPlayer player);
	}

	@FunctionalInterface
	public interface Travel {
		/**
		 * {@code entity} (a player, a mob or item, or a vehicle with its passengers) is going
		 * through {@code portal} (null: a nether or end portal) to {@code arrival}.
		 */
		PortalArrival onTravel(Entity entity, Portal portal, PortalArrival arrival);
	}
}
