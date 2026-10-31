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
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.ryan.netherview.client.RemoteDimensions.Link;
import dev.ryan.netherview.client.mixin.GameRendererAccessor;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
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
		activeLink = null;
		activeRemote = null;
		bridgeRendered = null;
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
		Link link = pickLink(minecraft, main);
		if (link == null) {
			return;
		}
		RemoteDimension remote = RemoteDimensions.get(link.dest());
		if (remote == null) {
			return;
		}
		try {
			renderRemote(minecraft, main, link, remote, levelProjection);
			activeLink = link;
			activeRemote = remote;
			lastRemoteTarget = remote.target;
		} catch (RuntimeException e) {
			if (!loggedFailure) {
				loggedFailure = true;
				LOGGER.error("Remote dimension pass failed; portal will show vanilla", e);
			}
		}
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
			bridge.camera.place(main, bridge.level, main.position(), main.yRot(), main.xRot());
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
			if (link.geometry().crossesSource(previous, eye) && RemoteDimensions.get(link.dest()) != null) {
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
			remote.camera.place(main, remote.level, remoteEye, geometry.toDestYaw(main.yRot()), main.xRot());
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

	/** The nearest portal that is on screen and still exists. One remote pass per frame. */
	private static Link pickLink(Minecraft minecraft, Camera main) {
		Frustum frustum = main.getCullFrustum();
		Link best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Link link : RemoteDimensions.links()) {
			if (!frustum.isVisible(link.geometry().sourceBox().inflate(0.25))) {
				continue;
			}
			if (!minecraft.level.getBlockState(link.link().min()).is(Blocks.NETHER_PORTAL)) {
				continue;
			}
			double distance = link.geometry().sourceCenter().distanceToSqr(main.position());
			if (distance < bestDistance) {
				bestDistance = distance;
				best = link;
			}
		}
		return best;
	}

	private static void renderRemote(Minecraft minecraft, Camera main, Link link, RemoteDimension remote, Matrix4f levelProjection) {
		PortalGeometry geometry = link.geometry();
		Vec3 remoteEye = geometry.toDest(main.position());
		remote.camera.place(main, remote.level, remoteEye, geometry.toDestYaw(main.yRot()), main.xRot());
		Matrix4f projection = obliqueProjection(levelProjection, clipPlane(geometry, remote.camera, remoteEye));
		renderLevelInto(minecraft, main, remote, remoteEye, projection);
	}

	/**
	 * Runs one full level render of {@code remote} from its (already placed) camera into
	 * its offscreen target, with the game renderer's shared state swapped to the remote's
	 * for the duration.
	 */
	private static void renderLevelInto(Minecraft minecraft, Camera main, RemoteDimension remote, Vec3 remoteEye, Matrix4f projection) {
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
				remote.camera, minecraft.options.getEffectiveRenderDistance(), delta, gameRenderer.bossOverlayWorldDarkening(partialTicks), remote.level);
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
		RemoteDimension bridge = bridgeRendered;
		bridgeRendered = null;
		if (bridge != null) {
			blitFullscreen(Minecraft.getInstance().gameRenderer.mainRenderTarget(), bridge, MERGE_PIPELINE);
		}
		Link link = activeLink;
		RemoteDimension remote = activeRemote;
		activeLink = null;
		activeRemote = null;
		if (link == null || remote == null) {
			return;
		}
		if (activeFullscreen) {
			activeFullscreen = false;
			blitFullscreen(Minecraft.getInstance().gameRenderer.mainRenderTarget(), remote, REPLACE_PIPELINE);
			return;
		}
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
					GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(mainCamera.viewRotationMatrix));
					RenderSystem.setProjectionMatrix(levelProjectionBuffer, ProjectionType.PERSPECTIVE);
					try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
							() -> "netherview portal window", mainTarget.getColorTextureView(), Optional.empty(),
							mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
						pass.setPipeline(RenderSystem.getCompiledPipeline(COMPOSITE_PIPELINE));
						RenderSystem.bindDefaultUniforms(pass);
						pass.setUniform("DynamicTransforms", transforms);
						pass.setUniform("Sampler0", remote.target.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
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
}
