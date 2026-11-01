package dev.ryan.seamlessportals.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Remote entity packets are replayed through vanilla's handlers with the listener aimed at
 * the remote level; a portal hand-off also marks the player loaded early.
 */
@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccessor {
	@Accessor("level")
	ClientLevel seamlessportals$getLevel();

	@Accessor("level")
	void seamlessportals$setLevel(ClientLevel level);

	@Invoker("notifyPlayerLoaded")
	void seamlessportals$notifyPlayerLoaded();
}
