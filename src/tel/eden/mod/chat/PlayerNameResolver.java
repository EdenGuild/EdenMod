package tel.eden.mod.chat;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Canonical player identity: real Minecraft usernames, with nicknames remembered as aliases. */
public final class PlayerNameResolver {
	private static final ConcurrentMap<String, String> USERNAME_BY_DISPLAY = new ConcurrentHashMap<>();
	private static final Pattern NICKNAME_USERNAME_PAREN = Pattern.compile("(?:\\[[^\\]]+\\]\\s*)?([a-zA-Z0-9_][a-zA-Z0-9_ ]*?)\\s*\\(([a-zA-Z0-9_]{3,16})\\)");
	private static final Pattern PARTY_JOINED_MSG = Pattern.compile("^(.+?)\\s+has joined (?:your|the)\\s+party", Pattern.CASE_INSENSITIVE);
	private static final Pattern PARTY_MEMBERS_MSG = Pattern.compile("^Party members:\\s*(.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SLASH_ALIAS_PATTERN = Pattern.compile("(?<![a-zA-Z0-9_./])([a-zA-Z0-9_]{3,16})/([a-zA-Z0-9_]{3,16})(?![a-zA-Z0-9_./])");
	private static final Set<String> URL_TOKENS = Set.of("http", "https", "com", "org", "net", "io", "gg");

	private PlayerNameResolver() {
	}

	public static void reset() {
		USERNAME_BY_DISPLAY.clear();
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
		String trimmed = name.trim();
		String direct = USERNAME_BY_DISPLAY.get(key(trimmed));
		if (direct != null) {
			return direct;
		}
		String clean = cleanKey(trimmed);
		if (clean.length() >= 3) {
			String cleanDirect = USERNAME_BY_DISPLAY.get(clean);
			if (cleanDirect != null) {
				return cleanDirect;
			}
			for (Map.Entry<String, String> entry : USERNAME_BY_DISPLAY.entrySet()) {
				if (entry.getKey().equalsIgnoreCase(entry.getValue())) {
					continue;
				}
				String entryClean = cleanKey(entry.getKey());
				if (entryClean.equals(clean) || (clean.length() >= 4 && entryClean.startsWith(clean))) {
					return entry.getValue();
				}
			}
		}
		return trimmed;
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
		String clean = cleanKey(trimmed);
		if (clean.length() >= 3) {
			String cleanDirect = USERNAME_BY_DISPLAY.get(clean);
			if (cleanDirect != null) {
				return Optional.of(cleanDirect);
			}
			for (Map.Entry<String, String> entry : USERNAME_BY_DISPLAY.entrySet()) {
				if (entry.getKey().equalsIgnoreCase(entry.getValue())) {
					continue;
				}
				String entryClean = cleanKey(entry.getKey());
				if (entryClean.equals(clean) || (clean.length() >= 4 && entryClean.startsWith(clean))) {
					return Optional.of(entry.getValue());
				}
			}
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
		String cleanDisplayed = cleanKey(displayed);
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
			String profileName = (info.getProfile() != null) ? info.getProfile().name() : null;
			if (profileName == null || !ChatText.IGN.matcher(profileName).matches()) {
				continue;
			}
			if (profileName.equalsIgnoreCase(displayed)) {
				return profileName;
			}
			String cleanProfile = profileName.toLowerCase(Locale.ROOT);
			if (!cleanDisplayed.isEmpty() && (cleanProfile.equals(cleanDisplayed) || (cleanDisplayed.length() >= 3 && cleanProfile.startsWith(cleanDisplayed)))) {
				return profileName;
			}
			Component tabName = info.getTabListDisplayName();
			if (tabName != null) {
				String hoverResolved = ChatText.resolveRealNameAnywhere(tabName, displayed);
				if (hoverResolved != null && ChatText.IGN.matcher(hoverResolved).matches()) {
					return hoverResolved;
				}
				String visible = cleanKey(tabName.getString());
				if (!cleanDisplayed.isEmpty() && (visible.equals(cleanDisplayed) || (cleanDisplayed.length() >= 3 && visible.contains(cleanDisplayed)))) {
					return profileName;
				}
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
		String clean = cleanKey(displayed);
		if (!clean.isEmpty() && !clean.equals(key(displayed))) {
			USERNAME_BY_DISPLAY.put(clean, username);
		}
	}

	/**
	 * Inspect any incoming chat message (party, guild, whispers, system alerts)
	 * and learn nickname -> real username mappings from text and component styles.
	 */
	public static void observeMessage(Component message) {
		if (message == null) {
			return;
		}

		message.visit((style, text) -> {
			if (text == null || text.isBlank()) {
				return Optional.empty();
			}
			String trimmedText = text.trim().replaceAll("[^a-zA-Z0-9_]", "");
			String hoverReal = ChatText.hoverRealName(style);
			if (hoverReal != null && ChatText.IGN.matcher(hoverReal).matches()) {
				if (!trimmedText.isEmpty() && !trimmedText.equalsIgnoreCase(hoverReal)) {
					recordAlias(trimmedText, hoverReal);
				}
			}
			String insertion = style.getInsertion();
			if (insertion != null && ChatText.IGN.matcher(insertion).matches()) {
				if (!trimmedText.isEmpty() && !trimmedText.equalsIgnoreCase(insertion)) {
					recordAlias(trimmedText, insertion);
				}
			}
			return Optional.empty();
		}, Style.EMPTY);

		String plain = message.getString();
		learnFromText(plain);

		Matcher parenMatcher = NICKNAME_USERNAME_PAREN.matcher(plain);
		while (parenMatcher.find()) {
			String nick = parenMatcher.group(1).trim();
			String real = parenMatcher.group(2).trim();
			if (!nick.isEmpty() && ChatText.IGN.matcher(real).matches()) {
				recordAlias(nick, real);
			}
		}

		Matcher joinedMatcher = PARTY_JOINED_MSG.matcher(plain);
		if (joinedMatcher.find()) {
			String joinedName = joinedMatcher.group(1).trim();
			String real = ChatText.resolveRealNameAnywhere(message, joinedName);
			if (real != null) {
				recordAlias(joinedName, real);
			}
		}

		Matcher membersMatcher = PARTY_MEMBERS_MSG.matcher(plain);
		if (membersMatcher.find()) {
			String tail = membersMatcher.group(1).trim();
			String[] tokens = tail.split("\\s*,\\s*(?:and\\s+)?|\\s+and\\s+");
			for (String token : tokens) {
				String item = token.trim().replaceAll("[^a-zA-Z0-9_]", "");
				if (!item.isEmpty()) {
					String real = ChatText.resolveRealNameAnywhere(message, item);
					if (real != null) {
						recordAlias(item, real);
					}
				}
			}
		}
	}

	/** Learn any explicit real/nickname pairs embedded in text (e.g. "Username/Nickname"). */
	public static void learnFromText(String text) {
		if (text == null || !text.contains("/")) {
			return;
		}
		// If the text contains player chat (e.g. "Player: text"), do not learn aliases from the chat content
		int colonIdx = text.indexOf(": ");
		Matcher matcher = SLASH_ALIAS_PATTERN.matcher(text);
		while (matcher.find()) {
			if (colonIdx >= 0 && matcher.start() > colonIdx) {
				// Inside player chat body - ignore to avoid normal conversational words
				continue;
			}
			String real = matcher.group(1);
			String nick = matcher.group(2);
			if (isUrlToken(real) || isUrlToken(nick)) {
				continue;
			}
			Minecraft mc = Minecraft.getInstance();
			boolean hasConnection = (mc != null && mc.getConnection() != null);
			if (hasConnection) {
				boolean realKnown = USERNAME_BY_DISPLAY.containsKey(key(real)) || resolveFromTabList(real) != null;
				boolean nickKnown = USERNAME_BY_DISPLAY.containsKey(key(nick)) || resolveFromTabList(nick) != null;
				if (realKnown) {
					recordAlias(nick, real);
				} else if (nickKnown) {
					recordAlias(real, nick);
				}
			} else {
				// Offline / test environment without live tab list
				boolean realKnown = USERNAME_BY_DISPLAY.containsKey(key(real));
				boolean nickKnown = USERNAME_BY_DISPLAY.containsKey(key(nick));
				if (realKnown) {
					recordAlias(nick, real);
				} else if (nickKnown) {
					recordAlias(real, nick);
				} else {
					recordAlias(nick, real);
				}
			}
		}
	}

	private static boolean isUrlToken(String token) {
		return token != null && URL_TOKENS.contains(token.toLowerCase(Locale.ROOT));
	}

	private static String key(String name) {
		return name.trim().toLowerCase(Locale.ROOT);
	}

	private static String cleanKey(String name) {
		return name.replaceAll("[^a-zA-Z0-9_]", "").toLowerCase(Locale.ROOT);
	}
}
