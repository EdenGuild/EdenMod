package tel.eden.mod.chat;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * Detects shout announcements and renders them for the bridge chat, e.g.:
 *
 * <pre>
 *   PlayerName/Nick &lt;rank&gt; shouts: Eden [EDN] is recruiting...
 * </pre>
 *
 * <p>The rank badge between the name and {@code shouts:} is a
 * custom-font glyph stripped by {@link ChatText#normalize}; the name is hover-resolved.
 */
public final class ShoutParser {
	// The name group excludes ':' so a copied shout pasted into another channel — which
	// always arrives as "<Sender>: <pasted text>" — can't be misread as a real shout.
	private static final Pattern SHOUT = Pattern.compile("^([^:]+?)\\s+shouts:\\s+(.+)$");

	private ShoutParser() {
	}

	/** Cheap keyword gate before the regex/cleanup runs. */
	public static boolean isCandidate(Component message) {
		return message != null && message.getString().contains("shouts:");
	}

	/**
	 * Whether this is the shout-composition prompt ("Type in chat the message you would
	 * like to display to the entire server..."), shown just before the live shout preview.
	 * Used to suppress that preview so an unsent shout isn't relayed.
	 */
	public static boolean isComposePrompt(Component message) {
		return message != null && message.getString().toLowerCase(Locale.ROOT).contains("would like to display");
	}

	/** Parse a shout into its bridge-chat line ("<name> shouts: <message>"), if it is one. */
	public static Optional<String> parse(Component message) {
		if (!isCandidate(message)) {
			return Optional.empty();
		}
		Matcher matcher = SHOUT.matcher(ChatText.normalize(message.getString()));
		if (!matcher.matches()) {
			return Optional.empty();
		}
		String body = stripLinkSchemes(matcher.group(2).trim());
		return Optional.of(resolve(message, matcher.group(1)) + " shouts: " + body);
	}

	/**
	 * The shouter's real account name, if this message is a shout. Used to make
	 * shouts clickable-to-reply; purely display-side, no relay effect.
	 */
	public static Optional<String> shouterRealName(Component message) {
		if (!isCandidate(message)) {
			return Optional.empty();
		}
		Matcher matcher = SHOUT.matcher(ChatText.normalize(message.getString()));
		if (!matcher.matches()) {
			return Optional.empty();
		}
		return Optional.of(resolve(message, matcher.group(1)));
	}

	/** Strip http(s):// from any links so Discord doesn't auto-embed them when relayed. */
	private static String stripLinkSchemes(String text) {
		return text.replaceAll("(?i)https?://", "");
	}

	/** Real account name from hover/insertion metadata, else the de-nicked display name. */
	private static String resolve(Component message, String displayed) {
		// Handles every nick form ("real/nick", "real(nick)", bare nick with a nickname
		// hover) and falls back to the stripped display name.
		return ChatText.resolveClickTargetName(message, displayed);
	}
}
