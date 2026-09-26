package tel.eden.mod.chat;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Assigns a deterministic 1-based occurrence index to repeated identical events
 * within a short rolling window. Used for guild-bank events so depositing the same
 * item several times in a row stays distinct (3 deposits = seq 1..3) instead of
 * being collapsed by content-based dedup.
 *
 * <p>Every connected mod sees the same chat in the same order, so within the window
 * they compute the same seq for a given occurrence — and the backend's cross-client
 * dedup collapses the same physical event seen by multiple clients. The window is
 * deliberately short so that <em>stale</em> earlier deposits (which different clients
 * may or may not have witnessed, depending on when they connected) don't inflate the
 * count differently across clients and cause duplicate Discord messages.
 *
 * <p>An optional tiny re-emit guard also coalesces the exact same line delivered
 * twice in quick succession onto one seq. Reward tickers disable that guard because
 * two rapid packets are two authoritative handouts; bank/chat callers retain it.
 */
public final class OccurrenceSequencer {
	// The same line re-delivered within this gap is a re-emit, not a new deposit.
	private static final long REEMIT_GUARD_MS = 200L;

	private final long windowMillis;
	private final boolean coalesceRapidReemits;
	private final Map<String, ArrayDeque<Long>> seen = new HashMap<>();

	public OccurrenceSequencer(long windowMillis) {
		this(windowMillis, true);
	}

	public OccurrenceSequencer(long windowMillis, boolean coalesceRapidReemits) {
		this.windowMillis = windowMillis;
		this.coalesceRapidReemits = coalesceRapidReemits;
	}

	/** Record an occurrence of {@code signature} and return its index in the window. */
	public synchronized int next(String signature) {
		long now = System.currentTimeMillis();
		purgeExpired(now);
		ArrayDeque<Long> times = seen.computeIfAbsent(signature, k -> new ArrayDeque<>());
		// A near-instant repeat is the same line emitted twice; reuse its index so the
		// backend dedups it instead of treating it as a second deposit.
		if (coalesceRapidReemits && !times.isEmpty() && now - times.peekLast() < REEMIT_GUARD_MS) {
			return times.size();
		}
		times.addLast(now);
		return times.size();
	}

	/** Drop expired occurrences and their signatures so unique chat lines cannot accumulate forever. */
	private void purgeExpired(long now) {
		Iterator<ArrayDeque<Long>> iterator = seen.values().iterator();
		while (iterator.hasNext()) {
			ArrayDeque<Long> times = iterator.next();
			while (!times.isEmpty() && now - times.peekFirst() > windowMillis) {
				times.pollFirst();
			}
			if (times.isEmpty()) {
				iterator.remove();
			}
		}
	}
}
