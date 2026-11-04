package dev.ryan.seamlessportals.api;

/** What fills a custom portal's opening (and how it looks when it isn't showing the far side). */
public enum PortalFill {
	/** The nether portal swirl; walls only. */
	NETHER_PORTAL,
	/** The end portal starfield; floors and ceilings only. */
	END_PORTAL,
	END_GATEWAY,
	WATER,
	LAVA,
	/**
	 * No blocks (it may lie over other blocks, or another portal's): things still go through it,
	 * but only players with the mod see it. Any way up.
	 */
	WINDOW,
	/**
	 * No blocks, like {@link #WINDOW}, and to players without the mod an end gateway's starfield
	 * exactly the size of the opening (insets included): a display entity, drawn by the
	 * SeamlessPortals resource pack.
	 */
	STARFIELD,
	/**
	 * An aether portal: no blocks, like {@link #STARFIELD}, and to players without the mod a blue
	 * portal swirl (the nether portal's, tinted) in place of the starfield. Any way up.
	 */
	AETHER
}
