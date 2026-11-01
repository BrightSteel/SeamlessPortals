package dev.ryan.netherview.compat;

import dev.ryan.netherview.client.mixin.LevelRendererAccessor;
import dev.ryan.netherview.client.mixin.RemoteCullAccessor;
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

	public RemoteLevelRenderer(Minecraft minecraft, LevelRenderState state, int width, int height) {
		this.renderer = new LevelRenderer(
				minecraft,
				minecraft.getEntityRenderDispatcher(),
				minecraft.getBlockEntityRenderDispatcher(),
				minecraft.renderBuffers(),
				minecraft.gameRenderer.getGameRenderState(),
				minecraft.gameRenderer.getFeatureRenderDispatcher());
		// A second LevelRenderer would otherwise share the game's LevelRenderState.
		((LevelRendererAccessor) this.renderer).netherview$setLevelRenderState(state);
		// Created after resources loaded, so the sky and outline target are set up by hand.
		this.renderer.onResourceManagerReload(minecraft.getResourceManager());
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
		this.renderer.setLevel(level);
	}

	public void resize(int width, int height) {
		this.renderer.resize(width, height);
	}

	public void setBlockDirty(BlockPos pos) {
		this.renderer.setBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
	}

	/** Re-cull on the next extract even if the camera hasn't turned. */
	public void forceRecull() {
		((RemoteCullAccessor) this.renderer).netherview$setPrevCamRotX(Double.NaN);
	}

	/** Culls, then fills the render state from {@code camera}, as GameRenderer's update and extract do. */
	public void extract(DeltaTracker delta, Camera camera, float partialTicks) {
		this.renderer.update(camera);
		this.renderer.extractLevel(delta, camera, partialTicks);
	}

	/** A chunk's data and light are in: the culling graph may now look through it (26.1 is told; later versions see it). */
	public void chunkReady(ChunkPos pos) {
		this.renderer.onChunkReadyToRender(pos);
	}

	/** Visible sections with something to draw. */
	public int renderedSections() {
		return this.renderer.countRenderedSections();
	}

	public SectionOcclusionGraph sectionOcclusionGraph() {
		return this.renderer.getSectionOcclusionGraph();
	}

	/** Whether {@code part} (the renderer or its section dispatcher, as mixins see them) is this one's. */
	public boolean owns(Object part) {
		return part == this.renderer || part != null && part == this.renderer.getSectionRenderDispatcher();
	}

	public void endFrame() {
		this.renderer.endFrame();
	}

	public void close() {
		this.renderer.setLevel(null);
		this.renderer.close();
	}
}
