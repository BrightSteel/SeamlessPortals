package dev.ryan.netherview.client;

import dev.ryan.netherview.PortalGeometry;
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
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import dev.ryan.netherview.net.PortalEnd;
import dev.ryan.netherview.net.PortalFill;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.ryan.netherview.client.RemoteDimensions.Link;
import dev.ryan.netherview.client.mixin.GameRendererAccessor;
import dev.ryan.netherview.client.mixin.LevelExtractorAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
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
import java.util.Optional;
import java.util.Set;
import java.util.OptionalDouble;

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
	private static final Logger LOGGER = LoggerFactory.getLogger("netherview");

	/**
	 * The window and the remote clip plane both sit on the portal's centre plane, like a
	 * hole in a wall: the near half of the opening's inner faces comes from this side's
	 * frame, the far half from the destination frame seen through the window.
	 */
	private static final double PORTAL_PLANE_OFFSET = 0.0;
	/** Depth of the window box behind the opening; well past the camera's 0.05 near plane. */
	private static final double WINDOW_DEPTH = 0.1;
	/** Keeps the oblique near plane from passing through the remote camera when standing in a portal. */
	private static final double MIN_CLIP_DISTANCE = 0.05;

	private static final RenderPipeline COMPOSITE_PIPELINE = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("netherview", "pipeline/portal_view"))
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
			.withVertexShader(Identifier.fromNamespaceAndPath("netherview", "core/portal_view"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("netherview", "core/portal_view"))
			.withVertexBinding(0, DefaultVertexFormat.POSITION)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withCull(false)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
			.build();

	private static final RenderPipeline MERGE_PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("netherview", "pipeline/bridge_merge"))
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath("netherview", "core/bridge_merge"))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
			.build();

	private static final RenderPipeline REPLACE_PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("netherview", "pipeline/remote_fullscreen"))
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath("netherview", "core/remote_fullscreen"))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
			.build();
	/** How long the camera may stay "through" a portal waiting for the server to move the player. */
	private static final long CROSSED_TIMEOUT_MS = 2000L;

	private static final RenderPipeline TEXTURED_CARD_PIPELINE = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("netherview", "pipeline/portal_card_tex"))
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
			.withVertexShader(Identifier.fromNamespaceAndPath("netherview", "core/portal_card_tex"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("netherview", "core/portal_card_tex"))
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withCull(false)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
			.build();

	private static final RenderPipeline CARD_PIPELINE = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("netherview", "pipeline/portal_card"))
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withVertexShader(Identifier.fromNamespaceAndPath("netherview", "core/portal_view"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("netherview", "core/portal_card"))
			.withVertexBinding(0, DefaultVertexFormat.POSITION)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withCull(false)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
			.build();
	/** Extra margin around a portal's screen rectangle when narrowing a view's culling frustum (NDC units). */
	private static final float CULL_RECT_MARGIN = 0.05F;
	/** How far beyond the opening a live portal is assumed to hide what's behind it (its frame). */
	private static final double FRAME_OCCLUDER_MARGIN = 0.9;
	/** Smallest half-size (NDC) of a narrowed culling rectangle; see {@link #narrowedToRect}. */
	private static final float MIN_CULL_HALF_SIZE = 0.1F;

	/** A live portal view rendered this frame, waiting to be composited. */
	private record Pass(Link link, RemoteDimension view) {
	}

	private static final List<Pass> passes = new ArrayList<>();
	private static final List<Link> cards = new ArrayList<>();
	/** Which view each live portal (by its lowest block) used last frame, so it keeps its meshes and visibility. */
	private static Map<BlockPos, RemoteDimension> assignments = new HashMap<>();

	private static Link activeLink;
	private static RemoteDimension bridgeRendered;
	/** Set once the camera has passed through a linked portal and until the dimension actually changes. */
	private static Link crossedLink;
	private static long crossedAt;
	private static boolean activeFullscreen;
	private static Vec3 lastEye;
	private static RemoteDimension activeRemote;
	private static GpuBufferSlice levelProjectionBuffer;
	private static boolean loggedFailure;
	private static RenderTarget lastRemoteTarget;

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
		Camera main = minecraft.gameRenderer.mainCamera();
		levelProjectionBuffer = RenderSystem.getProjectionMatrixBuffer();
		renderBridge(minecraft, main, levelProjection);
		trackCrossing(main.position());
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
		if (previous == null || previous.distanceToSqr(eye) > 16.0) {
			return;
		}
		if (crossedLink != null) {
			if (crossedLink.geometry().crossesSource(previous, eye)) {
				crossedLink = null;
			}
			return;
		}
		for (Link link : RemoteDimensions.links()) {
			// End portals are fallen into feet first: the server moves the player before the
			// camera reaches the surface, so there is no crossing to preview.
			if (!link.geometry().horizontal() && link.geometry().crossesSource(previous, eye) && RemoteDimensions.get(link.dest()) != null) {
				crossedLink = link;
				crossedAt = Util.getMillis();
				return;
			}
		}
	}

	/** Through the portal: the far side, from the carried-through camera, with no clip plane (it would be behind us). */
	private static void renderCrossed(Minecraft minecraft, Camera main, Matrix4f levelProjection) {
		Link link = crossedLink;
		RemoteDimension remote = RemoteDimensions.get(link.dest());
		if (remote == null) {
			return;
		}
		try {
			PortalGeometry geometry = link.geometry();
			Vec3 remoteEye = geometry.toDest(main.position());
			remote.camera.place(main, remote.level, remoteEye, geometry.toDest(main.rotation()));
			renderLevelInto(minecraft, main, remote, remoteEye, new Matrix4f(levelProjection));
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
		List<float[]> liveQuads = new ArrayList<>();
		Matrix4f viewProjection = new Matrix4f(levelProjection).mul(main.getViewRotationMatrix(new Matrix4f()));
		double maxDistanceSqr = config.effectiveLiveViewDistance() * config.effectiveLiveViewDistance();
		for (Link link : visible) {
			float[] quad = screenQuad(link, eye, viewProjection, 0.0);
			if (quad != null && liveQuads.stream().anyMatch(cover -> covers(cover, quad))) {
				continue; // entirely behind a nearer live portal (e.g. a chain of portals): nothing to see
			}
			if (live.size() < config.effectiveMaxLivePortals() && link.geometry().sourceCenter().distanceToSqr(eye) <= maxDistanceSqr) {
				live.add(link);
				// The occluder is the window plus its solid obsidian frame (most of a block wide;
				// the frame's corner blocks are optional, hence a little less than a full block).
				float[] occluder = screenQuad(link, eye, viewProjection, FRAME_OCCLUDER_MARGIN);
				if (occluder != null) {
					liveQuads.add(occluder);
				}
			} else {
				cards.add(link);
			}
		}

		Map<BlockPos, RemoteDimension> assigned = assignViews(live);
		for (Link link : live) {
			RemoteDimension view = assigned.get(link.link().min());
			Vector4f rect = screenRect(link, main, levelProjection);
			if (rect == null) {
				continue; // entirely off screen after all
			}
			try {
				renderRemote(minecraft, main, link, view, levelProjection, rect);
				passes.add(new Pass(link, view));
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

	/** Whether the opening still holds its fill (a broken portal stops being a window). */
	private static boolean isFill(BlockState state, PortalFill fill) {
		return switch (fill) {
			case NETHER_PORTAL -> state.is(Blocks.NETHER_PORTAL);
			case END_PORTAL -> state.is(Blocks.END_PORTAL);
			case END_GATEWAY -> state.is(Blocks.END_GATEWAY);
			case WATER -> state.is(Blocks.WATER);
			case LAVA -> state.is(Blocks.LAVA);
			case NONE -> state.isAir();
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
	 * view when it can; the rest take free views, which are re-centred on their new destination.
	 */
	private static Map<BlockPos, RemoteDimension> assignViews(List<Link> live) {
		Map<BlockPos, RemoteDimension> assigned = new HashMap<>();
		Set<RemoteDimension> used = new HashSet<>();
		List<Link> unassigned = new ArrayList<>();
		for (Link link : live) {
			RemoteDimension previous = assignments.get(link.link().min());
			if (previous != null && previous.key == link.dest() && !previous.isClosed() && used.add(previous)) {
				assigned.put(link.link().min(), previous);
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
			assigned.put(link.link().min(), view);
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
		Matrix4f projection = obliqueProjection(levelProjection, clipPlane(geometry, remote.camera, remoteEye));
		remote.camera.place(main, remote.level, remoteEye, orientation, narrowedToRect(levelProjection, rect));
		((LevelExtractorAccessor) remote.extractor).netherview$setPrevCamRotX(Double.NaN);
		renderLevelInto(minecraft, main, remote, remoteEye, projection);
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
		RenderTarget mainTarget = gameRenderer.mainRenderTarget();
		remote.ensureSize(mainTarget.width, mainTarget.height);
		remote.primeProbe(remoteEye);
		Vec3 eye = main.position();

		// Extract, mirroring GameRenderer.extract but into the remote's own state.
		DeltaTracker delta = minecraft.getDeltaTracker();
		float partialTicks = delta.getGameTimeDeltaPartialTick(false);
		CameraRenderState cameraState = remote.renderState.cameraRenderState;
		remote.camera.extractRenderState(cameraState, delta);
		cameraState.fogType = remote.camera.getFluidInCamera();
		cameraState.fogData = remote.fog.setupFog(
				remote.camera, Math.min(minecraft.options.getEffectiveRenderDistance(), ClientConfig.get().effectiveRemoteRenderDistance()), delta, gameRenderer.bossOverlayWorldDarkening(partialTicks), remote.level);
		remote.extractor.extract(delta, remote.camera, partialTicks);
		remote.renderState.playerCompiledSectionCallback = null;
		// Extraction pointed the shared dispatchers at the remote camera; point them back.
		minecraft.getEntityRenderDispatcher().prepare(main, minecraft.crosshairPickEntity);
		minecraft.getBlockEntityRenderDispatcher().prepare(eye);

		fillLightmapState(gameRenderer.gameRenderState().lightmapRenderState, remote, partialTicks);
		remote.lightmap.render(remote.lightmapState);

		ProjectionType savedProjectionType = RenderSystem.getProjectionType();
		GpuBufferSlice savedFog = RenderSystem.getShaderFog();
		Lightmap savedLightmap = gameRendererAccess.netherview$getLightmap();
		try {
			gameRendererAccess.netherview$setMainRenderTarget(remote.target);
			gameRendererAccess.netherview$setLightmap(remote.lightmap);
			updateGlobals(gameRenderer, remoteEye);
			RenderSystem.setProjectionMatrix(remote.projection.getBuffer(projection), ProjectionType.PERSPECTIVE);
			remote.fog.updateBuffer(cameraState.fogData);
			remote.renderer.render(
					remote.resourcePool, false, cameraState, remote.fog.getBuffer(FogRenderer.FogMode.WORLD), cameraState.fogData.color, true, false);
		} finally {
			gameRendererAccess.netherview$setMainRenderTarget(mainTarget);
			gameRendererAccess.netherview$setLightmap(savedLightmap);
			updateGlobals(gameRenderer, gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos);
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
		GameRenderState state = gameRenderer.gameRenderState();
		((GameRendererAccessor) gameRenderer).netherview$getGlobalSettingsUniform().update(
				state.windowRenderState.width,
				state.windowRenderState.height,
				state.optionsRenderState.glintStrength,
				state.levelRenderState.gameTime,
				state.levelRenderState.worldPartialTicks,
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
		state.blockLightTint = (Vector3fc) probe.getValue(EnvironmentAttributes.BLOCK_LIGHT_TINT, partialTicks);
		state.skyFactor = (Float) probe.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, partialTicks);
		state.skyLightColor = (Vector3fc) probe.getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, partialTicks);
		state.ambientColor = (Vector3fc) probe.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, partialTicks);
	}

	/**
	 * The destination portal plane in the remote camera's view space, as (n, d) with
	 * n·v + d ≥ 0 on the side that should stay visible (the side away from the camera).
	 */
	private static Vector4f clipPlane(PortalGeometry geometry, Camera remoteCamera, Vec3 remoteEye) {
		Vec3 normal = geometry.destNormal();
		Vec3 keep = remoteEye.subtract(geometry.destCenter()).dot(normal) >= 0 ? normal.scale(-1) : normal;
		Vec3 planePoint = geometry.destCenter().add(keep.scale(PORTAL_PLANE_OFFSET));
		double distance = Math.min(keep.dot(remoteEye.subtract(planePoint)), -MIN_CLIP_DISTANCE);
		Vector4f viewNormal = remoteCamera.getViewRotationMatrix(new Matrix4f())
				.transform(new Vector4f((float) keep.x, (float) keep.y, (float) keep.z, 0.0F));
		return new Vector4f(viewNormal.x, viewNormal.y, viewNormal.z, (float) distance);
	}

	/**
	 * Replaces the near plane of {@code projection} with {@code plane} (view space).
	 *
	 * <p>Minecraft uses reversed Z: the near plane is where clip z = w, the far plane where
	 * clip z = 0 (with GL_ARB_clip_control) or z = -w (without, e.g. macOS). Setting the
	 * z row to {@code row3 - a·plane} makes "z ≤ w" exactly "plane·v ≥ 0". The scale
	 * {@code a} is the largest value that still keeps every corner of the original frustum
	 * inside the new far plane, which preserves as much depth precision as possible.</p>
	 */
	static Matrix4f obliqueProjection(Matrix4f projection, Vector4f plane) {
		boolean zZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
		Matrix4f result = new Matrix4f(projection);
		Vector4f clipPlane = result.invert(new Matrix4f()).transpose().transform(new Vector4f(plane));

		float farZ = zZeroToOne ? 0.0F : -1.0F;
		float maxDot = Float.NEGATIVE_INFINITY;
		for (int sx = -1; sx <= 1; sx += 2) {
			for (int sy = -1; sy <= 1; sy += 2) {
				for (float sz : new float[] {farZ, 1.0F}) {
					maxDot = Math.max(maxDot, clipPlane.x * sx + clipPlane.y * sy + clipPlane.z * sz + clipPlane.w);
				}
			}
		}
		if (!(maxDot > 1.0E-6F)) {
			return result;
		}
		float scale = (zZeroToOne ? 1.0F : 2.0F) / maxDot;
		Vector4f wRow = result.getRow(3, new Vector4f());
		result.setRow(2, wRow.sub(new Vector4f(plane).mul(scale)));
		return result;
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
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> "netherview fullscreen", mainTarget.getColorTextureView(), Optional.empty(),
				mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
			GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			pass.setUniform("Sampler0", bridge.target.getColorTextureView(), nearest);
			pass.setUniform("Sampler1", bridge.target.getDepthTextureView(), nearest);
			pass.draw(3, 1, 0, 0);
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
			blitFullscreen(Minecraft.getInstance().gameRenderer.mainRenderTarget(), bridge, MERGE_PIPELINE);
		}
		Link crossed = activeLink;
		RemoteDimension crossedView = activeRemote;
		activeLink = null;
		activeRemote = null;
		if (crossed != null && crossedView != null && activeFullscreen) {
			activeFullscreen = false;
			blitFullscreen(Minecraft.getInstance().gameRenderer.mainRenderTarget(), crossedView, REPLACE_PIPELINE);
			return;
		}
		for (Pass pass : passes) {
			drawWindow(pass.link(), pass.view(), null);
		}
		// Over-budget portals look like their fill: the swirl, water or lava from the block atlas,
		// or for end portal and gateway fills the vanilla starfield, simply left unhidden.
		Set<BlockPos> unhiddenEnds = new HashSet<>();
		for (Link card : cards) {
			switch (card.link().source().fill()) {
				case NETHER_PORTAL, WATER, LAVA -> drawTexturedCard(card);
				case END_PORTAL, END_GATEWAY -> card.geometry().sourceBlocks().forEach(pos -> unhiddenEnds.add(pos.immutable()));
				case NONE -> drawWindow(card, null, cardColor(card));
			}
		}
		PortalHiding.setUnhiddenEndPortals(unhiddenEnds);
		passes.clear();
		cards.clear();
	}

	/** The far side's fog colour, from whichever view of it last rendered, or a per-dimension guess. */
	private static Vector4f cardColor(Link link) {
		RemoteDimension view = RemoteDimensions.get(link.dest());
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
		Minecraft minecraft = Minecraft.getInstance();
		GameRenderer gameRenderer = minecraft.gameRenderer;
		RenderTarget mainTarget = gameRenderer.mainRenderTarget();
		CameraRenderState mainCamera = gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		Vec3 eye = mainCamera.pos;
		// The opening (front) and the same rectangle pushed away from the camera (back): a
		// shallow open box. Up close the front quad falls inside the camera's near plane and
		// is clipped; the back and side faces still cover exactly the opening's pixels, and
		// every fragment samples the far side by screen position, so the image is unchanged.
		Vec3[] front = link.geometry().sourceCorners(eye, PORTAL_PLANE_OFFSET);
		Vec3[] back = link.geometry().sourceCorners(eye, PORTAL_PLANE_OFFSET - WINDOW_DEPTH);
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
			BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION);
			for (int index : triangles) {
				Vec3 corner = box[index].subtract(eye);
				builder.addVertex((float) corner.x, (float) corner.y, (float) corner.z);
			}
			try (MeshData mesh = builder.buildOrThrow()) {
				GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "netherview portal window", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
				try {
					GpuBufferSlice transforms = color == null
							? RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(mainCamera.viewRotationMatrix))
							: RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(mainCamera.viewRotationMatrix), color);
					RenderSystem.setProjectionMatrix(levelProjectionBuffer, ProjectionType.PERSPECTIVE);
					try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
							() -> "netherview portal window", mainTarget.getColorTextureView(), Optional.empty(),
							mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
						pass.setPipeline(RenderSystem.getCompiledPipeline(view != null ? COMPOSITE_PIPELINE : CARD_PIPELINE));
						RenderSystem.bindDefaultUniforms(pass);
						pass.setUniform("DynamicTransforms", transforms);
						if (view != null) {
							pass.setUniform("Sampler0", view.target.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
						}
						pass.setVertexBuffer(0, vertices.slice());
						pass.draw(triangles.length, 1, 0, 0);
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

	/** A culled portal drawn as its fill: one quad per block on the portal plane, with the fill's (animated) sprite. */
	private static void drawTexturedCard(Link link) {
		Minecraft minecraft = Minecraft.getInstance();
		PortalFill fill = link.link().source().fill();
		TextureAtlas atlas = (TextureAtlas) minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
		TextureAtlasSprite sprite = atlas.getSprite(Identifier.withDefaultNamespace(switch (fill) {
			case WATER -> "block/water_still";
			case LAVA -> "block/lava_still";
			default -> "block/nether_portal";
		}));
		Vector4f tint = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
		if (fill == PortalFill.WATER && minecraft.level != null) {
			int rgb = BiomeColors.getAverageWaterColor(minecraft.level, BlockPos.containing(link.geometry().sourceCenter()));
			tint.set((rgb >> 16 & 0xFF) / 255.0F, (rgb >> 8 & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F, 1.0F);
		}

		GameRenderer gameRenderer = minecraft.gameRenderer;
		RenderTarget mainTarget = gameRenderer.mainRenderTarget();
		CameraRenderState mainCamera = gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		Vec3 eye = mainCamera.pos;
		PortalEnd end = link.link().source();
		Vec3[] corners = link.geometry().sourceCorners(eye, PORTAL_PLANE_OFFSET);
		Vec3 origin = corners[0].subtract(eye);
		Vec3 right = corners[1].subtract(corners[0]).scale(1.0 / end.width());
		Vec3 up = corners[3].subtract(corners[0]).scale(1.0 / end.height());
		int vertexCount = end.width() * end.height() * 6;
		try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(DefaultVertexFormat.POSITION_TEX.getVertexSize() * vertexCount)) {
			BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION_TEX);
			for (int i = 0; i < end.width(); i++) {
				for (int j = 0; j < end.height(); j++) {
					Vec3 p00 = origin.add(right.scale(i)).add(up.scale(j));
					Vec3 p10 = p00.add(right);
					Vec3 p11 = p10.add(up);
					Vec3 p01 = p00.add(up);
					cardVertex(builder, p00, sprite.getU0(), sprite.getV1());
					cardVertex(builder, p10, sprite.getU1(), sprite.getV1());
					cardVertex(builder, p11, sprite.getU1(), sprite.getV0());
					cardVertex(builder, p00, sprite.getU0(), sprite.getV1());
					cardVertex(builder, p11, sprite.getU1(), sprite.getV0());
					cardVertex(builder, p01, sprite.getU0(), sprite.getV0());
				}
			}
			try (MeshData mesh = builder.buildOrThrow()) {
				GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "netherview portal card", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
				try {
					GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(mainCamera.viewRotationMatrix), tint);
					RenderSystem.setProjectionMatrix(levelProjectionBuffer, ProjectionType.PERSPECTIVE);
					try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
							() -> "netherview portal card", mainTarget.getColorTextureView(), Optional.empty(),
							mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
						pass.setPipeline(RenderSystem.getCompiledPipeline(TEXTURED_CARD_PIPELINE));
						RenderSystem.bindDefaultUniforms(pass);
						pass.setUniform("DynamicTransforms", transforms);
						pass.setUniform("Sampler0", atlas.getTextureView(), atlas.getSampler());
						pass.setVertexBuffer(0, vertices.slice());
						pass.draw(vertexCount, 1, 0, 0);
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
