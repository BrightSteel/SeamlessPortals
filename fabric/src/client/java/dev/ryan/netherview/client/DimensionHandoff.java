package dev.ryan.netherview.client;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.Entity;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import dev.ryan.netherview.compat.ClientVersioned;
import dev.ryan.netherview.PortalGeometry;
import dev.ryan.netherview.client.mixin.ClientPacketListenerAccessor;
import dev.ryan.netherview.client.mixin.LivingEntityAccessor;
import dev.ryan.netherview.client.mixin.PlayerAccessor;
import dev.ryan.netherview.net.PortalFill;
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
	private static RemoteDimension pendingView;
	/** The bridge view's entities, as spawn packets, to add to the new level with its terrain. */
	private static List<Packet<ClientGamePacketListener>> pendingEntities = List.of();
	/** Entities a hand-off added that the server hasn't sent itself yet, and when to drop them. */
	private static final IntSet seededEntities = new IntOpenHashSet();
	private static long seededEntitiesUntil;
	/** How long seeded entities wait for the server's own copy before they're taken away (out of its range). */
	private static final long SEEDED_ENTITY_MS = 5000L;
	/** The player object a hand-off respawn is replacing, to carry its hand and inventory over. */
	private static LocalPlayer previousPlayer;
	/** After a hand-off: the new level was filled from a remote view, see {@link #isStaleUnload}. */
	private static boolean filledLevel;
	/** Chunks the server itself has sent in the current level (not the ones a hand-off filled in). */
	private static final LongSet serverChunks = new LongOpenHashSet();
	private static boolean filling;

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
		filledLevel = false;
		serverChunks.clear();
		PortalLink announced = travel;
		travel = null;
		Minecraft minecraft = Minecraft.getInstance();
		pendingChunks = List.of();
		pendingCenter = null;
		pendingView = null;
		pendingEntities = List.of();
		seededEntities.clear();
		previousPlayer = null;
		arrivalPosition = null;
		wayBack = null;
		wayBackChunks = List.of();
		// Only a walk through a linked portal, announced by the server, is handed off: a /tp,
		// death or plugin teleport lands somewhere this view may have no terrain for, and its
		// placement must be honoured.
		if (announced == null || !announced.destDimension().equals(newDimension.identifier().toString())) {
			return;
		}
		// The view of the portal walked through: with several portals into this dimension,
		// another view may be centred somewhere else and lack the chunks you land in.
		RemoteDimension remote = minecraft.level == null || minecraft.level.dimension() == newDimension ? null : RemoteDimensions.viewFor(announced, newDimension);
		if (remote == null || remote.center() == null || minecraft.player == null) {
			return;
		}
		RemoteDimensions.Link used = new RemoteDimensions.Link(announced, PortalGeometry.of(announced), newDimension);
		pendingChunks = remote.snapshotChunks();
		pendingCenter = remote.center();
		pendingView = remote;
		pendingEntities = minecraft.getConnection() == null ? List.of() : remote.snapshotEntities(minecraft.getConnection());
		previousPlayer = minecraft.player;
		// The server placed the player from where it last saw them, and the client has kept
		// moving since; carry the client's own position through the portal instead.
		PortalGeometry geometry = used.geometry();
		arrivalPosition = geometry.toDest(minecraft.player.position());
		arrivalVelocity = geometry.rotate(minecraft.player.getDeltaMovement());
		float[] rotation = geometry.toDestRotation(minecraft.player.getYRot(), minecraft.player.getXRot());
		arrivalYRot = rotation[0];
		arrivalXRot = rotation[1];

		PortalLink link = used.link();
		if (link.dest().fill() == PortalFill.NONE) {
			return; // nothing where you land (the End platform, your spawn, a target): no way back to show
		}
		wayBack = link.reversed(minecraft.level.dimension().identifier().toString(),
				minecraft.level.dimensionTypeRegistration().unwrapKey().map(key -> key.identifier().toString()).orElse("minecraft:overworld"));
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
		RemoteDimensions.adoptAsBridge(level.dimension(), pendingView);
		pendingView = null;
		level.getChunkSource().updateViewCenter(center.x(), center.z());
		filling = true;
		try {
			for (ClientboundLevelChunkWithLightPacket chunk : chunks) {
				listener.handleLevelChunkWithLight(chunk);
			}
			// Its entities too, so mobs seen through the portal don't vanish until the server
			// sends them; the server's copies replace these (same ids) when they arrive.
			for (Packet<ClientGamePacketListener> packet : pendingEntities) {
				try {
					packet.handle(listener);
					if (packet instanceof ClientboundAddEntityPacket add) {
						seededEntities.add(add.getId());
					}
				} catch (RuntimeException ignored) {
					// an entity that can't be added here simply appears when the server sends it
				}
			}
		} finally {
			filling = false;
		}
		pendingEntities = List.of();
		seededEntitiesUntil = Util.getMillis() + SEEDED_ENTITY_MS;
		filledLevel = true;
		if (wayBack != null) {
			RemoteDimensions.installArrival(wayBack, wayBackDimension, wayBackChunks);
		}
		wayBack = null;
		wayBackChunks = List.of();

		// The new player object starts at the origin and the server's placement may land a
		// frame later; with no loading screen to hide that, place it now.
		placeAtArrival();
		carryOver(previousPlayer, Minecraft.getInstance().player);
		previousPlayer = null;
		// The main camera's environment probe (fog, sky and light colours) still holds the old
		// dimension and blends from it over the next tick; start it fresh in the new one.
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			EnvironmentAttributeProbe probe = ClientVersioned.mainCamera().attributeProbe();
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
	public static void beforeServerPlacement(ClientPacketListener listener) {
		if (sameWorldArrival(listener)) {
			return;
		}
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

	/**
	 * A portal within the same world: no respawn, just this position packet. The view of the
	 * destination is a separate copy of this world; its chunks fill whatever the player's level
	 * lacks there, it bridges while those are meshed, and the player keeps their own position
	 * carried through the portal.
	 */
	private static boolean sameWorldArrival(ClientPacketListener listener) {
		PortalLink announced = travel;
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (announced == null || level == null || minecraft.player == null
				|| !announced.destDimension().equals(level.dimension().identifier().toString())) {
			return false;
		}
		travel = null;
		PortalGeometry geometry = PortalGeometry.of(announced);
		LocalPlayer player = minecraft.player;
		float[] rotation = geometry.toDestRotation(player.getYRot(), player.getXRot());
		heldPosition = geometry.toDest(player.position());
		heldVelocity = geometry.rotate(player.getDeltaMovement());
		heldYRot = rotation[0];
		heldXRot = rotation[1];
		holdThroughPlacement = true;

		RemoteDimension remote = RemoteDimensions.viewFor(announced, level.dimension());
		if (remote != null && remote.center() != null) {
			level.getChunkSource().updateViewCenter(remote.center().x(), remote.center().z());
			for (ClientboundLevelChunkWithLightPacket chunk : remote.snapshotChunks()) {
				if (level.getChunkSource().getChunk(ClientVersioned.chunkX(chunk), ClientVersioned.chunkZ(chunk), false) == null) {
					listener.handleLevelChunkWithLight(chunk);
				}
			}
			RemoteDimensions.adoptAsBridge(level.dimension(), remote);
			fadeSuppressedUntil = Util.getMillis() + FADE_SUPPRESS_MS;
		}
		return true;
	}

	/**
	 * The server added an entity. If a hand-off seeded that very entity, it's kept and moved to
	 * the server's position instead: replacing it would restart its animation and interpolation,
	 * a visible flicker. Returns whether the packet is handled.
	 */
	public static boolean onServerEntity(ClientboundAddEntityPacket packet) {
		if (filling || !seededEntities.remove(packet.getId())) {
			return false;
		}
		ClientLevel level = Minecraft.getInstance().level;
		Entity entity = level == null ? null : level.getEntity(packet.getId());
		if (entity == null || entity.getType() != packet.getType() || !entity.getUUID().equals(packet.getUUID())) {
			return false;
		}
		entity.moveOrInterpolateTo(new Vec3(packet.getX(), packet.getY(), packet.getZ()), packet.getYRot(), packet.getXRot());
		entity.lerpHeadTo(packet.getYHeadRot(), 3);
		entity.setDeltaMovement(packet.getMovement());
		return true;
	}

	/** Every client tick: seeded entities the server never sent (outside its range) are removed. */
	public static void tick(Minecraft minecraft) {
		if (!seededEntities.isEmpty() && Util.getMillis() > seededEntitiesUntil) {
			if (minecraft.level != null) {
				for (int id : seededEntities) {
					minecraft.level.removeEntity(id, Entity.RemovalReason.DISCARDED);
				}
			}
			seededEntities.clear();
		}
	}

	/** A chunk packet is being handled: note it if the server sent it (not a hand-off filling the level). */
	public static void onServerChunk(ClientboundLevelChunkWithLightPacket packet) {
		if (!filling) {
			serverChunks.add(ChunkPos.pack(ClientVersioned.chunkX(packet), ClientVersioned.chunkZ(packet)));
		}
	}

	/**
	 * Whether an unload is left over from the previous level: after a hand-off filled the new
	 * level, for a chunk the server hasn't sent in it.
	 */
	public static boolean isStaleUnload(ChunkPos pos) {
		return filledLevel && !serverChunks.contains(pos.pack());
	}

	/** For a moment after arriving, new chunk sections appear at once instead of fading in. */
	public static boolean suppressChunkFade() {
		return Util.getMillis() < fadeSuppressedUntil;
	}

	/**
	 * The respawn made a new player object: its hand starts lowered, its attack timer at zero
	 * (so the hand dips) and its inventory empty until the server resends it (so the held item
	 * re-equips). Start it from the old one's instead; the server's copy replaces it shortly.
	 */
	private static void carryOver(LocalPlayer from, LocalPlayer to) {
		if (from == null || to == null || from == to) {
			return;
		}
		to.getInventory().replaceWith(from.getInventory());
		to.getInventory().setSelectedSlot(from.getInventory().getSelectedSlot());
		((LivingEntityAccessor) to).netherview$setAttackStrengthTicker(((LivingEntityAccessor) from).netherview$getAttackStrengthTicker());
		((LivingEntityAccessor) to).netherview$setItemSwapTicker(((LivingEntityAccessor) from).netherview$getItemSwapTicker());
		((PlayerAccessor) to).netherview$setLastItemInMainHand(((PlayerAccessor) from).netherview$getLastItemInMainHand());
		ClientVersioned.carryHands(from, to);
	}

	private static void placeAtArrival() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (arrivalPosition != null && player != null) {
			player.snapTo(arrivalPosition.x, arrivalPosition.y, arrivalPosition.z, arrivalYRot, arrivalXRot);
			player.setDeltaMovement(arrivalVelocity);
		}
	}
}
