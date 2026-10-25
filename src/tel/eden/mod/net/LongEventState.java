package tel.eden.mod.net;

import java.util.List;
import java.util.Locale;

/**
 * Snapshot of the active long-event mission state for this player, received in a
 * {@code longEventState} message from the bridge backend.
 */
public record LongEventState(boolean active, long endsAtMs, int pendingLe, int rerollCost, int triviaPoints, boolean repeatSlot2Active, String error, List<Slot> slots, List<Master> masters) {
	public LongEventState(boolean active, long endsAtMs, int pendingLe, int rerollCost, int triviaPoints, String error, List<Slot> slots, List<Master> masters) {
		this(active, endsAtMs, pendingLe, rerollCost, triviaPoints, false, error, slots, masters);
	}
	public LongEventState(boolean active, long endsAtMs, int pendingLe, String error, List<Slot> slots, List<Master> masters) {
		this(active, endsAtMs, pendingLe, 0, 0, false, error, slots, masters);
	}
	public LongEventState {
		error = error == null ? "" : error;
		slots = slots == null ? List.of() : List.copyOf(slots);
		masters = masters == null ? List.of() : List.copyOf(masters);
	}

	/** One mission slot ({@code "daily"}, {@code "weekly"}, or {@code "repeatable"}). */
	public record Slot(String slot, long resetAtMs, int missionId, String tag, String title, int progress, int target, int rewardLe, boolean completed, boolean canReroll, boolean canBuyReroll, long rerollAtMs, String note, String imageUrl) {
		public Slot(String slot, long resetAtMs, int missionId, String tag, String title, int progress, int target, int rewardLe, boolean completed, boolean canReroll, long rerollAtMs, String note, String imageUrl) {
			this(slot, resetAtMs, missionId, tag, title, progress, target, rewardLe, completed, canReroll, false, rerollAtMs, note, imageUrl);
		}
		public Slot {
			slot = slot == null ? "" : slot;
			tag = tag == null ? "" : tag;
			title = title == null ? "" : title;
			note = note == null ? "" : note;
			imageUrl = imageUrl == null ? "" : imageUrl;
		}
	}

	/** One master mission track ({@code "raids"} or {@code "missions"}). */
	public record Master(String kind, String title, int tier, int tierCount, int progress, int target, int rewardLe, int earnedLe, boolean completed) {
		public Master {
			kind = kind == null ? "" : kind;
			title = title == null ? "" : title;
		}
	}

	/** Find a slot by its identifier ({@code "daily"}, {@code "special"}, {@code "repeatable"}, {@code "repeatable2"}). */
	public Slot findSlot(String slotName) {
		for (Slot s : slots) {
			if (s.slot().equalsIgnoreCase(slotName)) {
				return s;
			}
		}
		if ("special".equalsIgnoreCase(slotName)) {
			for (Slot s : slots) {
				if ("weekly".equalsIgnoreCase(s.slot())) {
					return s;
				}
			}
		} else if ("weekly".equalsIgnoreCase(slotName)) {
			for (Slot s : slots) {
				if ("special".equalsIgnoreCase(s.slot())) {
					return s;
				}
			}
		}
		return null;
	}

	/**
	 * Format a remaining millisecond duration as {@code HH:MM:SS} or {@code Xd HH:MM:SS}.
	 * Non-positive durations clamp to zero.
	 */
	public static String formatCountdown(long remainingMs, boolean includeDays) {
		long totalSeconds = Math.max(0L, remainingMs / 1000L);
		if (includeDays) {
			long days = totalSeconds / 86_400L;
			long hours = (totalSeconds % 86_400L) / 3_600L;
			long minutes = (totalSeconds % 3_600L) / 60L;
			long seconds = totalSeconds % 60L;
			return String.format(Locale.ROOT, "%dd %02d:%02d:%02d", days, hours, minutes, seconds);
		}
		long hours = totalSeconds / 3_600L;
		long minutes = (totalSeconds % 3_600L) / 60L;
		long seconds = totalSeconds % 60L;
		return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
	}

	/** Live subtitle text shown above a slot card. */
	public static String slotCountdownText(String slotName, long resetAtMs, long nowMs) {
		if ("repeatable".equalsIgnoreCase(slotName) || "repeatable2".equalsIgnoreCase(slotName)) {
			return "Complete to unlock the next mission";
		}
		boolean specialOrWeekly = "special".equalsIgnoreCase(slotName) || "weekly".equalsIgnoreCase(slotName);
		long remaining = resetAtMs > 0L ? Math.max(0L, resetAtMs - nowMs) : 0L;
		return "Resets in " + formatCountdown(remaining, specialOrWeekly);
	}

	/** Cooldown rule line shown in the reroll tooltip for the given slot. */
	public static String rerollRuleText(String slotName) {
		if ("daily".equalsIgnoreCase(slotName)) {
			return "Daily: once per day";
		}
		if ("special".equalsIgnoreCase(slotName) || "weekly".equalsIgnoreCase(slotName)) {
			return "Special: once every 3 days";
		}
		return "Repeatable: every 3 hours";
	}
}
