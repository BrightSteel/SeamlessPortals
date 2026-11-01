package dev.ryan.seamlessportals.client.mixin;

import dev.ryan.seamlessportals.client.PortalHiding;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.TheEndGatewayRenderer;
import net.minecraft.client.renderer.blockentity.state.EndGatewayRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TheEndGatewayRenderer.class)
public abstract class TheEndGatewayRendererMixin {
	/** A gateway fill with a live view drops its beam too (its faces go with AbstractEndPortalRendererMixin). */
	@Inject(method = "extractRenderState(Lnet/minecraft/world/level/block/entity/TheEndGatewayBlockEntity;Lnet/minecraft/client/renderer/blockentity/state/EndGatewayRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V", at = @At("TAIL"))
	private void seamlessportals$hideLinkedGateway(TheEndGatewayBlockEntity blockEntity, EndGatewayRenderState state, float partialTicks, Vec3 cameraPosition,
			ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress, CallbackInfo ci) {
		if (blockEntity.getLevel() instanceof ClientLevel level && PortalHiding.hidesEndPortal(level, blockEntity.getBlockPos())) {
			state.height = 0;
			state.facesToShow.clear();
		}
	}
}
