package dev.ryan.netherview.client;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.Entity;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import dev.ryan.netherview.compat.ClientVersioned;
import dev.ryan.netherview.PortalGeometry;
import dev.ryan.netherview.client.mixin.ChunkPacketDataAccessor;
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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
	/** How long after a hand-off terrain GPU buffers are kept rather than freed and allocated anew. */
	private static final long KEEP_TERRAIN_BUFFERS_MS = 3000L;
	private static long keepTerrainBuffersUntil;
	/** Rounds of the light queue that drain a full hand-off fill (each takes at least a tenth, or 10). */
	private static final int LIGHT_DRAIN_ROUNDS = 64;

	/** Set by the server's travel announcement, consumed by the respawn that follows it. */
	private static PortalLink travel;
	private static List<ClientboundLevelChunkWithLightPacket> pendingChunks = List.of();
	private static ChunkPos pendingCenter;
	private static RemoteDimension pendingView;
	/** The bridge view's entities, as spawn packets, to add to the new level with its terrain. */
	private static List<Packet<ClientGamePacketListener>> pendingEntities = List.of();
	/**
	 * Per level (the new one, and the way-back view's): entities a hand-off added that the server
	 * hasn't sent itself yet, and when to drop them.
	 */
	private static final Map<ClientLevel, IntSet> seededEntities = new HashMap<>();
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
	/** A hand-off's chunks not filled in yet, nearest first, and the view to copy them from; see {@link #tick}. */
	private static final List<ChunkPos> deferredFill = new ArrayList<>();
	private static RemoteDimension fillSource;
	/** The block data of each chunk a hand-off filled in, until the server resends that chunk. */
	private static final Long2ObjectMap<byte[]> filledData = new Long2ObjectOpenHashMap<>();
	/** Chunks this near the arrival (in chunks) are filled in at once, the rest {@link #FILL_PER_TICK} a tick. */
	private static final int FILL_NOW_RADIUS = 3;
	private static final int FILL_PER_TICK = 8;

	/** Where the player really is (client-side) when the server moved them, carried through the portal. */
	private static Vec3 arrivalPosition;
	private static Vec3 arrivalVelocity;
	private static float arrivalYRot;
	private static PortalGeometry arrivalGeometry;
	private static float arrivalXRot;

	/** The portal just used, reversed, and the dimension being left with its chunks around that portal. */
	private static PortalLink wayBack;
	private static ResourceKey<Level> wayBackDimension;
	private static List<ClientboundLevelChunkWithLightPacket> wayBackChunks = List.of();
	private static List<Packet<ClientGamePacketListener>> wayBackEntities = List.of();

	/** Set between a hand-off and the server's placement packet that follows it. */
	private static boolean holdThroughPlacement;
	private static Vec3 heldPosition;
	private static Vec3 heldOldPosition;
	private static float heldYRotO;
	private static float heldXRotO;
	private static boolean heldOnGround;
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
		deferredFill.clear();
		fillSource = null;
		filledData.clear();
		previousPlayer = null;
		arrivalPosition = null;
		wayBack = null;
		wayBackChunks = List.of();
		wayBackEntities = List.of();
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
		// Only the chunks around the arrival now (encoding them all would stall this frame);
		// the rest are copied from the same view, the bridge by then, a few a tick (see tick).
		pendingChunks = remote.snapshotChunks(FILL_NOW_RADIUS);
		pendingCenter = remote.center();
		pendingView = remote;
		pendingEntities = minecraft.getConnection() == null ? List.of() : remote.snapshotEntities(minecraft.getConnection());
		previousPlayer = minecraft.player;
		// The server placed the player from where it last saw them, and the client has kept
		// moving since; carry the client's own position through the portal instead.
		PortalGeometry geometry = used.geometry();
		arrivalGeometry = geometry;
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
		ChunkPos backCenter = ChunkPos.containing(BlockPos.containing(geometry.sourceCenter()));
		// Not the chunks a view prepared while the player came up to the portal already has.
		ResourceKey<Level> backDimension = minecraft.level.dimension();
		wayBackChunks = RemoteDimension.snapshotChunks(minecraft.level, backCenter, RemoteDimension.chunkRadius(),
				(x, z) -> RemoteDimensions.preparedHas(backDimension, backCenter, x, z));
		// Its entities too, or the mobs just left behind vanish from the window back until the
		// server streams them to that view.
		wayBackEntities = minecraft.getConnection() == null ? List.of()
				: RemoteDimension.snapshotEntities(minecraft.level, minecraft.getConnection(), backCenter, RemoteDimension.chunkRadius());
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
		keepTerrainBuffersUntil = Util.getMillis() + KEEP_TERRAIN_BUFFERS_MS;
		RemoteDimensions.adoptAsBridge(level.dimension(), pendingView, true);
		fillSource = pendingView;
		pendingView = null;
		level.getChunkSource().updateViewCenter(center.x(), center.z());
		filling = true;
		try {
			// The chunks around the player now; the rest a few a tick (see tick), unless the
			// server's own come first: filling them all at once would stall this frame.
			for (ClientboundLevelChunkWithLightPacket chunk : chunks) {
				fill(listener, chunk);
			}
			deferredFill.clear();
			int radius = RemoteDimension.chunkRadius() + 3;
			for (int ring = FILL_NOW_RADIUS + 1; ring <= radius; ring++) {
				for (int x = center.x() - ring; x <= center.x() + ring; x++) {
					for (int z = center.z() - ring; z <= center.z() + ring; z++) {
						if (Math.max(Math.abs(x - center.x()), Math.abs(z - center.z())) == ring) {
							deferredFill.add(new ChunkPos(x, z));
						}
					}
				}
			}
			// Vanilla lights queued chunks a tenth of the queue a tick, and an entity in an unlit
			// chunk is drawn black: the mobs just seen through the portal would flash dark. These
			// chunks come with their light, so apply it all now.
			for (int i = 0; i < LIGHT_DRAIN_ROUNDS; i++) {
				level.pollLightUpdates();
			}
			level.getChunkSource().getLightEngine().runLightUpdates();
			// Its entities too, so mobs seen through the portal don't vanish until the server
			// sends them; the server's copies (same ids) update these when they arrive.
			for (Packet<ClientGamePacketListener> packet : pendingEntities) {
				try {
					packet.handle(listener);
					if (packet instanceof ClientboundAddEntityPacket add) {
						seededEntities.computeIfAbsent(level, key -> new IntOpenHashSet()).add(add.getId());
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
			filling = true;
			try {
				RemoteDimension back = RemoteDimensions.installArrival(wayBack, wayBackDimension, wayBackChunks, wayBackEntities);
				for (Packet<ClientGamePacketListener> packet : wayBackEntities) {
					if (packet instanceof ClientboundAddEntityPacket add && back.level.getEntity(add.getId()) != null) {
						seededEntities.computeIfAbsent(back.level, key -> new IntOpenHashSet()).add(add.getId());
					}
				}
			} finally {
				filling = false;
			}
		}
		wayBack = null;
		wayBackChunks = List.of();
		wayBackEntities = List.of();

		// The new player object starts at the origin and the server's placement may land a
		// frame later; with no loading screen to hide that, place it now.
		placeAtArrival();
		if (arrivalGeometry != null && arrivalVelocity != null) {
			arrivedThrough(arrivalGeometry, arrivalVelocity);
		}
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
		arrivalGeometry = null;
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
			heldOldPosition = new Vec3(player.xo, player.yo, player.zo);
			heldVelocity = player.getDeltaMovement();
			heldYRot = player.getYRot();
			heldXRot = player.getXRot();
			heldYRotO = player.yRotO;
			heldXRotO = player.xRotO;
			heldOnGround = player.onGround();
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
			// Snapping also resets the previous tick's position, which the camera interpolates
			// from: it would stand still until the next tick.
			if (heldOldPosition != null) {
				setPrevious(player, heldOldPosition, heldYRotO, heldXRotO);
			}
			player.setOnGround(heldOnGround);
		}
		holdThroughPlacement = false;
		heldPosition = null;
		heldOldPosition = null;
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
		heldOldPosition = geometry.toDest(new Vec3(player.xo, player.yo, player.zo));
		heldVelocity = geometry.rotate(player.getDeltaMovement());
		heldYRot = rotation[0];
		heldXRot = rotation[1];
		float[] previousRotation = geometry.toDestRotation(player.yRotO, player.xRotO);
		heldYRotO = previousRotation[0];
		heldXRotO = previousRotation[1];
		heldOnGround = player.onGround();
		holdThroughPlacement = true;
		arrivedThrough(geometry, heldVelocity);

		RemoteDimension remote = RemoteDimensions.viewFor(announced, level.dimension());
		if (remote != null && remote.center() != null) {
			level.getChunkSource().updateViewCenter(remote.center().x(), remote.center().z());
			for (ClientboundLevelChunkWithLightPacket chunk : remote.snapshotChunks()) {
				if (level.getChunkSource().getChunk(ClientVersioned.chunkX(chunk), ClientVersioned.chunkZ(chunk), false) == null) {
					listener.handleLevelChunkWithLight(chunk);
				}
			}
			RemoteDimensions.adoptAsBridge(level.dimension(), remote, false);
			fadeSuppressedUntil = Util.getMillis() + FADE_SUPPRESS_MS;
		}
		// The way back right away, as after a change of world (see afterRespawn): otherwise the
		// portal just come out of is a swirl until the server's next scan. The player's own
		// level still has everything around it.
		if (announced.dest().fill() != PortalFill.NONE) {
			PortalLink back = announced.reversed(level.dimension().identifier().toString(), announced.destDimensionType());
			ChunkPos backCenter = ChunkPos.containing(BlockPos.containing(geometry.sourceCenter()));
			ResourceKey<Level> dimension = level.dimension();
			List<ClientboundLevelChunkWithLightPacket> chunks = RemoteDimension.snapshotChunks(level, backCenter, RemoteDimension.chunkRadius(),
					(x, z) -> RemoteDimensions.preparedHas(dimension, backCenter, x, z));
			List<Packet<ClientGamePacketListener>> entities = RemoteDimension.snapshotEntities(level, listener, backCenter, RemoteDimension.chunkRadius());
			filling = true;
			try {
				RemoteDimension view = RemoteDimensions.installSameWorldArrival(back, dimension, chunks, entities);
				for (Packet<ClientGamePacketListener> packet : entities) {
					if (packet instanceof ClientboundAddEntityPacket add && view.level.getEntity(add.getId()) != null) {
						seededEntities.computeIfAbsent(view.level, key -> new IntOpenHashSet()).add(add.getId());
					}
				}
				seededEntitiesUntil = Util.getMillis() + SEEDED_ENTITY_MS;
			} finally {
				filling = false;
			}
		}
		return true;
	}

	/**
	 * The server added an entity. If a hand-off seeded that very entity, it's kept and moved to
	 * the server's position instead: replacing it would restart its animation and interpolation,
	 * a visible flicker. Returns whether the packet is handled.
	 */
	public static boolean onServerEntity(ClientboundAddEntityPacket packet, ClientLevel level) {
		IntSet seeded = seededEntities.get(level);
		if (filling || seeded == null || !seeded.remove(packet.getId())) {
			return false;
		}
		Entity entity = level.getEntity(packet.getId());
		if (entity == null || entity.getType() != packet.getType() || !entity.getUUID().equals(packet.getUUID())) {
			return false;
		}
		entity.moveOrInterpolateTo(new Vec3(packet.getX(), packet.getY(), packet.getZ()), packet.getYRot(), packet.getXRot());
		entity.lerpHeadTo(packet.getYHeadRot(), 3);
		entity.setDeltaMovement(packet.getMovement());
		return true;
	}

	/** Fill one chunk of a hand-off into the player's level, remembering its data for {@link #onServerChunk}. */
	private static void fill(ClientPacketListener listener, ClientboundLevelChunkWithLightPacket chunk) {
		listener.handleLevelChunkWithLight(chunk);
		filledData.put(ChunkPos.pack(ClientVersioned.chunkX(chunk), ClientVersioned.chunkZ(chunk)),
				((ChunkPacketDataAccessor) ClientVersioned.chunkData(chunk)).netherview$getBuffer());
	}

	/** Every client tick: seeded entities the server never sent (outside its range) are removed. */
	public static void tick(Minecraft minecraft) {
		ClientPacketListener connection = minecraft.getConnection();
		if (!deferredFill.isEmpty() && (connection == null || !filledLevel || fillSource == null || fillSource.isClosed()
				|| minecraft.level == null || minecraft.level.dimension() != fillSource.key)) {
			deferredFill.clear();
			fillSource = null;
		}
		if (!deferredFill.isEmpty()) {
			filling = true;
			try {
				for (int filled = 0; filled < FILL_PER_TICK && !deferredFill.isEmpty(); ) {
					ChunkPos pos = deferredFill.removeFirst();
					LevelChunk chunk = fillSource.level.getChunkSource().getChunk(pos.x(), pos.z(), false);
					if (chunk != null && !serverChunks.contains(pos.pack())) {
						fill(connection, new ClientboundLevelChunkWithLightPacket(chunk, fillSource.level.getLightEngine(), null, null));
						filled++;
					}
				}
			} finally {
				filling = false;
			}
		}
		if (!seededEntities.isEmpty() && Util.getMillis() > seededEntitiesUntil) {
			seededEntities.forEach((level, ids) -> ids.forEach(id -> level.removeEntity(id, Entity.RemovalReason.DISCARDED)));
			seededEntities.clear();
		}
	}

	/**
	 * A chunk packet is being handled: note it if the server sent it (not a hand-off filling
	 * the level). Returns whether to skip it.
	 */
	public static boolean onServerChunk(ClientboundLevelChunkWithLightPacket packet) {
		if (filling) {
			return false;
		}
		long pos = ChunkPos.pack(ClientVersioned.chunkX(packet), ClientVersioned.chunkZ(packet));
		serverChunks.add(pos);
		// The server resends every chunk after the respawn. One identical to what the hand-off
		// filled in is skipped: handling it would re-light and re-mesh it all for nothing.
		byte[] filled = filledData.remove(pos);
		ClientLevel level = Minecraft.getInstance().level;
		return filled != null && level != null && level.getChunkSource().getChunk(ClientVersioned.chunkX(packet), ClientVersioned.chunkZ(packet), false) != null
				&& Arrays.equals(filled, ((ChunkPacketDataAccessor) ClientVersioned.chunkData(packet)).netherview$getBuffer());
	}

	/**
	 * Whether an unload is left over from the previous level: after a hand-off filled the new
	 * level, for a chunk the server hasn't sent in it.
	 */
	public static boolean isStaleUnload(ChunkPos pos) {
		return filledLevel && !serverChunks.contains(pos.pack());
	}

	/** For a moment after arriving, new chunk sections appear at once instead of fading in. */
	/** Right after a hand-off: see the KeepTerrainBuffers and KeepTerrainHeaps mixins. */
	public static boolean keepTerrainBuffers() {
		return Util.getMillis() < keepTerrainBuffersUntil;
	}

	public static boolean suppressChunkFade() {
		return Util.getMillis() < fadeSuppressedUntil;
	}

	/**
	 * Vanilla hides an entity until its section is meshed, and right after a hand-off almost
	 * nothing is: the entities the player just saw through the portal would vanish and pop
	 * back one section at a time. Until the new level catches up they're drawn anyway; the
	 * bridge's terrain, depth-merged in front, still hides those behind walls.
	 */
	public static boolean showUnmeshedEntities() {
		return suppressChunkFade() || RemoteDimensions.bridge() != null;
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
		if (arrivalGeometry != null) {
			// The camera is drawn between the player's last two ticks: a new player object has
			// only one, so it would jump to the end of this tick and stand still until the
			// next (most visible flying or sprinting). Carry the previous tick through too.
			float[] previousRotation = arrivalGeometry.toDestRotation(from.yRotO, from.xRotO);
			setPrevious(to, arrivalGeometry.toDest(new Vec3(from.xo, from.yo, from.zo)), previousRotation[0], previousRotation[1]);
			// A new player object starts airborne: its first tick would move with air control
			// (a stumble while walking through) until it finds the ground again.
			to.setOnGround(from.onGround());
			float turn = to.getYRot() - from.getYRot();
			to.yBodyRot = from.yBodyRot + turn;
			to.yBodyRotO = from.yBodyRotO + turn;
			to.yHeadRot = from.yHeadRot + turn;
			to.yHeadRotO = from.yHeadRotO + turn;
			to.xBob = from.xBob;
			to.xBobO = from.xBobO;
			to.yBob = from.yBob + turn;
			to.yBobO = from.yBobO + turn;
			// View bobbing's walk cycle, or the view would jolt as it restarts.
			copyFields(from.avatarState(), to.avatarState(), "walkDist", "walkDistO", "bob", "bobO");
		}
		to.getInventory().replaceWith(from.getInventory());
		to.getInventory().setSelectedSlot(from.getInventory().getSelectedSlot());
		((LivingEntityAccessor) to).netherview$setAttackStrengthTicker(((LivingEntityAccessor) from).netherview$getAttackStrengthTicker());
		((LivingEntityAccessor) to).netherview$setItemSwapTicker(((LivingEntityAccessor) from).netherview$getItemSwapTicker());
		((PlayerAccessor) to).netherview$setLastItemInMainHand(((PlayerAccessor) from).netherview$getLastItemInMainHand());
		ClientVersioned.carryHands(from, to);
	}

	/** Tell the renderer which portal the player is coming out of, and which way (see {@link PortalViewRenderer#arrivedThrough}). */
	private static void arrivedThrough(PortalGeometry geometry, Vec3 velocity) {
		Vec3 normal = geometry.destNormal();
		double along = velocity.dot(normal);
		if (Math.abs(along) > 1.0E-4) {
			PortalViewRenderer.arrivedThrough(geometry.destCenter(), normal.scale(Math.signum(along)));
		}
	}

	private static void setPrevious(LocalPlayer player, Vec3 position, float yRot, float xRot) {
		player.xo = player.xOld = position.x;
		player.yo = player.yOld = position.y;
		player.zo = player.zOld = position.z;
		player.yRotO = yRot;
		player.xRotO = xRot;
	}

	private static void copyFields(Object from, Object to, String... names) {
		for (String name : names) {
			try {
				java.lang.reflect.Field field = from.getClass().getDeclaredField(name);
				field.setAccessible(true);
				field.set(to, field.get(from));
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// cosmetic only: that part of the animation restarts
			}
		}
	}

	private static void placeAtArrival() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (arrivalPosition != null && player != null) {
			player.snapTo(arrivalPosition.x, arrivalPosition.y, arrivalPosition.z, arrivalYRot, arrivalXRot);
			player.setDeltaMovement(arrivalVelocity);
		}
	}
}
