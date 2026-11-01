package dev.ryan.netherview.compat;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
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
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.joml.Vector3fc;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.ryan.netherview.client.mixin.MainRenderTargetAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/** The client calls that differ between Minecraft versions; one copy per version under fabric/src/compatClient. (26.3) */
public final class ClientVersioned {
	/** In GameRenderer.renderLevel, the first call after the level is drawn: before the 3D HUD (hand). */
	public static final String AFTER_LEVEL_RENDER = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V";

	private ClientVersioned() {
	}

	public static void extractCamera(Camera camera, CameraRenderState state, DeltaTracker delta) {
		camera.extractRenderState(state, delta);
	}

	/** Draws the level as GameRenderer does, from the extracted state, with the given fog. */
	public static void renderLevel(LevelRenderer renderer, GraphicsResourceAllocator allocator, DeltaTracker delta, LevelRenderState state, GpuBufferSlice fog) {
		CameraRenderState camera = state.cameraRenderState;
		renderer.render(allocator, false, camera, fog, camera.fogData.color, true, false);
	}

	public static void updateGlobals(GlobalSettingsUniform uniform, int width, int height, double glintStrength, LevelRenderState level, DeltaTracker delta,
			int menuBlur, net.minecraft.world.phys.Vec3 cameraPos, boolean rgss) {
		uniform.update(width, height, glintStrength, level.gameTime, level.worldPartialTicks, menuBlur, cameraPos, rgss);
	}

	public static Vector3fc blockLightTint(EnvironmentAttributeProbe probe, float partialTicks) {
		return probe.getValue(EnvironmentAttributes.BLOCK_LIGHT_TINT, partialTicks);
	}

	public static Vector3fc skyLightColor(EnvironmentAttributeProbe probe, float partialTicks) {
		return probe.getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, partialTicks);
	}

	public static Vector3fc ambientLightColor(EnvironmentAttributeProbe probe, float partialTicks) {
		return probe.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, partialTicks);
	}

	public static void setPipeline(RenderPass pass, RenderPipeline pipeline) {
		pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
	}

	public static void bindTexture(RenderPass pass, String name, GpuTextureView view, GpuSampler sampler) {
		pass.setUniform(name, view, sampler);
	}

	public static void setScreen(Minecraft minecraft, Screen screen) {
		minecraft.gui.setScreen(screen);
	}

	/** An offscreen colour + depth target. */
	public static TextureTarget newTarget(String name, int width, int height) {
		return new TextureTarget(name, width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
	}

	public static boolean isSectionCompiledAndVisible(LevelRenderer renderer, BlockPos pos) {
		return renderer.isSectionCompiledAndVisible(pos, 0L);
	}

	/** Loads a chunk from packet data into the client chunk cache, as ClientPacketListener does. */
	public static LevelChunk replaceWithPacketData(ClientChunkCache cache, int x, int z, ClientboundLevelChunkPacketData data) {
		return cache.replaceWithPacketData(x, z, data);
	}

	public static int chunkX(ClientboundLevelChunkWithLightPacket packet) {
		return packet.x();
	}

	public static int chunkZ(ClientboundLevelChunkWithLightPacket packet) {
		return packet.z();
	}

	public static ClientboundLevelChunkPacketData chunkData(ClientboundLevelChunkWithLightPacket packet) {
		return packet.chunkData();
	}

	public static ClientboundLightUpdatePacketData lightData(ClientboundLevelChunkWithLightPacket packet) {
		return packet.lightData();
	}

	public static BitSet skyYMask(ClientboundLightUpdatePacketData data) {
		return data.skyYMask();
	}

	public static BitSet emptySkyYMask(ClientboundLightUpdatePacketData data) {
		return data.emptySkyYMask();
	}

	public static List<byte[]> skyUpdates(ClientboundLightUpdatePacketData data) {
		return data.skyUpdates();
	}

	public static BitSet blockYMask(ClientboundLightUpdatePacketData data) {
		return data.blockYMask();
	}

	public static BitSet emptyBlockYMask(ClientboundLightUpdatePacketData data) {
		return data.emptyBlockYMask();
	}

	public static List<byte[]> blockUpdates(ClientboundLightUpdatePacketData data) {
		return data.blockUpdates();
	}

	// ── the game renderer ────────────────────────────────────────────────────

	public static RenderTarget mainRenderTarget() {
		return Minecraft.getInstance().gameRenderer.mainRenderTarget();
	}

	/** Swaps the target the level is drawn into (for a remote view's pass). */
	public static void setMainRenderTarget(RenderTarget target) {
		((MainRenderTargetAccessor) Minecraft.getInstance().gameRenderer).netherview$setMainRenderTarget(target);
	}

	public static Camera mainCamera() {
		return Minecraft.getInstance().gameRenderer.mainCamera();
	}

	public static GameRenderState gameRenderState() {
		return Minecraft.getInstance().gameRenderer.gameRenderState();
	}

	/** Carries the first-person hand animation state over to the player object a respawn created. */
	public static void carryHands(net.minecraft.client.player.LocalPlayer from, net.minecraft.client.player.LocalPlayer to) {
		// The first-person hand state lives on the player object here: copy it field by field.
		try {
			Object source = from.firstPersonHandsAndItems();
			Object target = to.firstPersonHandsAndItems();
			for (java.lang.reflect.Field field : source.getClass().getDeclaredFields()) {
				if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
					field.setAccessible(true);
					field.set(target, field.get(source));
				}
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// cosmetic only: the hand re-equips as vanilla would
		}
	}

	/** Visible sections of the player's own level with something to draw. */
	public static int mainRenderedSections() {
		return Minecraft.getInstance().levelExtractor.countRenderedSections();
	}

	/** Re-mesh a block in the player's own level. */
	public static void markMainBlockDirty(BlockPos pos) {
		Minecraft.getInstance().levelExtractor.setBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
	}

	public static float bossOverlayWorldDarkening(float partialTicks) {
		return Minecraft.getInstance().gameRenderer.bossOverlayWorldDarkening(partialTicks);
	}

	// ── depth ────────────────────────────────────────────────────────────────

	/** Reversed Z: near is 1 (or w in clip space), far is 0, and nearer means greater. */
	public static final boolean REVERSED_DEPTH = true;

	/** The depth test for "nearer than or as near as". */
	public static final CompareOp NEARER_OR_EQUAL = CompareOp.GREATER_THAN_OR_EQUAL;

	public static boolean isZZeroToOne() {
		return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
	}

	// ── drawing ──────────────────────────────────────────────────────────────

	/**
	 * A pipeline for this mod's shaders, drawing triangles. With {@code transforms} it has the
	 * globals, projection and dynamic transforms; {@code samplers} binds Sampler0 (and Sampler1);
	 * {@code vertices} is the vertex format, or null for a full-screen pass (no vertex buffer).
	 */
	public static RenderPipeline pipeline(String location, Identifier vertexShader, Identifier fragmentShader, boolean transforms,
			@Nullable VertexFormat vertices, int samplers, boolean cull, boolean translucent, boolean alwaysPassDepth) {
		RenderPipeline.Builder builder = transforms ? RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET) : RenderPipeline.builder();
		builder.withLocation(Identifier.fromNamespaceAndPath("netherview", location));
		if (transforms) {
			builder.withBindGroupLayout(BindGroupLayouts.PROJECTION).withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS);
		}
		if (samplers == 1) {
			builder.withBindGroupLayout(BindGroupLayouts.SAMPLER0);
		} else if (samplers == 2) {
			builder.withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1);
		}
		builder.withVertexShader(vertexShader).withFragmentShader(fragmentShader);
		if (vertices != null) {
			builder.withVertexBinding(0, vertices);
		}
		return builder.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(cull)
				.withColorTargetState(translucent ? new ColorTargetState(BlendFunction.TRANSLUCENT) : ColorTargetState.DEFAULT)
				.withDepthStencilState(new DepthStencilState(alwaysPassDepth ? CompareOp.ALWAYS_PASS : NEARER_OR_EQUAL, true))
				.build();
	}

	/** A pass drawing into {@code target}'s colour and depth, clearing neither. */
	public static RenderPass renderPass(Supplier<String> label, RenderTarget target) {
		return RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				label, target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty());
	}

	/** Draws {@code count} vertices (triangles) from {@code vertices}. */
	public static void drawVertices(RenderPass pass, GpuBuffer vertices, int count) {
		pass.setVertexBuffer(0, vertices.slice());
		pass.draw(count, 1, 0, 0);
	}

	/** Draws the full-screen triangle (no vertex buffer: the screenquad shader makes it). */
	public static void drawFullscreen(RenderPass pass) {
		pass.draw(3, 1, 0, 0);
	}

	/** The dynamic transforms for a model-view matrix, and a colour modulator if given. */
	public static GpuBufferSlice transform(Matrix4f modelView, @Nullable Vector4f color) {
		return color == null
				? RenderSystem.getDynamicUniforms().writeTransform(modelView)
				: RenderSystem.getDynamicUniforms().writeTransform(modelView, color);
	}

	public static BufferBuilder triangles(ByteBufferBuilder bytes, VertexFormat format) {
		return new BufferBuilder(bytes, PrimitiveTopology.TRIANGLES, format);
	}

}
