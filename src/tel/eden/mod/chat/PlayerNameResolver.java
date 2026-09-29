package tel.eden.mod.chat;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

/** Canonical player identity: real Minecraft usernames, with nicknames remembered as aliases. */
public final class PlayerNameResolver {
	private static final ConcurrentMap<String, String> USERNAME_BY_DISPLAY = new ConcurrentHashMap<>();

	private PlayerNameResolver() {
	}

	/** Resolve a rendered player name and retain any hover-provided nickname mapping. */
	public static String resolve(Component message, String displayed) {
		// A reward/bank line can contain several player names. Resolving from every
		// hover in the whole component made the receiver inherit the giver's hover
		// (e.g. a catboy/FadeDave hover turned every recipient into FadeDave). Only
		// accept metadata attached to this displayed span; a previously learned alias
		// is the safe fallback when the server omits that span's hover.
		String resolved = ChatText.resolveRealName(message, displayed);
		if (resolved == null) {
			resolved = canonicalize(displayed);
		}
		recordAlias(displayed, resolved);
		return resolved;
	}

	/** Return the remembered account name for a nickname, otherwise the supplied name. */
	public static String canonicalize(String name) {
		if (name == null) {
			return null;
		}
		return USERNAME_BY_DISPLAY.getOrDefault(key(name), name.trim());
	}

	/**
	 * Resolve a name with no chat context (e.g. Guild Log menu lore, which carries no
	 * hover metadata), or empty when it can't be confidently resolved.
	 *
	 * <p>Checks the alias cache built from chat hovers first, then the live tab list as
	 * a second source: a Wynncraft nickname only renders for an observer currently in
	 * the same world as the nicknamed player, and a colocated player's tab-list entry
	 * carries the same hover-based real-name metadata chat lines do — so this can
	 * resolve a player never previously seen in chat, as long as we're in their world
	 * right now. Never joins or switches worlds; this only reads state already local to
	 * the client. Returns empty rather than guessing when neither source has an answer,
	 * since sending an unresolved nickname to the backend for something it will act on
	 * (a balance deduction, a Discord post) is worse than omitting the data point.
	 */
	public static Optional<String> resolveKnown(String name) {
		if (name == null) {
			return Optional.empty();
		}
		String trimmed = name.trim();
		String cached = USERNAME_BY_DISPLAY.get(key(trimmed));
		if (cached != null) {
			return Optional.of(cached);
		}
		String fromTabList = resolveFromTabList(trimmed);
		if (fromTabList != null) {
			recordAlias(trimmed, fromTabList);
			return Optional.of(fromTabList);
		}
		return Optional.empty();
	}

	private static String resolveFromTabList(String displayed) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getConnection() == null) {
			return null;
		}
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
			Component tabName = info.getTabListDisplayName();
			if (tabName == null) {
				continue;
			}
			String visible = tabName.getString().replaceAll("[^a-zA-Z0-9_]", "");
			boolean plausibleMatch = visible.equalsIgnoreCase(displayed) || visible.toLowerCase(Locale.ROOT).contains(displayed.toLowerCase(Locale.ROOT));
			if (!plausibleMatch) {
				continue;
			}
			String hoverResolved = ChatText.resolveRealNameAnywhere(tabName, displayed);
			if (hoverResolved != null && ChatText.IGN.matcher(hoverResolved).matches()) {
				return hoverResolved;
			}
			String profileName = info.getProfile().name();
			if (profileName != null && ChatText.IGN.matcher(profileName).matches()) {
				return profileName;
			}
			if (visible.equalsIgnoreCase(displayed) && ChatText.IGN.matcher(visible).matches()) {
				// No contradicting hover, and this online player's own tab entry shows
				// exactly this name — not nicknamed right now, so it's already the real one.
				return visible;
			}
		}
		return null;
	}

	/** Remember a username resolved by a specialised parser such as guild chat. */
	public static void recordAlias(String displayed, String username) {
		if (displayed == null || username == null || !ChatText.IGN.matcher(username).matches()) {
			return;
		}
		USERNAME_BY_DISPLAY.put(key(displayed), username);
		USERNAME_BY_DISPLAY.put(key(username), username);
	}

	private static final java.util.regex.Pattern SLASH_ALIAS_PATTERN = java.util.regex.Pattern.compile("(?<![a-zA-Z0-9_./])([a-zA-Z0-9_]{3,16})/([a-zA-Z0-9_]{3,16})(?![a-zA-Z0-9_./])");

	/** Learn any explicit real/nickname pairs embedded in text (e.g. "Username/Nickname"). */
	public static void learnFromText(String text) {
		if (text == null || !text.contains("/")) {
			return;
		}
		java.util.regex.Matcher matcher = SLASH_ALIAS_PATTERN.matcher(text);
		while (matcher.find()) {
			String real = matcher.group(1);
			String nick = matcher.group(2);
			if (isUrlToken(real) || isUrlToken(nick)) {
				continue;
			}
			recordAlias(nick, real);
		}
	}

	private static boolean isUrlToken(String token) {
		String lower = token.toLowerCase(Locale.ROOT);
		return lower.equals("http") || lower.equals("https") || lower.equals("com") || lower.equals("org") || lower.equals("net") || lower.equals("io") || lower.equals("gg");
	}

	private static String key(String name) {
		return name.trim().toLowerCase(Locale.ROOT);
	}
}
