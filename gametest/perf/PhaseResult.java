package perf;

import java.util.List;
import java.util.Map;

/** Everything measured for one scene, in units that are meaningful across machines. */
record PhaseResult(String name, int frames, double medianCpuMs, double p99CpuMs, double maxCpuMs, double hitchRatio, double allocKiBPerFrame, double modSharePercent, Map<String, Integer> topModFrames, int renderSamples, String slowestFrames) {
	static PhaseResult of(String name, List<FrameProbe.Sample> samples, JfrShare share) {
		double[] cpu = samples.stream().mapToDouble(s -> s.cpuNanos() / 1e6).sorted().toArray();
		double alloc = samples.stream().mapToDouble(s -> s.allocBytes() / 1024.0).average().orElse(0);
		double median = cpu[cpu.length / 2];
		double p99 = cpu[(int) Math.min(cpu.length - 1, Math.floor(cpu.length * 0.99))];
		double max = cpu[cpu.length - 1];
		// How much worse the bad frames are than a normal one; independent of machine speed.
		double hitch = median > 0 ? p99 / median : 0;
		// Where the slowest frames fell, by frame number within the scene: a cluster at the start means warm-up,
		// an even spread means something periodic. This is what makes a CI failure explain itself.
		List<Integer> order = new java.util.ArrayList<>();
		for (int i = 0; i < samples.size(); i++) {
			order.add(i);
		}
		order.sort((a, b) -> Long.compare(samples.get(b).cpuNanos(), samples.get(a).cpuNanos()));
		StringBuilder slowest = new StringBuilder();
		for (int i = 0; i < Math.min(6, order.size()); i++) {
			slowest.append(i == 0 ? "" : ", ").append('#').append(order.get(i)).append('=').append(String.format(java.util.Locale.ROOT, "%.1f", samples.get(order.get(i)).cpuNanos() / 1e6)).append(" ms");
		}
		return new PhaseResult(name, cpu.length, median, p99, max, hitch, alloc, share.modPercent(), share.topModFrames(), share.renderSamples(), slowest.toString());
	}
}
