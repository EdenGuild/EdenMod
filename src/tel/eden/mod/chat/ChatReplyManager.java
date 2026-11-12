package tel.eden.mod.chat;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Manages active and pending Discord reply state for the in-game chat UI and bridge.
 */
public final class ChatReplyManager {
	public record ReplyInfo(String messageId, String author, String excerpt) {
	}

	private static final int MAX_CACHE_SIZE = 100;
	private static final Map<String, ReplyInfo> RECENT_MESSAGES = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, ReplyInfo> eldest) {
			return size() > MAX_CACHE_SIZE;
		}
	};

	private static ReplyInfo activeReply = null;
	private static String pendingReplyId = null;
	private static String pendingReplyText = null;
	private static long pendingReplyTime = 0L;
	private static final long PENDING_TIMEOUT_MS = 15_000L;

	private static final int BANNER_HEIGHT = 14;
	private static final int CLOSE_BUTTON_WIDTH = 14;

	@FunctionalInterface
	public interface ChatScreenInputHolder {
		void ensureGuildPrefix();
	}

	private static ChatScreenInputHolder activeInputHolder = null;

	private ChatReplyManager() {
	}

	/** Register callback to manipulate the active ChatScreen input box. */
	public static synchronized void registerInputHolder(ChatScreenInputHolder holder) {
		activeInputHolder = holder;
	}

	/** Unregister callback when ChatScreen is closed. */
	public static synchronized void unregisterInputHolder() {
		activeInputHolder = null;
	}

	/** Ensure the chat input box starts with /g so messages are routed to guild chat. */
	public static synchronized void ensureGuildPrefix() {
		if (activeInputHolder != null) {
			activeInputHolder.ensureGuildPrefix();
		}
	}

	/** Record a received Discord message so it can be replied to by ID. */
	public static synchronized void recordDiscordMessage(String messageId, String author, String content) {
		if (messageId == null || messageId.isEmpty() || author == null) {
			return;
		}
		String excerpt = content != null ? content.strip() : "";
		if (excerpt.length() > 60) {
			excerpt = excerpt.substring(0, 57).strip() + "...";
		}
		RECENT_MESSAGES.put(messageId, new ReplyInfo(messageId, author, excerpt));
	}

	/** Retrieve cached message metadata by Discord message ID. */
	static synchronized ReplyInfo getRecent(String messageId) {
		return RECENT_MESSAGES.get(messageId);
	}

	/** Set active reply context looking up cached metadata. */
	public static synchronized void setActiveReply(String messageId) {
		ReplyInfo info = RECENT_MESSAGES.get(messageId);
		if (info != null) {
			activeReply = info;
		} else {
			activeReply = new ReplyInfo(messageId, "Discord", "");
		}
	}

	/** Currently active reply context in the chat UI. */
	static synchronized ReplyInfo activeReplyForTesting() {
		return activeReply;
	}

	/** Whether a reply banner should be rendered in the chat UI. */
	public static synchronized boolean hasActiveReply() {
		return activeReply != null;
	}

	/** Clear the active reply banner in the chat UI. */
	public static synchronized void clearActiveReply() {
		activeReply = null;
	}

	/** Move the active reply context into a pending reply state when chat is submitted. */
	public static synchronized void markPendingReply() {
		markPendingReply(null);
	}

	/**
	 * As {@link #markPendingReply()}, remembering the text that was sent so the echoed line can be
	 * recognised by what it says as well as by who it is from.
	 */
	public static synchronized void markPendingReply(String sentText) {
		if (activeReply != null) {
			pendingReplyId = activeReply.messageId();
			pendingReplyText = sentText == null ? null : normalize(sentText);
			pendingReplyTime = System.currentTimeMillis();
			activeReply = null;
		}
	}

	/** Consume the pending reply for an echoed line from {@code username}; see {@link #consumePendingReply(String, String)}. */
	public static synchronized String consumePendingReply(String username) {
		return consumePendingReply(username, null);
	}

	/**
	 * Consume and return the pending reply message ID if the echoed guild line is the local player's
	 * and hasn't timed out. A line counts as ours if it is from our account name, from our nickname, or
	 * says exactly what we sent: Wynncraft shows a nicknamed player under the nickname, and the parser
	 * only turns that back into the account name when the line carries a hover.
	 */
	public static synchronized String consumePendingReply(String username, String body) {
		if (pendingReplyId == null) {
			return null;
		}
		if (System.currentTimeMillis() - pendingReplyTime > PENDING_TIMEOUT_MS) {
			pendingReplyId = null;
			pendingReplyText = null;
			return null;
		}
		if (!isLocalPlayer(username) && !(pendingReplyText != null && body != null && pendingReplyText.equals(normalize(body)))) {
			return null;
		}
		String id = pendingReplyId;
		pendingReplyId = null;
		pendingReplyText = null;
		return id;
	}

	private static boolean isLocalPlayer(String username) {
		Minecraft mc = Minecraft.getInstance();
		if (username == null || mc == null || mc.getUser() == null) {
			return true;
		}
		return username.equalsIgnoreCase(mc.getUser().getName()) || username.equalsIgnoreCase(CharacterInfoNickname.localNickname());
	}

	private static String normalize(String text) {
		return text.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
	}

	/** Reset both active and pending reply states. */
	public static synchronized void reset() {
		activeReply = null;
		pendingReplyId = null;
		pendingReplyText = null;
	}

	/** Check if a click hit the close button on the active reply banner. */
	public static boolean isCloseButtonClicked(int inputX, int inputY, int inputWidth, double mouseX, double mouseY) {
		if (activeReply == null) {
			return false;
		}
		int bannerX = inputX;
		int bannerY = inputY - BANNER_HEIGHT;
		int closeX = bannerX + inputWidth - CLOSE_BUTTON_WIDTH;
		return mouseX >= closeX && mouseX <= closeX + CLOSE_BUTTON_WIDTH && mouseY >= bannerY && mouseY <= bannerY + BANNER_HEIGHT;
	}

	/** Render the Discord-style interactive reply banner above the chat box. */
	public static void renderReplyBanner(GuiGraphics graphics, Font font, int inputX, int inputY, int inputWidth, int mouseX, int mouseY) {
		ReplyInfo reply = activeReply;
		if (reply == null) {
			return;
		}
		int bannerX = inputX;
		int bannerY = inputY - BANNER_HEIGHT;
		int bannerW = inputWidth;
		int bannerH = BANNER_HEIGHT - 2;

		// Background: semi-transparent dark grey/slate (Discord style)
		graphics.fill(bannerX, bannerY, bannerX + bannerW, bannerY + bannerH, 0xEE1E1F22);
		// Top border subtle line
		graphics.fill(bannerX, bannerY, bannerX + bannerW, bannerY + 1, 0xFF35373C);
		// Left vertical accent: Discord Blurple (#5865F2)
		graphics.fill(bannerX, bannerY, bannerX + 2, bannerY + bannerH, 0xFF5865F2);

		int textY = bannerY + 2;
		int curX = bannerX + 6;

		// Arrow and label: "⤹ Replying to @"
		String prefix = "\u2939 Replying to @";
		graphics.drawString(font, prefix, curX, textY, 0xFFAAAAAA);
		curX += font.width(prefix);

		// Author name in Discord Blurple
		String author = reply.author();
		graphics.drawString(font, author, curX, textY, 0xFF5865F2);
		curX += font.width(author);

		int closeX = bannerX + bannerW - CLOSE_BUTTON_WIDTH;
		boolean closeHovered = mouseX >= closeX && mouseX <= closeX + CLOSE_BUTTON_WIDTH && mouseY >= bannerY && mouseY <= bannerY + bannerH;

		// Excerpt if available
		if (!reply.excerpt().isEmpty()) {
			String sep = ": \"";
			graphics.drawString(font, sep, curX, textY, 0xFF888888);
			curX += font.width(sep);

			int maxExcerptW = (closeX - 6) - curX - font.width("\"");
			if (maxExcerptW > 20) {
				String excerpt = font.plainSubstrByWidth(reply.excerpt(), maxExcerptW);
				graphics.drawString(font, excerpt, curX, textY, 0xFF888888);
				curX += font.width(excerpt);
				graphics.drawString(font, "\"", curX, textY, 0xFF888888);
			}
		}

		// Close button [✕]
		if (closeHovered) {
			graphics.fill(closeX, bannerY, closeX + CLOSE_BUTTON_WIDTH, bannerY + bannerH, 0x40FF0000);
			graphics.drawString(font, "\u2715", closeX + 3, textY, 0xFFFF5555);
		} else {
			graphics.drawString(font, "\u2715", closeX + 3, textY, 0xFF888888);
		}
	}
}
