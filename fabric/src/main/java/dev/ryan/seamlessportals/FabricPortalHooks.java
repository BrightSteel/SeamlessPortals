package dev.ryan.seamlessportals;

import dev.ryan.seamlessportals.api.SeamlessPortalsEvents;
import dev.ryan.seamlessportals.api.SeamlessPortalsApi;
import dev.ryan.seamlessportals.api.PortalArrival;
import dev.ryan.seamlessportals.server.CustomPortal;
import dev.ryan.seamlessportals.server.PortalHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** The server half's say on portals, as the API's {@link SeamlessPortalsEvents}. */
final class FabricPortalHooks implements PortalHooks {
	@Override
	public boolean creating(CustomPortal portal, ServerPlayer actor) {
		return SeamlessPortalsEvents.CREATE.invoker().allowCreate(SeamlessPortalsApi.toApi(portal), actor);
	}

	@Override
	public boolean deleting(CustomPortal portal, ServerPlayer actor) {
		return SeamlessPortalsEvents.DELETE.invoker().allowDelete(SeamlessPortalsApi.toApi(portal), actor);
	}

	@Override
	public boolean changing(CustomPortal before, CustomPortal after, ServerPlayer actor) {
		return SeamlessPortalsEvents.CHANGE.invoker().allowChange(SeamlessPortalsApi.toApi(before), SeamlessPortalsApi.toApi(after), actor);
	}

	@Override
	public Arrival travelling(Entity entity, CustomPortal portal, Arrival arrival) {
		PortalArrival planned = new PortalArrival(arrival.level(), arrival.position(), arrival.yRot(), arrival.xRot());
		PortalArrival result = SeamlessPortalsEvents.TRAVEL.invoker().onTravel(entity, portal == null ? null : SeamlessPortalsApi.toApi(portal), planned);
		if (result == null) {
			return null;
		}
		return result.equals(planned) ? arrival : new Arrival(result.level(), result.position(), result.yRot(), result.xRot());
	}
}
