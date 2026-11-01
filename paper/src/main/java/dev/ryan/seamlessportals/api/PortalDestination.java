package dev.ryan.seamlessportals.api;

import org.bukkit.Location;

/** Where a custom portal leads. */
public sealed interface PortalDestination {
	/** Nowhere yet: the portal shows its fill and nothing goes through. */
	record None() implements PortalDestination {
	}

	/** Two-way: the other portal of a linked pair. */
	record Linked(String portal) implements PortalDestination {
	}

	/** One-way to a fixed spot, arriving facing the location's yaw and pitch. */
	record Target(Location location) implements PortalDestination {
		public Target {
			location = location.clone();
		}

		@Override
		public Location location() {
			return this.location.clone();
		}
	}

	/** One-way to a new random safe spot in the portal's own world each trip. */
	record Random() implements PortalDestination {
	}
}
