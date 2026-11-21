package tel.eden.mod.chat;

import java.net.URI;
import java.util.List;
import java.util.Locale;
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
import tel.eden.mod.guild.AllyTagCache;

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

	// A relayed sticker tag "[sticker: <name>] <url>" collapses to one previewable token,
	// bare http(s) links become clickable, and :shortcode: tokens matching a known emote
	// (see EmoteRegistry) become an inline image — all in a single pass over the message so
	// they interleave correctly with plain text. The sticker alternative comes first so it
	// claims its trailing url instead of that url matching on its own.
	private static final Pattern TOKEN_PATTERN = Pattern.compile("\\[sticker: (?<sname>[^\\]]+)\\] (?<surl>https?://\\S+)|(?<url>https?://\\S+)|:(?<emote>[a-zA-Z0-9_+\\-]{2,32}):");

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

	/** "The promotion cycle has concluded! [Print] [View]" for in-game guild members only. */
	public static Component cycleConcluded(String cycleCode) {
		String arg = (cycleCode != null && !cycleCode.isBlank()) ? " " + cycleCode.trim() : "";
		Style print = Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/eden cycle print" + arg)).withHoverEvent(new HoverEvent.ShowText(Component.literal("Print top cycle results to chat")));
		Style view = Style.EMPTY.withColor(ChatFormatting.GREEN).withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/eden cycle view" + arg)).withHoverEvent(new HoverEvent.ShowText(Component.literal("Open the promotion cycle leaderboard")));
		return Component.empty().append(prefix(SHIELD)).append(Component.literal("The promotion cycle has concluded! ").withStyle(ChatFormatting.GOLD)).append(Component.literal("[Print]").setStyle(print)).append(Component.literal(" ")).append(Component.literal("[View]").setStyle(view));
	}

	/** Format concluded cycle summary (top 3 raids, top 1 wars, top 3 raffle, totals) for [Print]. */
	public static Component formatCyclePrint(tel.eden.mod.net.CycleLeaderboardData.ConcludedCycleSummary summary) {
		MutableComponent out = Component.empty().append(prefix(SHIELD));
		String title = (summary.cycleCode() != null && !summary.cycleCode().isBlank()) ? "Promotion Cycle " + summary.cycleCode() + " Results:" : "Promotion Cycle Results:";
		out.append(Component.literal(title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		out.append(Component.literal("\n  Top 3 Raids:").withStyle(ChatFormatting.AQUA));
		List<tel.eden.mod.net.CycleLeaderboardData.LeaderboardEntry> topRaids = summary.topRaids().stream().filter(e -> e.count() > 0 && e.rank() <= 3).limit(3).toList();
		if (topRaids.isEmpty()) {
			out.append(Component.literal("\n    None").withStyle(ChatFormatting.GRAY));
		} else {
			for (tel.eden.mod.net.CycleLeaderboardData.LeaderboardEntry e : topRaids) {
				out.append(Component.literal("\n    #" + e.rank() + " " + e.name()).withStyle(ChatFormatting.WHITE)).append(Component.literal(" — " + e.count() + " raids").withStyle(ChatFormatting.GRAY));
			}
		}
		out.append(Component.literal("\n  Top 1 Wars:").withStyle(ChatFormatting.RED));
		List<tel.eden.mod.net.CycleLeaderboardData.LeaderboardEntry> topWars = summary.topWars().stream().filter(e -> e.count() > 0 && e.rank() == 1).limit(3).toList();
		if (topWars.isEmpty()) {
			out.append(Component.literal("\n    None").withStyle(ChatFormatting.GRAY));
		} else {
			for (tel.eden.mod.net.CycleLeaderboardData.LeaderboardEntry e : topWars) {
				out.append(Component.literal("\n    #" + e.rank() + " " + e.name()).withStyle(ChatFormatting.WHITE)).append(Component.literal(" — " + e.count() + " wars").withStyle(ChatFormatting.GRAY));
			}
		}
		out.append(Component.literal("\n  Top 3 Raffle:").withStyle(ChatFormatting.LIGHT_PURPLE));
		List<tel.eden.mod.net.CycleLeaderboardData.RaffleWinner> winners = summary.raffleWinners().subList(0, Math.min(3, summary.raffleWinners().size()));
		if (winners.isEmpty()) {
			out.append(Component.literal("\n    None").withStyle(ChatFormatting.GRAY));
		} else {
			for (int i = 0; i < winners.size(); i++) {
				tel.eden.mod.net.CycleLeaderboardData.RaffleWinner w = winners.get(i);
				out.append(Component.literal("\n    #" + (i + 1) + " " + w.name()).withStyle(ChatFormatting.WHITE)).append(Component.literal(" — " + w.tickets() + " tickets (" + w.raids() + " raids)").withStyle(ChatFormatting.GRAY));
			}
		}
		out.append(Component.literal("\n  Totals: ").withStyle(ChatFormatting.GOLD)).append(Component.literal(summary.totalRaids() + " raids | " + summary.totalWars() + " wars | " + summary.totalTickets() + " tickets").withStyle(ChatFormatting.YELLOW));
		return out;
	}

	private static final Pattern WIKI_COORDS_PATTERN = Pattern.compile("\\(\\s*-?\\d+(?:\\s*,\\s*-?\\d+){1,2}\\s*\\)");
	private static final int WIKI_BODY_RGB = 0xCCCCCC;
	private static final int WIKI_WRAP_COLS = 54;

	/** Format a backend {@code wikiReply} (single article card, compact clickable choices, empty, or error). */
	public static Component wikiReply(tel.eden.mod.net.WikiReplyData reply) {
		if (reply == null || "error".equals(reply.status())) {
			String msg = (reply != null && !reply.error().isEmpty()) ? reply.error() : "Failed to look up wiki article.";
			return systemLine(msg, ChatFormatting.RED);
		}
		if ("choices".equals(reply.status()) && !reply.choices().isEmpty()) {
			MutableComponent out = Component.empty().append(prefix(SHIELD)).append(wikiPill(ChatFormatting.GOLD)).append(Component.literal(" Matches for \"" + reply.query() + "\" (click one):").withStyle(ChatFormatting.GOLD));
			int rowLen = 0;
			out.append(Component.literal("\n  "));
			for (tel.eden.mod.net.WikiReplyData.WikiChoice choice : reply.choices()) {
				String title = choice.title();
				String cmd = "/eden wiki " + title;
				boolean canRun = cmd.length() <= 256;
				URI uri = canRun ? null : parseHttpUri(choice.url());
				Component tip = Component.literal(canRun ? "Click to look up " + title : "Open " + title + " on wynncraft.wiki.gg").withStyle(ChatFormatting.AQUA);
				Style linkStyle = Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true).withHoverEvent(new HoverEvent.ShowText(tip));
				if (canRun) {
					linkStyle = linkStyle.withClickEvent(new ClickEvent.RunCommand(cmd));
				} else if (uri != null) {
					linkStyle = linkStyle.withClickEvent(new ClickEvent.OpenUrl(uri));
				}
				int chipWidth = title.length() + 4;
				if (rowLen > 0 && rowLen + chipWidth > WIKI_WRAP_COLS) {
					out.append(Component.literal("\n  "));
					rowLen = 0;
				} else if (rowLen > 0) {
					out.append(Component.literal("  "));
				}
				out.append(Component.literal("[" + title + "]").setStyle(linkStyle));
				rowLen += chipWidth;
			}
			return out;
		}
		if (!"page".equals(reply.status()) || reply.title().isEmpty()) {
			return systemLine("No wiki articles found for \"" + reply.query() + "\".", ChatFormatting.GOLD);
		}
		ChatFormatting accent = wikiAccentColor(reply.fields());
		Component cardTooltip = buildWikiCardTooltip(reply.card());
		MutableComponent titleComp = Component.literal(" " + reply.title()).withStyle(style -> {
			Style s = style.withColor(accent).withBold(true);
			return cardTooltip != null ? s.withHoverEvent(new HoverEvent.ShowText(cardTooltip)) : s;
		});
		MutableComponent out = Component.empty().append(prefix(SHIELD)).append(wikiPill(accent)).append(titleComp);
		if (cardTooltip != null) {
			Style statsStyle = Style.EMPTY.withColor(ChatFormatting.YELLOW).withUnderlined(true).withHoverEvent(new HoverEvent.ShowText(cardTooltip));
			out.append(Component.literal("  ")).append(Component.literal("[Stats]").setStyle(statsStyle));
		}
		URI pageUri = parseHttpUri(reply.url());
		if (pageUri != null) {
			Style link = Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(new ClickEvent.OpenUrl(pageUri)).withHoverEvent(new HoverEvent.ShowText(Component.literal("Open " + reply.title() + " on wynncraft.wiki.gg")));
			out.append(Component.literal("  ")).append(Component.literal("[Link]").setStyle(link));
		}
		if (!reply.description().isEmpty()) {
			Style bodyStyle = Style.EMPTY.withColor(WIKI_BODY_RGB);
			for (String para : reply.description().split("\\R")) {
				String trimmed = para.trim();
				if (!trimmed.isEmpty()) {
					out.append(Component.literal("\n  " + trimmed).withStyle(bodyStyle));
				}
			}
		}
		List<tel.eden.mod.net.WikiReplyData.WikiField> fields = reply.fields();
		for (int i = 0; i < fields.size(); i++) {
			tel.eden.mod.net.WikiReplyData.WikiField f = fields.get(i);
			out.append(Component.literal(i % 2 == 0 ? "\n  " : "  •  ").withStyle(ChatFormatting.DARK_GRAY));
			out.append(Component.literal(f.name() + ": ").withStyle(ChatFormatting.GRAY)).append(Component.literal(f.value()).withStyle(wikiFieldValueColor(f.name(), f.value(), accent)));
		}
		if (!reply.obtaining().isEmpty() || !reply.location().isEmpty()) {
			out.append(Component.literal("\n  ────────────────────").withStyle(ChatFormatting.DARK_GRAY));
		}
		if (!reply.obtaining().isEmpty()) {
			out.append(Component.literal("\n  ▸ Obtaining").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			appendWikiBullets(out, reply.obtaining(), ChatFormatting.YELLOW, false);
		}
		if (!reply.location().isEmpty()) {
			out.append(Component.literal("\n  ▸ Location").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
			appendWikiBullets(out, reply.location(), ChatFormatting.WHITE, true);
		}
		return out;
	}

	private static ChatFormatting wikiFieldValueColor(String name, String value, ChatFormatting accent) {
		if ("Tier".equalsIgnoreCase(name) || "Rarity".equalsIgnoreCase(name)) {
			return accent;
		}
		if ("Professions".equalsIgnoreCase(name)) {
			return ChatFormatting.GOLD;
		}
		if ("Level".equalsIgnoreCase(name) || "Min Level".equalsIgnoreCase(name)) {
			return ChatFormatting.YELLOW;
		}
		boolean neg = value.startsWith("-");
		if (name.toLowerCase(Locale.ROOT).contains("spell cost")) {
			return neg ? ChatFormatting.GREEN : (value.startsWith("+") ? ChatFormatting.RED : ChatFormatting.WHITE);
		}
		if (value.startsWith("+")) {
			return ChatFormatting.GREEN;
		}
		return neg ? ChatFormatting.RED : ChatFormatting.WHITE;
	}

	private static Component buildWikiCardTooltip(List<List<tel.eden.mod.net.WikiReplyData.CardSpan>> card) {
		if (card == null || card.isEmpty()) {
			return null;
		}
		MutableComponent tip = Component.empty();
		for (int i = 0; i < card.size(); i++) {
			if (i > 0) {
				tip.append(Component.literal("\n"));
			}
			for (tel.eden.mod.net.WikiReplyData.CardSpan span : card.get(i)) {
				ChatFormatting color = wikiCardColor(span.color());
				tip.append(Component.literal(span.text()).withStyle(style -> {
					Style s = style.withColor(color);
					return span.bold() ? s.withBold(true) : s;
				}));
			}
		}
		return tip;
	}

	private static ChatFormatting wikiCardColor(String name) {
		if (name == null) {
			return ChatFormatting.WHITE;
		}
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "dark_purple" -> ChatFormatting.DARK_PURPLE;
			case "red" -> ChatFormatting.RED;
			case "aqua" -> ChatFormatting.AQUA;
			case "light_purple" -> ChatFormatting.LIGHT_PURPLE;
			case "yellow" -> ChatFormatting.YELLOW;
			case "green" -> ChatFormatting.GREEN;
			case "gold" -> ChatFormatting.GOLD;
			case "gray" -> ChatFormatting.GRAY;
			case "dark_gray" -> ChatFormatting.DARK_GRAY;
			default -> ChatFormatting.WHITE;
		};
	}

	private static Component wikiPill(ChatFormatting color) {
		return Component.literal(pillLabel("wiki")).withStyle(Style.EMPTY.withFont(PILL_FONT).withColor(color));
	}

	private static ChatFormatting wikiAccentColor(List<tel.eden.mod.net.WikiReplyData.WikiField> fields) {
		for (tel.eden.mod.net.WikiReplyData.WikiField field : fields) {
			if (!"Rarity".equalsIgnoreCase(field.name()) && !"Tier".equalsIgnoreCase(field.name())) {
				continue;
			}
			String v = field.value().toLowerCase(Locale.ROOT).trim();
			if (v.contains("mythic")) {
				return ChatFormatting.DARK_PURPLE;
			}
			if (v.contains("fabled")) {
				return ChatFormatting.RED;
			}
			if (v.contains("legendary") || "3".equals(v)) {
				return ChatFormatting.AQUA;
			}
			if (v.contains("rare") || "2".equals(v)) {
				return ChatFormatting.LIGHT_PURPLE;
			}
			if (v.contains("unique") || "1".equals(v)) {
				return ChatFormatting.YELLOW;
			}
			if (v.contains("set")) {
				return ChatFormatting.GREEN;
			}
			if ("0".equals(v)) {
				return ChatFormatting.GRAY;
			}
		}
		return ChatFormatting.GOLD;
	}

	private static void appendWikiBullets(MutableComponent out, String text, ChatFormatting color, boolean highlightCoords) {
		for (String raw : text.split("\\R")) {
			String line = raw.trim();
			if (line.isEmpty()) {
				continue;
			}
			out.append(Component.literal("\n    • ").withStyle(ChatFormatting.DARK_GRAY));
			if (!highlightCoords) {
				out.append(Component.literal(line).withStyle(color));
				continue;
			}
			Matcher m = WIKI_COORDS_PATTERN.matcher(line);
			int last = 0;
			while (m.find()) {
				if (m.start() > last) {
					out.append(Component.literal(line.substring(last, m.start())).withStyle(color));
				}
				out.append(Component.literal(m.group()).withStyle(ChatFormatting.GREEN));
				last = m.end();
			}
			if (last < line.length()) {
				out.append(Component.literal(line.substring(last)).withStyle(color));
			}
		}
	}

	private static URI parseHttpUri(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			URI parsed = URI.create(raw.trim());
			String scheme = parsed.getScheme();
			return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) ? parsed : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
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

	/** Guild war counts (backend-authoritative), own row highlighted. */
	public static Component warCounts(int days, java.util.List<tel.eden.mod.net.WarCountEntry> entries, String requester) {
		MutableComponent out = Component.empty().append(prefix(SHIELD));
		if (entries.isEmpty()) {
			return out.append(Component.literal("No guild wars in the last " + days + " days.").withStyle(ChatFormatting.GOLD));
		}
		out.append(Component.literal("Guild wars — last " + days + " days:").withStyle(ChatFormatting.GREEN));
		for (tel.eden.mod.net.WarCountEntry entry : entries) {
			boolean own = entry.name().equalsIgnoreCase(requester);
			out.append(Component.literal("\n  " + entry.name()).withStyle(own ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.AQUA)).append(Component.literal(" — " + entry.wars()).withStyle(ChatFormatting.GRAY));
		}
		return out;
	}

	/**
	 * Guild ally activity list (backend-authoritative, ordered from most inactive to active).
	 * If {@code canManage} is true (Strategist+), each ally row has a hover tooltip and click
	 * action to suggest {@code /eden ally remove {name}}.
	 */
	public static Component allyActivity(java.util.List<tel.eden.mod.net.AllyActivityEntry> entries, boolean canManage) {
		MutableComponent out = Component.empty().append(prefix(SHIELD));
		if (entries == null || entries.isEmpty()) {
			return out.append(Component.literal("Unable to fetch ally info, try again later.").withStyle(ChatFormatting.RED));
		}
		out.append(Component.literal("Guild Allies (by inactivity):").withStyle(ChatFormatting.GREEN));
		for (tel.eden.mod.net.AllyActivityEntry entry : entries) {
			ChatFormatting color;
			if (entry.elapsedMinutes() > 45) {
				color = ChatFormatting.RED;
			} else if (entry.elapsedMinutes() >= 30) {
				color = ChatFormatting.YELLOW;
			} else {
				color = ChatFormatting.GREEN;
			}
			long mins = Math.round(entry.elapsedMinutes());
			String timeStr = mins < 60 ? mins + "m ago" : (mins / 60) + "h " + (mins % 60) + "m ago";
			String tag = (entry.tag() != null && !entry.tag().isEmpty()) ? entry.tag() : AllyTagCache.getTag(entry.name());
			if (tag != null && !tag.isEmpty()) {
				AllyTagCache.remember(entry.name(), tag);
			}
			String tagStr = (tag != null && !tag.isEmpty()) ? " [" + tag + "]" : "";
			String lineText = "\n  " + entry.name() + tagStr + " — " + timeStr;

			MutableComponent line = Component.literal(lineText).withStyle(color);
			if (canManage) {
				String target = (tag != null && !tag.isEmpty()) ? tag : entry.name();
				line = line.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to suggest /eden ally remove " + target).withStyle(ChatFormatting.GRAY))).withClickEvent(new ClickEvent.SuggestCommand("/eden ally remove " + target)));
			}
			out.append(line);
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
		return format(author, content, replyTo, replyExcerpt, null);
	}

	/**
	 * Build the guild-styled chat line for a relayed Discord message with reply context
	 * and interactive reply click events.
	 */
	public static Component format(String author, String content, String replyTo, String replyExcerpt, String messageId) {
		if (messageId != null && !messageId.isEmpty()) {
			ChatReplyManager.recordDiscordMessage(messageId, author, content);
		}
		Style replyStyle = (messageId != null && !messageId.isEmpty()) ? Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/eden reply " + messageId)).withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to reply to @" + author).withStyle(ChatFormatting.GRAY))) : null;

		Style pillStyle = Style.EMPTY.withFont(PILL_FONT).withColor(ChatFormatting.GREEN);
		if (replyStyle != null) {
			pillStyle = pillStyle.withClickEvent(replyStyle.getClickEvent()).withHoverEvent(replyStyle.getHoverEvent());
		}
		MutableComponent body = Component.empty().append(Component.literal(pillLabel("discord")).withStyle(pillStyle));

		String authorText = (replyTo != null && !replyTo.isEmpty()) ? " " + author : " " + author + ": ";
		MutableComponent authorComp = Component.literal(authorText).withStyle(ChatFormatting.GREEN);
		if (replyStyle != null) {
			authorComp.setStyle(authorComp.getStyle().withClickEvent(replyStyle.getClickEvent()).withHoverEvent(replyStyle.getHoverEvent()));
		}
		body.append(authorComp);

		if (replyTo != null && !replyTo.isEmpty()) {
			body.append(Component.literal(" replied to ").withStyle(ChatFormatting.GRAY)).append(replyTarget(replyTo, replyExcerpt, Style.EMPTY.withColor(ChatFormatting.GREEN))).append(Component.literal(": ").withStyle(ChatFormatting.GREEN));
		}
		body.append(linkify(content, replyStyle));
		return withGuildPrefix(body);
	}

	// Traveler chat's own color, lavender, so it never reads as guild (aqua) or Discord (green) chat.
	private static final int TRAVELER_RGB = 0xB388FF;
	// The message text is a lighter shade than the tag and name so the name stands out.
	private static final int TRAVELER_TEXT_RGB = 0xD8C2FF;
	// Emblem colour on traveler lines that came from Discord (Discord blurple).
	private static final int DISCORD_RGB = 0x5865F2;

	// Invisible line markers a Wynntils chat-tab regex can match: one zero-width
	// non-joiner on every line this mod draws, two on traveler chat so it can be
	// routed to (or kept out of) its own tab.
	private static final String LINE_MARKER = "\u200C";
	private static final String TRAVELER_MARKER = "\u200C\u200C";

	/**
	 * A traveler-chat line; links, images, GIFs, stickers and emotes render as in bridge chat.
	 * {@code tier} ("member", "veteran", anything else = traveler) picks the pill text; the
	 * emblem is lavender, or Discord blurple when {@code fromDiscord}.
	 */
	public static Component travelerChat(String author, String content, String replyTo, String replyExcerpt, String messageId, String tier, boolean fromDiscord) {
		return travelerChat(author, content, replyTo, replyExcerpt, messageId, tier, fromDiscord, null, null);
	}

	/** {@link #travelerChat} with the first {@code itemLabel} in the text styled {@code itemStyle} (a shared item's hover). */
	public static Component travelerChat(String author, String content, String replyTo, String replyExcerpt, String messageId, String tier, boolean fromDiscord, String itemLabel, Style itemStyle) {
		boolean hasId = messageId != null && !messageId.isEmpty();
		if (hasId) {
			ChatReplyManager.recordTravelerMessage(messageId, author, content);
		}
		String label = "member".equals(tier) || "veteran".equals(tier) ? tier : "traveler";
		Style color = Style.EMPTY.withColor(TRAVELER_RGB);
		Style click = hasId ? color.withClickEvent(new ClickEvent.RunCommand("/eden treply " + messageId)).withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to reply to @" + author).withStyle(ChatFormatting.GRAY))) : color;
		boolean reply = replyTo != null && !replyTo.isEmpty();
		MutableComponent body = Component.empty().append(Component.literal(pillLabel(label)).withStyle(click.withFont(PILL_FONT))).append(Component.literal(reply ? " " + author : " " + author + ": ").withStyle(click));
		if (reply) {
			body.append(Component.literal(" replied to ").withStyle(ChatFormatting.GRAY)).append(replyTarget(replyTo, replyExcerpt, color)).append(Component.literal(": ").withStyle(color));
		}
		int at = itemLabel == null || itemStyle == null ? -1 : content.indexOf(itemLabel);
		Style textStyle = Style.EMPTY.withColor(TRAVELER_TEXT_RGB);
		if (at < 0) {
			body.append(linkify(content, hasId ? click : null, textStyle));
		} else {
			int end = at + itemLabel.length();
			body.append(linkify(content.substring(0, at), hasId ? click : null, textStyle)).append(Component.literal(itemLabel).withStyle(itemStyle)).append(linkify(content.substring(end), hasId ? click : null, textStyle));
		}
		return withGuildPrefix(body, TRAVELER_MARKER, fromDiscord ? DISCORD_RGB : TRAVELER_RGB);
	}

	/**
	 * Build a gold-pill bridge line (Quick Reactions, {@code /eden cf}/{@code diceroll}):
	 * a gold pill labelled {@code label}, then the {@code content} in gold. Mirrors
	 * {@link #format} but in gold rather than the green {@code discord} styling.
	 */
	public static Component pill(String label, String content) {
		return pill(label, content, null);
	}

	/**
	 * Build a pill bridge line in a custom color (e.g. the pink daily gambit-reset
	 * reminder). {@code colorRgb} of {@code null} falls back to the default gold.
	 */
	public static Component pill(String label, String content, Integer colorRgb) {
		return pill(label, content, colorRgb, false);
	}

	/**
	 * Build a pill bridge line, optionally bold (e.g. the storage-nearly-full
	 * in-game warning) — for a message that needs to stand out further than
	 * color alone.
	 */
	public static Component pill(String label, String content, Integer colorRgb, boolean bold) {
		int rgb = colorRgb != null ? colorRgb : ChatFormatting.GOLD.getColor();
		// The label is a run of custom glyph codepoints from a resource-pack font
		// (see pillLabel) — Minecraft's bold rendering does a synthetic pixel-shift
		// on the glyph bitmap that corrupts non-standard glyphs like these, so bold
		// only ever applies to the plain-text content segment, never the label.
		// Emotes are swapped in before wrapping, so a :shortcode: can't be split across lines.
		MutableComponent body = Component.empty().append(Component.literal(pillLabel(label)).withStyle(Style.EMPTY.withFont(PILL_FONT).withColor(rgb))).append(processEmotes(Component.literal(" " + content).withStyle(style -> style.withColor(rgb).withBold(bold))));
		return withGuildPrefix(body);
	}

	/** The "replyTo (excerpt)" segment: name in green, inline quote gray, full quote on hover. */
	private static MutableComponent replyTarget(String replyTo, String replyExcerpt, Style nameStyle) {
		MutableComponent segment = Component.literal(replyTo).withStyle(nameStyle);
		String quote = replyExcerpt == null ? "" : ChatText.normalizeWhitespace(replyExcerpt);
		if (!quote.isEmpty()) {
			String shown = quote.length() > EXCERPT_MAX ? quote.substring(0, EXCERPT_MAX).strip() + "…" : quote;
			Style hover = Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withHoverEvent(new HoverEvent.ShowText(Component.literal(quote)));
			segment.append(Component.literal(" (" + shown + ")").setStyle(hover));
		}
		return segment;
	}

	/**
	 * Render message text with any http(s) URLs as clickable, underlined aqua links,
	 * and any {@code :shortcode:} token matching a known emote (see
	 * {@link EmoteRegistry}) as an inline image. Unknown shortcodes are left as
	 * plain literal text (e.g. {@code :notarealemote:} stays exactly that), so a
	 * message never loses information just because an emote isn't recognized.
	 */
	private static MutableComponent linkify(String content) {
		return linkify(content, null);
	}

	private static MutableComponent linkify(String content, Style replyStyle) {
		return linkify(content, replyStyle, Style.EMPTY.withColor(ChatFormatting.GREEN));
	}

	private static MutableComponent linkify(String content, Style replyStyle, Style textStyle) {
		MutableComponent out = Component.empty();
		Matcher matcher = TOKEN_PATTERN.matcher(content);
		int last = 0;
		// Click event for the text body (without hover popup so reading isn't obstructed)
		ClickEvent textClick = replyStyle != null ? replyStyle.getClickEvent() : null;
		java.util.function.Consumer<String> appendText = text -> {
			MutableComponent comp = Component.literal(text).withStyle(textStyle);
			if (textClick != null) {
				comp.setStyle(comp.getStyle().withClickEvent(textClick));
			}
			out.append(comp);
		};

		while (matcher.find()) {
			if (matcher.start() > last) {
				appendText.accept(content.substring(last, matcher.start()));
			}
			String stickerName = matcher.group("sname");
			String url = matcher.group("url");
			if (stickerName != null) {
				// A relayed Discord sticker: render the single "[sticker: <name>]" token,
				// hoverable to preview the sticker image itself (same trusted-CDN preview
				// path as image links).
				String stickerUrl = matcher.group("surl");
				boolean isImage = ImagePreviewManager.isPreviewable(stickerUrl);
				out.append(Component.literal("[sticker: " + stickerName + "]").withStyle(linkStyle(stickerUrl, isImage)));
			} else if (url != null) {
				// Only images from trusted CDNs (Discord, Imgur, Tenor, Giphy, etc.) or
				// a Tenor share-page link become an inline preview; any other
				// image-looking link stays a plain link so hovering it can't make the
				// client fetch an attacker-controlled URL. See
				// ImagePreviewManager.isPreviewable.
				boolean isImage = ImagePreviewManager.isPreviewable(url);
				String label = isImage ? (ImagePreviewManager.isGifUrl(url) ? "[GIF]" : "[Image]") : url;
				out.append(Component.literal(label).withStyle(linkStyle(url, isImage)));
			} else {
				String shortcode = matcher.group("emote");
				Component emote = emoteComponent(shortcode);
				if (emote != null) {
					out.append(emote);
				} else {
					appendText.accept(matcher.group());
				}
			}
			last = matcher.end();
		}
		if (last < content.length()) {
			appendText.accept(content.substring(last));
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

	private static Style linkStyle(String url, boolean isImage) {
		Style base = Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true);
		try {
			// Embed the shared marker in the hover text so GuiGraphicsMixin can extract the URL for the preview.
			Component hoverText = isImage ? Component.literal(ImagePreviewManager.HOVER_MARKER + url) : Component.literal("Open " + url);
			return base.withClickEvent(new ClickEvent.OpenUrl(URI.create(url))).withHoverEvent(new HoverEvent.ShowText(hoverText));
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
		return withGuildPrefix(body, LINE_MARKER);
	}

	private static Component withGuildPrefix(Component body, String marker) {
		return withGuildPrefix(body, marker, null);
	}

	/** {@code emblemRgb} recolours the emblem and wrap bars; null keeps the guild green. */
	private static Component withGuildPrefix(Component body, String marker, Integer emblemRgb) {
		Component shield = prefix(SHIELD, marker, emblemRgb);
		Component continuation = prefix(CONTINUATION, marker, emblemRgb);
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
		return prefix(glyphs, LINE_MARKER);
	}

	private static Component prefix(int[] glyphs, String marker) {
		return prefix(glyphs, marker, null);
	}

	private static Component prefix(int[] glyphs, String marker, Integer emblemRgb) {
		StringBuilder out = new StringBuilder();
		for (int glyph : glyphs) {
			out.appendCodePoint(glyph);
		}
		if (emblemRgb == null) {
			return Component.literal(marker + out).append(" ").withStyle(PREFIX_STYLE);
		}
		// The marker keeps the guild-green style so Wynntils tab filters see the same text.
		return Component.literal(marker).withStyle(PREFIX_STYLE).append(Component.literal(out + " ").withStyle(PREFIX_STYLE.withColor(emblemRgb)));
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
