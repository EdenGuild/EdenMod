package support;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets every suite run even if an earlier one failed, and reports all failures together at the end.
 *
 * <p>Fabric's runner stops at the first entrypoint that throws, so a perf failure used to hide whether
 * the other suites pass. On CI a run takes minutes, so finding out about one problem per run is slow.
 * Each suite runs inside {@link #run}, which records a failure instead of throwing; the last entrypoint,
 * {@link FailureReport}, throws once with everything that went wrong.
 */
public final class Failures {
	private static final List<String> FAILED = new ArrayList<>();

	private Failures() {
	}

	/** A suite body. */
	@FunctionalInterface
	public interface Body {
		void run() throws Exception;
	}

	public static void run(String suite, Body body) {
		try {
			body.run();
		} catch (Throwable failure) {
			System.out.println("[gametest] suite '" + suite + "' FAILED: " + failure);
			failure.printStackTrace(System.out);
			String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
			FAILED.add(suite + ": " + message.replace("\n", "\n    "));
		}
	}

	/** Throws one AssertionError naming every suite that failed, or does nothing if none did. */
	static void throwIfAny() {
		if (!FAILED.isEmpty()) {
			throw new AssertionError("EdenMod gametests failed:\n  " + String.join("\n  ", FAILED));
		}
	}
}
