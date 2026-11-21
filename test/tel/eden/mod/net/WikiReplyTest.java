package tel.eden.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import tel.eden.mod.chat.DiscordChatFormatter;

class WikiReplyTest {
	@Test
	void parsesAndFormatsWikiPageWithObtainingLocationAndLink() {
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
					  "obtaining": "Dropped by The Elephelk in the Canyon of the Lost.",
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
		assertTrue(text.contains("Wiki: Elephelk Trunk"));
		assertTrue(text.contains("[Link]"));
		assertTrue(text.contains("Obtaining: Dropped by The Elephelk"));
		assertTrue(text.contains("Location: Spawn Node — Canyon of the Lost (585, 35, -4974)"));
		assertTrue(text.contains("Tier: 3 | Level: 86"));
	}

	@Test
	void disambiguationChoicesCarryClickableRunCommands() {
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
		assertTrue(rendered.getString().contains("[Hero]"));
		assertTrue(rendered.getString().contains("[HERO]"));

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
		assertEquals(ClickEvent.class, ClickEvent.class);
		assertInstanceOf(Component.class, DiscordChatFormatter.wikiReply(err));
		assertTrue(DiscordChatFormatter.wikiReply(err).getString().contains("Timed out"));
	}
}
