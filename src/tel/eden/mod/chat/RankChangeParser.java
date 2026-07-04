package tel.eden.mod.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * Detects Wynncraft guild rank-change messages and extracts a {@link RankChange}.
 * The visible form is, e.g.:
 *
 * <pre>
 *   OfficerName has set MemberName guild rank from Recruit to Strategist
 * </pre>
 *
 * <p>The affected member's real account name is recovered from hover text like
 * guild chat.
 */
public final class RankChangeParser {
	private static final Pattern RANK_PATTERN = Pattern.compile("^(.+?)\\s+has set\\s+(.+?)\\s+guild rank from\\s+(\\w+)\\s+to\\s+(\\w+)$", Pattern.CASE_INSENSITIVE);

	private RankChangeParser() {
	}

	/** Cheap keyword gate before the regex/cleanup runs. */
	public static boolean isCandidate(Component message) {
		if (message == null) {
			return false;
		}
		String plain = message.getString();
		return plain.contains("has set") && plain.contains("guild rank from");
	}

	/** Parse a system-chat component, returning a rank change if it is one. */
	public static Optional<RankChange> parse(Component message) {
		if (!isCandidate(message)) {
			return Optional.empty();
		}
		Matcher matcher = RANK_PATTERN.matcher(ChatText.normalize(message.getString()));
		if (!matcher.matches()) {
			return Optional.empty();
		}
		String displayedSetter = matcher.group(1).trim();
		String displayedTarget = matcher.group(2).trim();
		String oldRank = matcher.group(3).trim();
		String newRank = matcher.group(4).trim();

		String target = ChatText.resolveRealName(message, displayedTarget);
		if (target == null) {
			return Optional.empty();
		}
		String resolvedSetter = ChatText.resolveRealName(message, displayedSetter);
		String setter = resolvedSetter != null ? resolvedSetter : displayedSetter;
		return Optional.of(new RankChange(target, oldRank, newRank, setter));
	}
}
