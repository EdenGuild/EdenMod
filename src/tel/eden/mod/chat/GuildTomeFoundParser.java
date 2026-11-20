package tel.eden.mod.chat;

import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * Detects the system-chat line Wynncraft shows when a Guild Tome drops into the guild's
 * reward pool:
 *
 * <pre>
 *   A Guild Tome has been found and added to the Guild Rewards
 * </pre>
 *
 * <p>The whole line must match, so a player quoting it in chat ("Name: A Guild Tome ...")
 * is not mistaken for the real announcement.
 */
public final class GuildTomeFoundParser {
	private static final Pattern FOUND = Pattern.compile("^A Guild Tome has been found and added to the Guild Rewards[.!]?$", Pattern.CASE_INSENSITIVE);

	private GuildTomeFoundParser() {
	}

	/** Whether {@code message} is the "Guild Tome found" announcement. */
	public static boolean matches(Component message) {
		if (message == null || !message.getString().contains("Guild Tome has been found")) {
			return false;
		}
		return FOUND.matcher(ChatText.normalize(message.getString())).matches();
	}
}
