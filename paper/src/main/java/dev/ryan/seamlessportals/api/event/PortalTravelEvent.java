package dev.ryan.seamlessportals.api.event;

import dev.ryan.seamlessportals.api.Portal;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * SeamlessPortals is about to move something through a portal: anything through a custom portal,
 * or a player with the mod (and what they ride or lead) walking through a nether or end
 * portal. Trips SeamlessPortals leaves to vanilla fire Bukkit's own {@code PlayerPortalEvent} and
 * {@code EntityPortalEvent} instead.
 *
 * <p>The entity is what's moved: a player, a mob or item, or a vehicle with its passengers
 * ({@link Entity#getPassengers()}). Cancel it to keep it on this side (it then just passes the
 * opening), or {@link #setTo} somewhere else: that trip is then an ordinary teleport, not a
 * seamless crossing, since the player saw the original destination through the portal.
 */
public class PortalTravelEvent extends Event implements Cancellable {
	private static final HandlerList HANDLERS = new HandlerList();
	private final Entity entity;
	private final Portal portal;
	private final Location from;
	private Location to;
	private boolean cancelled;

	public PortalTravelEvent(Entity entity, Portal portal, Location from, Location to) {
		this.entity = entity;
		this.portal = portal;
		this.from = from.clone();
		this.to = to.clone();
	}

	public Entity getEntity() {
		return this.entity;
	}

	/** The custom portal it's going through, or null for a nether or end portal. */
	public Portal getPortal() {
		return this.portal;
	}

	public Location getFrom() {
		return this.from.clone();
	}

	/** Where it arrives, facing the location's yaw and pitch. */
	public Location getTo() {
		return this.to.clone();
	}

	public void setTo(Location to) {
		if (to.getWorld() == null) {
			throw new IllegalArgumentException("The destination needs a world");
		}
		this.to = to.clone();
	}

	@Override
	public boolean isCancelled() {
		return this.cancelled;
	}

	@Override
	public void setCancelled(boolean cancel) {
		this.cancelled = cancel;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
