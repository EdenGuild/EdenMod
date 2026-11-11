package perf;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Per-scene limits from {@code perf-budgets.json}; see gametest/README.md for how they were chosen. */
record PerfBudgets(int minRenderSamples, Map<String, Budget> software, Map<String, Budget> gpu) {
	/** The slowest frame must also be at least this long for the hitch ratio to matter. */
	private static final double HITCH_FLOOR_MS = 15.0;

	record Budget(double maxModSharePercent, double maxHitchRatio, double maxModAllocKiBPerFrame) {
	}

	static PerfBudgets load() {
		try (Reader reader = new InputStreamReader(PerfBudgets.class.getResourceAsStream("/perf-budgets.json"))) {
			return new Gson().fromJson(reader, PerfBudgets.class);
		} catch (IOException | NullPointerException e) {
			throw new IllegalStateException("perf-budgets.json is missing from the gametest resources", e);
		}
	}

	/** Budgets differ by renderer: software (CI) and a GPU spend their time differently, so each has its own, enforced alike. */
	List<String> check(List<PhaseResult> results, boolean softwareRendering) {
		Map<String, Budget> scenes = softwareRendering ? software : gpu;
		List<String> violations = new ArrayList<>();
		for (PhaseResult result : results) {
			Budget budget = scenes.get(result.name());
			if (budget == null) {
				violations.add(result.name() + ": no budget in perf-budgets.json (every scene needs one)");
				continue;
			}
			// A share computed from a handful of samples is noise, and noise passes by accident.
			if (result.renderSamples() < minRenderSamples) {
				violations.add(String.format("%s: only %d render-thread samples (need %d); the measurement is not trustworthy", result.name(), result.renderSamples(), minRenderSamples));
			}
			if (result.modSharePercent() > budget.maxModSharePercent()) {
				violations.add(String.format("%s: EdenMod is %.1f%% of render-thread Java time (budget %.1f%%); hottest: %s", result.name(), result.modSharePercent(), budget.maxModSharePercent(), result.topModFrames()));
			}
			// Allocation does not depend on how fast the machine is, so unlike the CPU share it keeps its meaning on a
			// slow CI runner, where a per-frame allocation regression is only a few CPU samples out of hundreds.
			if (result.modAllocKiBPerFrame() > budget.maxModAllocKiBPerFrame()) {
				violations.add(String.format("%s: EdenMod allocates %.1f KiB per frame (budget %.1f KiB); allocated by: %s", result.name(), result.modAllocKiBPerFrame(), budget.maxModAllocKiBPerFrame(), result.topModAllocators()));
			}
			// A ratio of tiny frames is noise: on a GPU a normal frame is about 1 ms, so a 4 ms one reads as 4x.
			// Only a frame that is slow in absolute terms counts as a stall.
			if (result.hitchRatio() > budget.maxHitchRatio() && result.maxCpuMs() >= HITCH_FLOOR_MS) {
				violations.add(String.format("%s: p99 frame is %.1fx the median (budget %.1fx); max %.1f ms", result.name(), result.hitchRatio(), budget.maxHitchRatio(), result.maxCpuMs()));
			}
		}
		return violations;
	}
}
