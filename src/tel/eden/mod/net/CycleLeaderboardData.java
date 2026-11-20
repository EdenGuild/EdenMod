package tel.eden.mod.net;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Models and ranking/interval validation helpers for Promotion Cycle and Lifetime leaderboards. */
public final class CycleLeaderboardData {
	private static final Pattern POSITIVE_NUMBER = Pattern.compile("^\\d+(?:\\.\\d+)?$");
	private static final Pattern CYCLE_CODE = Pattern.compile("^(\\d{2})(\\d{2})([AB])$");

	private CycleLeaderboardData() {
	}

	/** One ranked player row on either the cycle or lifetime leaderboard. */
	public record LeaderboardEntry(String uuid, String name, int count, int rank, Integer delta) {
	}

	/** Lifetime cycle podium finishes (number of #1, #2, and #3 finishes across past cycles). */
	public record PodiumEntry(String uuid, String name, int first, int second, int third) {
	}

	/** One promotion-cycle raffle winner (only top 3 are shown publicly). */
	public record RaffleWinner(String name, int tickets, int raids) {
	}

	/** Validated custom day-interval bounds (`toDays == 0.0` means right now). */
	public record IntervalDays(double fromDays, double toDays) {
	}

	/** Snapshot returned by {@code cycleLeaderboardReply}. */
	public record CycleSnapshot(String cycleCode, List<String> cycles, List<LeaderboardEntry> raids, List<LeaderboardEntry> wars, List<RaffleWinner> raffleWinners, int totalRaids, int totalWars, int totalTickets, long endedAtMs, boolean finalized, String error) {
		public CycleSnapshot(String cycleCode, List<String> cycles, List<LeaderboardEntry> raids, List<LeaderboardEntry> wars, List<RaffleWinner> raffleWinners, int totalRaids, int totalWars, int totalTickets, String error) {
			this(cycleCode, cycles, raids, wars, raffleWinners, totalRaids, totalWars, totalTickets, 0L, false, error);
		}

		public static CycleSnapshot error(String message) {
			return new CycleSnapshot("", List.of(), List.of(), List.of(), List.of(), 0, 0, 0, 0L, false, message);
		}
	}

	/** Snapshot returned by {@code lifetimeLeaderboardReply}. */
	public record LifetimeSnapshot(boolean hasInterval, Double fromDays, Double toDays, List<LeaderboardEntry> raids, List<LeaderboardEntry> wars, List<PodiumEntry> raidFinishes, List<PodiumEntry> warFinishes, String error) {
		public static LifetimeSnapshot error(String message) {
			return new LifetimeSnapshot(false, null, null, List.of(), List.of(), List.of(), List.of(), message);
		}
	}

	/** Broadcast payload sent to guild members when a promotion cycle concludes. */
	public record ConcludedCycleSummary(String cycleCode, List<LeaderboardEntry> topRaids, List<LeaderboardEntry> topWars, List<RaffleWinner> raffleWinners, int totalRaids, int totalWars, int totalTickets) {
	}

	/** Derive a printable concluded-cycle summary from any fetched cycle snapshot. */
	public static ConcludedCycleSummary summaryFromSnapshot(CycleSnapshot snap) {
		List<LeaderboardEntry> topRaids = snap.raids().stream().filter(e -> e.count() > 0 && e.rank() <= 3).toList();
		List<LeaderboardEntry> topWars = snap.wars().stream().filter(e -> e.count() > 0 && e.rank() == 1).toList();
		List<RaffleWinner> winners = snap.raffleWinners().subList(0, Math.min(3, snap.raffleWinners().size()));
		return new ConcludedCycleSummary(snap.cycleCode(), topRaids, topWars, winners, snap.totalRaids(), snap.totalWars(), snap.totalTickets());
	}

	/**
	 * Sort entries descending by count (zeros at the bottom) and apply Standard Competition
	 * Ranking ({@code 1, 2, 2, 4}) so tied players share the same position.
	 */
	public static List<LeaderboardEntry> rankEntries(List<LeaderboardEntry> raw) {
		if (raw == null || raw.isEmpty()) {
			return List.of();
		}
		List<LeaderboardEntry> sorted = new ArrayList<>(raw);
		sorted.sort(Comparator.comparingInt(LeaderboardEntry::count).reversed().thenComparing(e -> e.name().toLowerCase(java.util.Locale.ROOT)));
		List<LeaderboardEntry> out = new ArrayList<>(sorted.size());
		int prevCount = Integer.MIN_VALUE;
		int prevRank = 0;
		for (int i = 0; i < sorted.size(); i++) {
			LeaderboardEntry entry = sorted.get(i);
			int rank = (i > 0 && entry.count() == prevCount) ? prevRank : (i + 1);
			prevCount = entry.count();
			prevRank = rank;
			out.add(new LeaderboardEntry(entry.uuid(), entry.name(), entry.count(), rank, entry.delta()));
		}
		return List.copyOf(out);
	}

	/**
	 * Return medal index ({@code 1}, {@code 2}, or {@code 3}) for {@code entry}, or {@code 0} for
	 * no medal (zero count, rank below 3, or interval-filtered leaderboards where medals are hidden).
	 */
	public static int medalForEntry(LeaderboardEntry entry, boolean showMedals) {
		if (!showMedals || entry == null || entry.count() <= 0) {
			return 0;
		}
		int rank = entry.rank();
		return rank >= 1 && rank <= 3 ? rank : 0;
	}

	/**
	 * Parse user-entered interval fields ({@code From} and optional {@code To} days ago).
	 * Only positive integers/decimals are accepted; empty {@code To} means now ({@code 0.0}),
	 * and {@code From} must be strictly greater than {@code To}. Returns {@code null} on invalid input.
	 */
	public static IntervalDays parseIntervalDays(String fromRaw, String toRaw) {
		if (fromRaw == null) {
			return null;
		}
		String fromTrim = fromRaw.trim();
		if (fromTrim.isEmpty() || !POSITIVE_NUMBER.matcher(fromTrim).matches()) {
			return null;
		}
		double fromVal;
		try {
			fromVal = Double.parseDouble(fromTrim);
		} catch (NumberFormatException e) {
			return null;
		}
		if (!Double.isFinite(fromVal) || fromVal <= 0.0) {
			return null;
		}

		double toVal = 0.0;
		if (toRaw != null && !toRaw.trim().isEmpty()) {
			String toTrim = toRaw.trim();
			if (!POSITIVE_NUMBER.matcher(toTrim).matches()) {
				return null;
			}
			try {
				toVal = Double.parseDouble(toTrim);
			} catch (NumberFormatException e) {
				return null;
			}
			if (!Double.isFinite(toVal) || toVal <= 0.0) {
				return null;
			}
		}
		if (fromVal <= toVal) {
			return null;
		}
		return new IntervalDays(fromVal, toVal);
	}

	/** Whether {@code snapshot} answers a request for {@code requestedCode} ({@code null}/empty means latest). */
	public static boolean matchesRequestedCycle(CycleSnapshot snapshot, String requestedCode) {
		if (snapshot == null || !snapshot.error().isEmpty()) {
			return false;
		}
		if (requestedCode == null || requestedCode.isBlank()) {
			return snapshot.cycles().isEmpty() || snapshot.cycleCode().equals(snapshot.cycles().getLast());
		}
		return requestedCode.trim().equals(snapshot.cycleCode());
	}

	/** Whether {@code snapshot} is an already-concluded historical cycle matching {@code cycleCode}. */
	public static boolean isHistoricalCycle(CycleSnapshot snapshot, String cycleCode) {
		return cycleCode != null && !cycleCode.isBlank() && matchesRequestedCycle(snapshot, cycleCode) && !snapshot.cycles().isEmpty() && !cycleCode.trim().equals(snapshot.cycles().getLast());
	}

	/** Whether {@code snapshot} answers a lifetime/interval request for {@code (expectedFrom, expectedTo)}. */
	public static boolean matchesRequestedInterval(LifetimeSnapshot snapshot, Double expectedFrom, Double expectedTo) {
		if (snapshot == null || !snapshot.error().isEmpty()) {
			return false;
		}
		if (expectedFrom == null) {
			return !snapshot.hasInterval();
		}
		if (!snapshot.hasInterval() || snapshot.fromDays() == null) {
			return false;
		}
		double expTo = expectedTo != null ? expectedTo : 0.0;
		double actTo = snapshot.toDays() != null ? snapshot.toDays() : 0.0;
		return Math.abs(expectedFrom - snapshot.fromDays()) < 1.0e-6 && Math.abs(expTo - actTo) < 1.0e-6;
	}

	/** Cycles 2503A and earlier counted both Party Finder and guild raids in historical tracking. */
	public static boolean countsPartyFinderRaids(String cycleCode) {
		if (cycleCode == null) {
			return false;
		}
		String trimmed = cycleCode.trim();
		return CYCLE_CODE.matcher(trimmed).matches() && trimmed.compareTo("2503A") <= 0;
	}

	/** Resolve the cycle's end timestamp in epoch ms (falling back to UTC half-month boundaries from {@code cycleCode}). */
	public static long cycleEndEpochMs(CycleSnapshot snapshot) {
		if (snapshot == null) {
			return 0L;
		}
		if (snapshot.endedAtMs() > 0L) {
			return snapshot.endedAtMs();
		}
		String code = snapshot.cycleCode();
		if (code == null) {
			return 0L;
		}
		Matcher m = CYCLE_CODE.matcher(code.trim());
		if (!m.matches()) {
			return 0L;
		}
		int year = 2000 + Integer.parseInt(m.group(1));
		int month = Integer.parseInt(m.group(2));
		try {
			LocalDate endDate = "A".equals(m.group(3)) ? LocalDate.of(year, month, 15) : LocalDate.of(year, month, 1).plusMonths(1);
			return endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
		} catch (DateTimeException e) {
			return 0L;
		}
	}

	/** Format the cycle-end countdown (or {@code "Ended"} once concluded), or empty if unknown. */
	public static String cycleEndText(CycleSnapshot snapshot, long nowMs) {
		if (snapshot == null) {
			return "";
		}
		long endMs = cycleEndEpochMs(snapshot);
		if (endMs <= 0L) {
			return snapshot.finalized() ? "Ended" : "";
		}
		if (snapshot.finalized() || isHistoricalCycle(snapshot, snapshot.cycleCode())) {
			return "Ended";
		}
		long remainingMs = endMs - nowMs;
		return remainingMs > 0L ? "Ends in " + LongEventState.formatCountdown(remainingMs, true) : "Ended";
	}
}
