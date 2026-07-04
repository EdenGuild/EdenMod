package tel.eden.mod.chat;

import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * Detects the guild level-up banner and returns it for the bridge chat, e.g.:
 *
 * <pre>
 *   Guild Level Up! Eden is now level 111  +4 Member Slots
 * </pre>
 *
 * <p>The reward tail differs at every level (member slots, bank pages, etc.), so the
 * normalized line is relayed verbatim rather than reformatted.
 */
public final class GuildLevelUpParser {
	// Gate: the banner always carries this header, and the data line always states
	// the new level. Both must be present so a stray "Guild Level Up" without the
	// value line (or vice-versa) is not relayed.
	private static final Pattern LEVEL = Pattern.compile("is now level \\d+", Pattern.CASE_INSENSITIVE);

	private GuildLevelUpParser() {
	}

	/** Cheap keyword gate before normalization runs. */
	public static boolean isCandidate(Component message) {
		return message != null && message.getString().contains("Guild Level Up");
	}

	/** Parse a system-chat component, returning the verbatim line if it is a level-up. */
	public static Optional<String> parse(Component message) {
		if (!isCandidate(message)) {
			return Optional.empty();
		}
		String text = ChatText.normalize(message.getString());
		if (!LEVEL.matcher(text).find()) {
			return Optional.empty();
		}
		return Optional.of(text);
	}
}
