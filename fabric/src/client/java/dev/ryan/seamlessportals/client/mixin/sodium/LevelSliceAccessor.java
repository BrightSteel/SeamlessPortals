package dev.ryan.seamlessportals.client.mixin.sodium;

import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The level a Sodium mesh task's copy of blocks was taken from. */
@Mixin(LevelSlice.class)
public interface LevelSliceAccessor {
	@Accessor("level")
	ClientLevel seamlessportals$getLevel();
}
