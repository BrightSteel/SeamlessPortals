package dev.ryan.seamlessportals.api;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;

import java.util.Collection;
import java.util.Optional;

/**
 * SeamlessPortals's custom portals, for other plugins: create, move, link and delete them, and
 * look them up. Get it with {@link #get()} once SeamlessPortals is enabled (depend or softdepend on
 * {@code SeamlessPortals} in your plugin.yml).
 *
 * <p>Call it from the main thread. Every change works like the matching {@code /sportal}
 * command (without its permission check): it's saved, it fires the events in
 * {@code dev.ryan.seamlessportals.api.event} with no player, and it throws {@link PortalException}
 * when it can't be done (or a listener cancels it). Portals you get are snapshots.
 */
public interface SeamlessPortalsApi {
	/** The API, or an {@link IllegalStateException} if SeamlessPortals isn't enabled. */
	static SeamlessPortalsApi get() {
		SeamlessPortalsApi api = Bukkit.getServicesManager().load(SeamlessPortalsApi.class);
		if (api == null) {
			throw new IllegalStateException("SeamlessPortals isn't enabled");
		}
		return api;
	}

	Collection<Portal> portals();

	Optional<Portal> portal(String name);

	/** The portal whose opening includes this block. */
	Optional<Portal> portalAt(Location location);

	/**
	 * Fills the opening between two corner blocks (in one world, one block thick along
	 * {@code facing}, at most 21×21, empty) and makes it a portal leading nowhere yet, private.
	 * A wall is filled with nether portal, a floor or ceiling with end portal.
	 */
	default Portal create(String name, Location corner1, Location corner2, BlockFace facing) {
		return this.create(name, corner1, corner2, facing, null, null);
	}

	/**
	 * {@link #create(String, Location, Location, BlockFace)} with which way is up in the opening
	 * (for a floor or ceiling; null: south) and its fill (null: the default).
	 */
	Portal create(String name, Location corner1, Location corner2, BlockFace facing, BlockFace up, PortalFill fill);

	/**
	 * Moves a portal to another opening, in any world: its blocks are cleared where it was and
	 * placed where it goes, and it keeps its name, fill, links and access. {@code up} may be null.
	 */
	Portal move(String name, Location corner1, Location corner2, BlockFace facing, BlockFace up);

	/** Clears a portal's blocks and forgets it (its partner, if linked, then leads nowhere). */
	void delete(String name);

	/** Links two portals both ways (unlinking whatever either was linked to). Returns {@code a}. */
	Portal link(String a, String b);

	/** Makes a portal lead one-way to {@code target}, arriving facing its yaw and pitch. */
	Portal setTarget(String name, Location target);

	/** Makes a portal lead one-way to a new random safe spot in its own world each trip. */
	Portal setRandomTarget(String name);

	/** Makes a portal lead nowhere. */
	Portal unlink(String name);

	Portal setFill(String name, PortalFill fill);

	Portal setAccess(String name, PortalAccess access);
}
