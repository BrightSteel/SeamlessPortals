package dev.ryan.seamlessportals.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.mojang.datafixers.util.Pair;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * Mirrors the entities around one player's portal destination, tick by tick, as the same
 * packets vanilla would send to a player standing there.
 *
 * <p>It polls instead of hooking vanilla's entity tracker: running a second tracker would
 * consume the entity's dirty-data flags out from under real players in that dimension.
 * Full state (pairing packets) is sent once, then positions every tick, entity data as soon as
 * it changes, and the complete entity data / equipment every {@link #FULL_SYNC_TICKS} (but a
 * display's data only when it changes).</p>
 */
final class RemoteEntityTracker {
	/** Vertical reach as a fraction of the horizontal radius; portals look across more than up. */
	private static final double VERTICAL_FRACTION = 2.0 / 3.0;
	private static final int FULL_SYNC_TICKS = 10;
	private static final ServerEntity.Synchronizer NO_BROADCAST = new ServerEntity.Synchronizer() {
		@Override
		public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {
		}

		@Override
		public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {
		}

		@Override
		public void sendToTrackingPlayersFiltered(Packet<? super ClientGamePacketListener> packet, Predicate<ServerPlayer> predicate) {
		}
	};

	private final IntSet tracked = new IntOpenHashSet();
	/**
	 * Each tracked entity's data as last sent (its hash): sent again as soon as it changes, not
	 * only at the next full sync, so a display's new transformation arrives with its
	 * interpolation (a door swinging) rather than a while later in one jump.
	 */
	private final Int2IntMap sentData = new Int2IntOpenHashMap();
	private String dimension;

	/** Packets that bring the client's copy of {@code level} around {@code center} up to date. */
	List<byte[]> update(ServerPlayer player, ServerLevel level, String dimension, List<Vec3> centers, double radius, int now) {
		List<byte[]> out = new ArrayList<>();
		if (!dimension.equals(this.dimension)) {
			// A new remote level on the client starts empty.
			this.tracked.clear();
			this.sentData.clear();
			this.dimension = dimension;
		}
		StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec = codec(level);
		IntSet present = new IntOpenHashSet();
		boolean fullSync = now % FULL_SYNC_TICKS == 0;
		List<Entity> nearby = new ArrayList<>();
		if (radius > 0) {
			for (Vec3 center : centers) {
				AABB area = new AABB(center, center).inflate(radius, radius * VERTICAL_FRACTION, radius);
				for (Entity entity : level.getEntities((Entity) null, area, e -> !e.isRemoved())) {
					if (present.add(entity.getId())) {
						nearby.add(entity);
					}
				}
			}
		}

		for (Entity entity : nearby) {
			int id = entity.getId();
			List<SynchedEntityData.DataValue<?>> data = entity.getEntityData().getNonDefaultValues();
			int dataHash = hash(data);
			if (this.tracked.add(id)) {
				newServerEntity(level, entity)
						.sendPairingData(player, packet -> out.add(encode(codec, packet)));
				this.sentData.put(id, dataHash);
				continue;
			}
			out.add(encode(codec, ClientboundEntityPositionSyncPacket.of(entity)));
			out.add(encode(codec, new ClientboundRotateHeadPacket(entity, Mth.packDegrees(entity.getYHeadRot()))));
			boolean dataChanged = !this.sentData.containsKey(id) || this.sentData.get(id) != dataHash;
			// Not a display's unchanged data: the client takes any transformation it's sent as a
			// new one to interpolate to from where it is, over the whole duration again, so a
			// swing caught mid-way by a full sync would slow to a crawl at its end.
			if (fullSync && !(entity instanceof Display) || dataChanged) {
				if (data != null) {
					out.add(encode(codec, new ClientboundSetEntityDataPacket(id, withInterpolationStart(entity, data))));
				}
				this.sentData.put(id, dataHash);
			}
			if (fullSync) {
				if (entity instanceof LivingEntity living) {
					List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
					for (EquipmentSlot slot : EquipmentSlot.VALUES) {
						slots.add(Pair.of(slot, living.getItemBySlot(slot).copy()));
					}
					out.add(encode(codec, new ClientboundSetEquipmentPacket(id, slots)));
				}
			}
		}

		IntSet gone = new IntOpenHashSet(this.tracked);
		gone.removeAll(present);
		if (!gone.isEmpty()) {
			this.tracked.removeAll(gone);
			gone.forEach(this.sentData::remove);
			out.add(encode(codec, new ClientboundRemoveEntitiesPacket(gone.toIntArray())));
		}
		return out;
	}

	/**
	 * A hash of entity data that's the same for the same data: item stacks (an item display's,
	 * a dropped item's) hashed by their contents, not by identity (each read is a fresh copy, so
	 * the data would look changed every tick, and be sent every tick).
	 */
	private static int hash(List<SynchedEntityData.DataValue<?>> data) {
		if (data == null) {
			return 0;
		}
		int hash = 1;
		for (SynchedEntityData.DataValue<?> value : data) {
			int valueHash = value.value() instanceof ItemStack stack
					? 31 * ItemStack.hashItemAndComponents(stack) + stack.getCount()
					: java.util.Objects.hashCode(value.value());
			hash = 31 * hash + 31 * value.id() + valueHash;
		}
		return hash;
	}

	/**
	 * A display's data, with its interpolation start always in it: the client starts a new
	 * transformation's interpolation only when that arrives, and at its usual value (no delay,
	 * the default) it's left out of an entity's non-default data.
	 */
	private static List<SynchedEntityData.DataValue<?>> withInterpolationStart(Entity entity, List<SynchedEntityData.DataValue<?>> data) {
		if (!(entity instanceof Display display) || INTERPOLATION_START == null) {
			return data;
		}
		List<SynchedEntityData.DataValue<?>> all = new ArrayList<>(data);
		all.removeIf(value -> value.id() == INTERPOLATION_START.id());
		all.add(SynchedEntityData.DataValue.create(INTERPOLATION_START, display.getTransformationInterpolationDelay()));
		return all;
	}

	/** Display's (private) interpolation start accessor, or null if this game hasn't one by that name. */
	@SuppressWarnings("unchecked")
	private static final EntityDataAccessor<Integer> INTERPOLATION_START = interpolationStart();

	@SuppressWarnings("unchecked")
	private static EntityDataAccessor<Integer> interpolationStart() {
		try {
			java.lang.reflect.Field field = Display.class.getDeclaredField("DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID");
			field.setAccessible(true);
			return (EntityDataAccessor<Integer>) field.get(null);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	String dimension() {
		return this.dimension;
	}

	/** Forget everything (the destination changed); returns the removal packet if anything was shown. */
	List<byte[]> reset(ServerLevel level) {
		List<byte[]> out = new ArrayList<>();
		if (!this.tracked.isEmpty() && level != null) {
			out.add(encode(codec(level), new ClientboundRemoveEntitiesPacket(this.tracked.toIntArray())));
		}
		this.tracked.clear();
		this.sentData.clear();
		this.dimension = null;
		return out;
	}

	private static Constructor<?> serverEntityConstructor;

	/**
	 * A throwaway ServerEntity, only used to produce pairing packets. Paper adds a parameter
	 * (the set of tracking connections) to the vanilla constructor, so pick whichever exists.
	 */
	private static ServerEntity newServerEntity(ServerLevel level, Entity entity) {
		try {
			if (serverEntityConstructor == null) {
				for (Constructor<?> constructor : ServerEntity.class.getConstructors()) {
					int count = constructor.getParameterCount();
					if ((count == 5 || count == 6) && constructor.getParameterTypes()[0] == ServerLevel.class) {
						serverEntityConstructor = constructor;
					}
				}
			}
			Object interval = updateIntervalNever(serverEntityConstructor.getParameterTypes()[2]);
			Object[] args = serverEntityConstructor.getParameterCount() == 5
					? new Object[] {level, entity, interval, false, NO_BROADCAST}
					: new Object[] {level, entity, interval, false, NO_BROADCAST, new HashSet<>()};
			return (ServerEntity) serverEntityConstructor.newInstance(args);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("No usable ServerEntity constructor", e);
		}
	}

	/**
	 * "Never send periodic updates" for the constructor's interval parameter: an
	 * {@code UpdateInterval} from 26.3, a tick count before (where the largest never comes round).
	 */
	private static Object updateIntervalNever(Class<?> type) throws ReflectiveOperationException {
		return type == int.class ? Integer.MAX_VALUE : type.getField("NEVER").get(null);
	}

	private static StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> boundCodec;
	private static Object boundRegistries;

	private static StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec(ServerLevel level) {
		if (boundRegistries != level.registryAccess()) {
			boundCodec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(level.registryAccess())).codec();
			boundRegistries = level.registryAccess();
		}
		return boundCodec;
	}

	private static byte[] encode(StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec, Packet<? super ClientGamePacketListener> packet) {
		ByteBuf buf = Unpooled.buffer();
		try {
			codec.encode(buf, packet);
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			return bytes;
		} finally {
			buf.release();
		}
	}
}
