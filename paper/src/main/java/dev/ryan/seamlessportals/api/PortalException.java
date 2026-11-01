package dev.ryan.seamlessportals.api;

/**
 * An API call that couldn't be done: an unknown or taken name, an opening that's too big,
 * blocked or the wrong shape, a fill that doesn't work facing that way, or another plugin
 * cancelling the event. The message says which, in words fit to show a player.
 */
public class PortalException extends RuntimeException {
	public PortalException(String message) {
		super(message);
	}
}
