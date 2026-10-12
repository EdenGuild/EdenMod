package tel.eden.mod.war;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.chat.ChatText;
import tel.eden.mod.config.BridgeConfig;

/**
 * Plays an anvil falling sound when a territory is taken by an opposing guild.
 *
 * <p>Client-side only; does not require backend/bot connection. Wynncraft announces
 * captures server-wide with the format:
 * <pre>
 *   [TAG] has taken control of <territory>!
 * </pre>
 */
public final class TerritoryLossTracker {
	private static final Pattern LOSS_PATTERN = Pattern.compile("^\\[([A-Za-z0-9]{3,4})\\]\\s+has taken control of\\s+(.+?)[!.]*$", Pattern.CASE_INSENSITIVE);

	private static long lastSoundPlayTime = 0L;
	private static final long DEBOUNCE_MS = 500L;

	private TerritoryLossTracker() {
	}

	/**
	 * Inspects an incoming system chat message line and plays the anvil sound if it matches
	 * a territory capture not suppressed by configuration or the territory blacklist.
	 */
	public static void onChat(String rawLine) {
		if (rawLine == null || !rawLine.contains("has taken control of")) {
			return;
		}

		EdenModClient client = EdenModClient.instance();
		BridgeConfig config = (client != null) ? client.config() : null;
		if (config == null || !config.territoryLossSound) {
			return;
		}

		if (processMessage(rawLine, config)) {
			playAnvilSound();
		}
	}

	/**
	 * Inspects a chat message and returns whether it is a territory loss event
	 * that should trigger the anvil sound under the given configuration.
	 */
	public static boolean processMessage(String rawLine, BridgeConfig config) {
		if (rawLine == null || config == null || !config.territoryLossSound) {
			return false;
		}

		String clean = ChatText.normalize(WarDPS.clean(rawLine));
		if (isIgnoredChatType(clean)) {
			return false;
		}

		Matcher m = LOSS_PATTERN.matcher(clean);
		if (!m.matches()) {
			return false;
		}

		String tag = m.group(1).trim();
		if (isEden(tag)) {
			return false;
		}

		String territory = m.group(2).trim();
		if (territory.isEmpty()) {
			return false;
		}

		if (config.territoryLossBlacklistEnabled && isBlacklisted(territory, config)) {
			return false;
		}

		return true;
	}

	/**
	 * Checks whether the specified territory name matches any entry in the blacklist (case-insensitive).
	 */
	public static boolean isBlacklisted(String territory, BridgeConfig config) {
		if (territory == null || config == null || config.territoryLossBlacklist == null) {
			return false;
		}
		for (String blacklisted : config.territoryLossBlacklist) {
			if (blacklisted != null && blacklisted.trim().equalsIgnoreCase(territory.trim())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isEden(String tag) {
		return tag.equalsIgnoreCase("EDN") || tag.equalsIgnoreCase("EDEN");
	}

	private static boolean isIgnoredChatType(String clean) {
		return clean.startsWith("[Guild") || clean.startsWith("[GUILD") || clean.startsWith("[Allies") || clean.startsWith("[ALLIES") || clean.startsWith("[Party") || clean.startsWith("[PARTY");
	}

	public static void playAnvilSound() {
		long now = System.currentTimeMillis();
		if (now - lastSoundPlayTime < DEBOUNCE_MS) {
			return;
		}
		lastSoundPlayTime = now;

		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.getSoundManager() != null) {
				mc.execute(() -> {
					try {
						mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ANVIL_LAND, 1.0f));
					} catch (Throwable ignored) {
					}
				});
			}
		} catch (Throwable ignored) {
		}
	}
}
