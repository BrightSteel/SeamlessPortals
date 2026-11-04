package dev.ryan.seamlessportals.server;

import com.mojang.math.Transformation;
import dev.ryan.seamlessportals.PortalGeometry;
import dev.ryan.seamlessportals.net.PortalEnd;
import dev.ryan.seamlessportals.net.PortalFill;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The starfield of {@link PortalFill#STARFIELD} portals (and the blue swirl of {@link
 * PortalFill#AETHER} ones), for players who don't see the portal live: an item display exactly
 * the size of the opening, standing in its plane. It's never a
 * real entity, only packets to those players (so it's never saved, and nobody else has it).
 *
 * <p>The item is black concrete marked with custom model data: the SeamlessPortals resource
 * pack ({@link StarfieldPack}) draws it as a flat panel with the end gateway's starfield;
 * without the pack it's a thin black slab.</p>
 */
public final class StarfieldPanels {
	/** How often the panels and who has them are brought up to date (ticks). */
	private static final int UPDATE_TICKS = 10;
	/** Players this close (blocks) are sent a panel; further away it's taken back. */
	private static final double RANGE = 96.0;
	/** The custom model data strings the pack's black concrete draws as a starfield, and as the aether's swirl. */
	static final String MODEL_DATA = "seamlessportals:starfield";
	static final String AETHER_MODEL_DATA = "seamlessportals:aether";

	/** By id: the class holding the entity types differs between the game versions this builds for. */
	@SuppressWarnings("unchecked")
	private static final EntityType<Display.ItemDisplay> ITEM_DISPLAY =
			(EntityType<Display.ItemDisplay>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("item_display"));

	private static final Map<String, Panel> PANELS = new HashMap<>();

	/** One portal's panel: the display it's drawn with, and who has it (the player object, so a respawn counts as new). */
	private record Panel(PortalEnd end, int depth, String world, Display.ItemDisplay display, Map<UUID, ServerPlayer> viewers) {
	}

	private StarfieldPanels() {
	}

	static void tick(MinecraftServer server) {
		if (server.getTickCount() % UPDATE_TICKS != 0) {
			return;
		}
		Set<String> live = new HashSet<>();
		for (CustomPortal portal : CustomPortals.all()) {
			if (!portal.end().fill().panel()) {
				continue;
			}
			ServerLevel level = CustomPortals.level(server, portal.world());
			if (level == null) {
				continue;
			}
			live.add(portal.name());
			Panel panel = PANELS.get(portal.name());
			if (panel == null || !panel.end().equals(portal.end()) || panel.depth() != portal.panelDepth() || !panel.world().equals(portal.world())) {
				if (panel != null) {
					takeBackAll(panel);
				}
				panel = new Panel(portal.end(), portal.panelDepth(), portal.world(), display(level, portal.end(), portal.panelDepth()), new HashMap<>());
				PANELS.put(portal.name(), panel);
			}
			boolean linked = CustomPortals.link(server, portal) != null;
			Vec3 center = PortalGeometry.center(portal.end());
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				// Players with the mod see a linked portal live; the panel is for everyone else.
				boolean sees = player.level() == level && player.position().distanceToSqr(center) <= RANGE * RANGE
						&& !(linked && PortalViewServer.hasMod(player));
				ServerPlayer had = panel.viewers().get(player.getUUID());
				if (sees && had != player) {
					send(player, panel.display());
					panel.viewers().put(player.getUUID(), player);
				} else if (!sees && had != null) {
					player.connection.send(new ClientboundRemoveEntitiesPacket(panel.display().getId()));
					panel.viewers().remove(player.getUUID());
				}
			}
			panel.viewers().keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
		}
		PANELS.entrySet().removeIf(entry -> {
			if (live.contains(entry.getKey())) {
				return false;
			}
			takeBackAll(entry.getValue());
			return true;
		});
	}

	/** Takes every panel back from everyone (the server or plugin is stopping). */
	public static void clear() {
		PANELS.values().forEach(StarfieldPanels::takeBackAll);
		PANELS.clear();
	}

	private static void takeBackAll(Panel panel) {
		for (ServerPlayer player : panel.viewers().values()) {
			if (!player.hasDisconnected()) {
				player.connection.send(new ClientboundRemoveEntitiesPacket(panel.display().getId()));
			}
		}
		panel.viewers().clear();
	}

	private static void send(ServerPlayer player, Display.ItemDisplay display) {
		Vec3 pos = display.position();
		player.connection.send(new ClientboundAddEntityPacket(display.getId(), display.getUUID(), pos.x, pos.y, pos.z, 0.0F, 0.0F,
				ITEM_DISPLAY, 0, Vec3.ZERO, 0.0));
		List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> data = display.getEntityData().getNonDefaultValues();
		if (data != null) {
			player.connection.send(new ClientboundSetEntityDataPacket(display.getId(), data));
		}
	}

	/**
	 * The display for {@code end}: at its opening's centre (moved {@code depth} sixteenths back),
	 * the panel (a 1×1 square facing +Z in the item model) turned to face the way the portal
	 * does and stretched to its opening.
	 */
	private static Display.ItemDisplay display(ServerLevel level, PortalEnd end, int depth) {
		Display.ItemDisplay display = new Display.ItemDisplay(ITEM_DISPLAY, level);
		Vec3 center = PortalGeometry.center(end).subtract(Vec3.atLowerCornerOf(end.facing().getUnitVec3i()).scale(depth / 16.0));
		display.setPos(center.x, center.y, center.z);
		ItemStack item = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("black_concrete")));
		String model = end.fill() == PortalFill.AETHER ? AETHER_MODEL_DATA : MODEL_DATA;
		item.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(model), List.of()));
		display.setItemStack(item);
		display.setItemTransform(ItemDisplayContext.NONE);
		display.setBrightnessOverride(Brightness.FULL_BRIGHT);
		Vector3f right = unit(end.right());
		Vector3f up = unit(end.up());
		Vector3f facing = unit(end.facing());
		Quaternionf turn = new Quaternionf().setFromNormalized(new Matrix3f(right, up, facing));
		Vector3f size = new Vector3f((float) (end.halfWidth() * 2.0), (float) (end.halfHeight() * 2.0), 1.0F);
		display.setTransformation(new Transformation(new Vector3f(), turn, size, new Quaternionf()));
		return display;
	}

	private static Vector3f unit(Direction direction) {
		return new Vector3f(direction.getStepX(), direction.getStepY(), direction.getStepZ());
	}
}
