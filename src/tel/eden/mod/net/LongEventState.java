package tel.eden.mod.net;

import java.util.List;
import java.util.Locale;

/**
 * Snapshot of the active long-event mission state for this player, received in a
 * {@code longEventState} message from the bridge backend.
 */
public record LongEventState(boolean active, long endsAtMs, int pendingLe, int rerollCost, int triviaPoints, boolean repeatSlot2Active, String error, List<Slot> slots, List<Master> masters, List<SpecialSlot> specialSlots) {
	public LongEventState(boolean active, long endsAtMs, int pendingLe, int rerollCost, int triviaPoints, boolean repeatSlot2Active, String error, List<Slot> slots, List<Master> masters) {
		this(active, endsAtMs, pendingLe, rerollCost, triviaPoints, repeatSlot2Active, error, slots, masters, List.of());
	}
	public LongEventState(boolean active, long endsAtMs, int pendingLe, int rerollCost, int triviaPoints, String error, List<Slot> slots, List<Master> masters) {
		this(active, endsAtMs, pendingLe, rerollCost, triviaPoints, false, error, slots, masters, List.of());
	}
	public LongEventState(boolean active, long endsAtMs, int pendingLe, String error, List<Slot> slots, List<Master> masters) {
		this(active, endsAtMs, pendingLe, 0, 0, false, error, slots, masters, List.of());
	}
	public LongEventState {
		error = error == null ? "" : error;
		slots = slots == null ? List.of() : List.copyOf(slots);
		masters = masters == null ? List.of() : List.copyOf(masters);
		specialSlots = specialSlots == null ? List.of() : List.copyOf(specialSlots);
	}

	/** One mission slot ({@code "daily"}, {@code "weekly"}, or {@code "repeatable"}). */
	public record Slot(String slot, long resetAtMs, int missionId, String tag, String title, int progress, int target, int rewardLe, boolean completed, boolean canReroll, boolean canBuyReroll, long rerollAtMs, String note, String imageUrl, int completions, int maxCompletions) {
		public Slot(String slot, long resetAtMs, int missionId, String tag, String title, int progress, int target, int rewardLe, boolean completed, boolean canReroll, boolean canBuyReroll, long rerollAtMs, String note, String imageUrl) {
			this(slot, resetAtMs, missionId, tag, title, progress, target, rewardLe, completed, canReroll, canBuyReroll, rerollAtMs, note, imageUrl, 0, 0);
		}
		public Slot(String slot, long resetAtMs, int missionId, String tag, String title, int progress, int target, int rewardLe, boolean completed, boolean canReroll, long rerollAtMs, String note, String imageUrl) {
			this(slot, resetAtMs, missionId, tag, title, progress, target, rewardLe, completed, canReroll, false, rerollAtMs, note, imageUrl, 0, 0);
		}
		public Slot {
			slot = slot == null ? "" : slot;
			tag = tag == null ? "" : tag;
			title = title == null ? "" : title;
			note = note == null ? "" : note;
			imageUrl = imageUrl == null ? "" : imageUrl;
		}
	}

	public record ScavengerItem(String difficulty, boolean unlocked, long unlockAtMs, String note, String imageUrl) {
		public ScavengerItem {
			difficulty = difficulty == null ? "" : difficulty;
			note = note == null ? "" : note;
			imageUrl = imageUrl == null ? "" : imageUrl;
		}
	}

	public record ClueEntry(String note, String imageUrl) {
		public ClueEntry {
			note = note == null ? "" : note;
			imageUrl = imageUrl == null ? "" : imageUrl;
		}
	}

	public record SpecialSlot(int slotNum, String kind, String title, boolean opened, boolean completed, int completions, long unlockAtMs, int raidProgress, int raidRequirement, long expiresAtMs, int rewardLe, String note, String imageUrl, List<ScavengerItem> items, List<ClueEntry> clues, int clueRaidProgress, int clueRaidTarget, boolean clueUnlockedToday, long nextClueAtMs, long nextGuessAtMs, boolean canGuessFree, boolean canGuessTrivia, String lastGuessStatus) {
		public SpecialSlot(int slotNum, String kind, String title, boolean opened, boolean completed, int completions, long unlockAtMs, int raidProgress, int raidRequirement, long expiresAtMs, int rewardLe, String note, String imageUrl, List<ScavengerItem> items, List<ClueEntry> clues, int clueRaidProgress, int clueRaidTarget, boolean clueUnlockedToday, long nextGuessAtMs, boolean canGuessFree, boolean canGuessTrivia, String lastGuessStatus) {
			this(slotNum, kind, title, opened, completed, completions, unlockAtMs, raidProgress, raidRequirement, expiresAtMs, rewardLe, note, imageUrl, items, clues, clueRaidProgress, clueRaidTarget, clueUnlockedToday, 0L, nextGuessAtMs, canGuessFree, canGuessTrivia, lastGuessStatus);
		}
		public SpecialSlot {
			kind = kind == null ? "" : kind;
			title = title == null ? "" : title;
			note = note == null ? "" : note;
			imageUrl = imageUrl == null ? "" : imageUrl;
			items = items == null ? List.of() : List.copyOf(items);
			clues = clues == null ? List.of() : List.copyOf(clues);
			lastGuessStatus = lastGuessStatus == null ? "" : lastGuessStatus;
		}
	}

	/** One master mission track ({@code "raids"} or {@code "missions"}). */
	public record Master(String kind, String title, int tier, int tierCount, int progress, int target, int rewardLe, int earnedLe, boolean completed) {
		public Master {
			kind = kind == null ? "" : kind;
			title = title == null ? "" : title;
		}
	}

	/**
	 * Whether this reply carries the player's real state. The bot's error fallback
	 * ({@code active=false}, no slots, an error) doesn't, so it must not replace a good snapshot.
	 */
	public boolean isSnapshot() {
		return error.isEmpty() || !slots.isEmpty() || !specialSlots.isEmpty();
	}

	/** Find a special mission slot by its slot number (1 to 5). */
	public SpecialSlot findSpecialSlot(int slotNum) {
		for (SpecialSlot s : specialSlots) {
			if (s.slotNum() == slotNum) {
				return s;
			}
		}
		return null;
	}

	private static boolean isSuperAlias(String name) {
		return "special".equalsIgnoreCase(name) || "super".equalsIgnoreCase(name) || "weekly".equalsIgnoreCase(name);
	}

	/** Find a slot by its identifier ({@code "daily"}, {@code "special"}, {@code "super"}, {@code "repeatable"}, {@code "repeatable2"}). */
	public Slot findSlot(String slotName) {
		for (Slot s : slots) {
			if (s.slot().equalsIgnoreCase(slotName)) {
				return s;
			}
		}
		if (isSuperAlias(slotName)) {
			for (Slot s : slots) {
				if (isSuperAlias(s.slot())) {
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
		boolean specialOrWeekly = "special".equalsIgnoreCase(slotName) || "weekly".equalsIgnoreCase(slotName) || "super".equalsIgnoreCase(slotName);
		long remaining = resetAtMs > 0L ? Math.max(0L, resetAtMs - nowMs) : 0L;
		return "Resets in " + formatCountdown(remaining, specialOrWeekly);
	}

	/** Cooldown rule line shown in the reroll tooltip for the given slot. */
	public static String rerollRuleText(String slotName) {
		if ("daily".equalsIgnoreCase(slotName)) {
			return "Daily: once per day";
		}
		if ("special".equalsIgnoreCase(slotName) || "weekly".equalsIgnoreCase(slotName) || "super".equalsIgnoreCase(slotName)) {
			return "Super: once every 3 days";
		}
		return "Repeatable: every 3 hours";
	}
}
