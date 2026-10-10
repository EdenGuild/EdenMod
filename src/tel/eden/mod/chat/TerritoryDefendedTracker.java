package tel.eden.mod.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * Detects territory defense victories from in-game chat.
 *
 * <p>Wynncraft emits two messages back-to-back when an Eden territory is successfully defended:
 * <pre>
 *   Your guild has successfully defended Chasm Overlook.
 *   [TAG] has lost the war!
 * </pre>
 * Either message may arrive first due to packet timing. Once both lines are observed
 * within {@value #WINDOW_MS} ms of each other, a {@link TerritoryDefendedEvent} is emitted.
 */
public final class TerritoryDefendedTracker {
	private static final long WINDOW_MS = 5000L;

	private static final Pattern DEFENDED_PATTERN = Pattern.compile("^Your guild has successfully defended\\s+(.+?)[.!]?$", Pattern.CASE_INSENSITIVE);

	private static final Pattern LOST_WAR_PATTERN = Pattern.compile("^(.+?)\\s+has lost the war!?$", Pattern.CASE_INSENSITIVE);

	private String pendingTerritory;
	private long pendingTerritoryTime;

	private String pendingAttacker;
	private long pendingAttackerTime;

	/**
	 * Inspect a chat message component. If this completes a paired defend sequence,
	 * returns the {@link TerritoryDefendedEvent}.
	 */
	public synchronized Optional<TerritoryDefendedEvent> onChat(Component message) {
		if (message == null) {
			return Optional.empty();
		}
		String raw = message.getString();
		boolean isDefendedLine = raw.contains("successfully defended");
		boolean isLostWarLine = raw.contains("has lost the war");
		if (!isDefendedLine && !isLostWarLine) {
			return Optional.empty();
		}

		String text = ChatText.normalize(raw);
		long now = System.currentTimeMillis();

		if (isDefendedLine) {
			Matcher m = DEFENDED_PATTERN.matcher(text);
			if (m.matches()) {
				String territory = m.group(1).trim();
				if (!territory.isEmpty()) {
					if (pendingAttacker != null && (now - pendingAttackerTime <= WINDOW_MS)) {
						String attacker = pendingAttacker;
						clear();
						return Optional.of(new TerritoryDefendedEvent(territory, attacker));
					}
					pendingTerritory = territory;
					pendingTerritoryTime = now;
				}
			}
		} else if (isLostWarLine) {
			Matcher m = LOST_WAR_PATTERN.matcher(text);
			if (m.matches()) {
				String attacker = cleanTag(m.group(1));
				if (!attacker.isEmpty() && !attacker.equalsIgnoreCase("Eden")) {
					if (pendingTerritory != null && (now - pendingTerritoryTime <= WINDOW_MS)) {
						String territory = pendingTerritory;
						clear();
						return Optional.of(new TerritoryDefendedEvent(territory, attacker));
					}
					pendingAttacker = attacker;
					pendingAttackerTime = now;
				}
			}
		}

		if (pendingTerritory != null && (now - pendingTerritoryTime > WINDOW_MS)) {
			pendingTerritory = null;
		}
		if (pendingAttacker != null && (now - pendingAttackerTime > WINDOW_MS)) {
			pendingAttacker = null;
		}

		return Optional.empty();
	}

	private static String cleanTag(String raw) {
		return raw.replaceAll("[\\[\\]]", "").trim();
	}

	public synchronized void clear() {
		pendingTerritory = null;
		pendingTerritoryTime = 0;
		pendingAttacker = null;
		pendingAttackerTime = 0;
	}
}
