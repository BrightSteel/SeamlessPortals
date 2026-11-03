package dev.ryan.seamlessportals.paper;

import dev.ryan.seamlessportals.api.BlockLooks;
import dev.ryan.seamlessportals.api.event.PortalChangeEvent;
import dev.ryan.seamlessportals.api.event.PortalCreateEvent;
import dev.ryan.seamlessportals.api.event.PortalDeleteEvent;
import dev.ryan.seamlessportals.api.event.PortalTravelEvent;
import dev.ryan.seamlessportals.server.CustomPortal;
import dev.ryan.seamlessportals.server.PortalHooks;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.entity.Player;

/** The server half's say on portals, as the API's events. */
final class PaperPortalHooks implements PortalHooks {
	@Override
	public boolean creating(CustomPortal portal, ServerPlayer actor) {
		return new PortalCreateEvent(ApiPortals.toApi(portal), player(actor)).callEvent();
	}

	@Override
	public boolean deleting(CustomPortal portal, ServerPlayer actor) {
		return new PortalDeleteEvent(ApiPortals.toApi(portal), player(actor)).callEvent();
	}

	@Override
	public boolean changing(CustomPortal before, CustomPortal after, ServerPlayer actor) {
		return new PortalChangeEvent(ApiPortals.toApi(before), ApiPortals.toApi(after), player(actor)).callEvent();
	}

	@Override
	public Arrival travelling(Entity entity, CustomPortal portal, Arrival arrival) {
		Location to = new Location(arrival.level().getWorld(), arrival.position().x, arrival.position().y, arrival.position().z,
				arrival.yRot(), arrival.xRot());
		PortalTravelEvent event = new PortalTravelEvent(entity.getBukkitEntity(), portal == null ? null : ApiPortals.toApi(portal),
				entity.getBukkitEntity().getLocation(), to);
		if (!event.callEvent()) {
			return null;
		}
		Location redirected = event.getTo();
		if (redirected.equals(to)) {
			return arrival;
		}
		return new Arrival(ApiPortals.level(redirected), new Vec3(redirected.getX(), redirected.getY(), redirected.getZ()),
				redirected.getYaw(), redirected.getPitch());
	}

	/** What other plugins say about how blocks look (see {@link BlockLooks}), and, by state, whether any of them may change it. */
	static final List<BlockLooks> LOOKS = new CopyOnWriteArrayList<>();
	static final Map<BlockState, Boolean> MAY_CHANGE = new ConcurrentHashMap<>();

	@Override
	public boolean mayChangeLooks(BlockState state) {
		if (LOOKS.isEmpty()) {
			return false;
		}
		return MAY_CHANGE.computeIfAbsent(state, s -> {
			BlockData data = CraftBlockData.createData(s);
			return LOOKS.stream().anyMatch(looks -> looks.mayChange(data));
		});
	}

	@Override
	public BlockState looks(ServerLevel level, BlockPos pos, BlockState state) {
		BlockData data = CraftBlockData.createData(state);
		for (BlockLooks looks : LOOKS) {
			if (!looks.mayChange(data)) {
				continue;
			}
			BlockData shown = looks.looks(level.getWorld(), pos.getX(), pos.getY(), pos.getZ(), data);
			if (shown != null) {
				return ((CraftBlockData) shown).getState();
			}
		}
		return null;
	}

	private static Player player(ServerPlayer actor) {
		return actor == null ? null : actor.getBukkitEntity();
	}
}
