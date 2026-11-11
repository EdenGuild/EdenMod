package perf;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Records, once per rendered frame, how much CPU time and heap the render thread used since
 * the previous frame.
 *
 * <p>CPU time is used rather than wall time on purpose: the client gametest advances the game
 * in lockstep with the test thread, so wall-clock gaps include waiting that has nothing to do
 * with the mod, and a CI runner's speed only scales CPU time instead of adding noise to it.
 * A render-thread stall caused by the mod (a cleanup burst, a deep hash) is CPU work and
 * shows up as a spike here.
 */
final class FrameProbe {
	private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

	private final List<Sample> samples = new ArrayList<>();
	private volatile boolean recording;
	private volatile int totalFrames;
	private long lastCpuNanos = -1;
	private long lastWallNanos = -1;
	private long lastAllocBytes = -1;

	record Sample(long cpuNanos, long wallNanos, long allocBytes) {
	}

	/** Called on the render thread once per frame. */
	void onFrame() {
		long cpu = THREADS.getCurrentThreadCpuTime();
		long wall = System.nanoTime();
		long alloc = THREADS.getCurrentThreadAllocatedBytes();
		if (recording && lastCpuNanos >= 0) {
			samples.add(new Sample(cpu - lastCpuNanos, wall - lastWallNanos, alloc - lastAllocBytes));
		}
		totalFrames++;
		lastCpuNanos = cpu;
		lastWallNanos = wall;
		lastAllocBytes = alloc;
	}

	synchronized void start() {
		samples.clear();
		recording = true;
	}

	synchronized List<Sample> stop() {
		recording = false;
		return new ArrayList<>(samples);
	}

	/** Frames rendered since the probe was registered, recording or not. */
	int totalFrames() {
		return totalFrames;
	}
}
