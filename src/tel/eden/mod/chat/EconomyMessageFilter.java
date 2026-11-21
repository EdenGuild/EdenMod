package tel.eden.mod.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * Filters territory economy and management announcements from chat.
 *
 * <p>Matches territory upgrade and bonus changes, tax rate adjustments, route style changes,
 * border open/close toggles, guild headquarters assignments, loadout applications, and
 * territory resource overflow/stabilization alerts. Normal player chat messages discussing
 * territory changes are preserved because they include an author separator ({@code :}).
 */
public final class EconomyMessageFilter {
	private static final Pattern FORMAT_CODES = Pattern.compile("(?i)[§&][0-9a-fk-orx]|&\\{[^}]+\\}");

	private static final String ACTOR_ACTIONS = "changed \\d+ (?:upgrades|bonuses) on .+" + "|changed the (?:global )?(?:ally )?tax(?: of .+)? to \\d+%" + "|changed (?:the global style|the style of .+) to (?:fastest|cheapest)" + "|changed (?:the global borders|the borders of .+) to (?:open|close)" + "|set the guild headquarters to .+" + "|set .+ (?:bonus|upgrade) to level \\d+ on .+" + "|removed .+ (?:bonus|upgrade) from .+" + "|applied the loadout(?:\\s+|:).+ on .+";

	private static final Pattern ACTOR_ECONOMY_PATTERN = Pattern.compile("^([^:]{1,64}?)\\s+(" + ACTOR_ACTIONS + ")$", Pattern.CASE_INSENSITIVE);
	private static final Pattern SYSTEM_ECONOMY_PATTERN = Pattern.compile("^(?:Territory .+ (?:is using|is producing) more resources than it can store[!.]?|Territory .+ production has stabil(?:ised|ized)[.!]?)$", Pattern.CASE_INSENSITIVE);

	private EconomyMessageFilter() {
	}

	/**
	 * Checks if an incoming system chat component is a territory economy announcement.
	 *
	 * @param message the chat component to check
	 * @return true if the message is an economy announcement that should be hidden
	 */
	public static boolean isEconomyMessage(Component message) {
		return parse(message).isPresent();
	}

	/** Parse a raw component into a normalized economy line, resolving known nicknames when possible. */
	public static Optional<String> parse(Component message) {
		return parseComponent(message, false);
	}

	/**
	 * Parse a component for backend relay, returning empty if a player actor is an
	 * unresolved nickname so raw nicknames are never reported.
	 */
	public static Optional<String> parseForRelay(Component message) {
		return parseComponent(message, true);
	}

	private static Optional<String> parseComponent(Component message, boolean requireVerifiedActor) {
		if (message == null) {
			return Optional.empty();
		}
		String rawText = message.getString();
		if (rawText == null || rawText.isBlank()) {
			return Optional.empty();
		}
		String normalized = normalize(rawText);
		Matcher actorMatcher = ACTOR_ECONOMY_PATTERN.matcher(normalized);
		if (actorMatcher.matches()) {
			String displayedActor = actorMatcher.group(1).trim();
			Optional<String> verified = PlayerNameResolver.resolveVerified(message, displayedActor);
			if (requireVerifiedActor && verified.isEmpty()) {
				return Optional.empty();
			}
			String actor = verified.orElse(displayedActor);
			return Optional.of(clip(actor + " " + actorMatcher.group(2)));
		}
		if (SYSTEM_ECONOMY_PATTERN.matcher(normalized).matches()) {
			return Optional.of(clip(normalized));
		}
		return Optional.empty();
	}

	/**
	 * Checks if normalized raw chat text matches territory economy patterns.
	 *
	 * @param rawText the raw or unformatted chat text
	 * @return true if the text matches an economy message pattern
	 */
	public static boolean isEconomyMessage(String rawText) {
		return parse(rawText).isPresent();
	}

	/** Parse a raw string into a normalized economy line, or empty if it is not an economy message. */
	public static Optional<String> parse(String rawText) {
		if (rawText == null || rawText.isBlank()) {
			return Optional.empty();
		}
		String normalized = normalize(rawText);
		if (!ACTOR_ECONOMY_PATTERN.matcher(normalized).matches() && !SYSTEM_ECONOMY_PATTERN.matcher(normalized).matches()) {
			return Optional.empty();
		}
		return Optional.of(clip(normalized));
	}

	private static String clip(String normalized) {
		return normalized.length() > 300 ? normalized.substring(0, 300) : normalized;
	}

	/**
	 * Strips formatting, replacement characters, and Wynncraft glyph padding,
	 * collapsing whitespace into standard single spaces.
	 */
	static String normalize(String rawText) {
		if (rawText == null || rawText.isBlank()) {
			return "";
		}
		String text = FORMAT_CODES.matcher(rawText).replaceAll("").replace("§", "").replace("\uFFFD", "");
		return ChatText.normalize(text);
	}
}
