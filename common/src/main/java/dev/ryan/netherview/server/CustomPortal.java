package dev.ryan.netherview.server;

import dev.ryan.netherview.net.PortalEnd;

/**
 * An admin-defined portal: a flat opening ({@link #end}, whose fill is what's physically in it)
 * in the world {@code world} (a dimension id such as {@code minecraft:overworld}), leading to
 * {@link #destination}. Private portals can only be used (and seen through) by players with
 * the {@code netherview.portal.<name>} permission; public ones by everyone.
 */
public record CustomPortal(String name, String world, PortalEnd end, Destination destination, boolean isPublic) {

	public CustomPortal withDestination(Destination destination) {
		return new CustomPortal(this.name, this.world, this.end, destination, this.isPublic);
	}

	public CustomPortal withEnd(PortalEnd end) {
		return new CustomPortal(this.name, this.world, end, this.destination, this.isPublic);
	}

	public CustomPortal withPublic(boolean isPublic) {
		return new CustomPortal(this.name, this.world, this.end, this.destination, isPublic);
	}

	/** Where a custom portal leads. */
	public sealed interface Destination {
		/** Not linked yet: just its fill, no view, no travel. */
		record None() implements Destination {
		}

		/** Two-way: the other portal of the pair, by name. */
		record Portal(String name) implements Destination {
		}

		/** One-way: a fixed spot, arriving facing {@code yaw}. */
		record Target(String world, double x, double y, double z, float yaw, float pitch) implements Destination {
		}
	}
}
