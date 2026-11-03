package dev.ryan.seamlessportals.paper;

import dev.ryan.seamlessportals.api.BlockLooks;
import dev.ryan.seamlessportals.api.SeamlessPortalsApi;
import dev.ryan.seamlessportals.api.Portal;
import dev.ryan.seamlessportals.api.PortalAccess;
import dev.ryan.seamlessportals.api.PortalException;
import dev.ryan.seamlessportals.api.PortalFill;
import dev.ryan.seamlessportals.net.PortalInsets;
import dev.ryan.seamlessportals.server.CustomPortal;
import dev.ryan.seamlessportals.server.CustomPortals;
import dev.ryan.seamlessportals.server.PortalAdmin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** {@link SeamlessPortalsApi} on Paper: each change is the matching {@code /sportal} operation, run as no player. */
final class PaperPortalApi implements SeamlessPortalsApi {
	@Override
	public Collection<Portal> portals() {
		return CustomPortals.all().stream().map(ApiPortals::toApi).toList();
	}

	@Override
	public Optional<Portal> portal(String name) {
		return Optional.ofNullable(CustomPortals.get(name)).map(ApiPortals::toApi);
	}

	@Override
	public Optional<Portal> portalAt(Location location) {
		return Optional.ofNullable(CustomPortals.at(ApiPortals.level(location), ApiPortals.block(location))).map(ApiPortals::toApi);
	}

	@Override
	public Portal create(String name, Location corner1, Location corner2, BlockFace facing, BlockFace up, PortalFill fill) {
		return this.change(name, () -> {
			ServerLevel level = this.sameWorld(corner1, corner2);
			return PortalAdmin.createAt(level, name, ApiPortals.block(corner1), ApiPortals.block(corner2),
					this.required(facing), ApiPortals.direction(up), fill == null ? null : fill.name().toLowerCase(Locale.ROOT));
		});
	}

	@Override
	public Portal move(String name, Location corner1, Location corner2, BlockFace facing, BlockFace up) {
		return this.change(name, () -> PortalAdmin.move(MinecraftServer.getServer(), name, this.sameWorld(corner1, corner2),
				ApiPortals.block(corner1), ApiPortals.block(corner2), this.required(facing), ApiPortals.direction(up)));
	}

	@Override
	public void delete(String name) {
		this.run(() -> PortalAdmin.delete(MinecraftServer.getServer(), name));
	}

	@Override
	public Portal link(String a, String b) {
		return this.change(a, () -> PortalAdmin.link(a, b));
	}

	@Override
	public Portal setTarget(String name, Location target) {
		return this.change(name, () -> PortalAdmin.targetAt(name, ApiPortals.level(target), target.getX(), target.getY(), target.getZ(),
				target.getYaw(), target.getPitch()));
	}

	@Override
	public Portal setRandomTarget(String name) {
		return this.change(name, () -> PortalAdmin.targetRandom(name));
	}

	@Override
	public Portal unlink(String name) {
		return this.change(name, () -> PortalAdmin.unlink(name));
	}

	@Override
	public Portal setFill(String name, PortalFill fill) {
		return this.change(name, () -> PortalAdmin.fill(MinecraftServer.getServer(), name, fill.name().toLowerCase(Locale.ROOT)));
	}

	@Override
	public Portal setAccess(String name, PortalAccess access) {
		return this.change(name, () -> PortalAdmin.setPublic(name, CustomPortal.Access.valueOf(access.name()).setting()));
	}

	@Override
	public Portal setSurface(String name, int sixteenths) {
		return this.change(name, () -> PortalAdmin.setSurface(name, sixteenths));
	}

	@Override
	public Portal setInsets(String name, int left, int right, int bottom, int top) {
		return this.change(name, () -> PortalAdmin.setInsets(name, new PortalInsets(left, right, bottom, top)));
	}

	@Override
	public Portal setFrontOnly(String name, boolean frontOnly) {
		return this.change(name, () -> PortalAdmin.setFrontOnly(name, frontOnly));
	}

	@Override
	public Portal setPanelDepth(String name, int sixteenths) {
		return this.change(name, () -> PortalAdmin.setPanelDepth(name, sixteenths));
	}

	@Override
	public void addBlockLooks(BlockLooks looks) {
		PaperPortalHooks.LOOKS.add(looks);
		PaperPortalHooks.MAY_CHANGE.clear();
	}

	@Override
	public void removeBlockLooks(BlockLooks looks) {
		PaperPortalHooks.LOOKS.remove(looks);
		PaperPortalHooks.MAY_CHANGE.clear();
	}

	/** Runs an operation and returns the portal {@code name} after it. */
	private Portal change(String name, Supplier<String> operation) {
		this.run(operation);
		return ApiPortals.toApi(CustomPortals.get(name));
	}

	private void run(Supplier<String> operation) {
		if (!Bukkit.isPrimaryThread()) {
			throw new IllegalStateException("SeamlessPortals's API must be called from the main thread");
		}
		try {
			operation.get();
		} catch (IllegalArgumentException e) {
			throw new PortalException(e.getMessage());
		}
	}

	private ServerLevel sameWorld(Location corner1, Location corner2) {
		ServerLevel level = ApiPortals.level(corner1);
		if (level != ApiPortals.level(corner2)) {
			throw new PortalException("Both corners must be in the same world.");
		}
		return level;
	}

	private net.minecraft.core.Direction required(BlockFace facing) {
		if (facing == null) {
			throw new PortalException("Say which way the portal faces.");
		}
		return ApiPortals.direction(facing);
	}
}
