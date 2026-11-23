package tel.eden.mod.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * Detects guild "flavour" announcements that should be mirrored into the
 * Discord bridge chat (not the management feed), e.g.:
 *
 * <pre>
 *   KingVonGaming has finished their weekly objective.
 *   m1ngx210 has started boosting the guild
 * </pre>
 *
 * <p>Resolves nicknames to real account names via {@link PlayerNameResolver}.
 */
public final class GuildAnnounceParser {
	private static final Pattern ANNOUNCE_PATTERN = Pattern.compile("^(.+?)\\s+(has finished their weekly objective\\.?|has started boosting the guild\\.?)$", Pattern.CASE_INSENSITIVE);

	private GuildAnnounceParser() {
	}

	/** Cheap keyword gate before the cleanup/match runs. */
	public static boolean isCandidate(Component message) {
		if (message == null) {
			return false;
		}
		String plain = message.getString();
		return plain.contains("weekly objective") || plain.contains("boosting the guild");
	}

	/** Parse a system-chat component, resolving known nicknames when possible. */
	public static Optional<String> parse(Component message) {
		return parseComponent(message, false);
	}

	/**
	 * Parse a component for backend relay, returning empty if the player is an
	 * unresolved nickname so raw nicknames are never reported.
	 */
	public static Optional<String> parseForRelay(Component message) {
		return parseComponent(message, true);
	}

	private static Optional<String> parseComponent(Component message, boolean requireVerifiedActor) {
		if (!isCandidate(message)) {
			return Optional.empty();
		}
		Matcher matcher = ANNOUNCE_PATTERN.matcher(ChatText.normalize(message.getString()));
		if (!matcher.matches()) {
			return Optional.empty();
		}
		String displayedActor = matcher.group(1).trim();
		Optional<String> verified = PlayerNameResolver.resolveVerified(message, displayedActor);
		if (requireVerifiedActor && verified.isEmpty()) {
			return Optional.empty();
		}
		String actor = verified.orElse(displayedActor);
		return Optional.of(actor + " " + matcher.group(2));
	}
}
