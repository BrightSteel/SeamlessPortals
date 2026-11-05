# SeamlessPortals

See-through portals for Minecraft Java 26.1.2, 26.2 and 26.3. Nether portals, End portals and
admin-made custom portals show the world on the other side, live, and you cross them by just
walking through: no swirl, no loading screen, no stopping. The same goes for riding through (a
horse, boat, minecart, pig or anything else you sit on, with everyone on it), flying through with
an elytra, and animals on your lead come along with you.

The project ships two things:

- **A Fabric mod** (client and server in one jar). On the client it renders the far side of
  portals and makes the crossing seamless. On a Fabric server or in singleplayer it also streams
  the far side to players, handles travel and adds `/sportal`.
- **A Paper plugin**, the server half for Paper servers. Players with the mod get the full
  experience; players without it see and use portals as in vanilla.

## Install

| Where | What |
|---|---|
| Singleplayer | The Fabric mod (plus Fabric API) |
| Fabric server | The Fabric mod on the server, and on each client that should see through portals |
| Paper server | The Paper plugin on the server; the Fabric mod on clients |

Each jar is built for one Minecraft version, named `seamlessportals-<fabric|paper>-mc<version>-<release>.jar`.
Requires Java 25.

## Custom portals

`/seamlessportals` (alias `/sportal`) creates portals of any size and fill out of any opening,
and links them to each other or to a fixed or random spot, within one world or across worlds.

```
/sportal wand                          get the wand; left/right click two corners
/sportal create <name> [fill]          a portal from the selection (you stand on its front)
/sportal createat <name> <world> <x1 y1 z1> <x2 y2 z2> <facing> [up] [fill]
/sportal move <name> <world> <x1 y1 z1> <x2 y2 z2> <facing> [up]   (keeps its links)
/sportal link <a> <b>                  two-way pair
/sportal target <name> here | <world> <x y z> [yaw pitch] | random
/sportal fill|public|unlink|delete|info|tp|list ...
```

Fills: `nether_portal`, `end_portal`, `end_gateway`, `water`, `lava`, `window`, `starfield`, `aether`.
`window` places no blocks: only players with the mod see anything there (it still takes everyone
through). `starfield` is the same, and players without the mod see an end gateway's starfield
exactly the size of the opening (insets included): an item display sent only to them, drawn by the
SeamlessPortals resource pack. The server writes that pack at startup (`plugins/SeamlessPortals/resource
pack.zip` on Paper, `config/seamlessportals/resource pack.zip` on Fabric); add it to the server's
resource packs. On 26.3 its item shader draws the real end gateway effect from the game's own
`end_portal` texture; older versions show that texture flat, and without the pack it's a black slab.
`aether` is a blue portal: no blocks, like `starfield`, and players without the mod see the game's
own nether portal swirl tinted blue (from the same pack); with the mod, its swirl is blue too. It
works any way up.

Permissions (Paper, or a permissions mod on Fabric; operators have them all by default):
`seamlessportals.admin` to manage portals, `seamlessportals.use.<name>` to use one,
`seamlessportals.see.<name>` to see through it only. `/sportal public <name> true|see|false` opens a
portal to everyone.

## API for other plugins and mods

Other plugins (Paper) and mods (Fabric server) can create, move, link and delete custom portals,
look them up, and hook into portal changes and trips. Every change works like the matching
`/sportal` command and fires the same events.

**Paper.** Compile against `seamlessportals-paper-api-<release>.jar` (from `build/dist`), add
`depend: [SeamlessPortals]` (or `softdepend`) to your plugin.yml, then:

```java
SeamlessPortalsApi api = SeamlessPortalsApi.get();
Portal portal = api.create("spawn", corner1, corner2, BlockFace.NORTH);
api.link("spawn", "hub");
api.move("spawn", newCorner1, newCorner2, BlockFace.EAST, null);
api.delete("spawn");
```

Events in `dev.ryan.seamlessportals.api.event`, all cancellable: `PortalCreateEvent`,
`PortalChangeEvent` (moved, refilled, linked, retargeted, made public or private),
`PortalDeleteEvent`, and `PortalTravelEvent`, which fires whenever SeamlessPortals moves something
through a portal (custom portals, and mod players through nether and end portals). Its
`setTo` sends the trip somewhere else, as an ordinary teleport.

**Custom blocks in portal views (Paper).** Portal views send the far side's chunks in their own
packets, of another world than the player's, so plugins that show blocks as others by rewriting
chunk packets (custom blocks drawn from note block or tripwire states, say) never see them, and
those blocks show as the vanilla block underneath. Such a plugin registers a `BlockLooks` to be
asked instead, with the right world:

```java
SeamlessPortalsApi.get().addBlockLooks(new BlockLooks() {
	public boolean mayChange(BlockData state) { return state.getMaterial() == Material.NOTE_BLOCK; }
	public BlockData looks(World world, int x, int y, int z, BlockData state) { return myCustomLookAt(world, x, y, z); }
});
```

Plugins whose custom blocks are real block states on the server need nothing: portal views show
what's there. (Running portal views through other plugins' packet listeners instead wouldn't
work: they'd read the chunk with the player's own world and height.)

**Fabric.** Depend on the SeamlessPortals mod; `dev.ryan.seamlessportals.api.SeamlessPortalsApi` has the
same operations with Minecraft types, and `SeamlessPortalsEvents` has `CREATE`, `CHANGE`, `DELETE`
and `TRAVEL` (return false, or null for a trip, to stop it).

Failures (a taken name, a blocked or oversized opening, a cancelled event) throw
`PortalException` with a message fit to show a player. Call the API from the server thread.

## Configuration

Server: `plugins/SeamlessPortals/config.yml` on Paper, `config/seamlessportals-server.properties` on Fabric
(same settings, camelCase names there).

| Setting | Default | |
|---|---|---|
| `scan-radius` | 24 | Blocks around a player searched for portals |
| `max-portals` | 4 | Portals per player that show their far side; the rest stay vanilla |
| `streamed-destinations` | 2 | Different destinations per player whose chunks and mobs are streamed |
| `view-radius-chunks` | 8 | Chunk radius streamed around each destination (1-8) |
| `chunks-per-scan` | 8 | Chunks sent per player every half second |
| `entity-radius` | 24 | Blocks around a destination whose mobs, players and items are mirrored |
| `max-portal-size` | 21 | Bigger portals stay vanilla |
| `tick-destination` | false | Keep the far side ticking while someone looks through |
| `seamless-travel` | true | Walk-through travel for mod players; false keeps vanilla travel |

Client: `config/seamlessportals-client.json`, or in game from the mod list with
[Mod Menu](https://modrinth.com/mod/modmenu) installed.

| Setting | Default | |
|---|---|---|
| `maxLivePortals` | 2 | Portals rendered live each frame; others show a flat card |
| `liveViewDistance` | 48 | Blocks within which a portal gets a live view |
| `remoteRenderDistance` | 8 | Render distance through portals (capped at your own) |
| `maxPortalSize` | 21 | Bigger portals stay vanilla on this client |
| `portalParticles` | 0.25 | Share of swirl particles a linked portal keeps (0 = none) |
| `streamedPortalOpacity` | 0 | How opaque the portal's swirl or starfield is over a live view (0 = clear) |
| `offlinePreviews` | true | Previews on servers without SeamlessPortals (below) |
| `offlinePortalOpacity` | 0.45 | How opaque the portal's swirl or starfield is over such a preview (0-1) |
| `offlineCacheMb` | 256 | Disk space kept for remembered chunks, per server |

## Servers without SeamlessPortals

On a multiplayer server without the plugin or the server half, the mod remembers each Nether or
End portal trip you take: where the server set you down, and the terrain around both ends (in
`seamlessportals/<server>/` in the game folder). From then on that portal shows the far side as you
last saw it, with no mobs and under a see-through swirl (a starfield on End portals). Travel stays
vanilla: you wait in the portal, then see the loading screen. Portals you've never been through
look like normal portals; nothing is guessed.

## Upgrading from NetherView

This project was called NetherView. On first start, files saved under the old name are moved to
the new one: the plugin folder (`plugins/NetherView` with its portals), the Fabric server's config
and per-world portals, the client config and its remembered chunks. Two things need changing by
hand: permissions are now `seamlessportals.*` (were `netherview.*`), and the command is
`/seamlessportals` or `/sportal` (was `/nvportal`).

## Building

```
./build-all-versions.sh
```

builds the mod and the plugin for every version in `versions/` into `build/dist`. For a single
version: `./gradlew build -Pmc=26.3`.

## License

MIT. See [LICENSE](LICENSE).
