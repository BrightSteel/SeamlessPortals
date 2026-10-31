package dev.ryan.netherview.client;

import dev.ryan.netherview.PortalGeometry;
import dev.ryan.netherview.client.mixin.ClientPacketListenerAccessor;
import dev.ryan.netherview.net.PortalLink;
import dev.ryan.netherview.net.PortalTravelPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Util;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Makes walking through a portal seamless. The dimension on the other side is already
 * loaded client-side (that is what the portal was showing), so when the server moves the
 * player there:
 * <ul>
 *   <li>its chunks are handed to the new level straight away and the loading screen is skipped;</li>
 *   <li>the remote renderer stays on briefly as a bridge while the main renderer meshes them;</li>
 *   <li>the portal just used is linked in reverse at once, its view seeded with the chunks left behind;</li>
 *   <li>the player keeps moving: they are placed where their own movement carried them through
 *       the portal, and vanilla's "wait for chunks" freeze is skipped.</li>
 * </ul>
 * The server's own chunks, links and placement still arrive and take over as usual.
 */
public final class DimensionHandoff {
	/**
	 * Vanilla fades new chunk sections in from the sky. Right after arriving that would show
	 * once the bridge ends, and the hand-off already filled the level, so it is skipped briefly.
	 */
	private static final long FADE_SUPPRESS_MS = 2000L;

	/** Set by the server's travel announcement, consumed by the respawn that follows it. */
	private static PortalLink travel;
	private static List<ClientboundLevelChunkWithLightPacket> pendingChunks = List.of();
	private static ChunkPos pendingCenter;

	/** Where the player really is (client-side) when the server moved them, carried through the portal. */
	private static Vec3 arrivalPosition;
	private static Vec3 arrivalVelocity;
	private static float arrivalYRot;
	private static float arrivalXRot;

	/** The portal just used, reversed, and the dimension being left with its chunks around that portal. */
	private static PortalLink wayBack;
	private static ResourceKey<Level> wayBackDimension;
	private static List<ClientboundLevelChunkWithLightPacket> wayBackChunks = List.of();

	/** Set between a hand-off and the server's placement packet that follows it. */
	private static boolean holdThroughPlacement;
	private static Vec3 heldPosition;
	private static Vec3 heldVelocity;
	private static float heldYRot;
	private static float heldXRot;

	private static long fadeSuppressedUntil;

	private DimensionHandoff() {
	}

	/** The server is about to move the player through this portal pair (see {@link PortalTravelPayload}). */
	public static void onTravel(PortalTravelPayload payload) {
		travel = payload.link();
	}

	/** Respawn, on the main thread, before the old level is replaced. */
	public static void beforeRespawn(ResourceKey<Level> newDimension) {
		PortalLink announced = travel;
		travel = null;
		Minecraft minecraft = Minecraft.getInstance();
		pendingChunks = List.of();
		pendingCenter = null;
		arrivalPosition = null;
		wayBack = null;
		wayBackChunks = List.of();
		RemoteDimension remote = minecraft.level == null || minecraft.level.dimension() == newDimension ? null : RemoteDimensions.get(newDimension);
		if (remote == null || remote.center() == null || minecraft.player == null) {
			return;
		}
		// Only a walk through a linked portal, announced by the server, is handed off: a /tp,
		// death or plugin teleport lands somewhere this view may have no terrain for, and its
		// placement must be honoured.
		if (announced == null || !announced.destDimension().equals(newDimension.identifier().toString())) {
			return;
		}
		RemoteDimensions.Link used = new RemoteDimensions.Link(announced, PortalGeometry.of(announced), newDimension);
		pendingChunks = remote.snapshotChunks();
		pendingCenter = remote.center();
		// The server placed the player from where it last saw them, and the client has kept
		// moving since; carry the client's own position through the portal instead.
		PortalGeometry geometry = used.geometry();
		arrivalPosition = geometry.toDest(minecraft.player.position());
		arrivalVelocity = geometry.rotate(minecraft.player.getDeltaMovement());
		arrivalYRot = geometry.toDestYaw(minecraft.player.getYRot());
		arrivalXRot = minecraft.player.getXRot();

		PortalLink link = used.link();
		wayBack = new PortalLink(link.destMin(), link.destAxis(), link.destWidth(), link.destHeight(),
				minecraft.level.dimension().identifier().toString(), link.min(), link.axis(), link.width(), link.height());
		wayBackDimension = minecraft.level.dimension();
		wayBackChunks = RemoteDimension.snapshotChunks(minecraft.level,
				ChunkPos.containing(BlockPos.containing(geometry.sourceCenter())), RemoteDimension.chunkRadius());
	}

	/** While a hand-off is pending, the new level already has terrain to show. */
	public static boolean skipLoadingScreen() {
		return !pendingChunks.isEmpty();
	}

	/** Respawn finished: the listener now holds the new level and player. */
	public static void afterRespawn(ClientPacketListener listener) {
		List<ClientboundLevelChunkWithLightPacket> chunks = pendingChunks;
		ChunkPos center = pendingCenter;
		pendingChunks = List.of();
		pendingCenter = null;
		holdThroughPlacement = false;
		ClientLevel level = Minecraft.getInstance().level;
		if (chunks.isEmpty() || level == null) {
			return;
		}
		RemoteDimensions.adoptAsBridge(level.dimension());
		level.getChunkSource().updateViewCenter(center.x(), center.z());
		for (ClientboundLevelChunkWithLightPacket chunk : chunks) {
			listener.handleLevelChunkWithLight(chunk);
		}
		if (wayBack != null) {
			RemoteDimensions.installArrival(wayBack, wayBackDimension, wayBackChunks);
		}
		wayBack = null;
		wayBackChunks = List.of();

		// The new player object starts at the origin and the server's placement may land a
		// frame later; with no loading screen to hide that, place it now.
		placeAtArrival();
		// The main camera's environment probe (fog, sky and light colours) still holds the old
		// dimension and blends from it over the next tick; start it fresh in the new one.
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			EnvironmentAttributeProbe probe = minecraft.gameRenderer.mainCamera().attributeProbe();
			probe.reset();
			probe.tick(level, minecraft.player.getEyePosition());
		}
		holdThroughPlacement = arrivalPosition != null;
		arrivalPosition = null;
		// Let the player keep moving right away: vanilla would hold them until the server's
		// chunks arrive. This sends the same "player loaded" packet vanilla sends after that wait.
		((ClientPacketListenerAccessor) listener).netherview$notifyPlayerLoaded();
		fadeSuppressedUntil = Util.getMillis() + FADE_SUPPRESS_MS;
		DevCapture.startBurst();
	}

	/**
	 * Just before vanilla applies the server's placement after a hand-off: remember where the
	 * player has actually got to, so {@link #afterServerPlacement} can put them back there.
	 */
	public static void beforeServerPlacement() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (holdThroughPlacement && player != null) {
			heldPosition = player.position();
			heldVelocity = player.getDeltaMovement();
			heldYRot = player.getYRot();
			heldXRot = player.getXRot();
		}
	}

	/**
	 * The server placed the player from where it last saw them; the client has been moving
	 * since. Keep the client's position (its next movement packet tells the server), so
	 * there is no pull-back.
	 */
	public static void afterServerPlacement() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (holdThroughPlacement && heldPosition != null && player != null) {
			player.snapTo(heldPosition.x, heldPosition.y, heldPosition.z, heldYRot, heldXRot);
			player.setDeltaMovement(heldVelocity);
		}
		holdThroughPlacement = false;
		heldPosition = null;
	}

	/** For a moment after arriving, new chunk sections appear at once instead of fading in. */
	public static boolean suppressChunkFade() {
		return Util.getMillis() < fadeSuppressedUntil;
	}

	private static void placeAtArrival() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (arrivalPosition != null && player != null) {
			player.snapTo(arrivalPosition.x, arrivalPosition.y, arrivalPosition.z, arrivalYRot, arrivalXRot);
			player.setDeltaMovement(arrivalVelocity);
		}
	}
}
