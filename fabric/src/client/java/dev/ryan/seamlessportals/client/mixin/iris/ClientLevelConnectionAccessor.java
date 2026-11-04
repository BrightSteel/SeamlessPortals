package dev.ryan.seamlessportals.client.mixin.iris;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The server's dimensions while a level's being set, before the player (and Minecraft.getConnection) exists. */
@Mixin(ClientLevel.class)
public interface ClientLevelConnectionAccessor {
	@Accessor("connection")
	ClientPacketListener seamlessportals$getConnection();
}
