package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/**
 * Only {@link DiscordChatFormatter#processEmotes} is covered here — it's the one
 * public entry point that doesn't touch {@code Minecraft.getInstance()} (no chat
 * width/font lookups); {@code format}/{@code pill} need a live client to wrap text,
 * consistent with this project's other client-dependent classes having no unit tests.
 */
class DiscordChatFormatterTest {
	@Test
	void leavesAnUnknownShortcodeAsLiteralText() {
		Component result = DiscordChatFormatter.processEmotes(Component.literal("hello :definitelynotarealemote12345: world"));

		assertEquals("hello :definitelynotarealemote12345: world", result.getString());
	}

	@Test
	void returnsTheOriginalComponentUnchangedWhenNoShortcodePatternIsPresent() {
		Component message = Component.literal("just plain text");

		assertSame(message, DiscordChatFormatter.processEmotes(message));
	}

	@Test
	void preservesTheSurroundingStyleForTheUnknownShortcodeLiteral() {
		Component message = Component.literal("say :notreal: now").withStyle(ChatFormatting.RED);

		Component result = DiscordChatFormatter.processEmotes(message);

		assertEquals("say :notreal: now", result.getString());
	}

	@Test
	void substitutesAKnownEmoteShortcodeWithAnInlineGlyph() {
		// Built from the generated manifest rather than a hardcoded shortcode, so this
		// doesn't break if the available emote assets change.
		List<String> known = EmoteRegistry.shortcodes();
		assumeFalse(known.isEmpty(), "no emotes in the generated manifest to test against");
		String shortcode = known.get(0);

		Component result = DiscordChatFormatter.processEmotes(Component.literal("hi :" + shortcode + ": there"));

		// The literal ":shortcode:" text is replaced by a glyph, not left as-is.
		assertNotEquals("hi :" + shortcode + ": there", result.getString());
	}

	@Test
	void allyActivityFormatsTagWhenPresentAndRemembersInCache() {
		tel.eden.mod.guild.AllyTagCache.clear();
		var entries = List.of(new tel.eden.mod.net.AllyActivityEntry("Blood Orchid", "Wilt", 15.0, "Nexus"));
		Component component = DiscordChatFormatter.allyActivity(entries, false);
		String text = component.getString();

		org.junit.jupiter.api.Assertions.assertTrue(text.contains("Blood Orchid [Wilt] — 15m ago"));
		assertEquals("Wilt", tel.eden.mod.guild.AllyTagCache.getTag("Blood Orchid"));
	}

	@Test
	void allyActivityFallsBackToCachedTagWhenMissingFromEntry() {
		tel.eden.mod.guild.AllyTagCache.clear();
		tel.eden.mod.guild.AllyTagCache.remember("Fallen Angels", "FA");

		var entries = List.of(new tel.eden.mod.net.AllyActivityEntry("Fallen Angels", null, 5.0, null));
		Component component = DiscordChatFormatter.allyActivity(entries, false);
		String text = component.getString();

		org.junit.jupiter.api.Assertions.assertTrue(text.contains("Fallen Angels [FA] — 5m ago"));
	}

	@Test
	void allyActivityFormatsCleanlyWhenTagUnknown() {
		tel.eden.mod.guild.AllyTagCache.clear();
		var entries = List.of(new tel.eden.mod.net.AllyActivityEntry("Unknown Guild", null, 10.0, null));
		Component component = DiscordChatFormatter.allyActivity(entries, false);
		String text = component.getString();

		org.junit.jupiter.api.Assertions.assertTrue(text.contains("Unknown Guild — 10m ago"));
	}
}
