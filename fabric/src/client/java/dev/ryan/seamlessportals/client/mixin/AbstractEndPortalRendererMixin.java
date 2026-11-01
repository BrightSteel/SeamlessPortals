package dev.ryan.seamlessportals.client.mixin;

import dev.ryan.seamlessportals.client.PortalHiding;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.blockentity.state.EndPortalRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractEndPortalRenderer.class)
public abstract class AbstractEndPortalRendererMixin {
	/** A linked end portal is drawn as a window, so its starfield (a block entity, not part of the chunk mesh) is left out. */
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void seamlessportals$hideLinkedEndPortal(TheEndPortalBlockEntity blockEntity, EndPortalRenderState state, float partialTicks, Vec3 cameraPosition,
			ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress, CallbackInfo ci) {
		if (blockEntity.getLevel() instanceof ClientLevel level && PortalHiding.hidesEndPortal(level, blockEntity.getBlockPos())) {
			state.facesToShow.clear();
		}
	}
}
