package tel.eden.mod.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stand-alone helper that swaps the EdenMod jar in once the game has exited.
 *
 * <p>{@link UpdateInstaller} launches this as a detached JVM (via {@code javaw} on
 * Windows so no console window appears, {@code java} elsewhere) from the freshly
 * downloaded jar. Running it from a plain {@code -cp} entry point means it has no
 * dependency on Fabric or Minecraft and replaces the old shell/batch swap script,
 * which spawned a visible command prompt and could fail to locate itself.
 *
 * <p>It waits for the still-running game to release its lock on the old jar (on
 * Windows the jar is locked until the JVM fully exits), deletes it, then copies the
 * new jar into the mods folder. The copy (not move) leaves the staged jar — which is
 * this process's own classpath and therefore locked — untouched; it lives outside
 * the mods folder, so it is never loaded twice, and is swept on the next launch.
 *
 * <p>Args: {@code <oldJar> <stagedJar> <newJar>}.
 */
public final class UpdateApplier {
	private static final long RETRY_DELAY_MS = 500L;
	private static final long MAX_WAIT_MS = 120_000L;
	private static final int EXPECTED_ARGS = 3;

	private UpdateApplier() {
	}

	/** Entry point for the detached swap process. */
	public static void main(String[] args) {
		if (args.length < EXPECTED_ARGS) {
			return;
		}
		Path oldJar = Path.of(args[0]);
		Path staged = Path.of(args[1]);
		Path newJar = Path.of(args[2]);
		try {
			waitForRelease(oldJar);
			Files.copy(staged, newJar, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			// Detached process with nowhere useful to log: just exit. The staged jar
			// is left in place so a manual install is still possible from the link.
			return;
		}
	}

	/** Delete {@code jar}, retrying until the exiting game releases its lock (bounded). */
	private static void waitForRelease(Path jar) {
		long waited = 0L;
		while (waited < MAX_WAIT_MS) {
			try {
				Files.deleteIfExists(jar);
			} catch (IOException e) {
				// Still locked by the exiting game; fall through and retry.
			}
			if (!Files.exists(jar)) {
				return;
			}
			try {
				Thread.sleep(RETRY_DELAY_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			waited += RETRY_DELAY_MS;
		}
	}
}
