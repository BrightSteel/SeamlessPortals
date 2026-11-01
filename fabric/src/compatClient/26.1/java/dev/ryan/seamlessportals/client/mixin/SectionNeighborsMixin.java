package dev.ryan.seamlessportals.client.mixin;

import dev.ryan.seamlessportals.client.RemoteDimensions;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.1 culls (and meshes) a section only once all eight neighbouring chunks are in. A portal
 * view is only sent the chunks it can see, a cone from the far portal, so the chunks around
 * its camera never all arrive and the view would stay almost empty. For remote views a
 * section needs only its own chunk, as later versions require.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection")
public abstract class SectionNeighborsMixin {
	@Shadow
	@Final
	SectionRenderDispatcher this$0;

	@Shadow
	private volatile long sectionNode;

	@Shadow
	private boolean doesChunkExistAt(long sectionNode) {
		throw new AssertionError();
	}

	@Inject(method = "hasAllNeighbors", at = @At("HEAD"), cancellable = true)
	private void seamlessportals$ownChunkForRemoteViews(CallbackInfoReturnable<Boolean> cir) {
		if (RemoteDimensions.isRemote(this.this$0)) {
			cir.setReturnValue(this.doesChunkExistAt(this.sectionNode));
		}
	}
}
