package dev.ryan.seamlessportals.client.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryan.seamlessportals.client.PortalHiding;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Sodium's chunk meshing, in place of SectionCompiler's: linked portals' blocks and fills are left out the same way (see SectionCompilerMixin). */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class SodiumMeshingMixin {
	@WrapOperation(
			method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
			at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;renderModel(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V"))
	private void seamlessportals$hideLinkedPortals(BlockRenderer renderer, BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin,
			Operation<Void> original, @Local LevelSlice slice) {
		if (!PortalHiding.shouldHide(((LevelSliceAccessor) (Object) slice).seamlessportals$getLevel(), state, pos)) {
			original.call(renderer, model, state, pos, origin);
		}
	}

	@WrapOperation(
			method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
			at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/FluidRenderer;render(Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/TranslucentGeometryCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;)V"))
	private void seamlessportals$hideLinkedFluids(FluidRenderer renderer, LevelSlice slice, BlockState state, FluidState fluid, BlockPos pos, BlockPos origin,
			TranslucentGeometryCollector collector, ChunkBuildBuffers buffers, Operation<Void> original) {
		if (!PortalHiding.shouldHide(((LevelSliceAccessor) (Object) slice).seamlessportals$getLevel(), state, pos)) {
			original.call(renderer, slice, state, fluid, pos, origin, collector, buffers);
		}
	}
}
