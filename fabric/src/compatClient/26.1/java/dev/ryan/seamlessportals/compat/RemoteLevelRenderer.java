package dev.ryan.seamlessportals.compat;

import dev.ryan.seamlessportals.client.mixin.LevelRendererAccessor;
import dev.ryan.seamlessportals.client.mixin.RemoteCullAccessor;
import dev.ryan.seamlessportals.client.sodium.SodiumCompat;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * A second level renderer, for a dimension the player isn't in. 26.1: the {@link LevelRenderer}
 * culls and fills its render state itself (later versions split that into a LevelExtractor).
 */
public final class RemoteLevelRenderer {
	public final LevelRenderer renderer;
	/** This renderer's Sodium chunk renderer, with Sodium installed. */
	private final Object sodiumRenderer;

	public RemoteLevelRenderer(Minecraft minecraft, LevelRenderState state, int width, int height) {
		this.renderer = new LevelRenderer(
				minecraft,
				minecraft.getEntityRenderDispatcher(),
				minecraft.getBlockEntityRenderDispatcher(),
				minecraft.renderBuffers(),
				minecraft.gameRenderer.getGameRenderState(),
				minecraft.gameRenderer.getFeatureRenderDispatcher());
		// A second LevelRenderer would otherwise share the game's LevelRenderState.
		((LevelRendererAccessor) this.renderer).seamlessportals$setLevelRenderState(state);
		// Created after resources loaded, so the sky and outline target are set up by hand.
		this.renderer.onResourceManagerReload(minecraft.getResourceManager());
		this.sodiumRenderer = SodiumCompat.registerRemote(this.renderer);
	}

	public CloudRenderer clouds() {
		return this.renderer.getCloudRenderer();
	}

	/** A client level that reports its changes to this renderer. */
	public ClientLevel newLevel(ClientPacketListener connection, ClientLevel.ClientLevelData data, ResourceKey<Level> key,
			Holder<DimensionType> type, int chunkRadius, int seaLevel) {
		return new ClientLevel(connection, data, key, type, chunkRadius, chunkRadius, this.renderer, false, 0L, seaLevel);
	}

	public void setLevel(ClientLevel level) {
		SodiumCompat.asCurrent(this.renderer, () -> this.renderer.setLevel(level));
	}

	public void resize(int width, int height) {
		this.renderer.resize(width, height);
	}

	public void setBlockDirty(BlockPos pos) {
		SodiumCompat.asCurrent(this.renderer, () -> this.renderer.setBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ()));
	}

	/** Rebuilds every section (as a video settings change does). */
	public void allChanged() {
		SodiumCompat.asCurrent(this.renderer, this.renderer::allChanged);
	}

	/** Re-cull on the next extract even if the camera hasn't turned. */
	public void forceRecull() {
		((RemoteCullAccessor) this.renderer).seamlessportals$setPrevCamRotX(Double.NaN);
	}

	/** Culls, then fills the render state from {@code camera}, as GameRenderer's update and extract do. */
	public void extract(DeltaTracker delta, Camera camera, float partialTicks) {
		SodiumCompat.asCurrent(this.renderer, () -> {
			this.renderer.update(camera);
			this.renderer.extractLevel(delta, camera, partialTicks);
		});
	}

	/** A chunk's data and light are in: the culling graph may now look through it (26.1 is told; later versions see it). */
	public void chunkReady(ChunkPos pos) {
		this.renderer.onChunkReadyToRender(pos);
	}

	/** Visible sections with something to draw. */
	public int renderedSections() {
		return this.renderer.countRenderedSections();
	}

	/** Whether the section holding {@code pos} is meshed (and, where sections fade in, showing). */
	public boolean meshed(BlockPos pos) {
		return ClientVersioned.isSectionCompiledAndVisible(this.renderer, pos);
	}

	public SectionOcclusionGraph sectionOcclusionGraph() {
		return this.renderer.getSectionOcclusionGraph();
	}

	/** Whether {@code part} (the renderer or its section dispatcher, as mixins see them) is this one's. */
	public boolean owns(Object part) {
		return part == this.renderer || part != null && (part == this.renderer.getSectionRenderDispatcher() || part == this.sodiumRenderer);
	}

	public void endFrame() {
		this.renderer.endFrame();
	}

	public void close() {
		SodiumCompat.asCurrent(this.renderer, () -> this.renderer.setLevel(null));
		SodiumCompat.unregisterRemote(this.sodiumRenderer);
		this.renderer.close();
	}
}
