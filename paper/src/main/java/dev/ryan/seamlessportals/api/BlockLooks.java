package dev.ryan.seamlessportals.api;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

/**
 * How some blocks look to players, for plugins that show blocks as other blocks by rewriting
 * chunk packets (custom blocks drawn from a vanilla block's states, say). Portal views send the
 * far side's chunks in their own packets, which such plugins never see, and which are of another
 * world than the player's: registered with {@link SeamlessPortalsApi#addBlockLooks}, they're asked
 * here instead, with the right world, as each chunk is sent.
 *
 * <p>Plugins whose custom blocks are real block states on the server need nothing: portal views
 * show what's there.</p>
 */
public interface BlockLooks {
	/**
	 * Whether blocks in {@code state} may look like something else. Asked of the states a chunk
	 * section holds before its blocks are looked at one by one, and of each block's state: keep
	 * it quick.
	 */
	boolean mayChange(BlockData state);

	/**
	 * How the block at {@code x}, {@code y}, {@code z} in {@code world}, in {@code state}, looks
	 * to players; null for as it is. Called on the main thread.
	 */
	@Nullable
	BlockData looks(World world, int x, int y, int z, BlockData state);
}
