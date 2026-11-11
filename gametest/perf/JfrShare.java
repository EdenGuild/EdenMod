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
record JfrShare(double modPercent, int renderSamples, Map<String, Integer> topModFrames, double modAllocPercent, Map<String, Long> topModAllocators) {
	private static final String MOD_PREFIX = "tel.eden.mod.";

	/** Runs {@code work} under a 2 ms execution-sample recording and analyses it. */
	static JfrShare around(Path dumpFile, Runnable work) {
		try (Recording recording = new Recording()) {
			recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2));
			// Allocation is attributed to the code that made it. Unlike CPU time it does not depend on how fast the
			// machine is, so it still shows a per-frame allocation regression on a slow CI runner, where the same
			// regression is a few CPU samples out of hundreds.
			recording.enable("jdk.ObjectAllocationSample").with("throttle", "10000/s");
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
		long allocTotal = 0;
		long allocMod = 0;
		Map<String, Integer> byFrame = new TreeMap<>();
		Map<String, Long> allocByFrame = new TreeMap<>();
		for (RecordedEvent event : RecordingFile.readAllEvents(dumpFile)) {
			String eventType = event.getEventType().getName();
			boolean cpu = eventType.equals("jdk.ExecutionSample");
			boolean allocation = eventType.equals("jdk.ObjectAllocationSample");
			if (!cpu && !allocation) {
				continue;
			}
			var thread = event.getThread(cpu ? "sampledThread" : "eventThread");
			if (thread == null || !"Render thread".equals(thread.getJavaName()) || event.getStackTrace() == null) {
				continue;
			}
			String modFrame = null;
			for (RecordedFrame frame : event.getStackTrace().getFrames()) {
				String type = frame.getMethod().getType().getName();
				if (type.startsWith(MOD_PREFIX)) {
					modFrame = type.substring(MOD_PREFIX.length()) + "." + frame.getMethod().getName();
					break;
				}
			}
			if (cpu) {
				render++;
				if (modFrame != null) {
					mod++;
					byFrame.merge(modFrame, 1, Integer::sum);
				}
			} else {
				long weight = event.getLong("weight");
				allocTotal += weight;
				if (modFrame != null) {
					allocMod += weight;
					allocByFrame.merge(modFrame, weight, Long::sum);
				}
			}
		}
		Map<String, Integer> top = new LinkedHashMap<>();
		byFrame.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5).forEach(e -> top.put(e.getKey(), e.getValue()));
		Map<String, Long> topAlloc = new LinkedHashMap<>();
		allocByFrame.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5).forEach(e -> topAlloc.put(e.getKey(), e.getValue()));
		Files.deleteIfExists(dumpFile);
		return new JfrShare(render == 0 ? 0 : 100.0 * mod / render, render, top, allocTotal == 0 ? 0 : 100.0 * allocMod / allocTotal, topAlloc);
	}
}
