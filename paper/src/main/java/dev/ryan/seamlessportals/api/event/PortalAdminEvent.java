package dev.ryan.seamlessportals.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

/** A custom portal being created, changed or deleted: by a player's {@code /sportal} command, or the console or a plugin. Cancel it to stop it. */
public abstract class PortalAdminEvent extends Event implements Cancellable {
	private final Player player;
	private boolean cancelled;

	protected PortalAdminEvent(Player player) {
		this.player = player;
	}

	/** The player whose command it is, or null (the console or a plugin through the API). */
	public Player getPlayer() {
		return this.player;
	}

	@Override
	public boolean isCancelled() {
		return this.cancelled;
	}

	@Override
	public void setCancelled(boolean cancel) {
		this.cancelled = cancel;
	}
}
