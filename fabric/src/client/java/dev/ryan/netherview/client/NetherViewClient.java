package dev.ryan.netherview.client;

import dev.ryan.netherview.net.PortalLinksPayload;
import dev.ryan.netherview.net.PortalTravelPayload;
import dev.ryan.netherview.net.RemoteChunkPayload;
import dev.ryan.netherview.net.RemoteEntitiesPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class NetherViewClient implements ClientModInitializer {
	/** Development aid: register no channels, so servers treat this client as one without the mod. */
	private static final boolean ACT_VANILLA = Boolean.getBoolean("netherview.actVanilla");

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(DevCapture::tick);
		if (ACT_VANILLA) {
			return;
		}
		ClientPlayNetworking.registerGlobalReceiver(PortalLinksPayload.TYPE, (payload, context) -> RemoteDimensions.onLinks(payload));
		ClientPlayNetworking.registerGlobalReceiver(PortalTravelPayload.TYPE, (payload, context) -> DimensionHandoff.onTravel(payload));
		ClientPlayNetworking.registerGlobalReceiver(RemoteChunkPayload.TYPE, (payload, context) -> RemoteDimensions.onChunk(payload));
		ClientPlayNetworking.registerGlobalReceiver(RemoteEntitiesPayload.TYPE, (payload, context) -> RemoteDimensions.onEntities(payload));
		ClientTickEvents.END_CLIENT_TICK.register(RemoteDimensions::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RemoteDimensions.clear());
	}
}
