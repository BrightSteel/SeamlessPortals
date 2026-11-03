package dev.ryan.seamlessportals.client;

import dev.ryan.seamlessportals.compat.ClientVersioned;
import dev.ryan.seamlessportals.PortalGeometry;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.ryan.seamlessportals.net.PortalEnd;
import dev.ryan.seamlessportals.net.PortalFill;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.ryan.seamlessportals.client.RemoteDimensions.Link;
import dev.ryan.seamlessportals.client.mixin.GameRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.MinecartRenderState;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws the far side of a portal in two steps each frame.
 *
 * <p><b>1. Remote pass</b> (right after the level projection is set): the remote
 * dimension is rendered into its own offscreen framebuffer from a camera that is the
 * player's camera carried through the portal pair. It uses the player's exact projection,
 * so the two images line up pixel-for-pixel, with one change: the near clip plane is
 * swapped for the destination portal's plane (Lengyel's oblique-frustum trick, adapted to
 * Minecraft's reversed-Z depth). Everything between the remote camera and the destination
 * portal is clipped away, the same way it is hidden behind you in the real world.</p>
 *
 * <p><b>2. Composite</b> (after the level, before the hand): the portal opening is drawn
 * into the main framebuffer as a real 3D quad, depth-tested against the world, and each
 * of its fragments copies the offscreen pixel at the same screen position
 * ({@code gl_FragCoord / ScreenSize}). That makes the opening a window into the other
 * dimension, correct from any angle, and properly hidden behind anything in front of it.</p>
 */
public final class PortalViewRenderer {
	private static final Logger LOGGER = LoggerFactory.getLogger("seamlessportals");

	/**
	 * The window and the remote clip plane both sit on the portal's centre plane, like a
	 * hole in a wall: the near half of the opening's inner faces comes from this side's
	 * frame, the far half from the destination frame seen through the window.
	 */
	private static final double PORTAL_PLANE_OFFSET = 0.0;
	/**
	 * How far the window box is drawn toward the camera from the portal's plane (blocks): a few
	 * hundredths of a pixel, so a portal lying on a surface (a floor level with a slab's top, say)
	 * is drawn over it instead of flickering against it. The clip plane beyond stays on the plane.
	 */
	private static final double WINDOW_BIAS = 0.002;
	/** Depth of the window box behind the opening; well past the camera's 0.05 near plane. */
	private static final double WINDOW_DEPTH = 0.1;
	/**
	 * How far the window box reaches past the opening's edges (blocks), so it surely covers it:
	 * its shaders draw only what's seen through the opening itself (see {@link #opening}).
	 */
	private static final double WINDOW_OVERLAP = 1.0 / 64.0;
	/** The portal surface over a view sits this far in front of the window (blocks), clear of its depth. */
	private static final double SWIRL_OFFSET = 0.01;
	/** Vanilla's end portal textures: the backdrop and the star layers its shader scrolls. */
	private static final Identifier END_SKY_TEXTURE = Identifier.withDefaultNamespace("textures/environment/end_sky.png");
	private static final Identifier END_PORTAL_TEXTURE = Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png");
	/** How far in front of the eye the in-portal surface sits (blocks): past the 0.05 near plane. */
	private static final float FULLSCREEN_SURFACE_DISTANCE = 0.1F;
	/** A live view's surface fades out over this distance from the portal plane (blocks), gone as you walk through. */
	private static final double STREAMED_SURFACE_FADE = 1.0;
	/** Within this distance of the plane (blocks), an eye inside the opening sees the surface over the whole screen. */
	private static final double INSIDE_PORTAL_DEPTH = 0.4;
	/** Keeps the oblique near plane from passing through the remote camera when standing in a portal. */
	private static final double MIN_CLIP_DISTANCE = 0.05;

	// ClientVersioned.pipeline(location, vertex shader, fragment shader, transforms, vertex format,
	// samplers, cull, translucent, depth test always passes): built the way the running version's
	// pipeline API wants.
	private static final RenderPipeline COMPOSITE_PIPELINE = ClientVersioned.pipeline("pipeline/portal_view",
			id("core/portal_view"), id("core/portal_view"), true, DefaultVertexFormat.POSITION, 1, false, false, false);

	private static final RenderPipeline MERGE_PIPELINE = ClientVersioned.pipeline("pipeline/bridge_merge",
			Identifier.withDefaultNamespace("core/screenquad"), id("core/bridge_merge"), false, null, 2, true, false, false);

	private static final RenderPipeline REPLACE_PIPELINE = ClientVersioned.pipeline("pipeline/remote_fullscreen",
			Identifier.withDefaultNamespace("core/screenquad"), id("core/remote_fullscreen"), false, null, 2, true, false, true);
	/** How long the camera may stay "through" a portal waiting for the server to move the player. */
	private static final long CROSSED_TIMEOUT_MS = 2000L;

	private static final RenderPipeline TEXTURED_CARD_PIPELINE = ClientVersioned.pipeline("pipeline/portal_card_tex",
			id("core/portal_card_tex"), id("core/portal_card_tex"), true, DefaultVertexFormat.POSITION_TEX, 1, false, true, false);

	/** The end portal's starfield (vanilla's shader, made translucent) on a card. */
	private static final RenderPipeline STARFIELD_CARD_PIPELINE = ClientVersioned.pipeline("pipeline/portal_card_end",
			id("core/portal_card_end"), id("core/portal_card_end"), true, DefaultVertexFormat.POSITION_TEX, 2, false, true, false);

	private static final RenderPipeline CARD_PIPELINE = ClientVersioned.pipeline("pipeline/portal_card",
			id("core/portal_view"), id("core/portal_card"), true, DefaultVertexFormat.POSITION, 0, false, false, false);

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("seamlessportals", path);
	}

	/** Extra margin around a portal's screen rectangle when narrowing a view's culling frustum (NDC units). */
	private static final float CULL_RECT_MARGIN = 0.05F;
	/** How far beyond the opening a live portal is assumed to hide what's behind it (its frame). */
	private static final double FRAME_OCCLUDER_MARGIN = 0.9;
	/** Smallest half-size (NDC) of a narrowed culling rectangle; see {@link #narrowedToRect}. */
	private static final float MIN_CULL_HALF_SIZE = 0.1F;

	/** The portal the view being rendered is seen through, if any (see {@link #addRiddenVehicle}). */
	private static PortalGeometry lookingThrough;
	/** How near a portal the ridden vehicle must be to be drawn beyond it too (blocks). */
	private static final double RIDDEN_VEHICLE_REACH = 2.0;

	/** A live portal view rendered this frame, waiting to be composited. */
	private record Pass(Link link, RemoteDimension view, RenderTarget target) {
	}

	private static final List<Pass> passes = new ArrayList<>();
	/** Within this many blocks of a portal whose window isn't drawn, see {@link #warmBehind}. */
	private static final double WARM_DISTANCE = 2.5;
	/** Within this many blocks of a portal, the way back through it is prepared (see {@link RemoteDimensions#prepareWayBack}). */
	private static final double PREPARE_DISTANCE = 12.0;
	private static final int WARM_INTERVAL_FRAMES = 3;
	private static int warmFrames;
	private static final List<Link> cards = new ArrayList<>();
	/**
	 * Which view each live portal (by its whole opening: two portals can share a lowest block,
	 * as a doorway and a floor window do) used last frame, so it keeps its meshes and visibility.
	 */
	private static Map<PortalEnd, RemoteDimension> assignments = new HashMap<>();

	/** The view the portal opening {@code source} was last drawn with, if any. */
	static RemoteDimension assignedView(PortalEnd source) {
		return assignments.get(source);
	}

	private static Link activeLink;
	private static RemoteDimension bridgeRendered;
	/** Set once the camera has passed through a linked portal and until the dimension actually changes. */
	private static Link crossedLink;
	private static long crossedAt;
	private static boolean activeFullscreen;
	/** See {@link #arrivedThrough}. */
	private static Vec3 arrivalCenter;
	private static Vec3 arrivalOutward;
	private static long arrivalUntil;
	private static boolean arrivalPending;
	private static final long ARRIVAL_IGNORE_MS = 1500L;
	private static final double ARRIVAL_CLEAR_DISTANCE = 0.25;
	private static Vec3 lastEye;
	private static RemoteDimension activeRemote;
	private static GpuBufferSlice levelProjectionBuffer;
	/** This frame's {@link #projectionCenter} and the eye it puts in the world. */
	private static Vector3f projectionCenter = new Vector3f();
	private static Vec3 visualEye = Vec3.ZERO;
	private static boolean loggedFailure;
	/** When each live portal (by its end) became live, for {@link #EMPTY_VIEW_WAIT_MS}. */
	private static final Map<PortalEnd, Long> LIVE_SINCE = new HashMap<>();
	/** How long a blockless portal waits for its view to have terrain before it's drawn anyway (ms). */
	private static final long EMPTY_VIEW_WAIT_MS = 1500;
	/** The live portals (by their end) whose views have been drawn: never hidden again while live. */
	private static final Set<PortalEnd> DRAWN = new HashSet<>();
	private static RenderTarget lastRemoteTarget;
	private static RenderTarget leftBehind;

	private PortalViewRenderer() {
	}

	// ── 1. remote pass ──────────────────────────────────────────────────────

	public static void renderRemoteView(Matrix4f levelProjection) {
		PerfLog.frameStart();
		activeLink = null;
		activeRemote = null;
		bridgeRendered = null;
		passes.clear();
		cards.clear();
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) {
			return;
		}
		Camera main = ClientVersioned.mainCamera();
		levelProjectionBuffer = RenderSystem.getProjectionMatrixBuffer();
		projectionCenter = projectionCenter(levelProjection);
		visualEye = main.position().add(toWorld(main, projectionCenter));
		renderBridge(minecraft, main, levelProjection);
		trackCrossing(visualEye);
		activeFullscreen = false;
		if (crossedLink != null) {
			renderCrossed(minecraft, main, levelProjection);
			return;
		}
		if (DevToggles.on("skipRemote")) {
			return;
		}
		selectAndRender(minecraft, main, levelProjection);
	}

	/**
	 * Just after walking through a portal, the main renderer has no chunk meshes yet for the
	 * new level, but the remote renderer that was showing it does. Until the main renderer
	 * catches up, render the remote copy from the player's own camera (same projection, no
	 * portal transform, no clip plane) so {@link #composite()} can fill the gaps.
	 */
	private static void renderBridge(Minecraft minecraft, Camera main, Matrix4f levelProjection) {
		RemoteDimension bridge = RemoteDimensions.bridge();
		if (bridge == null || bridge.level.dimension() != minecraft.level.dimension()) {
			return;
		}
		try {
			bridge.camera.place(main, bridge.level, main.position(), main.rotation());
			renderLevelInto(minecraft, main, bridge, main.position(), new Matrix4f(levelProjection));
			bridgeRendered = bridge;
		} catch (RuntimeException e) {
			if (!loggedFailure) {
				loggedFailure = true;
				LOGGER.error("Bridge pass failed", e);
			}
		}
	}

	/**
	 * The camera passing through a linked portal's opening. The server moves the player one
	 * round trip later; until then the frame is drawn entirely from the other side, so the
	 * crossing itself is seamless. Crossing back cancels it.
	 */
	private static void trackCrossing(Vec3 eye) {
		Vec3 previous = lastEye;
		lastEye = eye;
		if (crossedLink != null && Util.getMillis() - crossedAt > CROSSED_TIMEOUT_MS) {
			crossedLink = null;
		}
		if (arrivalPending) {
			arrivalPending = false;
			if (crossedLink == null && eye.subtract(arrivalCenter).dot(arrivalOutward) < 0) {
				// Arrived with the camera still behind the plane of the portal come out of: as
				// far as the eye goes, the crossing isn't over. Draw it as crossed the other
				// way (the world left, the new one in the opening) until the camera is out.
				for (Link link : RemoteDimensions.links()) {
					if (link.geometry().sourceCenter().distanceToSqr(arrivalCenter) < 0.01 && RemoteDimensions.viewFor(link.link(), link.dest()) != null) {
						crossedLink = link;
						crossedAt = Util.getMillis();
						return;
					}
				}
			}
		}
		if (previous == null || previous.distanceToSqr(eye) > 16.0) {
			return;
		}
		if (crossedLink != null) {
			if (crossedLink.geometry().crossesSource(previous, eye)) {
				crossedLink = null;
			}
			return;
		}
		if (arrivalCenter != null && (Util.getMillis() > arrivalUntil || eye.subtract(arrivalCenter).dot(arrivalOutward) > ARRIVAL_CLEAR_DISTANCE)) {
			arrivalCenter = null;
		}
		for (Link link : RemoteDimensions.links()) {
			if (arrivalCenter != null && link.geometry().sourceCenter().distanceToSqr(arrivalCenter) < 0.01
					&& eye.subtract(previous).dot(arrivalOutward) > 0) {
				continue; // still coming out of the portal just arrived through, not going into it
			}
			// End portals are fallen into feet first: the server moves the player before the
			// camera reaches the surface, so there is no crossing to preview.
			// A recorded portal (no server half) keeps vanilla travel: the server won't move a player
			// for walking through, so there's no crossing to preview.
			if (!link.offline() && !link.geometry().horizontal() && link.geometry().entersSource(previous, eye) && RemoteDimensions.viewFor(link.link(), link.dest()) != null) {
				crossedLink = link;
				crossedAt = Util.getMillis();
				return;
			}
		}
	}

	/** Through the portal: the far side, from the carried-through camera, with no clip plane (it would be behind us). */
	private static void renderCrossed(Minecraft minecraft, Camera main, Matrix4f levelProjection) {
		Link link = crossedLink;
		RemoteDimension remote = RemoteDimensions.viewFor(link.link(), link.dest());
		if (remote == null) {
			return;
		}
		try {
			PortalGeometry geometry = link.geometry();
			Vec3 remoteEye = geometry.toDest(main.position());
			remote.camera.place(main, remote.level, remoteEye, geometry.toDest(main.rotation()));
			lookingThrough = geometry;
			try {
				renderLevelInto(minecraft, main, remote, remoteEye, new Matrix4f(levelProjection));
			} finally {
				lookingThrough = null;
			}
			activeLink = link;
			activeRemote = remote;
			activeFullscreen = true;
			lastRemoteTarget = remote.target;
		} catch (RuntimeException e) {
			if (!loggedFailure) {
				loggedFailure = true;
				LOGGER.error("Crossed-portal pass failed", e);
			}
		}
	}

	/**
	 * The player has just come out of the portal at {@code center}, heading {@code outward}.
	 * The server can move them a moment before their camera reaches the plane they went in by,
	 * so they arrive still behind this one's: carrying on through it is the same trip, not a
	 * new one into wherever this portal leads.
	 */
	static void arrivedThrough(Vec3 center, Vec3 outward) {
		arrivalCenter = center;
		arrivalOutward = outward;
		arrivalUntil = Util.getMillis() + ARRIVAL_IGNORE_MS;
		arrivalPending = true;
	}

	/** The player is through a portal and waiting for the server to catch up (the destination is being shown). */
	static Link crossedLink() {
		return crossedLink;
	}

	/**
	 * Choose this frame's live portals (nearest visible first, up to the configured budget and
	 * distance), give each a view, and render them. Other visible linked portals get a card.
	 */
	private static void selectAndRender(Minecraft minecraft, Camera main, Matrix4f levelProjection) {
		ClientConfig config = ClientConfig.get();
		Frustum frustum = main.getCullFrustum();
		Vec3 eye = main.position();
		List<Link> visible = new ArrayList<>();
		for (Link link : RemoteDimensions.links()) {
			if (frustum.isVisible(link.geometry().sourceBox().inflate(0.25))
					&& isFill(minecraft.level.getBlockState(link.link().min()), link.link().source().fill())) {
				visible.add(link);
			}
		}
		visible.sort(Comparator.comparingDouble(link -> link.geometry().sourceCenter().distanceToSqr(eye)));

		List<Link> live = new ArrayList<>();
		List<Link> occluding = new ArrayList<>();
		List<float[]> liveQuads = new ArrayList<>();
		Matrix4f viewProjection = new Matrix4f(levelProjection).mul(main.getViewRotationMatrix(new Matrix4f()));
		double maxDistanceSqr = config.effectiveLiveViewDistance() * config.effectiveLiveViewDistance();
		for (Link link : visible) {
			float[] quad = screenQuad(link, eye, viewProjection, 0.0);
			boolean hidden = false;
			for (int i = 0; i < liveQuads.size() && quad != null && !hidden; i++) {
				hidden = covers(liveQuads.get(i), quad) && behind(link, occluding.get(i), eye);
			}
			if (hidden) {
				continue; // entirely behind a nearer live portal (e.g. a chain of portals): nothing to see
			}
			if (live.size() < config.effectiveMaxLivePortals() && link.geometry().sourceCenter().distanceToSqr(eye) <= maxDistanceSqr) {
				live.add(link);
				// The occluder is the window plus its solid obsidian frame (most of a block wide;
				// the frame's corner blocks are optional, hence a little less than a full block).
				float[] occluder = screenQuad(link, eye, viewProjection, FRAME_OCCLUDER_MARGIN);
				if (occluder != null) {
					liveQuads.add(occluder);
					occluding.add(link);
				}
			} else {
				cards.add(link);
			}
		}

		Map<PortalEnd, RemoteDimension> assigned = assignViews(live);
		long now = Util.getMillis();
		Set<PortalEnd> liveSources = new HashSet<>();
		live.forEach(link -> liveSources.add(link.link().source()));
		LIVE_SINCE.keySet().retainAll(liveSources);
		DRAWN.retainAll(liveSources);
		// Before the windows: a warm-up pass may use a window's own view, whose image must end
		// up being the window's.
		warmBehind(minecraft, main, levelProjection);
		for (Link link : live) {
			RemoteDimension view = assigned.get(link.link().source());
			Vector4f rect = screenRect(link, main, levelProjection);
			if (rect == null) {
				continue; // entirely off screen after all
			}
			try {
				renderRemote(minecraft, main, link, view, levelProjection, rect);
				// A portal with no blocks of its own, whose view isn't ready yet (just made: no
				// terrain, or some of it still meshing, which would show half a room): not drawn,
				// so whatever lies behind it shows meanwhile, not an empty sky or a room's floor
				// alone. What's behind can be made to match (PaperTardis does). Not for long,
				// though: a view of the void never has terrain to show.
				long since = LIVE_SINCE.computeIfAbsent(link.link().source(), source -> now);
				if (link.link().source().fill().blockless() && !DRAWN.contains(link.link().source()) && !ready(link, view) && now - since < EMPTY_VIEW_WAIT_MS) {
					continue;
				}
				DRAWN.add(link.link().source());
				passes.add(new Pass(link, view, view.target));
				lastRemoteTarget = view.target;
			} catch (RuntimeException e) {
				if (!loggedFailure) {
					loggedFailure = true;
					LOGGER.error("Remote dimension pass failed; portal will show a card", e);
				}
				cards.add(link);
			}
		}
		assignments = assigned;
	}

	/**
	 * Whether {@code view} has the far end of {@code link} meshed: the sections its opening's in
	 * and those beside them (its chunk's neighbours'), so it shows what's around the far end
	 * whole, entities too (they're drawn only in meshed sections), not a part of it.
	 */
	private static boolean ready(Link link, RemoteDimension view) {
		if (view.renderer.renderedSections() == 0) {
			return false;
		}
		Set<Long> sections = new HashSet<>();
		for (BlockPos pos : link.link().dest().blocks()) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos at = pos.offset(dx * 16, 0, dz * 16);
					if (sections.add(SectionPos.asLong(at)) && !view.renderer.meshed(at)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/**
	 * What the player sees right after going through is mostly not what the windows showed
	 * before: through the window back, their own level (a view created only then), and when
	 * they back in, the far side they'll face (that window was behind them, never drawn).
	 * Near a portal, take turns rendering these now and then, not shown, just so they're
	 * meshed in time: a prepared copy of their own level ({@link RemoteDimensions#prepareWayBack})
	 * from their own camera, roughly what the window back will show; and within a step or two
	 * of the portal, its far side from the camera they'd have through it (as
	 * {@link #renderCrossed} will).
	 */
	private static void warmBehind(Minecraft minecraft, Camera main, Matrix4f levelProjection) {
		int phase = ++warmFrames % WARM_INTERVAL_FRAMES;
		Vec3 eye = main.position();
		Link nearest = null;
		for (Link link : RemoteDimensions.links()) {
			if (!link.geometry().horizontal() && link.geometry().sourceBox().inflate(PREPARE_DISTANCE).contains(eye)
					&& (nearest == null || link.geometry().sourceCenter().distanceToSqr(eye) < nearest.geometry().sourceCenter().distanceToSqr(eye))) {
				nearest = link;
			}
		}
		if (nearest != null) {
			Link link = nearest;
			// Prepared from a way off: its first render is a stall, better not right at the portal.
			// No way back is shown after a recorded trip (only a recorded trip back would show one).
			RemoteDimension back = link.offline() ? null : RemoteDimensions.prepareWayBack(link);
			RemoteDimension remote = RemoteDimensions.viewFor(link.link(), link.dest());
			// Even with its window on screen: what a window shows is only what lies within it,
			// and a sliver of window (backing in, looking down) meshes next to nothing.
			boolean farSide = remote != null && link.geometry().sourceBox().inflate(WARM_DISTANCE).contains(eye);
			try {
				if (phase == 0 && farSide) {
					PortalGeometry geometry = link.geometry();
					Vec3 remoteEye = geometry.toDest(eye);
					remote.camera.place(main, remote.level, remoteEye, geometry.toDest(main.rotation()));
					renderLevelInto(minecraft, main, remote, remoteEye, new Matrix4f(levelProjection));
				} else if (phase == 1 && back != null) {
					back.camera.place(main, back.level, eye, main.rotation());
					renderLevelInto(minecraft, main, back, eye, new Matrix4f(levelProjection));
				}
			} catch (RuntimeException e) {
				if (!loggedFailure) {
					loggedFailure = true;
					LOGGER.error("Warm-up pass failed", e);
				}
			}
		}
	}

	/** Whether the opening still holds its fill (a broken portal stops being a window). */
	private static boolean isFill(BlockState state, PortalFill fill) {
		return switch (fill) {
			case NETHER_PORTAL -> state.is(Blocks.NETHER_PORTAL);
			case END_PORTAL -> state.is(Blocks.END_PORTAL);
			case END_GATEWAY -> state.is(Blocks.END_GATEWAY);
			case WATER -> state.is(Blocks.WATER);
			case LAVA -> state.is(Blocks.LAVA);
			case NONE -> state.isAir();
			case WINDOW, STARFIELD -> true; // no blocks of its own to lose
		};
	}

	/**
	 * The portal opening's corners on screen (NDC x, y pairs, in order around the opening),
	 * or null if any corner is behind the camera.
	 */
	private static float[] screenQuad(Link link, Vec3 eye, Matrix4f viewProjection, double margin) {
		Vec3[] corners = link.geometry().sourceCorners(eye, PORTAL_PLANE_OFFSET, margin);
		float[] quad = new float[8];
		for (int i = 0; i < 4; i++) {
			Vec3 relative = corners[i].subtract(eye);
			Vector4f clip = viewProjection.transform(new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1.0F));
			if (clip.w < 0.05F) {
				return null;
			}
			quad[i * 2] = clip.x / clip.w;
			quad[i * 2 + 1] = clip.y / clip.w;
		}
		return quad;
	}

	/** Whether every corner of {@code inner} lies inside the convex quad {@code outer}. */
	/**
	 * Whether {@code link}'s opening lies wholly beyond {@code nearer}'s plane, seen from
	 * {@code eye}: only then can the nearer one hide it (a floor window in front of a doorway
	 * may fall inside the doorway's outline on screen, but it isn't behind it).
	 */
	private static boolean behind(Link link, Link nearer, Vec3 eye) {
		Vec3 center = nearer.geometry().sourceCenter();
		Vec3 normal = nearer.geometry().sourceNormal();
		double eyeSide = eye.subtract(center).dot(normal);
		for (Vec3 corner : link.geometry().sourceCorners(eye, 0.0)) {
			if (corner.subtract(center).dot(normal) * eyeSide > 1.0E-4) {
				return false;
			}
		}
		return true;
	}

	private static boolean covers(float[] outer, float[] inner) {
		for (int i = 0; i < 4; i++) {
			if (!insideConvex(outer, inner[i * 2], inner[i * 2 + 1])) {
				return false;
			}
		}
		return true;
	}

	private static boolean insideConvex(float[] quad, float x, float y) {
		int sign = 0;
		for (int i = 0; i < 4; i++) {
			float ax = quad[i * 2];
			float ay = quad[i * 2 + 1];
			float bx = quad[(i + 1) % 4 * 2];
			float by = quad[(i + 1) % 4 * 2 + 1];
			float cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
			int side = cross > 0 ? 1 : cross < 0 ? -1 : 0;
			if (side != 0) {
				if (sign == 0) {
					sign = side;
				} else if (side != sign) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Gives each live portal a view of its destination dimension. A portal keeps last frame's
	 * view when it can (following its destination if that moved); the rest take free views,
	 * which are re-centred on their new destination.
	 */
	private static Map<PortalEnd, RemoteDimension> assignViews(List<Link> live) {
		Map<PortalEnd, RemoteDimension> assigned = new HashMap<>();
		Set<RemoteDimension> used = new HashSet<>();
		List<Link> unassigned = new ArrayList<>();
		for (Link link : live) {
			RemoteDimension previous = assignments.get(link.link().source());
			if (previous != null && previous.key == link.dest() && !previous.isClosed() && used.add(previous)) {
				// Same portal, but its destination can move (a random portal's next spot).
				RemoteDimensions.recenter(previous, ChunkPos.containing(BlockPos.containing(link.geometry().destCenter())));
				assigned.put(link.link().source(), previous);
			} else {
				unassigned.add(link);
			}
		}
		for (Link link : unassigned) {
			RemoteDimension view = null;
			for (int index = 0; view == null; index++) {
				RemoteDimension candidate = RemoteDimensions.view(link.dest(), index);
				if (used.add(candidate)) {
					view = candidate;
				}
			}
			RemoteDimensions.recenter(view, ChunkPos.containing(BlockPos.containing(link.geometry().destCenter())));
			assigned.put(link.link().source(), view);
		}
		return assigned;
	}

	/**
	 * The portal opening's bounding rectangle on screen, in NDC (xMin, yMin, xMax, yMax),
	 * or null if it is entirely off screen. If any corner is behind the camera the whole
	 * screen is returned, since the projection can't bound it.
	 */
	private static Vector4f screenRect(Link link, Camera main, Matrix4f levelProjection) {
		Matrix4f viewProjection = new Matrix4f(levelProjection).mul(main.getViewRotationMatrix(new Matrix4f()));
		float xMin = Float.MAX_VALUE;
		float yMin = Float.MAX_VALUE;
		float xMax = -Float.MAX_VALUE;
		float yMax = -Float.MAX_VALUE;
		Vec3 eye = main.position();
		for (Vec3 corner : link.geometry().sourceCorners(eye, PORTAL_PLANE_OFFSET)) {
			Vec3 relative = corner.subtract(eye);
			Vector4f clip = viewProjection.transform(new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1.0F));
			if (clip.w < 0.05F) {
				return new Vector4f(-1.0F, -1.0F, 1.0F, 1.0F);
			}
			xMin = Math.min(xMin, clip.x / clip.w);
			yMin = Math.min(yMin, clip.y / clip.w);
			xMax = Math.max(xMax, clip.x / clip.w);
			yMax = Math.max(yMax, clip.y / clip.w);
		}
		if (xMax < -1.0F || xMin > 1.0F || yMax < -1.0F || yMin > 1.0F) {
			return null;
		}
		return new Vector4f(
				Math.max(-1.0F, xMin - CULL_RECT_MARGIN), Math.max(-1.0F, yMin - CULL_RECT_MARGIN),
				Math.min(1.0F, xMax + CULL_RECT_MARGIN), Math.min(1.0F, yMax + CULL_RECT_MARGIN));
	}

	/**
	 * A projection whose frustum covers only {@code rect} of the screen: only used for culling,
	 * so the far side's sections and entities are tested against the portal's own slice of
	 * the view and a small or distant portal extracts only what can be seen through it.
	 *
	 * <p>Uses the ordinary projection, not the oblique one: vanilla's visibility pass slides
	 * the culling frustum back until an 8-block cube around the camera fits inside, which
	 * never happens once the oblique near/far planes are in it (an endless loop). The
	 * rectangle also has a minimum size, so the slide always ends a bounded distance back.</p>
	 */
	private static Matrix4f narrowedToRect(Matrix4f projection, Vector4f rect) {
		float halfWidth = Math.max(MIN_CULL_HALF_SIZE, (rect.z - rect.x) / 2.0F);
		float halfHeight = Math.max(MIN_CULL_HALF_SIZE, (rect.w - rect.y) / 2.0F);
		float centerX = (rect.z + rect.x) / 2.0F;
		float centerY = (rect.w + rect.y) / 2.0F;
		// In clip space x' = (x - cx·w) / hw (and likewise y): after the divide, the
		// rectangle's NDC range becomes [-1, 1]. Column-major arguments.
		Matrix4f clipRemap = new Matrix4f(
				1.0F / halfWidth, 0.0F, 0.0F, 0.0F,
				0.0F, 1.0F / halfHeight, 0.0F, 0.0F,
				0.0F, 0.0F, 1.0F, 0.0F,
				-centerX / halfWidth, -centerY / halfHeight, 0.0F, 1.0F);
		return clipRemap.mul(projection, new Matrix4f());
	}

	private static void renderRemote(Minecraft minecraft, Camera main, Link link, RemoteDimension remote, Matrix4f levelProjection, Vector4f rect) {
		PortalGeometry geometry = link.geometry();
		Vec3 remoteEye = geometry.toDest(main.position());
		Quaternionf orientation = geometry.toDest(main.rotation());
		// Place once to get the view rotation for the clip plane, then again with the culling
		// frustum narrowed to the portal (the rotation is unchanged, so the plane still holds).
		remote.camera.place(main, remote.level, remoteEye, orientation);
		Matrix4f projection = obliqueProjection(levelProjection, clipPlane(geometry, remote.camera, remoteEye, projectionCenter));
		remote.camera.place(main, remote.level, remoteEye, orientation, narrowedToRect(levelProjection, rect));
		remote.renderer.forceRecull();
		lookingThrough = geometry;
		try {
			renderLevelInto(minecraft, main, remote, remoteEye, projection);
		} finally {
			lookingThrough = null;
		}
	}

	/**
	 * The vehicle the player rides, drawn on the far side too while it noses through the
	 * portal: its front is past the plane, where the window covers it in the main view, and the
	 * far side doesn't have it (yet). Moved through the portal it shows there, the view's clip
	 * plane cutting off what's still on this side.
	 */
	private static void addRiddenVehicle(Minecraft minecraft, RemoteDimension remote, PortalGeometry geometry, float partialTicks) {
		LocalPlayer player = minecraft.player;
		if (player == null || !player.isPassenger()) {
			return;
		}
		Entity vehicle = player.getRootVehicle();
		if (!vehicle.getBoundingBox().inflate(RIDDEN_VEHICLE_REACH).intersects(PortalGeometry.box(geometry.sourceEnd()))) {
			return;
		}
		EntityRenderState state = minecraft.getEntityRenderDispatcher().extractEntity(vehicle, partialTicks);
		Vec3 pos = geometry.toDest(new Vec3(state.x, state.y, state.z));
		state.x = pos.x;
		state.y = pos.y;
		state.z = pos.z;
		state.leashStates = null;
		float turn = geometry.toDestRotation(vehicle.getYRot(), 0.0F)[0] - vehicle.getYRot();
		if (state instanceof LivingEntityRenderState living) {
			living.bodyRot += turn;
		} else if (state instanceof BoatRenderState boat) {
			boat.yRot += turn;
		} else if (state instanceof MinecartRenderState minecart) {
			minecart.yRot += turn;
		}
		remote.renderState.entityRenderStates.add(state);
	}

	/**
	 * Runs one full level render of {@code remote} from its (already placed) camera into
	 * its offscreen target, with the game renderer's shared state swapped to the remote's
	 * for the duration.
	 */
	private static void renderLevelInto(Minecraft minecraft, Camera main, RemoteDimension remote, Vec3 remoteEye, Matrix4f projection) {
		long start = System.nanoTime();
		try {
			renderLevelIntoTimed(minecraft, main, remote, remoteEye, projection);
		} finally {
			PerfLog.remotePass(System.nanoTime() - start);
		}
	}

	private static void renderLevelIntoTimed(Minecraft minecraft, Camera main, RemoteDimension remote, Vec3 remoteEye, Matrix4f projection) {
		GameRenderer gameRenderer = minecraft.gameRenderer;
		GameRendererAccessor gameRendererAccess = (GameRendererAccessor) gameRenderer;
		RenderTarget mainTarget = ClientVersioned.mainRenderTarget();
		remote.ensureSize(mainTarget.width, mainTarget.height);
		remote.primeProbe(remoteEye);
		Vec3 eye = main.position();

		// Extract, mirroring GameRenderer.extract but into the remote's own state.
		DeltaTracker delta = minecraft.getDeltaTracker();
		float partialTicks = delta.getGameTimeDeltaPartialTick(false);
		CameraRenderState cameraState = remote.renderState.cameraRenderState;
		ClientVersioned.extractCamera(remote.camera, cameraState, delta);
		cameraState.fogType = remote.camera.getFluidInCamera();
		cameraState.fogData = remote.fog.setupFog(
				remote.camera, Math.min(minecraft.options.getEffectiveRenderDistance(), ClientConfig.get().effectiveRemoteRenderDistance()), delta, ClientVersioned.bossOverlayWorldDarkening(partialTicks), remote.level);
		remote.renderer.extract(delta, remote.camera, partialTicks);
		if (lookingThrough != null) {
			addRiddenVehicle(minecraft, remote, lookingThrough, partialTicks);
		}
		// Extraction pointed the shared dispatchers at the remote camera; point them back.
		minecraft.getEntityRenderDispatcher().prepare(main, minecraft.crosshairPickEntity);
		minecraft.getBlockEntityRenderDispatcher().prepare(eye);

		fillLightmapState(ClientVersioned.gameRenderState().lightmapRenderState, remote, partialTicks);
		remote.lightmap.render(remote.lightmapState);

		ProjectionType savedProjectionType = RenderSystem.getProjectionType();
		GpuBufferSlice savedFog = RenderSystem.getShaderFog();
		Lightmap savedLightmap = gameRendererAccess.seamlessportals$getLightmap();
		try {
			ClientVersioned.setMainRenderTarget(remote.target);
			gameRendererAccess.seamlessportals$setLightmap(remote.lightmap);
			updateGlobals(gameRenderer, remoteEye);
			RenderSystem.setProjectionMatrix(remote.projection.getBuffer(projection), ProjectionType.PERSPECTIVE);
			remote.fog.updateBuffer(cameraState.fogData);
			ClientVersioned.renderLevel(remote.renderer.renderer, remote.resourcePool, delta, remote.renderState, remote.fog.getBuffer(FogRenderer.FogMode.WORLD));
		} finally {
			ClientVersioned.setMainRenderTarget(mainTarget);
			gameRendererAccess.seamlessportals$setLightmap(savedLightmap);
			updateGlobals(gameRenderer, ClientVersioned.gameRenderState().levelRenderState.cameraRenderState.pos);
			RenderSystem.setProjectionMatrix(levelProjectionBuffer, savedProjectionType);
			if (savedFog != null) {
				RenderSystem.setShaderFog(savedFog);
			}
			remote.fog.endFrame();
			remote.resourcePool.endFrame();
			remote.renderer.endFrame();
		}
	}

	/** Same values GameRenderer.render writes, but with the given camera position (terrain is drawn relative to it). */
	private static void updateGlobals(GameRenderer gameRenderer, Vec3 cameraPos) {
		GameRenderState state = ClientVersioned.gameRenderState();
		ClientVersioned.updateGlobals(((GameRendererAccessor) gameRenderer).seamlessportals$getGlobalSettingsUniform(),
				state.windowRenderState.width,
				state.windowRenderState.height,
				state.optionsRenderState.glintStrength,
				state.levelRenderState,
				Minecraft.getInstance().getDeltaTracker(),
				state.optionsRenderState.menuBackgroundBlurriness,
				cameraPos,
				state.optionsRenderState.textureFiltering == TextureFilteringMethod.RGSS);
	}

	/** Player effects (brightness, night vision, ...) come from the player; light colours come from the remote dimension. */
	private static void fillLightmapState(LightmapRenderState main, RemoteDimension remote, float partialTicks) {
		LightmapRenderState state = remote.lightmapState;
		EnvironmentAttributeProbe probe = remote.camera.attributeProbe();
		state.needsUpdate = true;
		state.blockFactor = main.blockFactor;
		state.brightness = main.brightness;
		state.darknessEffectScale = main.darknessEffectScale;
		state.nightVisionEffectIntensity = main.nightVisionEffectIntensity;
		state.nightVisionColor = main.nightVisionColor;
		state.bossOverlayWorldDarkening = main.bossOverlayWorldDarkening;
		state.blockLightTint = ClientVersioned.blockLightTint(probe, partialTicks);
		state.skyFactor = (Float) probe.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, partialTicks);
		state.skyLightColor = ClientVersioned.skyLightColor(probe, partialTicks);
		state.ambientColor = ClientVersioned.ambientLightColor(probe, partialTicks);
	}

	/**
	 * The destination portal plane in the remote camera's view space, as (n, d) with
	 * n·v + d ≥ 0 on the side that should stay visible (the side away from the camera).
	 */
	private static Vector4f clipPlane(PortalGeometry geometry, Camera remoteCamera, Vec3 remoteEye, Vector3f center) {
		Vec3 normal = geometry.destNormal();
		Vec3 keep = remoteEye.subtract(geometry.destCenter()).dot(normal) >= 0 ? normal.scale(-1) : normal;
		Vec3 planePoint = geometry.destCenter().add(keep.scale(PORTAL_PLANE_OFFSET));
		Vector4f viewNormal = remoteCamera.getViewRotationMatrix(new Matrix4f())
				.transform(new Vector4f((float) keep.x, (float) keep.y, (float) keep.z, 0.0F));
		// The plane must stay ahead of where the projection converges, which view bobbing
		// moves off the camera: an oblique near plane behind that point clips the wrong side.
		double atCenter = viewNormal.x * center.x + viewNormal.y * center.y + viewNormal.z * center.z;
		double distance = Math.min(keep.dot(remoteEye.subtract(planePoint)), -MIN_CLIP_DISTANCE - atCenter);
		return new Vector4f(viewNormal.x, viewNormal.y, viewNormal.z, (float) distance);
	}

	/**
	 * Where the level projection converges, in view space: the eye as actually rendered.
	 * Vanilla folds view bobbing into the projection, so this moves a few centimetres off the
	 * camera with each step (along the view's up axis, which looking down is nearly
	 * horizontal: enough to be through a portal's plane before the camera is).
	 */
	private static Vector3f projectionCenter(Matrix4f projection) {
		Vector4f r0 = projection.getRow(0, new Vector4f());
		Vector4f r1 = projection.getRow(1, new Vector4f());
		Vector4f r3 = projection.getRow(3, new Vector4f());
		// Clip x, y and w are all zero there: three rows, three unknowns.
		Matrix3f rows = new Matrix3f(r0.x, r1.x, r3.x, r0.y, r1.y, r3.y, r0.z, r1.z, r3.z);
		if (Math.abs(rows.determinant()) < 1.0E-9F) {
			return new Vector3f();
		}
		return rows.invert().transform(new Vector3f(-r0.w, -r1.w, -r3.w));
	}

	private static Vec3 toWorld(Camera camera, Vector3f viewOffset) {
		Vector3f world = camera.getViewRotationMatrix(new Matrix4f()).invert().transformDirection(new Vector3f(viewOffset));
		return new Vec3(world.x, world.y, world.z);
	}

	/**
	 * Replaces the near plane of {@code projection} with {@code plane} (view space).
	 *
	 * <p>With reversed Z (26.2+) the near plane is where clip z = w, the far plane where
	 * clip z = 0 (with GL_ARB_clip_control) or z = -w (without, e.g. macOS). Setting the
	 * z row to {@code row3 - a·plane} makes "z ≤ w" exactly "plane·v ≥ 0". With the standard
	 * range (26.1) the near plane is z = 0 or z = -w and the z row becomes {@code a·plane} or
	 * {@code a·plane - row3}. The scale {@code a} is the largest value that still keeps every
	 * corner of the original frustum inside the new far plane, which preserves as much depth
	 * precision as possible.</p>
	 */
	static Matrix4f obliqueProjection(Matrix4f projection, Vector4f plane) {
		boolean zZeroToOne = ClientVersioned.isZZeroToOne();
		Matrix4f result = new Matrix4f(projection);
		Vector4f clipPlane = result.invert(new Matrix4f()).transpose().transform(new Vector4f(plane));

		float zLow = zZeroToOne ? 0.0F : -1.0F; // the clip-space z range is [zLow, 1]
		float maxDot = Float.NEGATIVE_INFINITY;
		for (int sx = -1; sx <= 1; sx += 2) {
			for (int sy = -1; sy <= 1; sy += 2) {
				for (float sz : new float[] {zLow, 1.0F}) {
					maxDot = Math.max(maxDot, clipPlane.x * sx + clipPlane.y * sy + clipPlane.z * sz + clipPlane.w);
				}
			}
		}
		if (!(maxDot > 1.0E-6F)) {
			return result;
		}
		float scale = (zZeroToOne ? 1.0F : 2.0F) / maxDot;
		Vector4f wRow = result.getRow(3, new Vector4f());
		Vector4f scaledPlane = new Vector4f(plane).mul(scale);
		if (ClientVersioned.REVERSED_DEPTH) {
			result.setRow(2, wRow.sub(scaledPlane));
		} else {
			result.setRow(2, zZeroToOne ? scaledPlane : scaledPlane.sub(wRow));
		}
		return result;
	}

	/** The bridge stopped being one (it lives on as another view). */
	static void forgetBridge() {
		bridgeRendered = null;
	}

	/** Called when remote dimensions are torn down, so nothing keeps a destroyed target. */
	static void forgetRemotes() {
		activeLink = null;
		activeRemote = null;
		bridgeRendered = null;
		passes.clear();
		cards.clear();
		assignments = new HashMap<>();
		crossedLink = null;
		activeFullscreen = false;
		lastEye = null;
		lastRemoteTarget = null;
	}

	/** The offscreen view most recently rendered, for {@link DevCapture}. */
	static RenderTarget lastRemoteTarget() {
		return lastRemoteTarget;
	}

	// ── 2. composite ────────────────────────────────────────────────────────

	/**
	 * Full-screen copy of a remote view (colour and depth) into the main framebuffer.
	 *
	 * <p>With {@link #MERGE_PIPELINE} (the bridge), a pixel is written only where the remote
	 * drew geometry at least as close as the main view's: while the main renderer is still
	 * meshing a newly entered level that fills exactly its holes, and where both have the
	 * geometry the images are identical. With {@link #REPLACE_PIPELINE} the whole frame is
	 * replaced (the camera is through a portal).</p>
	 */
	private static void blitFullscreen(RenderTarget mainTarget, RemoteDimension bridge, RenderPipeline pipeline) {
		try (RenderPass pass = ClientVersioned.renderPass(() -> "seamlessportals fullscreen", mainTarget)) {
			ClientVersioned.setPipeline(pass, pipeline);
			GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			ClientVersioned.bindTexture(pass, "Sampler0", bridge.target.getColorTextureView(), nearest);
			ClientVersioned.bindTexture(pass, "Sampler1", bridge.target.getDepthTextureView(), nearest);
			ClientVersioned.drawFullscreen(pass);
		} catch (RuntimeException e) {
			if (!loggedFailure) {
				loggedFailure = true;
				LOGGER.error("Fullscreen remote blit failed", e);
			}
		}
	}

	public static void composite() {
		try {
			compositeAll();
		} finally {
			DevCapture.frame(Minecraft.getInstance());
			DevDrive.frame(Minecraft.getInstance());
		}
	}

	private static void compositeAll() {
		if (DevToggles.on("skipComposite")) {
			activeLink = null;
			activeRemote = null;
			bridgeRendered = null;
			return;
		}
		RemoteDimension bridge = bridgeRendered;
		bridgeRendered = null;
		if (bridge != null) {
			blitFullscreen(ClientVersioned.mainRenderTarget(), bridge, MERGE_PIPELINE);
		}
		Link crossed = activeLink;
		RemoteDimension crossedView = activeRemote;
		activeLink = null;
		activeRemote = null;
		if (crossed != null && crossedView != null && activeFullscreen) {
			activeFullscreen = false;
			// The far side replaces the frame, but looking back at the portal (backing through
			// it), its opening must still show the side being left, which the main view has
			// just drawn: keep that inside the opening, as the window back will once the
			// server moves the player. Otherwise it shows whatever is behind that portal.
			RenderTarget mainTarget = ClientVersioned.mainRenderTarget();
			RenderTarget left = leftBehind(mainTarget);
			RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(mainTarget.getColorTexture(), left.getColorTexture(),
					0, 0, 0, 0, 0, mainTarget.width, mainTarget.height);
			blitFullscreen(mainTarget, crossedView, REPLACE_PIPELINE);
			drawWindow(crossed, left, null);
			return;
		}
		ClientConfig config = ClientConfig.get();
		Vec3 eye = ClientVersioned.gameRenderState().levelRenderState.cameraRenderState.pos;
		for (Pass pass : passes) {
			drawWindow(pass.link(), pass.target(), null);
			// The portal's own surface over the view, as translucent as configured. By default only
			// a remembered view (frozen, without mobs) gets it: it reads as a glimpse, and as a
			// portal you wait in rather than walk through.
			Link link = pass.link();
			double opacity = link.offline() ? config.offlinePortalOpacity : config.streamedPortalOpacity;
			if (opacity <= 0.0 || link.link().source().fill() == PortalFill.NONE || link.link().source().fill().blockless()) {
				continue;
			}
			double depth = Math.abs(eye.subtract(link.geometry().sourceCenter()).dot(link.geometry().sourceNormal()));
			boolean inOpening = insideOpening(link, eye);
			if (!link.offline() && inOpening) {
				// Walked through, not waited in: the surface thins out to nothing at the plane.
				opacity *= Math.min(1.0, depth / STREAMED_SURFACE_FADE);
			}
			if (opacity <= 0.0) {
				continue;
			}
			if (link.offline() && inOpening && depth < INSIDE_PORTAL_DEPTH) {
				drawSurfaceFullscreen(link, (float) opacity);
			} else {
				drawTexturedCard(link, (float) opacity, SWIRL_OFFSET);
			}
		}
		// Over-budget portals look like their fill: the swirl, water or lava from the block atlas,
		// or for end portal and gateway fills the vanilla starfield, simply left unhidden.
		Set<BlockPos> unhiddenEnds = new HashSet<>();
		for (Link card : cards) {
			switch (card.link().source().fill()) {
				case NETHER_PORTAL, WATER, LAVA -> drawTexturedCard(card, 1.0F, PORTAL_PLANE_OFFSET);
				case END_PORTAL, END_GATEWAY -> card.geometry().sourceBlocks().forEach(pos -> unhiddenEnds.add(pos.immutable()));
				case NONE -> drawWindow(card, (RenderTarget) null, cardColor(card));
				case WINDOW, STARFIELD -> {
					// only ever a window: over budget, it's simply not there
				}
			}
		}
		PortalHiding.setUnhiddenEndPortals(unhiddenEnds);
		passes.clear();
		cards.clear();
	}

	/** The far side's fog colour, from whichever view of it last rendered, or a per-dimension guess. */
	private static Vector4f cardColor(Link link) {
		RemoteDimension view = RemoteDimensions.viewFor(link.link(), link.dest());
		if (view != null && view.renderState.cameraRenderState.fogData != null) {
			Vector4f fog = view.renderState.cameraRenderState.fogData.color;
			return new Vector4f(fog.x, fog.y, fog.z, 1.0F);
		}
		if (link.dest() == Level.NETHER) {
			return new Vector4f(0.2F, 0.03F, 0.03F, 1.0F);
		}
		return link.dest() == Level.END ? new Vector4f(0.04F, 0.03F, 0.06F, 1.0F) : new Vector4f(0.62F, 0.75F, 1.0F, 1.0F);
	}

	/**
	 * Draws a portal opening into the main framebuffer: sampling {@code view}'s offscreen image
	 * by screen position, or (with no view) as a flat card in {@code color}.
	 */
	private static void drawWindow(Link link, RemoteDimension view, Vector4f color) {
		drawWindow(link, view == null ? null : view.target, color);
	}

	/** A copy of the main view as drawn this frame, for {@link #compositeAll} while crossing. */
	private static RenderTarget leftBehind(RenderTarget mainTarget) {
		if (leftBehind == null || leftBehind.width != mainTarget.width || leftBehind.height != mainTarget.height) {
			if (leftBehind != null) {
				leftBehind.destroyBuffers();
			}
			leftBehind = ClientVersioned.newTarget("seamlessportals left behind", mainTarget.width, mainTarget.height);
		}
		return leftBehind;
	}

	/**
	 * The opening, for the window's shaders to draw only what's seen through it (the box they're
	 * drawn on covers more of the screen, from close up and askew): its middle relative to
	 * {@code eye}, its right and up axes over its half width and height, and its plane's normal,
	 * as the matrix's columns.
	 */
	private static Matrix4f opening(Link link, Vec3 eye) {
		PortalGeometry geometry = link.geometry();
		Vec3 middle = geometry.sourceCenter().subtract(eye);
		Vec3[] corners = geometry.sourceCorners(eye, 0.0);
		Vec3 right = corners[1].subtract(corners[0]);
		Vec3 up = corners[3].subtract(corners[0]);
		Vec3 normal = geometry.sourceNormal();
		right = right.scale(2.0 / right.lengthSqr());
		up = up.scale(2.0 / up.lengthSqr());
		return new Matrix4f(
				(float) middle.x, (float) middle.y, (float) middle.z, 0.0F,
				(float) right.x, (float) right.y, (float) right.z, 0.0F,
				(float) up.x, (float) up.y, (float) up.z, 0.0F,
				(float) normal.x, (float) normal.y, (float) normal.z, 0.0F);
	}

	/** The portal opening filled with {@code source}'s image (by screen position), or with {@code color} if there's none. */
	private static void drawWindow(Link link, RenderTarget source, Vector4f color) {
		Minecraft minecraft = Minecraft.getInstance();
		GameRenderer gameRenderer = minecraft.gameRenderer;
		RenderTarget mainTarget = ClientVersioned.mainRenderTarget();
		CameraRenderState mainCamera = ClientVersioned.gameRenderState().levelRenderState.cameraRenderState;
		Vec3 eye = mainCamera.pos;
		// The opening (front) and the same rectangle pushed away from the camera (back): a
		// shallow open box. Up close the front quad falls inside the camera's near plane and
		// is clipped; the back and side faces still cover exactly the opening's pixels, and
		// every fragment samples the far side by screen position, so the image is unchanged.
		// Which side the box extends to follows the eye as rendered (see projectionCenter).
		Vec3[] front = link.geometry().sourceCorners(visualEye, PORTAL_PLANE_OFFSET + WINDOW_BIAS, WINDOW_OVERLAP);
		Vec3[] back = link.geometry().sourceCorners(visualEye, PORTAL_PLANE_OFFSET + WINDOW_BIAS - WINDOW_DEPTH, WINDOW_OVERLAP);
		Vec3[] box = {front[0], front[1], front[2], front[3], back[0], back[1], back[2], back[3]};
		int[] triangles = {
				0, 1, 2, 0, 2, 3, // front (the opening)
				4, 5, 6, 4, 6, 7, // back
				0, 1, 5, 0, 5, 4, // bottom
				1, 2, 6, 1, 6, 5, // side
				2, 3, 7, 2, 7, 6, // top
				3, 0, 4, 3, 4, 7  // side
		};

		try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(DefaultVertexFormat.POSITION.getVertexSize() * triangles.length)) {
			BufferBuilder builder = ClientVersioned.triangles(bytes, DefaultVertexFormat.POSITION);
			for (int index : triangles) {
				Vec3 corner = box[index].subtract(eye);
				builder.addVertex((float) corner.x, (float) corner.y, (float) corner.z);
			}
			try (MeshData mesh = builder.buildOrThrow()) {
				GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "seamlessportals portal window", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
				try {
					GpuBufferSlice transforms = ClientVersioned.transform(new Matrix4f(mainCamera.viewRotationMatrix), color, opening(link, eye));
					RenderSystem.setProjectionMatrix(levelProjectionBuffer, ProjectionType.PERSPECTIVE);
					try (RenderPass pass = ClientVersioned.renderPass(() -> "seamlessportals portal window", mainTarget)) {
						ClientVersioned.setPipeline(pass, source != null ? COMPOSITE_PIPELINE : CARD_PIPELINE);
						RenderSystem.bindDefaultUniforms(pass);
						pass.setUniform("DynamicTransforms", transforms);
						if (source != null) {
							ClientVersioned.bindTexture(pass, "Sampler0", source.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
						}
						ClientVersioned.drawVertices(pass, vertices, triangles.length);
					}
				} finally {
					vertices.close();
				}
			}
		} catch (RuntimeException e) {
			if (!loggedFailure) {
				loggedFailure = true;
				LOGGER.error("Portal composite failed", e);
			}
		}
	}

	/** Whether {@code eye}, seen along the portal's normal, is within its opening. */
	private static boolean insideOpening(Link link, Vec3 eye) {
		Vec3[] corners = link.geometry().sourceCorners(eye, 0.0);
		Vec3 across = corners[1].subtract(corners[0]);
		Vec3 along = corners[3].subtract(corners[0]);
		Vec3 rel = eye.subtract(corners[0]);
		double a = rel.dot(across) / across.lengthSqr();
		double b = rel.dot(along) / along.lengthSqr();
		return a >= 0.0 && a <= 1.0 && b >= 0.0 && b <= 1.0;
	}

	/** A culled portal drawn as its fill: one quad per block on the portal plane, with the fill's (animated) sprite. */
	private static void drawTexturedCard(Link link, float alpha, double offset) {
		CameraRenderState mainCamera = ClientVersioned.gameRenderState().levelRenderState.cameraRenderState;
		Vec3 eye = mainCamera.pos;
		PortalEnd end = link.link().source();
		Vec3[] corners = link.geometry().sourceCorners(eye, offset);
		Vec3 origin = corners[0].subtract(eye);
		Vec3 right = corners[1].subtract(corners[0]).scale(1.0 / end.width());
		Vec3 up = corners[3].subtract(corners[0]).scale(1.0 / end.height());
		List<Vec3[]> quads = new ArrayList<>();
		for (int i = 0; i < end.width(); i++) {
			for (int j = 0; j < end.height(); j++) {
				Vec3 p00 = origin.add(right.scale(i)).add(up.scale(j));
				quads.add(new Vec3[] {p00, p00.add(right), p00.add(right).add(up), p00.add(up)});
			}
		}
		drawSurface(link, alpha, quads, "seamlessportals portal card");
	}

	/**
	 * The fill over the whole screen, for an eye inside the opening (where a card on the plane
	 * would be behind it or cut by the near plane): one quad just in front of the camera,
	 * covering its view, as vanilla's own in-portal overlay does.
	 */
	private static void drawSurfaceFullscreen(Link link, float alpha) {
		CameraRenderState mainCamera = ClientVersioned.gameRenderState().levelRenderState.cameraRenderState;
		Matrix4f unproject = new Matrix4f(mainCamera.projectionMatrix).mul(mainCamera.viewRotationMatrix).invert();
		Vector3f forward = unproject.transformProject(new Vector3f(0.0F, 0.0F, 0.5F)).normalize();
		Vec3[] corner = new Vec3[4];
		float[][] ndc = {{-1.0F, -1.0F}, {1.0F, -1.0F}, {1.0F, 1.0F}, {-1.0F, 1.0F}};
		for (int k = 0; k < 4; k++) {
			Vector3f ray = unproject.transformProject(new Vector3f(ndc[k][0], ndc[k][1], 0.5F)).normalize();
			ray.mul(FULLSCREEN_SURFACE_DISTANCE / ray.dot(forward));
			corner[k] = new Vec3(ray.x, ray.y, ray.z);
		}
		drawSurface(link, alpha, List.<Vec3[]>of(corner), "seamlessportals portal surface");
	}

	/**
	 * Draws {@code quads} (camera-relative corners, counter-clockwise from bottom left) with the
	 * fill of {@code link}'s portal: the swirl, water or lava sprite stretched over each quad, or
	 * for end portals and gateways vanilla's moving starfield, projected on the screen as vanilla
	 * draws it.
	 */
	private static void drawSurface(Link link, float alpha, List<Vec3[]> quads, String label) {
		Minecraft minecraft = Minecraft.getInstance();
		PortalFill fill = link.link().source().fill();
		boolean starfield = fill == PortalFill.END_PORTAL || fill == PortalFill.END_GATEWAY;
		AbstractTexture texture;
		AbstractTexture stars = null;
		float u0 = 0.0F, u1 = 1.0F, v0 = 0.0F, v1 = 1.0F;
		Vector4f tint = new Vector4f(1.0F, 1.0F, 1.0F, alpha);
		if (starfield) {
			texture = minecraft.getTextureManager().getTexture(END_SKY_TEXTURE);
			stars = minecraft.getTextureManager().getTexture(END_PORTAL_TEXTURE);
		} else {
			TextureAtlas atlas = (TextureAtlas) minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
			TextureAtlasSprite sprite = atlas.getSprite(Identifier.withDefaultNamespace(switch (fill) {
				case WATER -> "block/water_still";
				case LAVA -> "block/lava_still";
				default -> "block/nether_portal";
			}));
			texture = atlas;
			u0 = sprite.getU0();
			u1 = sprite.getU1();
			v0 = sprite.getV0();
			v1 = sprite.getV1();
		}
		if (fill == PortalFill.WATER && minecraft.level != null) {
			int rgb = BiomeColors.getAverageWaterColor(minecraft.level, BlockPos.containing(link.geometry().sourceCenter()));
			tint.set((rgb >> 16 & 0xFF) / 255.0F, (rgb >> 8 & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F, alpha);
		}

		RenderTarget mainTarget = ClientVersioned.mainRenderTarget();
		CameraRenderState mainCamera = ClientVersioned.gameRenderState().levelRenderState.cameraRenderState;
		int vertexCount = quads.size() * 6;
		try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(DefaultVertexFormat.POSITION_TEX.getVertexSize() * vertexCount)) {
			BufferBuilder builder = ClientVersioned.triangles(bytes, DefaultVertexFormat.POSITION_TEX);
			for (Vec3[] quad : quads) {
				cardVertex(builder, quad[0], u0, v1);
				cardVertex(builder, quad[1], u1, v1);
				cardVertex(builder, quad[2], u1, v0);
				cardVertex(builder, quad[0], u0, v1);
				cardVertex(builder, quad[2], u1, v0);
				cardVertex(builder, quad[3], u0, v0);
			}
			try (MeshData mesh = builder.buildOrThrow()) {
				GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
				try {
					GpuBufferSlice transforms = ClientVersioned.transform(new Matrix4f(mainCamera.viewRotationMatrix), tint);
					RenderSystem.setProjectionMatrix(levelProjectionBuffer, ProjectionType.PERSPECTIVE);
					try (RenderPass pass = ClientVersioned.renderPass(() -> label, mainTarget)) {
						ClientVersioned.setPipeline(pass, starfield ? STARFIELD_CARD_PIPELINE : TEXTURED_CARD_PIPELINE);
						RenderSystem.bindDefaultUniforms(pass);
						pass.setUniform("DynamicTransforms", transforms);
						ClientVersioned.bindTexture(pass, "Sampler0", texture.getTextureView(), texture.getSampler());
						if (stars != null) {
							ClientVersioned.bindTexture(pass, "Sampler1", stars.getTextureView(), stars.getSampler());
						}
						ClientVersioned.drawVertices(pass, vertices, vertexCount);
					}
				} finally {
					vertices.close();
				}
			}
		} catch (RuntimeException e) {
			if (!loggedFailure) {
				loggedFailure = true;
				LOGGER.error("Portal card failed", e);
			}
		}
	}

	private static void cardVertex(BufferBuilder builder, Vec3 pos, float u, float v) {
		builder.addVertex((float) pos.x, (float) pos.y, (float) pos.z).setUv(u, v);
	}
}
