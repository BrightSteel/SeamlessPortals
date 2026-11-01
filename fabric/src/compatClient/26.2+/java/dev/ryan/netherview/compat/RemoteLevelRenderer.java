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
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * A second level renderer, for a dimension the player isn't in. 26.2+: a {@link LevelRenderer}
 * plus the {@link LevelExtractor} that fills its render state (and culls).
 */
public final class RemoteLevelRenderer {
	public final LevelRenderer renderer;
	private final LevelExtractor extractor;
	private final LevelRenderState state;

	public RemoteLevelRenderer(Minecraft minecraft, LevelRenderState state, int width, int height) {
		this.state = state;
		this.renderer = new LevelRenderer(
				minecraft.getEntityRenderDispatcher(),
				minecraft.getBlockEntityRenderDispatcher(),
				minecraft.getModelManager(),
				minecraft.getTextureManager(),
				minecraft.getAtlasManager(),
				minecraft.getShaderManager(),
				minecraft.gameRenderer,
				width,
				height);
		// A second LevelRenderer would otherwise share the game's LevelRenderState.
		((LevelRendererAccessor) this.renderer).netherview$setLevelRenderState(state);
		this.extractor = new LevelExtractor(minecraft, state, this.renderer);
	}

	public CloudRenderer clouds() {
		return this.renderer.cloudRenderer();
	}

	/** A client level that reports its changes to this renderer. */
	public ClientLevel newLevel(ClientPacketListener connection, ClientLevel.ClientLevelData data, ResourceKey<Level> key,
			Holder<DimensionType> type, int chunkRadius, int seaLevel) {
		return new ClientLevel(connection, data, key, type, chunkRadius, chunkRadius, this.extractor, false, 0L, seaLevel);
	}

	public void setLevel(ClientLevel level) {
		this.extractor.setLevel(level);
	}

	public void resize(int width, int height) {
		this.renderer.resize(width, height);
	}

	public void setBlockDirty(BlockPos pos) {
		this.extractor.setBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
	}

	/** Re-cull on the next extract even if the camera hasn't turned. */
	public void forceRecull() {
		((RemoteCullAccessor) this.extractor).netherview$setPrevCamRotX(Double.NaN);
	}

	/** Culls, then fills the render state from {@code camera}. */
	public void extract(DeltaTracker delta, Camera camera, float partialTicks) {
		this.extractor.extract(delta, camera, partialTicks);
		this.state.playerCompiledSectionCallback = null;
	}

	/** A chunk's data and light are in. (26.2+: the culling graph learns of loaded chunks itself.) */
	public void chunkReady(ChunkPos pos) {
	}

	/** Visible sections with something to draw. */
	public int renderedSections() {
		return this.extractor.countRenderedSections();
	}

	public SectionOcclusionGraph sectionOcclusionGraph() {
		return this.renderer.sectionOcclusionGraph();
	}

	/** Whether {@code part} (a renderer or extractor, as mixins see it) is this one's. */
	public boolean owns(Object part) {
		return part == this.renderer || part == this.extractor;
	}

	public void endFrame() {
		this.renderer.endFrame();
	}

	public void close() {
		this.extractor.setLevel(null);
		this.renderer.close();
	}
}
