package dev.ryan.netherview.server;

import dev.ryan.netherview.PortalGeometry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The portal wand: a stick that marks two corners of a new portal (left click, right click),
 * like WorldEdit's. While held, the selection and nearby custom portals are outlined. The
 * platforms pass clicks in ({@link #click}) and cancel the click when it returns true.
 */
public final class PortalWands {
	private static final String TAG = "netherview_wand";
	private static final int OUTLINE_INTERVAL = 10;
	private static final double OUTLINE_STEP = 0.25;
	private static final double SHOW_PORTALS_WITHIN = 48.0;
	private static final DustParticleOptions SELECTION = new DustParticleOptions(0x50DCFF, 0.7F);
	private static final DustParticleOptions PORTAL = new DustParticleOptions(0xBE5AFF, 0.6F);

	/** Two corners in one world (a dimension id); either may still be unset. */
	public record Selection(String world, BlockPos first, BlockPos second) {
		public boolean complete() {
			return this.first != null && this.second != null;
		}
	}

	private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();
	/** Who may use the wand (and {@code /nvportal}); the platform decides. */
	private static Predicate<ServerPlayer> admin = player -> false;

	private PortalWands() {
	}

	public static void init(Predicate<ServerPlayer> isAdmin) {
		admin = isAdmin;
	}

	public static boolean isAdmin(ServerPlayer player) {
		return admin.test(player);
	}

	public static ItemStack create() {
		ItemStack stick = new ItemStack(Items.STICK);
		stick.set(DataComponents.CUSTOM_NAME, Component.literal("Portal Wand").withStyle(style -> style.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(false)));
		stick.set(DataComponents.LORE, new ItemLore(List.of(
				lore("Left click: first corner"),
				lore("Right click: second corner"),
				lore("Then /nvportal create <name> [fill]"))));
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(TAG, true);
		stick.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stick;
	}

	private static Component lore(String text) {
		return Component.literal(text).withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false));
	}

	public static boolean isWand(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().getBooleanOr(TAG, false);
	}

	public static Selection selection(ServerPlayer player) {
		return SELECTIONS.get(player.getUUID());
	}

	/** A wand click on a block; true if it was one (the platform should then cancel the click). */
	public static boolean click(ServerPlayer player, ItemStack held, BlockPos pos, boolean first) {
		if (!isWand(held) || !isAdmin(player)) {
			return false;
		}
		String world = player.level().dimension().identifier().toString();
		Selection old = SELECTIONS.get(player.getUUID());
		if (old == null || !old.world().equals(world)) {
			old = new Selection(world, null, null);
		}
		BlockPos block = pos.immutable();
		Selection updated = first ? new Selection(world, block, old.second()) : new Selection(world, old.first(), block);
		SELECTIONS.put(player.getUUID(), updated);
		Component message = Component.literal((first ? "First" : "Second") + " corner set to " + block.getX() + " " + block.getY() + " " + block.getZ())
				.withStyle(ChatFormatting.AQUA);
		if (updated.complete()) {
			BlockPos size = BlockPos.max(updated.first(), updated.second()).subtract(BlockPos.min(updated.first(), updated.second())).offset(1, 1, 1);
			message = message.copy().append(Component.literal(" (" + size.getX() + "×" + size.getY() + "×" + size.getZ() + ")").withStyle(ChatFormatting.GRAY));
		}
		player.sendSystemMessage(message);
		return true;
	}

	public static void forget(UUID player) {
		SELECTIONS.remove(player);
	}

	static void tick(MinecraftServer server) {
		if (server.getTickCount() % OUTLINE_INTERVAL != 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!isWand(player.getMainHandItem()) || !isAdmin(player)) {
				continue;
			}
			ServerLevel level = player.level();
			Selection selection = SELECTIONS.get(player.getUUID());
			if (selection != null && selection.world().equals(level.dimension().identifier().toString())) {
				BlockPos a = selection.first() != null ? selection.first() : selection.second();
				BlockPos b = selection.second() != null ? selection.second() : selection.first();
				if (a != null) {
					BlockPos min = BlockPos.min(a, b);
					BlockPos max = BlockPos.max(a, b);
					outline(level, player, new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1), SELECTION);
				}
			}
			for (CustomPortal portal : CustomPortals.near(level, player.position(), SHOW_PORTALS_WITHIN)) {
				outline(level, player, PortalGeometry.box(portal.end()), PORTAL);
			}
		}
	}

	/** Particles along a box's twelve edges, visible to this player only. */
	private static void outline(ServerLevel level, ServerPlayer player, AABB box, DustParticleOptions dust) {
		double[] xs = {box.minX, box.maxX};
		double[] ys = {box.minY, box.maxY};
		double[] zs = {box.minZ, box.maxZ};
		for (double y : ys) {
			for (double z : zs) {
				line(level, player, box.minX, y, z, box.maxX, y, z, dust);
			}
		}
		for (double x : xs) {
			for (double z : zs) {
				line(level, player, x, box.minY, z, x, box.maxY, z, dust);
			}
		}
		for (double x : xs) {
			for (double y : ys) {
				line(level, player, x, y, box.minZ, x, y, box.maxZ, dust);
			}
		}
	}

	private static void line(ServerLevel level, ServerPlayer player, double x0, double y0, double z0, double x1, double y1, double z1, DustParticleOptions dust) {
		double length = Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0));
		int steps = Math.max(1, (int) Math.ceil(length / OUTLINE_STEP));
		for (int i = 0; i <= steps; i++) {
			double t = (double) i / steps;
			level.sendParticles(player, dust, true, true, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t, 1, 0, 0, 0, 0);
		}
	}
}
