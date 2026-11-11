package support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Where the gametests write their results, and the "I finished" markers the build checks for. */
public final class Output {
	private Output() {
	}

	public static Path dir() {
		Path dir = Path.of(System.getProperty("edenmod.gametest.out", "build/gametest")).toAbsolutePath();
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			throw new IllegalStateException("Could not create " + dir, e);
		}
		return dir;
	}

	/** Copies a screenshot into the folder the build reports, so a human can look at what a test saw. */
	public static void keepScreenshot(Path screenshot) {
		try {
			Path screens = dir().resolve("screens");
			Files.createDirectories(screens);
			Files.copy(screenshot, screens.resolve(screenshot.getFileName()), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new IllegalStateException("Could not save screenshot " + screenshot, e);
		}
	}

	/** Whether a suite ("perf", "behaviour", "ui") was selected with {@code -Pgametest.suites=}; all run by default. */
	public static boolean enabled(String suite) {
		return List.of(System.getProperty("edenmod.gametest.suites", "perf,behaviour,ui").split(",")).contains(suite);
	}

	/**
	 * Records that a test ran to the end. The build only trusts a test that left its marker: a
	 * client that crashed at start-up can still exit 0, which must not read as a pass.
	 */
	public static void marker(String name) {
		try {
			Files.writeString(dir().resolve(name), "ok");
		} catch (IOException e) {
			throw new IllegalStateException("Could not write marker " + name, e);
		}
	}
}
