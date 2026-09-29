package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatReplyManagerTest {

	@BeforeEach
	void setUp() {
		ChatReplyManager.reset();
	}

	@Test
	void recordsAndRetrievesDiscordMessages() {
		ChatReplyManager.recordDiscordMessage("123456789", "Alice", "Hello there!");
		ChatReplyManager.ReplyInfo info = ChatReplyManager.getRecent("123456789");
		assertNotNull(info);
		assertEquals("123456789", info.messageId());
		assertEquals("Alice", info.author());
		assertEquals("Hello there!", info.excerpt());
	}

	@Test
	void truncatesLongExcerpt() {
		String longMsg = "This is a very long message that should be truncated because it exceeds sixty characters in total length.";
		ChatReplyManager.recordDiscordMessage("999", "Bob", longMsg);
		ChatReplyManager.ReplyInfo info = ChatReplyManager.getRecent("999");
		assertNotNull(info);
		assertTrue(info.excerpt().endsWith("..."));
		assertTrue(info.excerpt().length() <= 60);
	}

	@Test
	void managesActiveReplyState() {
		assertFalse(ChatReplyManager.hasActiveReply());
		assertNull(ChatReplyManager.activeReplyForTesting());

		ChatReplyManager.recordDiscordMessage("111", "Charlie", "Msg");
		ChatReplyManager.setActiveReply("111");

		assertTrue(ChatReplyManager.hasActiveReply());
		assertEquals("111", ChatReplyManager.activeReplyForTesting().messageId());
		assertEquals("Charlie", ChatReplyManager.activeReplyForTesting().author());

		ChatReplyManager.clearActiveReply();
		assertFalse(ChatReplyManager.hasActiveReply());
		assertNull(ChatReplyManager.activeReplyForTesting());
	}

	@Test
	void marksAndConsumesPendingReply() {
		ChatReplyManager.recordDiscordMessage("222", "Dave", "Question");
		ChatReplyManager.setActiveReply("222");

		ChatReplyManager.markPendingReply();
		assertFalse(ChatReplyManager.hasActiveReply());

		// In headless test mc.getUser() is null, so username check is bypassed
		String replyId = ChatReplyManager.consumePendingReply("AnyUser", "Answer");
		assertEquals("222", replyId);

		// Second consume returns null (consumed)
		assertNull(ChatReplyManager.consumePendingReply("AnyUser", "Answer"));
	}

	@Test
	void detectsCloseButtonClick() {
		ChatReplyManager.recordDiscordMessage("333", "Eve", "Test");
		ChatReplyManager.setActiveReply("333");
		int inputX = 4;
		int inputY = 200;
		int inputW = 300;

		// Close button is at bannerX + inputW - 14 to bannerX + inputW
		// bannerX = 4, closeX = 4 + 300 - 14 = 290
		// bannerY = 200 - 14 = 186, bannerH = 14 (186 to 200)

		// Inside close button
		assertTrue(ChatReplyManager.isCloseButtonClicked(inputX, inputY, inputW, 295, 190));

		// Outside close button (to the left in the banner)
		assertFalse(ChatReplyManager.isCloseButtonClicked(inputX, inputY, inputW, 100, 190));

		// Outside vertically
		assertFalse(ChatReplyManager.isCloseButtonClicked(inputX, inputY, inputW, 295, 180));
		assertFalse(ChatReplyManager.isCloseButtonClicked(inputX, inputY, inputW, 295, 205));
	}

	@Test
	void invokesRegisteredInputHolder() {
		AtomicBoolean invoked = new AtomicBoolean(false);
		ChatReplyManager.ChatScreenInputHolder holder = () -> invoked.set(true);

		ChatReplyManager.registerInputHolder(holder);
		ChatReplyManager.ensureGuildPrefix();
		assertTrue(invoked.get());

		invoked.set(false);
		ChatReplyManager.unregisterInputHolder();
		ChatReplyManager.ensureGuildPrefix();
		assertFalse(invoked.get());
	}
}
