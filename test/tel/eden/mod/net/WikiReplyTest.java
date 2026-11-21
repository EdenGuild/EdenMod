package tel.eden.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import tel.eden.mod.chat.DiscordChatFormatter;

class WikiReplyTest {
	@Test
	void parsesAndFormatsWikiPageWithObtainingLocationRarityAndCoords() {
		String json = """
					{
					  "type": "wikiReply",
					  "requestId": 7,
					  "status": "page",
					  "query": "Elephelk Trunk",
					  "title": "Elephelk Trunk",
					  "url": "https://wynncraft.wiki.gg/wiki/Elephelk_Trunk",
					  "lore": "A prized trophy.",
					  "description": "Elephelk Trunk is a Tier 3 Crafting Ingredient.",
					  "obtaining": "Boss Drop\\nDropped by The Elephelk in the Canyon of the Lost.",
					  "location": "Spawn Node — Canyon of the Lost (585, 35, -4974)",
					  "fields": [{"name": "Tier", "value": "3"}, {"name": "Level", "value": "86"}]
					}
					""";
		WikiReplyData reply = BridgeWebSocketClient.parseWikiReply(JsonParser.parseString(json).getAsJsonObject());
		assertEquals(7L, reply.requestId());
		assertEquals("page", reply.status());
		assertEquals("Elephelk Trunk", reply.title());
		assertEquals(2, reply.fields().size());

		Component rendered = DiscordChatFormatter.wikiReply(reply);
		String text = rendered.getString();
		assertTrue(text.contains("Elephelk Trunk"));
		assertTrue(text.contains("[Link]"));
		assertTrue(text.contains("Tier: 3  •  Level: 86"));
		assertTrue(text.contains("▸ Obtaining"));
		assertTrue(text.contains("• Boss Drop"));
		assertTrue(text.contains("• Dropped by The Elephelk"));
		assertTrue(text.contains("▸ Location"));
		assertTrue(text.contains("• Spawn Node — Canyon of the Lost (585, 35, -4974)"));

		AtomicBoolean titleIsAquaBold = new AtomicBoolean();
		AtomicBoolean coordsAreGreen = new AtomicBoolean();
		rendered.visit((style, content) -> {
			if (content.contains("Elephelk Trunk") && style.isBold() && Integer.valueOf(ChatFormatting.AQUA.getColor()).equals(style.getColor() != null ? style.getColor().getValue() : null)) {
				titleIsAquaBold.set(true);
			}
			if ("(585, 35, -4974)".equals(content) && Integer.valueOf(ChatFormatting.GREEN.getColor()).equals(style.getColor() != null ? style.getColor().getValue() : null)) {
				coordsAreGreen.set(true);
			}
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		assertTrue(titleIsAquaBold.get());
		assertTrue(coordsAreGreen.get());
	}

	@Test
	void disambiguationChoicesShowCompactClickableOptionsWithoutDescriptions() {
		String json = """
					{
					  "type": "wikiReply",
					  "requestId": 9,
					  "status": "choices",
					  "query": "Hero",
					  "choices": [
					    {"title": "Hero", "url": "https://wynncraft.wiki.gg/wiki/Hero", "snippet": "Mythic Spear."},
					    {"title": "HERO", "url": "https://wynncraft.wiki.gg/wiki/HERO", "snippet": "Store rank."}
					  ]
					}
					""";
		WikiReplyData reply = BridgeWebSocketClient.parseWikiReply(JsonParser.parseString(json).getAsJsonObject());
		Component rendered = DiscordChatFormatter.wikiReply(reply);
		String text = rendered.getString();
		assertTrue(text.contains("[Hero]"));
		assertTrue(text.contains("[HERO]"));
		assertFalse(text.contains("Mythic Spear"));
		assertFalse(text.contains("Store rank"));

		AtomicBoolean foundHeroClick = new AtomicBoolean();
		rendered.visit((style, content) -> {
			if ("[Hero]".equals(content) && style.getClickEvent() instanceof ClickEvent.RunCommand cmd) {
				assertEquals("/eden wiki Hero", cmd.command());
				foundHeroClick.set(true);
			}
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		assertTrue(foundHeroClick.get());
	}

	@Test
	void emptyAndErrorStatesRenderCleanly() {
		var empty = BridgeWebSocketClient.parseWikiReply(JsonParser.parseString("{\"type\":\"wikiReply\",\"status\":\"empty\",\"query\":\"MissingItem\"}").getAsJsonObject());
		assertTrue(DiscordChatFormatter.wikiReply(empty).getString().contains("No wiki articles found for \"MissingItem\""));

		var err = BridgeWebSocketClient.parseWikiReply(JsonParser.parseString("{\"type\":\"wikiReply\",\"status\":\"error\",\"error\":\"Timed out\"}").getAsJsonObject());
		assertTrue(DiscordChatFormatter.wikiReply(err).getString().contains("Timed out"));
	}
}
