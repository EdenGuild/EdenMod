package tel.eden.mod.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads a newer EdenMod jar and swaps it in when the game closes.
 *
 * <p>The new jar is downloaded to a staging folder (<em>never</em> the mods folder,
 * so it can't be loaded a second time and cause a duplicate-mod crash). A JVM
 * shutdown hook then launches {@link UpdateApplier} as a small detached JVM (via
 * {@code javaw}/{@code java}, so no console window appears) that deletes the old jar
 * and copies the new one into the mods folder. On Windows the old jar is file-locked
 * while the game runs, so the helper retries until the game has fully exited; on Unix
 * the swap applies immediately and simply takes effect on the next launch.
 *
 * <p>The applier runs from a copy of the <em>current</em> jar (not the downloaded
 * one): a release older than this self-updater wouldn't contain {@link UpdateApplier},
 * and the old jar can't be its own classpath because it must stay deletable.
 *
 * <p>Failure is non-destructive: the staged jar lives outside the mods folder, and
 * the old jar is only removed once the swap actually runs, so a failed update never
 * leaves the mods folder with two EdenMod jars.
 */
public final class UpdateInstaller {
	private static final Logger LOGGER = LoggerFactory.getLogger("edenmod");
	private static final String MOD_ID = "edenmod";
	private static final int HTTP_OK = 200;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

	/** Outcome of a download-and-stage attempt. */
	public enum Result {
		/** Downloaded and staged; the swap will run when the game closes. */
		SCHEDULED,
		/** The mod isn't running from a jar (dev env) — can't self-apply. */
		NOT_INSTALLED_FROM_JAR,
		/** The download or staging failed. */
		FAILED
	}

	/** Download {@code info}'s jar and arrange the on-close swap. */
	public Result downloadAndStage(UpdateInfo info) {
		Path oldJar = currentJar();
		if (oldJar == null || oldJar.getParent() == null) {
			return Result.NOT_INSTALLED_FROM_JAR;
		}
		try {
			Path stageDir = FabricLoader.getInstance().getGameDir().resolve("edenmod-update");
			Files.createDirectories(stageDir);
			sweepStaleStaging(stageDir);
			Path staged = stageDir.resolve("edenmod-" + info.version() + ".jar");
			download(info.jarUrl(), staged);
			if (!Files.isRegularFile(staged) || Files.size(staged) <= 0) {
				return Result.FAILED;
			}
			Path newJar = oldJar.getParent().resolve("edenmod-" + info.version() + ".jar");
			// The swap helper runs from a copy of the current jar, which is guaranteed
			// to contain UpdateApplier and is neither the (deletable) old jar nor the
			// staged jar. It is left behind and swept on the next launch.
			Path helper = stageDir.resolve("edenmod-helper.jar");
			Files.copy(oldJar, helper, StandardCopyOption.REPLACE_EXISTING);
			Runtime.getRuntime().addShutdownHook(new Thread(() -> spawnSwapper(oldJar, staged, newJar, helper), "edenmod-apply"));
			return Result.SCHEDULED;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Update download/stage failed", e);
			return Result.FAILED;
		}
	}

	private void download(String url, Path target) throws IOException {
		try {
			HttpResponse<Path> resp = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "EdenMod-updater").timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofFile(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
			if (resp.statusCode() != HTTP_OK) {
				throw new IOException("download returned HTTP " + resp.statusCode());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("download interrupted", e);
		}
	}

	private static Path currentJar() {
		try {
			return FabricLoader.getInstance().getModContainer(MOD_ID).flatMap(c -> c.getOrigin().getPaths().stream().findFirst()).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).filter(Files::isRegularFile).orElse(null);
		} catch (RuntimeException e) {
			// Some origins (nested jars/dev classpath) don't expose a single path.
			return null;
		}
	}

	/** Launch the detached {@link UpdateApplier} JVM (runs on after this JVM exits). */
	private void spawnSwapper(Path oldJar, Path staged, Path newJar, Path helper) {
		try {
			ProcessBuilder pb = new ProcessBuilder(javaExecutable(), "-cp", helper.toString(), "tel.eden.mod.update.UpdateApplier", oldJar.toString(), staged.toString(), newJar.toString());
			// No inherited console (javaw already has none on Windows); discard streams
			// so the detached process never blocks on a full pipe.
			pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			pb.redirectError(ProcessBuilder.Redirect.DISCARD);
			pb.start();
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Failed to launch the update applier", e);
		}
	}

	/**
	 * The JVM launcher from the running JRE: {@code javaw} on Windows (so the swap
	 * pops up no console window), {@code java} elsewhere. Falls back to the bare name
	 * on the PATH if the expected binary isn't found under {@code java.home}.
	 */
	private static String javaExecutable() {
		Path bin = Path.of(System.getProperty("java.home", ""), "bin");
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		if (windows) {
			Path javaw = bin.resolve("javaw.exe");
			if (Files.isRegularFile(javaw)) {
				return javaw.toString();
			}
			Path java = bin.resolve("java.exe");
			return Files.isRegularFile(java) ? java.toString() : "javaw";
		}
		Path java = bin.resolve("java");
		return Files.isRegularFile(java) ? java.toString() : "java";
	}

	/** Best-effort removal of jars staged by an earlier (already-applied) update. */
	private static void sweepStaleStaging(Path stageDir) {
		try (java.util.stream.Stream<Path> files = Files.list(stageDir)) {
			files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException e) {
					// Possibly still locked by an in-flight applier; leave it.
				}
			});
		} catch (IOException e) {
			LOGGER.debug("Could not sweep stale update staging", e);
		}
	}
}
