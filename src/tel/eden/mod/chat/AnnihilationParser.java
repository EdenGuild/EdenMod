package tel.eden.mod.chat;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * Detects the in-game Annihilation world-event warning and extracts the lead time
 * in seconds. Visible form:
 *
 * <pre>
 *   Hateful echoes erupt from the Portal. Wynn faces Annihilation. Prepare to
 *   defend the province at the Corruption Portal in 1h!
 * </pre>
 *
 * <p>The countdown can be hours and/or minutes ("1h", "30m", "1h 30m"); the result
 * is the total seconds until the event begins.
 */
public final class AnnihilationParser {
	private static final Pattern COUNTDOWN = Pattern.compile("Wynn faces Annihilation.*?\\bin\\s+(.+?)\\s*!", Pattern.CASE_INSENSITIVE);
	private static final Pattern HOURS = Pattern.compile("(\\d+)\\s*h", Pattern.CASE_INSENSITIVE);
	private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*m", Pattern.CASE_INSENSITIVE);

	private AnnihilationParser() {
	}

	/** Cheap keyword gate before the regex/cleanup runs. */
	public static boolean isCandidate(Component message) {
		return message != null && message.getString().contains("Annihilation");
	}

	/** Parse the warning, returning seconds until Annihilation begins if present. */
	public static OptionalInt parse(Component message) {
		if (!isCandidate(message)) {
			return OptionalInt.empty();
		}
		Matcher matcher = COUNTDOWN.matcher(ChatText.normalize(message.getString()));
		if (!matcher.find()) {
			return OptionalInt.empty();
		}
		String token = matcher.group(1);
		int seconds = 0;
		Matcher h = HOURS.matcher(token);
		if (h.find()) {
			seconds += Integer.parseInt(h.group(1)) * 3600;
		}
		Matcher m = MINUTES.matcher(token);
		if (m.find()) {
			seconds += Integer.parseInt(m.group(1)) * 60;
		}
		return seconds > 0 ? OptionalInt.of(seconds) : OptionalInt.empty();
	}
}
