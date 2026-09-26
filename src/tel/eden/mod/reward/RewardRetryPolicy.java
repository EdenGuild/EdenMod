package tel.eden.mod.reward;

/** Pure reward-retry accounting, kept separate from the menu driver for tests. */
final class RewardRetryPolicy {
	static final long BACKOFF_BASE_MS = 500L;
	static final long BACKOFF_MAX_MS = 8000L;

	record Correction(int unavailableUnits, int silentUnits) {
	}

	private RewardRetryPolicy() {
	}

	/**
	 * Split a shortfall into units covered by explicit unavailable responses and units
	 * with no outcome (including clicks the stopped burst never sent). An explicit
	 * unavailable response owns one sent-click outcome before confirmations are counted;
	 * this prevents a duplicated ticker from masking the rejected unit.
	 */
	static Correction correction(int requested, int sent, int confirmed, int unavailableSignals) {
		int safeRequested = Math.max(0, requested);
		int safeSent = Math.max(0, Math.min(sent, safeRequested));
		int unavailableUnits = Math.max(0, Math.min(unavailableSignals, safeSent));
		int maxConfirmable = safeSent - unavailableUnits;
		int safeConfirmed = Math.max(0, Math.min(confirmed, maxConfirmable));
		int silentUnits = Math.max(0, safeRequested - safeConfirmed - unavailableUnits);
		return new Correction(unavailableUnits, silentUnits);
	}

	/** First rejection retries immediately; later ones wait 0.5s, 1s, 2s, ... (capped). */
	static long backoffMs(int consecutiveUnavailable) {
		if (consecutiveUnavailable <= 1) {
			return 0L;
		}
		int shift = Math.min(30, consecutiveUnavailable - 2);
		long delay = BACKOFF_BASE_MS << shift;
		return Math.min(BACKOFF_MAX_MS, delay);
	}
}
