package dev.ryan.seamlessportals.api.event;

import dev.ryan.seamlessportals.api.Portal;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * A custom portal is about to change: moved, refilled, linked, unlinked, retargeted, or made
 * public or private. Linking two portals fires one for each.
 */
public class PortalChangeEvent extends PortalAdminEvent {
	private static final HandlerList HANDLERS = new HandlerList();
	private final Portal previous;
	private final Portal portal;

	public PortalChangeEvent(Portal previous, Portal portal, Player player) {
		super(player);
		this.previous = previous;
		this.portal = portal;
	}

	/** The portal as it is now. */
	public Portal getPrevious() {
		return this.previous;
	}

	/** The portal as it will be. */
	public Portal getPortal() {
		return this.portal;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
