package dev.ryan.seamlessportals.client;

import dev.ryan.seamlessportals.net.PortalLink;
import dev.ryan.seamlessportals.server.Renamed;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * On disk, per server (and game protocol, so a different version never reads another's chunk
 * data): the portal trips recorded there and the chunks around each end of them, as last seen.
 * Files are written and read on a background thread; the chunks are the server's own packets.
 */
final class OfflineCache {
	private static final Logger LOGGER = LoggerFactory.getLogger("seamlessportals");
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "SeamlessPortals cache");
		thread.setDaemon(true);
		return thread;
	});

	/** Whether the cache saved under the old name has been looked for (see {@link Renamed}). */
	private static boolean carriedOver;

	private OfflineCache() {
	}

	/** This server's folder, or null when not on a remote server (singleplayer has the server half). */
	static Path folder() {
		Minecraft minecraft = Minecraft.getInstance();
		ServerData server = minecraft.getCurrentServer();
		if (server == null || minecraft.isLocalServer()) {
			return null;
		}
		String name = server.ip.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		if (!carriedOver) {
			carriedOver = true;
			Renamed.carryOver(minecraft.gameDirectory.toPath().resolve("netherview"), minecraft.gameDirectory.toPath().resolve("seamlessportals"));
		}
		return minecraft.gameDirectory.toPath().resolve("seamlessportals").resolve(name).resolve("protocol-" + SharedConstants.getProtocolVersion());
	}

	static List<OfflinePortals.Recorded> loadLinks(Path folder) {
		List<OfflinePortals.Recorded> links = new ArrayList<>();
		Path file = folder.resolve("links.bin");
		if (!Files.exists(file)) {
			return links;
		}
		try {
			ByteBuf buf = Unpooled.wrappedBuffer(Files.readAllBytes(file));
			FriendlyByteBuf in = new FriendlyByteBuf(buf);
			int count = in.readVarInt();
			for (int i = 0; i < count; i++) {
				links.add(new OfflinePortals.Recorded(in.readUtf(), PortalLink.STREAM_CODEC.decode(in)));
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Couldn't read recorded portals from {}: {}", file, e.toString());
		}
		return links;
	}

	static void saveLinks(Path folder, List<OfflinePortals.Recorded> links) {
		FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
		out.writeVarInt(links.size());
		for (OfflinePortals.Recorded link : links) {
			out.writeUtf(link.sourceDimension());
			PortalLink.STREAM_CODEC.encode(out, link.link());
		}
		byte[] bytes = new byte[out.readableBytes()];
		out.readBytes(bytes);
		IO.execute(() -> {
			try {
				Files.createDirectories(folder);
				Files.write(folder.resolve("links.bin"), bytes);
			} catch (IOException e) {
				LOGGER.warn("Couldn't save recorded portals: {}", e.toString());
			}
		});
	}

	private static Path chunksFile(Path folder, ResourceKey<Level> dimension, ChunkPos center) {
		String dim = dimension.identifier().toString().replaceAll("[^a-z0-9._-]", "_");
		return folder.resolve("chunks").resolve(dim + "_" + center.x() + "_" + center.z() + ".bin");
	}

	/** Save the chunks around {@code center} (nearest first), replacing what was there. */
	static void saveChunks(Path folder, ResourceKey<Level> dimension, ChunkPos center, List<ClientboundLevelChunkWithLightPacket> chunks, long capBytes) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null || chunks.isEmpty()) {
			return;
		}
		var registries = connection.registryAccess();
		Path file = chunksFile(folder, dimension, center);
		IO.execute(() -> {
			try {
				Files.createDirectories(file.getParent());
				Path temp = file.resolveSibling(file.getFileName() + ".tmp");
				try (OutputStream raw = Files.newOutputStream(temp); DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
					out.writeInt(chunks.size());
					for (ClientboundLevelChunkWithLightPacket chunk : chunks) {
						RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
						try {
							ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, chunk);
							byte[] bytes = new byte[buf.readableBytes()];
							buf.readBytes(bytes);
							out.writeInt(bytes.length);
							out.write(bytes);
						} finally {
							buf.release();
						}
					}
				}
				Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				evict(folder.resolve("chunks"), capBytes);
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Couldn't save cached chunks {}: {}", file, e.toString());
			}
		});
	}

	/** Read the chunks saved around {@code center}, if any, and hand their bytes to {@code done} on the main thread. */
	static void loadChunks(Path folder, ResourceKey<Level> dimension, ChunkPos center, Consumer<List<byte[]>> done) {
		Path file = chunksFile(folder, dimension, center);
		IO.execute(() -> {
			if (!Files.exists(file)) {
				return;
			}
			List<byte[]> chunks = new ArrayList<>();
			try (InputStream raw = Files.newInputStream(file); DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
				int count = in.readInt();
				for (int i = 0; i < count; i++) {
					byte[] bytes = new byte[in.readInt()];
					in.readFully(bytes);
					chunks.add(bytes);
				}
				Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Couldn't read cached chunks {}: {}", file, e.toString());
				return;
			}
			Minecraft.getInstance().execute(() -> done.accept(chunks));
		});
	}

	/** Oldest-used areas go first once the cache is over its cap. */
	private static void evict(Path chunks, long capBytes) throws IOException {
		List<Path> files;
		try (Stream<Path> list = Files.list(chunks)) {
			files = new ArrayList<>(list.filter(p -> p.toString().endsWith(".bin")).toList());
		}
		long total = 0;
		for (Path file : files) {
			total += Files.size(file);
		}
		if (total <= capBytes) {
			return;
		}
		files.sort(Comparator.comparingLong(file -> {
			try {
				return Files.getLastModifiedTime(file).toMillis();
			} catch (IOException e) {
				return 0L;
			}
		}));
		for (Path file : files) {
			if (total <= capBytes) {
				break;
			}
			total -= Files.size(file);
			Files.deleteIfExists(file);
		}
	}
}
