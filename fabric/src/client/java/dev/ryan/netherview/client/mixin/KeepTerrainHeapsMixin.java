package dev.ryan.netherview.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import dev.ryan.netherview.client.DimensionHandoff;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * An emptied terrain heap is freed on the next upload; right after a hand-off the old world's
 * meshes have just been released, and a heap freed now would be allocated again (a stall)
 * moments later for the new world's. Keep them a little while.
 */
@Mixin(UberGpuBuffer.class)
public abstract class KeepTerrainHeapsMixin {
	@WrapOperation(method = "uploadStagedAllocations", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/TlsfAllocator;isCompletelyFree()Z"))
	private boolean netherview$keepHeap(TlsfAllocator allocator, Operation<Boolean> original) {
		return !DimensionHandoff.keepTerrainBuffers() && original.call(allocator);
	}
}
