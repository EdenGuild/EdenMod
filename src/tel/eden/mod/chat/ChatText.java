package tel.eden.mod.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/**
 * Shared text helpers for parsing Wynncraft chat components. Wynncraft pads its
 * lines with private-use glyphs (rank badges, banners) and control characters
 * that render in-game but get in the way of parsing; these collapse them away.
 */
final class ChatText {
	static final Pattern IGN = Pattern.compile("[a-zA-Z0-9_]{3,16}");
	private static final Pattern HOVER_REAL_NAME = Pattern.compile("(?:'(?:s)? real name is\\s+|Real Username:\\s*)([a-zA-Z0-9_]{3,16})", Pattern.CASE_INSENSITIVE);

	private ChatText() {
	}

	/**
	 * Resolve a displayed (possibly nicked) name to its real account username by
	 * locating the name in the component's flattened text and reading the hover of
	 * the characters that make it up. Falls back to the displayed name when it is
	 * already a valid username, else null.
	 */
	static String resolveRealName(Component message, String displayed) {
		List<MetaChar> chars = flatten(message);
		String text = text(chars);
		int start = text.indexOf(displayed);
		if (start >= 0) {
			int end = Math.min(start + displayed.length(), chars.size());
			for (int i = start; i < end; i++) {
				if (chars.get(i).hover() != null) {
					return chars.get(i).hover();
				}
			}
			// No hover on the name: a nicked sender's real account name may instead ride
			// along as the name's shift-click insertion (guild chat relies on this too).
			String trimmed = displayed.trim();
			for (int i = start; i < end; i++) {
				String insertion = chars.get(i).insertion();
				if (insertion != null && IGN.matcher(insertion).matches() && !insertion.equalsIgnoreCase(trimmed)) {
					return insertion;
				}
			}
		}
		String cleaned = displayed.replaceAll("[^a-zA-Z0-9_]", "");
		return IGN.matcher(cleaned).matches() ? cleaned : null;
	}

	private static List<MetaChar> flatten(Component message) {
		List<MetaChar> out = new ArrayList<>();
		message.visit((style, fragment) -> {
			String hover = hoverRealName(style);
			String insertion = style.getInsertion();
			boolean previousWasSpace = !out.isEmpty() && out.get(out.size() - 1).value() == ' ';
			for (int index = 0; index < fragment.length();) {
				int codePoint = fragment.codePointAt(index);
				index += Character.charCount(codePoint);
				if (Character.isWhitespace(codePoint) || isIgnorable(codePoint)) {
					if (!previousWasSpace) {
						out.add(new MetaChar(' ', hover, insertion));
						previousWasSpace = true;
					}
					continue;
				}
				for (char ch : Character.toChars(codePoint)) {
					out.add(new MetaChar(ch, hover, insertion));
				}
				previousWasSpace = false;
			}
			return Optional.empty();
		}, Style.EMPTY);
		return out;
	}

	private static String text(List<MetaChar> chars) {
		// Not trimmed: indices must stay aligned with the char list for the hover lookup.
		StringBuilder builder = new StringBuilder(chars.size());
		for (MetaChar mc : chars) {
			builder.append(mc.value());
		}
		return builder.toString();
	}

	private static String hoverRealName(Style style) {
		HoverEvent hover = style.getHoverEvent();
		if (hover instanceof HoverEvent.ShowText showText) {
			String text = showText.value().getString().replace('’', '\'').replace('‘', '\'');
			Matcher matcher = HOVER_REAL_NAME.matcher(text);
			if (matcher.find()) {
				return matcher.group(1);
			}
		}
		return null;
	}

	private record MetaChar(char value, String hover, String insertion) {
	}

	/**
	 * Strip private-use glyph spam and control characters and collapse whitespace,
	 * fixing spacing before punctuation so {@code name: message} and raid-completion
	 * shapes are cleanly matchable.
	 */
	static String normalize(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		StringBuilder out = new StringBuilder(raw.length());
		boolean previousWasSpace = false;
		for (int index = 0; index < raw.length();) {
			int codePoint = raw.codePointAt(index);
			index += Character.charCount(codePoint);
			if (Character.isWhitespace(codePoint) || isIgnorable(codePoint)) {
				if (!previousWasSpace) {
					out.append(' ');
					previousWasSpace = true;
				}
				continue;
			}
			out.appendCodePoint(codePoint);
			previousWasSpace = false;
		}
		return out.toString().trim().replaceAll("\\s+([,.:;!?])", "$1").replaceAll(" {2,}", " ");
	}

	/** Whether the code point is a control/format/private-use/surrogate/unassigned char. */
	static boolean isIgnorable(int codePoint) {
		return switch (Character.getType(codePoint)) {
			case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED -> true;
			default -> false;
		};
	}
}
