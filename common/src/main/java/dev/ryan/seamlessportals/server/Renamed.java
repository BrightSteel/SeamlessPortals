package dev.ryan.seamlessportals.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SeamlessPortals was called NetherView: files and folders saved under the old name (configs,
 * portals, the client's remembered chunks) are moved to the new one the first time they're
 * looked for, so nothing set up before the rename is lost.
 */
public final class Renamed {
	private static final Logger LOGGER = LoggerFactory.getLogger("seamlessportals");

	private Renamed() {
	}

	/** Moves {@code old} to {@code current} if only the old one exists. */
	public static void carryOver(Path old, Path current) {
		if (Files.exists(current) || !Files.exists(old)) {
			return;
		}
		try {
			if (current.getParent() != null) {
				Files.createDirectories(current.getParent());
			}
			Files.move(old, current);
			LOGGER.info("Moved {} to {} (NetherView is now SeamlessPortals)", old, current);
		} catch (IOException e) {
			LOGGER.warn("Couldn't move {} to {}: {}", old, current, e.toString());
		}
	}
}
