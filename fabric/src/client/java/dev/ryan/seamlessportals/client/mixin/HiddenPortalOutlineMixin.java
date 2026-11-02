package dev.ryan.seamlessportals.client.mixin;

import dev.ryan.seamlessportals.client.PortalHiding;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A linked portal's blocks are left out of the mesh (the view is there instead), so they
 * shouldn't be aimed at either: no outline drawn over the view, and the crosshair goes on
 * through to whatever's behind. Only their outline shape; collision is untouched.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class HiddenPortalOutlineMixin {
	@Inject(method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
			at = @At("HEAD"), cancellable = true)
	private void seamlessportals$noOutlineOnHiddenPortals(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
		if (level instanceof ClientLevel clientLevel && PortalHiding.shouldHide(clientLevel, (BlockState) (Object) this, pos)) {
			cir.setReturnValue(Shapes.empty());
		}
	}
}
