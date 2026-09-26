package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class GuildChatParserTest {
	private static final String WYNN_SOFT_WRAP = "\uDAFF\uDFFC\uE001\uDB00\uDC06";

	@Test
	void joinsAWynncraftSoftWrapInsideALink() {
		String first = "https://wynnbuilder.github.io/builder/#CYG4OaaZWnGa0Q8tid818RX8mZPum";
		String second = "ZPuGFvEvGH24169v8OC49W6";
		Component message = Component.literal("FadeDave: " + first + "\n" + WYNN_SOFT_WRAP + " " + second).withStyle(ChatFormatting.AQUA);

		CapturedMessage parsed = GuildChatParser.parse(message).orElseThrow();

		assertEquals(first + second, parsed.message());
	}

	@Test
	void retainsASpaceForAWynncraftSoftWrapInProse() {
		Component message = Component.literal("FadeDave: hello\n" + WYNN_SOFT_WRAP + " world").withStyle(ChatFormatting.AQUA);

		assertEquals("hello world", GuildChatParser.parse(message).orElseThrow().message());
	}
}
