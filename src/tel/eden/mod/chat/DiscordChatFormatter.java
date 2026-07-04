package tel.eden.mod.chat;

import tel.eden.mod.net.PendingEntry;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * Renders a relayed Discord message for in-game display so it sits flush with
 * native Wynncraft guild chat: a green guild-emblem shield, then a green
 * {@code discord} pill, then {@code author: content}. Long messages wrap with the
 * guild continuation glyph under the shield.
 *
 * <p>The pill uses Wynncraft's {@code banner/pill} font and the shield/continuation
 * use its {@code chat/prefix} font; both render only while the Wynncraft pack is
 * loaded, which is always the case on a connected bridge. Glyphs are built from
 * code points so no literal private-use characters appear in the source.
 */
public final class DiscordChatFormatter {
	private static final FontDescription PILL_FONT = new FontDescription.Resource(Identifier.parse("banner/pill"));
	private static final FontDescription PREFIX_FONT = new FontDescription.Resource(Identifier.parse("chat/prefix"));
	private static final Style PREFIX_STYLE = Style.EMPTY.withFont(PREFIX_FONT).withColor(ChatFormatting.GREEN);

	// banner/pill font: left cap, lowercase letters from U+E030, fill spacer, right cap.
	private static final int PILL_LEFT_CAP = 0xE060;
	private static final int PILL_RIGHT_CAP = 0xE062;
	private static final int PILL_LETTER_BASE = 0xE030;
	private static final int PILL_FILL = 0xCFFFF;
	private static final int ALPHABET = 26;

	// chat/prefix font: the guild emblem shield, and the wrap-continuation bars.
	private static final int[] SHIELD = {0xCFFFC, 0xE006, 0xCFFFF, 0xE002, 0xCFFFE};
	private static final int[] CONTINUATION = {0xCFFFC, 0xE001, 0xD0006};

	// Bare http(s) links become clickable, and :shortcode: tokens matching a
	// known emote (see EmoteRegistry) become an inline image, in a single pass
	// over relayed message content so both interleave correctly with plain text.
	private static final Pattern TOKEN_PATTERN = Pattern.compile("(?<url>https?://\\S+)|:(?<emote>[a-zA-Z0-9_+\\-]{2,32}):");

	// Matches any :shortcode: token (shared with linkify).
	private static final Pattern EMOTE_PATTERN = Pattern.compile(":(?<emote>[a-zA-Z0-9_+\\-]{2,32}):");

	// Wynncraft chat line width: floor(chatWidth * 280 + 40), then divided by the
	// chat scale (mirrors ChatComponent's internal wrap width).
	private static final double CHAT_WIDTH_SCALE = 280.0;
	private static final double CHAT_WIDTH_BASE = 40.0;

	// Like guild chat: the shield emblem leads a run of Discord lines, then the
	// continuation bars take over until a server chat line breaks the run (or the
	// run gets long). Shared between the client thread (format) and the chat-capture
	// thread (onServerChatLine), so guard the counter.
	private static final int MAX_BLOCK_LINES = 18;
	private static final Object BLOCK_LOCK = new Object();
	private static int linesSinceEmblem = MAX_BLOCK_LINES;

	private DiscordChatFormatter() {
	}

	/** The green guild-emblem shield prefix, reused by other client-side notices. */
	public static Component shieldPrefix() {
		return prefix(SHIELD);
	}

	/** A server chat line appeared; start the next Discord message with a fresh emblem. */
	public static void onServerChatLine() {
		synchronized (BLOCK_LOCK) {
			linesSinceEmblem = MAX_BLOCK_LINES;
		}
	}

	/** A green client-side notice that a bridge user has logged in. */
	public static Component loginNotice(String username) {
		return Component.empty().append(prefix(SHIELD)).append(Component.literal(username + " has logged in!").withStyle(ChatFormatting.GREEN));
	}

	/** A red client-side notice that a bridge user has fully logged out. */
	public static Component logoutNotice(String username) {
		return Component.empty().append(prefix(SHIELD)).append(Component.literal(username + " has logged out!").withStyle(ChatFormatting.RED));
	}

	/** A green client-side notice that this account just linked to the bridge. */
	public static Component linkSuccess() {
		return Component.empty().append(prefix(SHIELD)).append(Component.literal("Successfully linked your account to the Eden bridge!").withStyle(ChatFormatting.GREEN));
	}

	/** A gold client-side reminder shown on login when the account is not yet linked. */
	public static Component linkReminder(Component configButton) {
		return Component.empty().append(prefix(SHIELD)).append(Component.literal("Not linked to the Eden bridge! Press ").withStyle(ChatFormatting.GOLD)).append(configButton).append(Component.literal(" and click Link to link your account!").withStyle(ChatFormatting.GOLD));
	}

	/** A red client-side notice shown on login when the stored token has expired. */
	public static Component tokenExpired(Component configButton) {
		return Component.empty().append(prefix(SHIELD)).append(Component.literal("Bridge token expired! Press ").withStyle(ChatFormatting.RED)).append(configButton).append(Component.literal(" and click Link to re-link.").withStyle(ChatFormatting.RED));
	}

	/** "EdenMod — update available (x.y.z)  [Download] [Link]" with clickable actions. */
	public static Component updateAvailable(String version, String pageUrl) {
		Style download = Style.EMPTY.withColor(ChatFormatting.GREEN).withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/eden update download")).withHoverEvent(new HoverEvent.ShowText(Component.literal("Download it now; it applies when you close the game")));
		MutableComponent line = Component.empty().append(prefix(SHIELD)).append(Component.literal("EdenMod — update available (" + version + ")  ").withStyle(ChatFormatting.GOLD)).append(Component.literal("[Download]").setStyle(download));
		if (pageUrl != null && !pageUrl.isEmpty()) {
			Style link = Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(new ClickEvent.OpenUrl(URI.create(pageUrl))).withHoverEvent(new HoverEvent.ShowText(Component.literal("Open the release on GitHub")));
			line.append(Component.literal("  ")).append(Component.literal("[Link]").setStyle(link));
		}
		return line;
	}

	/** A green/gold/red client-side notice line with the guild shield prefix. */
	public static Component systemLine(String text, ChatFormatting color) {
		return Component.empty().append(prefix(SHIELD)).append(Component.literal(text).withStyle(color));
	}

	/** A client-side list of who is currently connected to the bridge. */
	public static Component onlineList(java.util.List<String> users) {
		String body = users.isEmpty() ? "No one is online with the bridge." : "Online with the bridge (" + users.size() + "): " + String.join(", ", users);
		return Component.empty().append(prefix(SHIELD)).append(Component.literal(body).withStyle(ChatFormatting.GREEN));
	}

	/** A client-side list of who has how many pending aspects (Chiefs' reward helper). */
	public static Component aspectsPending(List<PendingEntry> entries, String error) {
		if (error != null && !error.isEmpty()) {
			return Component.empty().append(prefix(SHIELD)).append(Component.literal(error).withStyle(ChatFormatting.RED));
		}
		if (entries.isEmpty()) {
			return Component.empty().append(prefix(SHIELD)).append(Component.literal("No members have pending aspects.").withStyle(ChatFormatting.GREEN));
		}
		MutableComponent out = Component.empty().append(prefix(SHIELD)).append(Component.literal("Pending aspects (" + entries.size() + "):").withStyle(ChatFormatting.GREEN));
		for (PendingEntry entry : entries) {
			String command = "/eden gift " + entry.name() + " aspect " + entry.aspects();
			Style click = Style.EMPTY.withColor(ChatFormatting.YELLOW).withUnderlined(true).withClickEvent(new ClickEvent.SuggestCommand(command)).withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to fill: " + command)));
			out.append("\n").append(Component.literal("  " + entry.name() + " ").withStyle(ChatFormatting.AQUA)).append(Component.literal("[" + entry.aspects() + " aspects]").setStyle(click));
		}
		return out;
	}

	// Inline reply excerpt is truncated to this many characters (full text on hover).
	private static final int EXCERPT_MAX = 50;

	/** Build the guild-styled chat line for a relayed Discord message. */
	public static Component format(String author, String content) {
		return format(author, content, "", "");
	}

	/**
	 * Build the guild-styled chat line for a relayed Discord message, optionally with
	 * reply context. When {@code replyTo} is non-empty the line reads
	 * "<author> replied to <replyTo>: <content>"; a short quote of the replied-to
	 * message is shown inline (gray) and the full quote appears on hover.
	 */
	public static Component format(String author, String content, String replyTo, String replyExcerpt) {
		MutableComponent body = Component.empty().append(Component.literal(pillLabel("discord")).withStyle(Style.EMPTY.withFont(PILL_FONT).withColor(ChatFormatting.GREEN)));
		if (replyTo != null && !replyTo.isEmpty()) {
			body.append(Component.literal(" " + author).withStyle(ChatFormatting.GREEN)).append(Component.literal(" replied to ").withStyle(ChatFormatting.GRAY)).append(replyTarget(replyTo, replyExcerpt)).append(Component.literal(": ").withStyle(ChatFormatting.GREEN));
		} else {
			body.append(Component.literal(" " + author + ": ").withStyle(ChatFormatting.GREEN));
		}
		body.append(linkify(content));
		return withGuildPrefix(body);
	}

	/**
	 * Build a gold-pill bridge line (Quick Reactions, {@code /eden cf}/{@code diceroll}):
	 * a gold pill labelled {@code label}, then the {@code content} in gold. Mirrors
	 * {@link #format} but in gold rather than the green {@code discord} styling.
	 */
	public static Component pill(String label, String content) {
		MutableComponent body = Component.empty().append(Component.literal(pillLabel(label)).withStyle(Style.EMPTY.withFont(PILL_FONT).withColor(ChatFormatting.GOLD))).append(Component.literal(" " + content).withStyle(ChatFormatting.GOLD));
		return withGuildPrefix(body);
	}

	/** The "replyTo (excerpt)" segment: name in green, inline quote gray, full quote on hover. */
	private static MutableComponent replyTarget(String replyTo, String replyExcerpt) {
		MutableComponent segment = Component.literal(replyTo).withStyle(ChatFormatting.GREEN);
		String quote = replyExcerpt == null ? "" : ChatText.normalize(replyExcerpt);
		if (!quote.isEmpty()) {
			String shown = quote.length() > EXCERPT_MAX ? quote.substring(0, EXCERPT_MAX).strip() + "…" : quote;
			Style hover = Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withHoverEvent(new HoverEvent.ShowText(Component.literal(quote)));
			segment.append(Component.literal(" (" + shown + ")").setStyle(hover));
		}
		return segment;
	}

	/** Render text with http(s) URLs as clickable, underlined aqua links. */
	/**
	 * Render message text with any http(s) URLs as clickable, underlined aqua links,
	 * and any {@code :shortcode:} token matching a known emote (see
	 * {@link EmoteRegistry}) as an inline image. Unknown shortcodes are left as
	 * plain literal text (e.g. {@code :notarealemote:} stays exactly that), so a
	 * message never loses information just because an emote isn't recognized.
	 */
	private static MutableComponent linkify(String content) {
		MutableComponent out = Component.empty();
		Matcher matcher = TOKEN_PATTERN.matcher(content);
		int last = 0;
		while (matcher.find()) {
			if (matcher.start() > last) {
				out.append(Component.literal(content.substring(last, matcher.start())).withStyle(ChatFormatting.GREEN));
			}
			String url = matcher.group("url");
			if (url != null) {
				out.append(Component.literal(url).withStyle(linkStyle(url)));
			} else {
				String shortcode = matcher.group("emote");
				Component emote = emoteComponent(shortcode);
				out.append(emote != null ? emote : Component.literal(matcher.group()).withStyle(ChatFormatting.GREEN));
			}
			last = matcher.end();
		}
		if (last < content.length()) {
			out.append(Component.literal(content.substring(last)).withStyle(ChatFormatting.GREEN));
		}
		return out;
	}

	/** The inline glyph for {@code shortcode}, hoverable to show its name, or {@code null} if unknown. */
	private static Component emoteComponent(String shortcode) {
		Integer codepoint = EmoteRegistry.codepointFor(shortcode);
		if (codepoint == null) {
			return null;
		}
		String glyph = new String(Character.toChars(codepoint));
		// WHITE is deliberate: Minecraft tints bitmap-font glyphs by the current
		// text color, and these are full-color images, not glyph masks — white
		// leaves the PNG's own colors untouched instead of dyeing it chat-green.
		Style style = Style.EMPTY.withFont(EmoteRegistry.font()).withColor(ChatFormatting.WHITE).withHoverEvent(new HoverEvent.ShowText(Component.literal(":" + shortcode + ":").withStyle(ChatFormatting.GRAY)));
		return Component.literal(glyph).withStyle(style);
	}

	private static Style linkStyle(String url) {
		Style base = Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true);
		try {
			return base.withClickEvent(new ClickEvent.OpenUrl(URI.create(url))).withHoverEvent(new HoverEvent.ShowText(Component.literal("Open " + url)));
		} catch (IllegalArgumentException e) {
			return Style.EMPTY.withColor(ChatFormatting.GREEN);
		}
	}

	/**
	 * Process any chat component for {@code :shortcode:} patterns and replace
	 * known emotes with inline image glyphs via {@link EmoteRegistry}, preserving
	 * per-element styles (Wynncraft rank tags, colours, etc.). Unknown shortcodes
	 * are left as literal text. Returns the original component unchanged if no
	 * emote pattern is found.
	 */
	public static Component processEmotes(Component message) {
		String text = message.getString();
		if (!EMOTE_PATTERN.matcher(text).find()) {
			return message;
		}
		MutableComponent result = Component.empty();
		// Visit each styled leaf segment preserving its original style.
		message.visit((style, segment) -> {
			Matcher matcher = EMOTE_PATTERN.matcher(segment);
			int last = 0;
			while (matcher.find()) {
				if (matcher.start() > last) {
					result.append(Component.literal(segment.substring(last, matcher.start())).withStyle(style));
				}
				String shortcode = matcher.group("emote");
				Integer codepoint = EmoteRegistry.codepointFor(shortcode);
				if (codepoint != null) {
					String glyph = new String(Character.toChars(codepoint));
					Style emoteStyle = Style.EMPTY.withFont(EmoteRegistry.font()).withColor(ChatFormatting.WHITE).withHoverEvent(new HoverEvent.ShowText(Component.literal(":" + shortcode + ":").withStyle(ChatFormatting.GRAY)));
					result.append(Component.literal(glyph).withStyle(emoteStyle));
				} else {
					result.append(Component.literal(":" + shortcode + ":").withStyle(style));
				}
				last = matcher.end();
			}
			if (last < segment.length()) {
				result.append(Component.literal(segment.substring(last)).withStyle(style));
			}
			return Optional.empty();
		}, Style.EMPTY);
		return result;
	}

	/** Wrap the body to chat width, prefixing line 1 with the shield and the rest with bars. */
	private static Component withGuildPrefix(Component body) {
		Component shield = prefix(SHIELD);
		Component continuation = prefix(CONTINUATION);
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		int reserve = font.width(continuation);
		int maxWidth = Math.max(1, chatWidth(mc) - reserve);

		List<FormattedText> lines = font.getSplitter().splitLines(body, maxWidth, Style.EMPTY);
		if (lines.isEmpty()) {
			lines = List.of((FormattedText) body);
		}
		boolean emblem;
		synchronized (BLOCK_LOCK) {
			emblem = linesSinceEmblem >= MAX_BLOCK_LINES;
			linesSinceEmblem = (emblem ? 0 : linesSinceEmblem) + lines.size();
		}
		MutableComponent out = Component.empty();
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) {
				out.append("\n");
			}
			out.append(i == 0 && emblem ? shield : continuation).append(toComponent(lines.get(i)));
		}
		return out;
	}

	private static int chatWidth(Minecraft mc) {
		double width = Math.floor(mc.options.chatWidth().get() * CHAT_WIDTH_SCALE + CHAT_WIDTH_BASE);
		double scale = mc.options.chatScale().get();
		return (int) Math.floor(scale > 0 ? width / scale : width);
	}

	private static Component prefix(int[] glyphs) {
		StringBuilder out = new StringBuilder();
		for (int glyph : glyphs) {
			out.appendCodePoint(glyph);
		}
		return Component.literal(out.toString()).append(" ").withStyle(PREFIX_STYLE);
	}

	private static MutableComponent toComponent(FormattedText text) {
		MutableComponent out = Component.empty();
		text.visit((style, content) -> {
			out.append(Component.literal(content).withStyle(style));
			return Optional.empty();
		}, Style.EMPTY);
		return out;
	}

	private static String pillLabel(String label) {
		StringBuilder out = new StringBuilder();
		out.appendCodePoint(PILL_LEFT_CAP).appendCodePoint(PILL_FILL);
		for (int i = 0; i < label.length(); i++) {
			int letter = Character.toLowerCase(label.charAt(i)) - 'a';
			if (letter >= 0 && letter < ALPHABET) {
				out.appendCodePoint(PILL_LETTER_BASE + letter).appendCodePoint(PILL_FILL);
			}
		}
		out.appendCodePoint(PILL_RIGHT_CAP);
		return out.toString();
	}
}
