package tel.eden.mod.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * Detects Wynncraft guild reactions (Quick Reactions events) in system chat, e.g.:
 *
 * <pre>
 *   REACTIONS » Solve 374 - 71 first to win! (answer in guild chat)
 *   ✅ neyuo got it in 4.0s! The answer was 303. (+1 point — 7 total)
 *   ❌ iceblue failed! The answer was 303.
 * </pre>
 *
 * <p>These are typically gold-pill system messages from the bridge that also appear
 * in guild chat. This parser captures them for relaying through the bridge chat channel.
 */
public final class ReactionsParser {
	// Reaction prompt: "REACTIONS » <question>"
	private static final Pattern REACTION_PROMPT = Pattern.compile("^REACTIONS\\s*»\\s*(.+?)(?:\\s*\\(answer in guild chat\\))?$", Pattern.CASE_INSENSITIVE);

	// Reaction response: "✅/❌ <player> <result>! <answer_info>. <points>"
	private static final Pattern REACTION_RESPONSE = Pattern.compile("^([✅❌])\\s+([a-zA-Z0-9_]+)\\s+(.+?)!\\s*(.*)$", Pattern.CASE_INSENSITIVE);

	private ReactionsParser() {
	}

	/** Cheap keyword gate before the regex runs. */
	public static boolean isCandidate(Component message) {
		if (message == null) {
			return false;
		}
		String plain = message.getString();
		return plain.contains("REACTIONS") || plain.contains("✅") || plain.contains("❌");
	}

	/** Parse a system-chat component, returning a reaction event if it is one. */
	public static Optional<ReactionEvent> parse(Component message) {
		if (!isCandidate(message)) {
			return Optional.empty();
		}
		String text = ChatText.normalize(message.getString());

		// Try to match a reaction prompt.
		Matcher promptMatcher = REACTION_PROMPT.matcher(text);
		if (promptMatcher.find()) {
			String question = promptMatcher.group(1).trim();
			return Optional.of(new ReactionEvent("prompt", "", question, "", ""));
		}

		// Try to match a reaction response.
		Matcher responseMatcher = REACTION_RESPONSE.matcher(text);
		if (responseMatcher.find()) {
			String status = responseMatcher.group(1).equals("✅") ? "success" : "failed";
			String player = responseMatcher.group(2).trim();
			String result = responseMatcher.group(3).trim(); // e.g., "got it in 4.0s" or "failed"
			String details = responseMatcher.group(4).trim(); // e.g., "The answer was 303. (+1 point — 7 total)"
			return Optional.of(new ReactionEvent(status, player, result, details, ""));
		}

		return Optional.empty();
	}

	/**
	 * A quick reaction event parsed from guild chat.
	 *
	 * <p>For prompts: {@code type} is "prompt", {@code player} is empty, {@code content} is the question.
	 * For responses: {@code type} is "success" or "failed", {@code player} is who answered, {@code content} is the result (e.g. "got it in 4.0s"), {@code details} is the answer/points info.
	 */
	public static record ReactionEvent(String type, String player, String content, String details, String unused) {
	}
}
