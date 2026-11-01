# NetherView

See-through portals for Minecraft Java 26.1.2, 26.2 and 26.3. Nether portals, End portals and
admin-made custom portals show the world on the other side, live, and you cross them by just
walking through: no swirl, no loading screen, no stopping.

The project ships two things:

- **A Fabric mod** (client and server in one jar). On the client it renders the far side of
  portals and makes the crossing seamless. On a Fabric server or in singleplayer it also streams
  the far side to players, handles travel and adds `/nvportal`.
- **A Paper plugin**, the server half for Paper servers. Players with the mod get the full
  experience; players without it still see through portals (see `fallback-views` below).

## Install

| Where | What |
|---|---|
| Singleplayer | The Fabric mod (plus Fabric API) |
| Fabric server | The Fabric mod on the server, and on each client that should see through portals |
| Paper server | The Paper plugin on the server; the Fabric mod on clients |

Each jar is built for one Minecraft version, named `netherview-<fabric|paper>-mc<version>-<release>.jar`.
Requires Java 25.

## Custom portals

`/nvportal` (alias `/nvp`) creates portals of any size and fill out of any opening, and links them
to each other or to a fixed or random spot, within one world or across worlds.

```
/nvportal wand                          get the wand; left/right click two corners
/nvportal create <name> [fill]          a portal from the selection (you stand on its front)
/nvportal createat <name> <world> <x1 y1 z1> <x2 y2 z2> <facing> [up] [fill]
/nvportal link <a> <b>                  two-way pair
/nvportal target <name> here | <world> <x y z> [yaw pitch] | random
/nvportal fill|public|unlink|delete|info|tp|list ...
```

Fills: `nether_portal`, `end_portal`, `end_gateway`, `water`, `lava`.

Permissions (Paper, or a permissions mod on Fabric; operators have them all by default):
`netherview.admin` to manage portals, `netherview.use.<name>` to use one,
`netherview.see.<name>` to see through it only. `/nvportal public <name> true|see|false` opens a
portal to everyone.

## Configuration

Server: `plugins/NetherView/config.yml` on Paper, `config/netherview-server.properties` on Fabric
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
| `fallback-views` | true | Players without the mod see through portals via fake blocks |
| `fallback-depth` | 24 | How far behind the portal that copy reaches (2-48) |

Client: `config/netherview-client.json`, or in game from the mod list with
[Mod Menu](https://modrinth.com/mod/modmenu) installed.

| Setting | Default | |
|---|---|---|
| `maxLivePortals` | 2 | Portals rendered live each frame; others show a flat card |
| `liveViewDistance` | 48 | Blocks within which a portal gets a live view |
| `remoteRenderDistance` | 8 | Render distance through portals (capped at your own) |
| `maxPortalSize` | 21 | Bigger portals stay vanilla on this client |
| `portalParticles` | 0.25 | Share of swirl particles a linked portal keeps (0 = none) |
| `streamedPortalOpacity` | 0 | How opaque the portal's swirl or starfield is over a live view (0 = clear) |
| `offlinePreviews` | true | Previews on servers without NetherView (below) |
| `offlinePortalOpacity` | 0.45 | How opaque the portal's swirl or starfield is over such a preview (0-1) |
| `offlineCacheMb` | 256 | Disk space kept for remembered chunks, per server |

## Servers without NetherView

On a multiplayer server without the plugin or the server half, the mod remembers each Nether or
End portal trip you take: where the server set you down, and the terrain around both ends (in
`netherview/<server>/` in the game folder). From then on that portal shows the far side as you
last saw it, with no mobs and under a see-through swirl (a starfield on End portals). Travel stays
vanilla (you wait in the portal), but a remembered trip lands without the loading screen. Portals
you've never been through look like normal portals; nothing is guessed.

## Building

```
./build-all-versions.sh
```

builds the mod and the plugin for every version in `versions/` into `build/dist`. For a single
version: `./gradlew build -Pmc=26.3`.
