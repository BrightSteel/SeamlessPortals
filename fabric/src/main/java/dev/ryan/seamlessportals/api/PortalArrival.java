package dev.ryan.seamlessportals.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Where a trip through a portal lands: the level, the spot and the way the traveller faces. */
public record PortalArrival(ServerLevel level, Vec3 position, float yRot, float xRot) {
}
