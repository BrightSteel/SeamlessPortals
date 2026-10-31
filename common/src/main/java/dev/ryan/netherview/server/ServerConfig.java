package dev.ryan.netherview.server;

/**
 * Server-side limits. Each platform loads these from its own config file (Fabric:
 * {@code config/netherview-server.properties}, Paper: {@code plugins/NetherView/config.yml}).
 *
 * @param scanRadius            blocks around a player searched for portals to link
 * @param streamedDestinations  how many distinct portal destinations per player get chunks
 *                              and entities streamed (nearest first); matches how many live
 *                              views a client is likely to render
 * @param viewRadiusChunks      chunk radius streamed around each destination
 * @param chunksPerScan         chunks sent per player per scan (every 10 ticks), all destinations together
 * @param entityRadius          blocks around each destination whose entities are mirrored (0 = off)
 * @param maxPortalSize         portals wider or taller than this (blocks of portal, not frame)
 *                              are left as vanilla portals: no view, vanilla travel
 * @param tickDestination       whether the streamed area around a destination is kept ticking
 *                              (mobs move and spawn, crops grow, farms run) while someone looks
 *                              through; off by default so a portal you merely stand near doesn't
 *                              run the other side, which vanilla never does
 * @param seamlessTravel        whether players with the client mod cross linked portals by
 *                              walking through their plane (instant, momentum kept); off means
 *                              vanilla travel for everyone, the view is still shown
 */
public record ServerConfig(int scanRadius, int streamedDestinations, int viewRadiusChunks, int chunksPerScan, double entityRadius, int maxPortalSize,
		boolean tickDestination, boolean seamlessTravel) {
	public static final ServerConfig DEFAULTS = new ServerConfig(24, 2, 4, 8, 24.0, 21, false, true);

	/** Clamps values to sane ranges so a bad config can't make the server do unbounded work. */
	public ServerConfig {
		scanRadius = Math.clamp(scanRadius, 4, 64);
		streamedDestinations = Math.clamp(streamedDestinations, 1, 8);
		viewRadiusChunks = Math.clamp(viewRadiusChunks, 1, 8);
		chunksPerScan = Math.clamp(chunksPerScan, 1, 64);
		entityRadius = Math.clamp(entityRadius, 0.0, 64.0);
		maxPortalSize = Math.clamp(maxPortalSize, 2, 21);
	}
}
