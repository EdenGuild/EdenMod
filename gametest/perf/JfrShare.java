package perf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/**
 * Samples the render thread's Java stacks and reports what share of them are inside EdenMod.
 *
 * <p>Only Java samples are counted. Native time (the graphics driver, which is software
 * rendering on CI) is excluded, so the share answers "of the Java work done to draw a frame,
 * how much is ours" and stays comparable between a laptop GPU and a CI runner.
 */
record JfrShare(double modPercent, int renderSamples, Map<String, Integer> topModFrames) {
	private static final String MOD_PREFIX = "tel.eden.mod.";

	/** Runs {@code work} under a 2 ms execution-sample recording and analyses it. */
	static JfrShare around(Path dumpFile, Runnable work) {
		try (Recording recording = new Recording()) {
			recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2));
			recording.start();
			try {
				work.run();
			} finally {
				recording.stop();
			}
			Files.createDirectories(dumpFile.getParent());
			recording.dump(dumpFile);
			return analyse(dumpFile);
		} catch (IOException e) {
			throw new IllegalStateException("Could not read JFR recording " + dumpFile, e);
		}
	}

	private static JfrShare analyse(Path dumpFile) throws IOException {
		int render = 0;
		int mod = 0;
		Map<String, Integer> byFrame = new TreeMap<>();
		for (RecordedEvent event : RecordingFile.readAllEvents(dumpFile)) {
			if (!"jdk.ExecutionSample".equals(event.getEventType().getName())) {
				continue;
			}
			var thread = event.getThread("sampledThread");
			if (thread == null || !"Render thread".equals(thread.getJavaName()) || event.getStackTrace() == null) {
				continue;
			}
			render++;
			for (RecordedFrame frame : event.getStackTrace().getFrames()) {
				String type = frame.getMethod().getType().getName();
				if (type.startsWith(MOD_PREFIX)) {
					mod++;
					byFrame.merge(type.substring(MOD_PREFIX.length()) + "." + frame.getMethod().getName(), 1, Integer::sum);
					break;
				}
			}
		}
		Map<String, Integer> top = new LinkedHashMap<>();
		byFrame.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5).forEach(e -> top.put(e.getKey(), e.getValue()));
		Files.deleteIfExists(dumpFile);
		return new JfrShare(render == 0 ? 0 : 100.0 * mod / render, render, top);
	}
}
