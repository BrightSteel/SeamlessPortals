package dev.ryan.netherview.server;

import dev.ryan.netherview.net.PortalEnd;

/**
 * An admin-defined portal: a flat opening ({@link #end}, whose fill is what's physically in it)
 * in the world {@code world} (a dimension id such as {@code minecraft:overworld}), leading to
 * {@link #destination}. Who may see through it and use it is {@link #access} plus the
 * {@code netherview.see.<name>} and {@code netherview.use.<name>} permissions (use grants see).
 */
public record CustomPortal(String name, String world, PortalEnd end, Destination destination, Access access) {
	/** What everyone may do with a portal, before permissions. */
	public enum Access {
		/** Only players with permission see through it or use it. */
		PRIVATE,
		/** Everyone sees through it; using it needs permission. */
		SEE,
		/** Everyone sees through it and uses it. */
		USE;

		/** The {@code public} setting: true, false, or see. */
		public static Access parse(String value) {
			return switch (value.toLowerCase(java.util.Locale.ROOT)) {
				case "true", "use" -> USE;
				case "see" -> SEE;
				case "false", "private" -> PRIVATE;
				default -> throw new IllegalArgumentException("Use true (anyone can use it), see (anyone can see through it) or false.");
			};
		}

		public String setting() {
			return switch (this) {
				case USE -> "true";
				case SEE -> "see";
				case PRIVATE -> "false";
			};
		}
	}

	public CustomPortal withDestination(Destination destination) {
		return new CustomPortal(this.name, this.world, this.end, destination, this.access);
	}

	public CustomPortal withEnd(PortalEnd end) {
		return new CustomPortal(this.name, this.world, end, this.destination, this.access);
	}

	public CustomPortal withAccess(Access access) {
		return new CustomPortal(this.name, this.world, this.end, this.destination, access);
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

		/**
		 * One-way to a random safe spot in the portal's own world. The portal shows the next
		 * spot of its own queue ({@link RandomDestinations}) and each trip uses it up.
		 */
		record Random() implements Destination {
		}
	}
}
