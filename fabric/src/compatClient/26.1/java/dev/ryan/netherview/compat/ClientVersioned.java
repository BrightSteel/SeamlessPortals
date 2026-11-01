package dev.ryan.netherview.compat;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.joml.Vector3fc;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.ryan.netherview.client.mixin.MainRenderTargetAccessor;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

/** The client calls that differ between Minecraft versions; one copy per version under fabric/src/compatClient. (26.1) */
public final class ClientVersioned {
	/** In GameRenderer.renderLevel, the first call after the level is drawn: before the hand's projection is set up. */
	public static final String AFTER_LEVEL_RENDER = "Lnet/minecraft/client/renderer/Projection;setupPerspective(FFFFF)V";

	private ClientVersioned() {
	}

	public static void extractCamera(Camera camera, CameraRenderState state, DeltaTracker delta) {
		camera.extractRenderState(state, delta.getGameTimeDeltaPartialTick(false));
	}

	/** Draws the level as GameRenderer does, from the extracted state, with the given fog. */
	public static void renderLevel(LevelRenderer renderer, GraphicsResourceAllocator allocator, DeltaTracker delta, LevelRenderState state, GpuBufferSlice fog) {
		CameraRenderState camera = state.cameraRenderState;
		renderer.renderLevel(allocator, delta, false, camera, camera.viewRotationMatrix, fog, camera.fogData.color, true, state.chunkSectionsToRender);
	}

	public static void updateGlobals(GlobalSettingsUniform uniform, int width, int height, double glintStrength, LevelRenderState level, DeltaTracker delta,
			int menuBlur, net.minecraft.world.phys.Vec3 cameraPos, boolean rgss) {
		uniform.update(width, height, glintStrength, level.gameTime, delta, menuBlur, cameraPos, rgss);
	}

	public static Vector3fc blockLightTint(EnvironmentAttributeProbe probe, float partialTicks) {
		return ARGB.vector3fFromRGB24(probe.getValue(EnvironmentAttributes.BLOCK_LIGHT_TINT, partialTicks));
	}

	public static Vector3fc skyLightColor(EnvironmentAttributeProbe probe, float partialTicks) {
		return ARGB.vector3fFromRGB24(probe.getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, partialTicks));
	}

	public static Vector3fc ambientLightColor(EnvironmentAttributeProbe probe, float partialTicks) {
		return ARGB.vector3fFromRGB24(probe.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, partialTicks));
	}

	public static void setPipeline(RenderPass pass, RenderPipeline pipeline) {
		pass.setPipeline(pipeline);
	}

	public static void bindTexture(RenderPass pass, String name, GpuTextureView view, GpuSampler sampler) {
		pass.bindTexture(name, view, sampler);
	}

	/** An offscreen colour + depth target. */
	public static TextureTarget newTarget(String name, int width, int height) {
		return new TextureTarget(name, width, height, true);
	}

	public static boolean isSectionCompiledAndVisible(LevelRenderer renderer, BlockPos pos) {
		return renderer.isSectionCompiledAndVisible(pos);
	}

	/** Loads a chunk from packet data into the client chunk cache, as ClientPacketListener does. */
	public static LevelChunk replaceWithPacketData(ClientChunkCache cache, int x, int z, ClientboundLevelChunkPacketData data) {
		return cache.replaceWithPacketData(x, z, data.getReadBuffer(), data.getHeightmaps(), data.getBlockEntitiesTagsConsumer(x, z));
	}

	public static int chunkX(ClientboundLevelChunkWithLightPacket packet) {
		return packet.getX();
	}

	public static int chunkZ(ClientboundLevelChunkWithLightPacket packet) {
		return packet.getZ();
	}

	public static ClientboundLevelChunkPacketData chunkData(ClientboundLevelChunkWithLightPacket packet) {
		return packet.getChunkData();
	}

	public static ClientboundLightUpdatePacketData lightData(ClientboundLevelChunkWithLightPacket packet) {
		return packet.getLightData();
	}

	public static BitSet skyYMask(ClientboundLightUpdatePacketData data) {
		return data.getSkyYMask();
	}

	public static BitSet emptySkyYMask(ClientboundLightUpdatePacketData data) {
		return data.getEmptySkyYMask();
	}

	public static List<byte[]> skyUpdates(ClientboundLightUpdatePacketData data) {
		return data.getSkyUpdates();
	}

	public static BitSet blockYMask(ClientboundLightUpdatePacketData data) {
		return data.getBlockYMask();
	}

	public static BitSet emptyBlockYMask(ClientboundLightUpdatePacketData data) {
		return data.getEmptyBlockYMask();
	}

	public static List<byte[]> blockUpdates(ClientboundLightUpdatePacketData data) {
		return data.getBlockUpdates();
	}

	// ── the game renderer ────────────────────────────────────────────────────

	public static RenderTarget mainRenderTarget() {
		return Minecraft.getInstance().getMainRenderTarget();
	}

	/** Swaps the target the level is drawn into (for a remote view's pass). */
	public static void setMainRenderTarget(RenderTarget target) {
		((MainRenderTargetAccessor) Minecraft.getInstance()).netherview$setMainRenderTarget(target);
	}

	public static Camera mainCamera() {
		return Minecraft.getInstance().gameRenderer.getMainCamera();
	}

	public static GameRenderState gameRenderState() {
		return Minecraft.getInstance().gameRenderer.getGameRenderState();
	}

	/** Carries the first-person hand animation state over to the player object a respawn created. */
	public static void carryHands(net.minecraft.client.player.LocalPlayer from, net.minecraft.client.player.LocalPlayer to) {
		// The game renderer keeps the first-person hand state across player objects.
	}

	/** Visible sections of the player's own level with something to draw. */
	public static int mainRenderedSections() {
		return Minecraft.getInstance().levelRenderer.countRenderedSections();
	}

	/** Re-mesh a block in the player's own level. */
	public static void markMainBlockDirty(BlockPos pos) {
		Minecraft.getInstance().levelRenderer.setBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
	}

	public static float bossOverlayWorldDarkening(float partialTicks) {
		return Minecraft.getInstance().gameRenderer.getBossOverlayWorldDarkening(partialTicks);
	}

	// ── depth ────────────────────────────────────────────────────────────────

	/** The standard depth range (26.1): near is 0 (or -w in clip space), far is 1, and nearer means less. */
	public static final boolean REVERSED_DEPTH = false;

	/** The depth test for "nearer than or as near as". */
	public static final CompareOp NEARER_OR_EQUAL = CompareOp.LESS_THAN_OR_EQUAL;

	public static boolean isZZeroToOne() {
		return RenderSystem.getDevice().isZZeroToOne();
	}

	// ── drawing ──────────────────────────────────────────────────────────────

	/**
	 * A pipeline for this mod's shaders, drawing triangles. With {@code transforms} it has the
	 * globals, projection and dynamic transforms; {@code samplers} binds Sampler0 (and Sampler1);
	 * {@code vertices} is the vertex format, or null for a full-screen pass (no vertex buffer).
	 */
	public static RenderPipeline pipeline(String location, Identifier vertexShader, Identifier fragmentShader, boolean transforms,
			@Nullable VertexFormat vertices, int samplers, boolean cull, boolean translucent, boolean alwaysPassDepth) {
		// Shaders that care which way depth runs check this define (26.1's range is the standard one).
		RenderPipeline.Builder builder = (transforms
				? RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET, RenderPipelines.MATRICES_PROJECTION_SNIPPET)
				: RenderPipeline.builder())
				.withShaderDefine("NETHERVIEW_STANDARD_DEPTH");
		builder.withLocation(Identifier.fromNamespaceAndPath("netherview", location));
		for (int i = 0; i < samplers; i++) {
			builder.withSampler("Sampler" + i);
		}
		return builder.withVertexShader(vertexShader)
				.withFragmentShader(fragmentShader)
				.withVertexFormat(vertices != null ? vertices : DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
				.withCull(cull)
				.withColorTargetState(translucent ? new ColorTargetState(BlendFunction.TRANSLUCENT) : ColorTargetState.DEFAULT)
				.withDepthStencilState(new DepthStencilState(alwaysPassDepth ? CompareOp.ALWAYS_PASS : NEARER_OR_EQUAL, true))
				.build();
	}

	/** A pass drawing into {@code target}'s colour and depth, clearing neither. */
	public static RenderPass renderPass(Supplier<String> label, RenderTarget target) {
		return RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				label, target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty());
	}

	/** Draws {@code count} vertices (triangles) from {@code vertices}. */
	public static void drawVertices(RenderPass pass, GpuBuffer vertices, int count) {
		pass.setVertexBuffer(0, vertices);
		pass.draw(0, count);
	}

	/** Draws the full-screen triangle (no vertex buffer: the screenquad shader makes it). */
	public static void drawFullscreen(RenderPass pass) {
		pass.draw(0, 3);
	}

	/** The dynamic transforms for a model-view matrix, and a colour modulator if given. */
	public static GpuBufferSlice transform(Matrix4f modelView, @Nullable Vector4f color) {
		return RenderSystem.getDynamicUniforms().writeTransform(
				modelView, color != null ? color : new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector3f(), new Matrix4f());
	}

	public static BufferBuilder triangles(ByteBufferBuilder bytes, VertexFormat format) {
		return new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, format);
	}

}
