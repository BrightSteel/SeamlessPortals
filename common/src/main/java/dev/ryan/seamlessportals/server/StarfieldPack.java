package dev.ryan.seamlessportals.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The SeamlessPortals resource pack, for players without the mod: what draws a
 * {@code starfield} portal's panel ({@link StarfieldPanels}). Black concrete with the
 * panel's custom model data becomes a flat square whose texture is the game's own
 * {@code end_portal} texture (mapped into the block atlas, so nothing of Mojang's is in the
 * pack), and from 26.3 (pack format 97) the item shaders draw it as the end gateway does.
 * Older versions show that texture as it is.
 */
public final class StarfieldPack {
	private static final String ROOT = "seamlessportals/pack/";
	/** The pack's files, from the jar's {@code seamlessportals/pack/}; {@code overlay_26_3/} is the 26.3 overlay. */
	private static final List<String> FILES = List.of(
			"assets/minecraft/items/black_concrete.json",
			"assets/minecraft/atlases/blocks.json",
			"assets/seamlessportals/models/item/starfield.json",
			"overlay_26_3/assets/minecraft/shaders/core/item.vsh",
			"overlay_26_3/assets/minecraft/shaders/core/item.fsh");
	private static final String OVERLAY = "overlay_26_3";
	private static final String META = """
			{
			  "pack": {
			    "description": "SeamlessPortals: starfield portals",
			    "pack_format": 15,
			    "min_format": 15,
			    "max_format": 32767,
			    "supported_formats": [15, 32767]
			  },
			  "overlays": {
			    "entries": [
			      {"min_format": 97, "max_format": 97, "directory": "%s"}
			    ]
			  }
			}
			""".formatted(OVERLAY);

	private StarfieldPack() {
	}

	/** Writes the pack to {@code zip}, unless it's there already as it would be. Returns whether it wrote it. */
	public static boolean write(Path zip) throws IOException {
		byte[] bytes = build();
		if (Files.isRegularFile(zip) && Arrays.equals(Files.readAllBytes(zip), bytes)) {
			return false;
		}
		Files.createDirectories(zip.getParent());
		Path temp = zip.resolveSibling(zip.getFileName() + ".tmp");
		Files.write(temp, bytes);
		Files.move(temp, zip, StandardCopyOption.REPLACE_EXISTING);
		return true;
	}

	private static byte[] build() throws IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			put(zip, "pack.mcmeta", META.getBytes(StandardCharsets.UTF_8));
			for (String file : FILES) {
				try (InputStream in = StarfieldPack.class.getClassLoader().getResourceAsStream(ROOT + file)) {
					if (in == null) {
						throw new IOException("Missing " + ROOT + file + " in the jar");
					}
					put(zip, file, in.readAllBytes());
				}
			}
		}
		return out.toByteArray();
	}

	/** An entry with a fixed time, so the same files make the same zip. */
	private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
		ZipEntry entry = new ZipEntry(name);
		entry.setTime(0L);
		zip.putNextEntry(entry);
		zip.write(bytes);
		zip.closeEntry();
	}
}
