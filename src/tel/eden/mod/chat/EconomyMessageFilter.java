package tel.eden.mod.chat;

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

	private static final Pattern ECONOMY_PATTERN = Pattern.compile("^(?:" + "[^:]{1,64} changed \\d+ (?:upgrades|bonuses) on .+" + "|[^:]{1,64} changed the (?:global )?(?:ally )?tax(?: of .+)? to \\d+%" + "|[^:]{1,64} changed (?:the global style|the style of .+) to (?:fastest|cheapest)" + "|[^:]{1,64} changed (?:the global borders|the borders of .+) to (?:open|close)" + "|[^:]{1,64} set the guild headquarters to .+" + "|[^:]{1,64} set .+ (?:bonus|upgrade) to level \\d+ on .+" + "|[^:]{1,64} removed .+ (?:bonus|upgrade) from .+" + "|[^:]{1,64} applied the loadout(?:\\s+|:).+ on .+" + "|Territory .+ (?:is using|is producing) more resources than it can store[!.]?" + "|Territory .+ production has stabil(?:ised|ized)[.!]?" + ")$", Pattern.CASE_INSENSITIVE);

	private EconomyMessageFilter() {
	}

	/**
	 * Checks if an incoming system chat component is a territory economy announcement.
	 *
	 * @param message the chat component to check
	 * @return true if the message is an economy announcement that should be hidden
	 */
	public static boolean isEconomyMessage(Component message) {
		return message != null && isEconomyMessage(message.getString());
	}

	/**
	 * Checks if normalized raw chat text matches territory economy patterns.
	 *
	 * @param rawText the raw or unformatted chat text
	 * @return true if the text matches an economy message pattern
	 */
	public static boolean isEconomyMessage(String rawText) {
		if (rawText == null || rawText.isBlank()) {
			return false;
		}
		return ECONOMY_PATTERN.matcher(normalize(rawText)).matches();
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
