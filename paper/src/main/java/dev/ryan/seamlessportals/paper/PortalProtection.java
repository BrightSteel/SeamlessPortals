package dev.ryan.seamlessportals.paper;

import dev.ryan.seamlessportals.server.CustomPortals;
import io.papermc.paper.event.entity.EntityInsideBlockEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockPos;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerPortalEvent;

import java.util.List;

/**
 * Keeps a custom portal's fill what it is: it can't be broken, drained, flowed out of,
 * pushed, blown up or built into, and the fill block's own vanilla behaviour (nether portal
 * trips and zombified piglins, end portal and gateway teleports, lava burns) doesn't happen
 * inside it. {@link dev.ryan.seamlessportals.server.CustomPortalBlocks#repair} restores anything
 * that gets past these (a frameless nether portal breaking itself on a shape update, say).
 */
final class PortalProtection implements Listener {
	private static boolean isPortal(Block block) {
		return CustomPortals.at(((CraftWorld) block.getWorld()).getHandle(), new BlockPos(block.getX(), block.getY(), block.getZ())) != null;
	}

	private static boolean touchesPortal(Entity entity) {
		return CustomPortals.touches(((CraftEntity) entity).getHandle());
	}

	// ── the fill's own behaviour ────────────────────────────────────────────

	@EventHandler(ignoreCancelled = true)
	public void onInside(EntityInsideBlockEvent event) {
		if (isPortal(event.getBlock())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerPortal(PlayerPortalEvent event) {
		if (touchesPortal(event.getPlayer())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onEntityPortal(EntityPortalEvent event) {
		if (touchesPortal(event.getEntity())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onSpawn(CreatureSpawnEvent event) {
		if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NETHER_PORTAL && touchesPortal(event.getEntity())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onDamage(EntityDamageEvent event) {
		EntityDamageEvent.DamageCause cause = event.getCause();
		if ((cause == EntityDamageEvent.DamageCause.LAVA || cause == EntityDamageEvent.DamageCause.FIRE || cause == EntityDamageEvent.DamageCause.FIRE_TICK)
				&& touchesPortal(event.getEntity())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onCombust(EntityCombustEvent event) {
		if (touchesPortal(event.getEntity())) {
			event.setCancelled(true);
		}
	}

	// ── keeping the blocks ──────────────────────────────────────────────────

	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
	public void onBreak(BlockBreakEvent event) {
		if (isPortal(event.getBlock())) {
			event.setCancelled(true);
			if (event.getPlayer().hasPermission("seamlessportals.admin")) {
				event.getPlayer().sendActionBar(Component.text("That's a custom portal: remove it with /sportal delete <name>", NamedTextColor.LIGHT_PURPLE));
			}
		}
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
	public void onPlace(BlockPlaceEvent event) {
		if (isPortal(event.getBlockPlaced())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onBucketFill(PlayerBucketFillEvent event) {
		if (isPortal(event.getBlockClicked())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onBucketEmpty(PlayerBucketEmptyEvent event) {
		if (isPortal(event.getBlock())) {
			event.setCancelled(true);
		}
	}

	/** Water and lava fills don't spread, and nothing flows into a portal. */
	@EventHandler(ignoreCancelled = true)
	public void onFlow(BlockFromToEvent event) {
		if (isPortal(event.getBlock()) || isPortal(event.getToBlock())) {
			event.setCancelled(true);
		}
	}

	/** A lava fill next to water doesn't turn into obsidian or cobblestone. */
	@EventHandler(ignoreCancelled = true)
	public void onForm(BlockFormEvent event) {
		if (isPortal(event.getBlock())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onPhysics(BlockPhysicsEvent event) {
		if (isPortal(event.getBlock())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onSponge(SpongeAbsorbEvent event) {
		event.getBlocks().removeIf(state -> isPortal(state.getBlock()));
	}

	@EventHandler(ignoreCancelled = true)
	public void onEntityChange(EntityChangeBlockEvent event) {
		if (isPortal(event.getBlock())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onEntityExplode(EntityExplodeEvent event) {
		event.blockList().removeIf(PortalProtection::isPortal);
	}

	@EventHandler(ignoreCancelled = true)
	public void onBlockExplode(BlockExplodeEvent event) {
		event.blockList().removeIf(PortalProtection::isPortal);
	}

	@EventHandler(ignoreCancelled = true)
	public void onPistonExtend(BlockPistonExtendEvent event) {
		if (pistonTouches(event.getBlocks(), event.getDirection().getModX(), event.getDirection().getModY(), event.getDirection().getModZ())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onPistonRetract(BlockPistonRetractEvent event) {
		if (pistonTouches(event.getBlocks(), event.getDirection().getModX(), event.getDirection().getModY(), event.getDirection().getModZ())) {
			event.setCancelled(true);
		}
	}

	/** Moved blocks, or where they'd land, in a portal (a block pushed into a water fill would replace it). */
	private static boolean pistonTouches(List<Block> moved, int dx, int dy, int dz) {
		for (Block block : moved) {
			if (isPortal(block) || isPortal(block.getRelative(dx, dy, dz))) {
				return true;
			}
		}
		return false;
	}
}
