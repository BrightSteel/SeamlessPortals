package dev.ryan.netherview.client.mixin;

import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A chunk packet's block data, to tell a chunk the server resends from the copy a hand-off filled in. */
@Mixin(ClientboundLevelChunkPacketData.class)
public interface ChunkPacketDataAccessor {
	@Accessor("buffer")
	byte[] netherview$getBuffer();
}
