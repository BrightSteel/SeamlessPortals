package dev.ryan.seamlessportals.api.event;

import dev.ryan.seamlessportals.api.Portal;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/** A custom portal is about to be deleted (its blocks cleared, its partner left leading nowhere). */
public class PortalDeleteEvent extends PortalAdminEvent {
	private static final HandlerList HANDLERS = new HandlerList();
	private final Portal portal;

	public PortalDeleteEvent(Portal portal, Player player) {
		super(player);
		this.portal = portal;
	}

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
