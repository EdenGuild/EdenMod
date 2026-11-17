package tel.eden.mod.net;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Models and ranking/interval validation helpers for Promotion Cycle and Lifetime leaderboards. */
public final class CycleLeaderboardData {
	private static final Pattern POSITIVE_NUMBER = Pattern.compile("^\\d+(?:\\.\\d+)?$");

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
	public record CycleSnapshot(String cycleCode, List<String> cycles, List<LeaderboardEntry> raids, List<LeaderboardEntry> wars, List<RaffleWinner> raffleWinners, int totalRaids, int totalWars, int totalTickets, String error) {
		public static CycleSnapshot error(String message) {
			return new CycleSnapshot("", List.of(), List.of(), List.of(), List.of(), 0, 0, 0, message);
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
}
