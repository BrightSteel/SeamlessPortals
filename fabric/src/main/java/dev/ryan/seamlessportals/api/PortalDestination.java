package dev.ryan.seamlessportals.api;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Where a custom portal leads. */
public sealed interface PortalDestination {
	/** Nowhere yet: the portal shows its fill and nothing goes through. */
	record None() implements PortalDestination {
	}

	/** Two-way: the other portal of a linked pair. */
	record Linked(String portal) implements PortalDestination {
	}

	/** One-way to a fixed spot, arriving facing {@code yRot} and {@code xRot}. */
	record Target(ResourceKey<Level> world, Vec3 position, float yRot, float xRot) implements PortalDestination {
	}

	/** One-way to a new random safe spot in the portal's own world each trip. */
	record Random() implements PortalDestination {
	}
}
