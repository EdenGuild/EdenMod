package tel.eden.mod.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/**
 * Turns a system-chat {@link Component} into a {@link CapturedMessage} when it
 * looks like Wynncraft guild chat.
 *
 * <p>Guild chat is leading-aqua and has a {@code <name>: <message>} shape. The
 * visible name is the player's <em>nickname</em>; the real account username is
 * carried out-of-band in the name component's hover text
 * ({@code "<nick>'s real name is <ign>"}) or its shift-click insertion. Guild
 * <em>announcements</em> (raid completions, bank deposits, joins/leaves) are also
 * aqua but lack the {@code name:} shape, so they are rejected.
 */
public final class GuildChatParser {
	/** Aqua (0x55FFFF / §b) — the colour Wynncraft uses for guild chat. */
	private static final int GUILD_CHAT_COLOR = 0x55FFFF;

	// Nicknames may contain spaces (e.g. "Emanant Force"); DOTALL so the message
	// group spans the newlines Wynncraft inserts for long lines.
	// The single-token alternative allows 1 char so a one-letter nickname (e.g. "Real/r")
	// still splits into name + message; the real username is recovered from hover and must
	// itself be a valid IGN, so a stray single letter with no hover is dropped downstream.
	private static final Pattern CHAT_PATTERN = Pattern.compile("^(?:<\\d+>\\s*)?" + "([a-zA-Z0-9_][a-zA-Z0-9_ ]*[a-zA-Z0-9_]|[a-zA-Z0-9_]{1,16})" + "\\s*:\\s*(.*)$", Pattern.DOTALL);

	// "<nick>'s real name is <ign>" (also "<nick>' real name is" for names ending
	// in s) and the legacy "Real Username: <ign>".
	private static final Pattern HOVER_REAL_NAME_PATTERN = Pattern.compile("(?:'(?:s)? real name is\\s+|Real Username:\\s*)([a-zA-Z0-9_]{3,16})", Pattern.CASE_INSENSITIVE);

	private static final Pattern IGN = Pattern.compile("[a-zA-Z0-9_]{3,16}");

	private GuildChatParser() {
	}

	private record Segment(String text, Style style, String hover) {
	}

	/** Whether this component looks like guild chat (leading aqua colour). */
	public static boolean looksLikeGuildChat(Component message) {
		return hasLeadingGuildChatColor(message);
	}

	/** Parse a system-chat component, returning a captured message if it is guild chat. */
	public static Optional<CapturedMessage> parse(Component message) {
		Optional<CapturedMessage> captured = parseSender(message);
		// A non-empty body is required for a relayable chat line (a lone shared-item
		// string normalizes to an empty body, but still has a resolvable sender).
		return captured.filter(line -> !line.message().isEmpty());
	}

	/**
	 * Resolve the sender (and any body) of a guild-chat component, even when the body
	 * is empty — e.g. a message that is only a shared-item string, whose private-use
	 * code points are stripped during normalization.
	 */
	public static Optional<CapturedMessage> parseSender(Component message) {
		if (!hasLeadingGuildChatColor(message)) {
			return Optional.empty();
		}
		List<Segment> segments = collect(message);
		String cleaned = ChatText.normalize(concat(segments));
		Matcher matcher = CHAT_PATTERN.matcher(cleaned);
		if (!matcher.find()) {
			return Optional.empty();
		}
		String displayed = matcher.group(1).trim();
		String content = matcher.group(2).trim();

		String real = findRealUsername(segments, displayed);
		String username = resolveAvatarUsername(displayed, real);
		if (username == null) {
			return Optional.empty();
		}
		String nickname = username.equalsIgnoreCase(displayed) ? username : displayed;
		return Optional.of(new CapturedMessage(username, nickname, content));
	}

	// -- real-username resolution -------------------------------------------

	/**
	 * Resolve the real account username from hover/insertion metadata, preferring
	 * a candidate that the nickname is a prefix of (Wynncraft nicks are often a
	 * shortened real name), then any candidate that differs from the nickname.
	 */
	private static String findRealUsername(List<Segment> segments, String displayed) {
		Set<String> candidates = new LinkedHashSet<>();
		for (Segment seg : segments) {
			String fromHover = hoverUsername(seg.hover());
			if (fromHover != null) {
				candidates.add(fromHover);
			}
		}
		for (Segment seg : segments) {
			String insertion = seg.style().getInsertion();
			if (insertion != null && IGN.matcher(insertion).matches()) {
				candidates.add(insertion);
			}
		}
		if (candidates.isEmpty()) {
			return null;
		}
		if (displayed == null || displayed.isBlank()) {
			return candidates.iterator().next();
		}
		String displayedLower = displayed.trim().toLowerCase(Locale.ROOT);

		String bestPrefix = null;
		for (String candidate : candidates) {
			String lower = candidate.toLowerCase(Locale.ROOT);
			if (lower.equals(displayedLower)) {
				continue;
			}
			if (lower.startsWith(displayedLower) && candidate.length() > displayed.trim().length() && (bestPrefix == null || candidate.length() > bestPrefix.length())) {
				bestPrefix = candidate;
			}
		}
		if (bestPrefix != null) {
			return bestPrefix;
		}
		for (String candidate : candidates) {
			if (!candidate.equalsIgnoreCase(displayed.trim())) {
				return candidate;
			}
		}
		return candidates.iterator().next();
	}

	private static String hoverUsername(String hover) {
		if (hover == null || hover.isBlank()) {
			return null;
		}
		String text = hover.replace('’', '\'').replace('‘', '\'');
		Matcher matcher = HOVER_REAL_NAME_PATTERN.matcher(text);
		return matcher.find() ? matcher.group(1) : null;
	}

	/** The avatar-safe username: real if valid, else the display name reduced to an IGN. */
	private static String resolveAvatarUsername(String displayed, String real) {
		if (real != null && IGN.matcher(real).matches()) {
			return real;
		}
		if (displayed != null && IGN.matcher(displayed).matches()) {
			return displayed;
		}
		String normalized = displayed == null ? "" : displayed.replaceAll("[^a-zA-Z0-9_]", "");
		return IGN.matcher(normalized).matches() ? normalized : null;
	}

	// -- component collection + text normalization --------------------------

	private static List<Segment> collect(Component message) {
		List<Segment> segments = new ArrayList<>();
		message.visit((style, text) -> {
			if (!text.isEmpty()) {
				segments.add(new Segment(text, style, hoverText(style)));
			}
			return Optional.empty();
		}, Style.EMPTY);
		return segments;
	}

	private static String hoverText(Style style) {
		HoverEvent hover = style.getHoverEvent();
		if (hover instanceof HoverEvent.ShowText showText) {
			return showText.value().getString();
		}
		return null;
	}

	/**
	 * Whether the component's leading (root, else first visible) colour is guild
	 * aqua. This rejects DMs/party/shout/territory lines that share Wynncraft's
	 * glyph prefix but use other colours.
	 */
	private static boolean hasLeadingGuildChatColor(Component message) {
		if (message == null) {
			return false;
		}
		var rootColor = message.getStyle().getColor();
		if (rootColor != null) {
			return rootColor.getValue() == GUILD_CHAT_COLOR;
		}
		Optional<Boolean> leading = message.visit((style, text) -> {
			if (text == null || text.isBlank()) {
				return Optional.empty();
			}
			var color = style.getColor();
			return Optional.of(color != null && color.getValue() == GUILD_CHAT_COLOR);
		}, Style.EMPTY);
		return leading.orElse(false);
	}

	private static String concat(List<Segment> segments) {
		StringBuilder builder = new StringBuilder();
		for (Segment segment : segments) {
			builder.append(segment.text());
		}
		return builder.toString();
	}

}
