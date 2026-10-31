package dev.ryan.netherview.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.UpdateInterval;
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
 * Full state (pairing packets) is sent once, then positions every tick and the complete
 * entity data / equipment every {@link #FULL_SYNC_TICKS}.</p>
 */
final class RemoteEntityTracker {
	private static final double HORIZONTAL_RADIUS = 24.0;
	private static final double VERTICAL_RADIUS = 16.0;
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
	private String dimension;

	/** Packets that bring the client's copy of {@code level} around {@code center} up to date. */
	List<byte[]> update(ServerPlayer player, ServerLevel level, String dimension, Vec3 center, int now) {
		List<byte[]> out = new ArrayList<>();
		if (!dimension.equals(this.dimension)) {
			// A new remote level on the client starts empty.
			this.tracked.clear();
			this.dimension = dimension;
		}
		StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec = codec(level);
		AABB area = new AABB(center, center).inflate(HORIZONTAL_RADIUS, VERTICAL_RADIUS, HORIZONTAL_RADIUS);
		IntSet present = new IntOpenHashSet();
		boolean fullSync = now % FULL_SYNC_TICKS == 0;

		for (Entity entity : level.getEntities((Entity) null, area, e -> !e.isRemoved())) {
			int id = entity.getId();
			present.add(id);
			if (this.tracked.add(id)) {
				newServerEntity(level, entity)
						.sendPairingData(player, packet -> out.add(encode(codec, packet)));
				continue;
			}
			out.add(encode(codec, ClientboundEntityPositionSyncPacket.of(entity)));
			out.add(encode(codec, new ClientboundRotateHeadPacket(entity, Mth.packDegrees(entity.getYHeadRot()))));
			if (fullSync) {
				List<SynchedEntityData.DataValue<?>> data = entity.getEntityData().getNonDefaultValues();
				if (data != null) {
					out.add(encode(codec, new ClientboundSetEntityDataPacket(id, data)));
				}
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
			out.add(encode(codec, new ClientboundRemoveEntitiesPacket(gone.toIntArray())));
		}
		return out;
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
			Object[] args = serverEntityConstructor.getParameterCount() == 5
					? new Object[] {level, entity, UpdateInterval.NEVER, false, NO_BROADCAST}
					: new Object[] {level, entity, UpdateInterval.NEVER, false, NO_BROADCAST, new HashSet<>()};
			return (ServerEntity) serverEntityConstructor.newInstance(args);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("No usable ServerEntity constructor", e);
		}
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
