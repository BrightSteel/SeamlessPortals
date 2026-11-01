package dev.ryan.seamlessportals.api;

/**
 * What everyone may do with a custom portal, before permissions: {@code seamlessportals.see.<name>}
 * lets a player see through it, {@code seamlessportals.use.<name>} use it (and see through it).
 */
public enum PortalAccess {
	/** Only players with permission see through it or use it. */
	PRIVATE,
	/** Everyone sees through it; using it needs permission. */
	SEE,
	/** Everyone sees through it and uses it. */
	USE
}
