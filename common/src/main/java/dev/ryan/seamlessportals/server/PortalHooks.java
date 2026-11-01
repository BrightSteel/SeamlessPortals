package dev.ryan.seamlessportals.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * What a platform may say about custom portals being created, changed and deleted, and about
 * trips through portals (the Paper plugin turns these into events for other plugins). By
 * default everything goes ahead.
 */
public interface PortalHooks {
	PortalHooks NONE = new PortalHooks() {
	};

	/** {@code portal} is about to be created by {@code actor} (null: the console or another plugin); false stops it. */
	default boolean creating(CustomPortal portal, ServerPlayer actor) {
		return true;
	}

	/** {@code portal} is about to be deleted; false stops it. */
	default boolean deleting(CustomPortal portal, ServerPlayer actor) {
		return true;
	}

	/** {@code before} is about to become {@code after}: moved, refilled, relinked, retargeted or made public or private; false stops it. */
	default boolean changing(CustomPortal before, CustomPortal after, ServerPlayer actor) {
		return true;
	}

	/**
	 * {@code entity} (with whatever rides it) is about to be moved through a portal to
	 * {@code arrival}: through the custom portal {@code portal}, or, if that's null, a nether or
	 * end portal. Returns where it goes instead (the same arrival to go ahead), or null to stop it.
	 */
	default Arrival travelling(Entity entity, CustomPortal portal, Arrival arrival) {
		return arrival;
	}

	/** Where a trip lands: the level, the spot and the way the traveller faces. */
	record Arrival(ServerLevel level, Vec3 position, float yRot, float xRot) {
	}
}
