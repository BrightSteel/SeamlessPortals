package dev.ryan.netherview.net;

/**
 * What physically fills a portal's opening: what players without the client mod see, and
 * what a modded client imitates when it doesn't render the portal live. {@link #NONE} marks a
 * virtual opening (a destination with no portal blocks, e.g. above the End platform).
 */
public enum PortalFill {
	NONE,
	NETHER_PORTAL,
	END_PORTAL,
	END_GATEWAY,
	WATER,
	LAVA;

	private static final PortalFill[] VALUES = values();

	public static PortalFill byId(int id) {
		return id >= 0 && id < VALUES.length ? VALUES[id] : NONE;
	}
}
