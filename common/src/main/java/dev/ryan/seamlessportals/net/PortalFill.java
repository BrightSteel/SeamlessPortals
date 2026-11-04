package dev.ryan.seamlessportals.net;

/**
 * What physically fills a portal's opening: what players without the client mod see, and
 * what a modded client imitates when it doesn't render the portal live. {@link #NONE} marks a
 * virtual opening (a destination with no portal blocks, e.g. above the End platform).
 * {@link #WINDOW} is a custom portal with no blocks of its own (it may lie over other blocks, or
 * another portal's): things go through it as through any portal, but only the mod shows it.
 * {@link #STARFIELD} is the same, with an end gateway's starfield exactly the size of its
 * opening, for players without the mod (a display entity, see StarfieldPanels). {@link #AETHER}
 * is the same, with a blue portal swirl (the nether portal's, tinted) in place of the starfield.
 */
public enum PortalFill {
	NONE,
	NETHER_PORTAL,
	END_PORTAL,
	END_GATEWAY,
	WATER,
	LAVA,
	WINDOW,
	STARFIELD,
	AETHER;

	private static final PortalFill[] VALUES = values();

	/** No blocks of its own: a window, with or without a panel (a starfield or the aether's swirl). */
	public boolean blockless() {
		return this == WINDOW || this == STARFIELD || this == AETHER;
	}

	/** Whether players without the mod see a panel in it (see StarfieldPanels). */
	public boolean panel() {
		return this == STARFIELD || this == AETHER;
	}

	public static PortalFill byId(int id) {
		return id >= 0 && id < VALUES.length ? VALUES[id] : NONE;
	}
}
