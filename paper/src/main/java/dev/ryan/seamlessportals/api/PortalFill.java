package dev.ryan.seamlessportals.api;

/** What fills a custom portal's opening (and how it looks when it isn't showing the far side). */
public enum PortalFill {
	/** The nether portal swirl; walls only. */
	NETHER_PORTAL,
	/** The end portal starfield; floors and ceilings only. */
	END_PORTAL,
	END_GATEWAY,
	WATER,
	LAVA
}
