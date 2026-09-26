package tel.eden.mod.chat;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/** Detects Wynncraft rejecting a guild-reward click while its reward service is busy. */
public final class RewardUnavailableParser {
	private static final String UNAVAILABLE = "rewards are not available at the moment";
	private static final String NOT_ENOUGH_PREFIX = "your guild does not have enough";
	private static final String NOT_ENOUGH_SUFFIX = "to send a reward";

	private RewardUnavailableParser() {
	}

	/**
	 * Whether this line is the transient reward-service rejection. A contains check is
	 * intentional: timestamps and Wynncraft's private-use prefix glyphs can surround the
	 * stable English message.
	 */
	public static boolean matches(Component message) {
		return message != null && message.getString().toLowerCase(Locale.ROOT).contains(UNAVAILABLE);
	}

	/** Whether the guild has run out of the reward being sent, making retries terminal. */
	public static boolean isOutOfStock(Component message) {
		if (message == null) {
			return false;
		}
		String text = message.getString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
		return text.contains(NOT_ENOUGH_PREFIX) && text.contains(NOT_ENOUGH_SUFFIX);
	}
}
