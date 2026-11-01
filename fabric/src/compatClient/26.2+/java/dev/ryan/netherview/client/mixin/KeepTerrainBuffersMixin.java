package dev.ryan.netherview.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryan.netherview.client.DimensionHandoff;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * On a change of world vanilla throws away the section dispatcher, GPU heaps and all, and the
 * new world's first meshes then allocate fresh ones: a stall of tens of milliseconds a few
 * frames in, which vanilla hides behind its loading screen. Right after a hand-off there is
 * none, so the player's own renderer keeps its dispatcher (as vanilla does when settings
 * change) and only the old world's meshes are released.
 */
@Mixin(LevelRenderer.class)
public abstract class KeepTerrainBuffersMixin {
	@WrapOperation(method = "resetLevelRenderData", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;dispose()V"))
	private void netherview$keepDispatcher(SectionRenderDispatcher dispatcher, Operation<Void> original) {
		if (this.netherview$keep()) {
			dispatcher.clearCompileQueue();
		} else {
			original.call(dispatcher);
		}
	}

	@WrapOperation(method = "resetLevelRenderData", at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;sectionRenderDispatcher:Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;",
			opcode = org.objectweb.asm.Opcodes.PUTFIELD))
	private void netherview$keepDispatcherField(LevelRenderer renderer, SectionRenderDispatcher value, Operation<Void> original) {
		if (!this.netherview$keep()) {
			original.call(renderer, value);
		}
	}

	private boolean netherview$keep() {
		return (Object) this == Minecraft.getInstance().levelRenderer && DimensionHandoff.keepTerrainBuffers();
	}
}
