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
					  "query": "Honoured Commander's Medal",
					  "title": "Honoured Commander's Medal",
					  "url": "https://wynncraft.wiki.gg/wiki/Honoured_Commander%27s_Medal",
					  "lore": "Should not appear in game.",
					  "description": "Honoured Commander's Medal is a Tier 3 Crafting Ingredient.",
					  "obtaining": "Boss Drop\\nDropped by Wings Commanders.",
					  "location": "Wellspring of Eternity (585, 35, -4974)",
					  "fields": [
					    {"name": "Tier", "value": "3"},
					    {"name": "Level", "value": "116"},
					    {"name": "Professions", "value": "Armouring"},
					    {"name": "Duration", "value": "-315s"},
					    {"name": "Touch Eff.", "value": "+50%"}
					  ],
					  "card": [
					    [{"t": "Honoured Commander's Medal", "c": "aqua", "b": true}, {"t": " [★★★]", "c": "aqua"}],
					    [{"t": "Tier 3 Crafting Ingredient", "c": "gray"}],
					    [{"t": "+50%", "c": "green"}, {"t": " Ingredient Effectiveness", "c": "white"}],
					    [{"t": "(To ingredients touching this one)", "c": "gray"}],
					    [{"t": "-170 Durability", "c": "red"}, {"t": " or ", "c": "gray"}, {"t": "-315s Duration", "c": "red"}],
					    [{"t": "Crafting Lv. Min: ", "c": "gray"}, {"t": "116", "c": "white"}]
					  ]
					}
					""";
		WikiReplyData reply = BridgeWebSocketClient.parseWikiReply(JsonParser.parseString(json).getAsJsonObject());
		assertEquals(7L, reply.requestId());
		assertEquals("page", reply.status());
		assertEquals("Honoured Commander's Medal", reply.title());
		assertEquals(5, reply.fields().size());
		assertEquals(6, reply.card().size());

		Component rendered = DiscordChatFormatter.wikiReply(reply);
		String text = rendered.getString();
		assertTrue(text.contains("Honoured Commander's Medal"));
		assertTrue(text.contains("[Stats]"));
		assertTrue(text.contains("[Link]"));
		assertFalse(text.contains("Should not appear in game"));
		assertTrue(text.contains("Tier: 3  •  Level: 116"));
		assertTrue(text.contains("▸ Obtaining"));
		assertTrue(text.contains("• Boss Drop"));
		assertTrue(text.contains("▸ Location"));
		assertTrue(text.contains("• Wellspring of Eternity (585, 35, -4974)"));

		AtomicBoolean titleIsAquaBold = new AtomicBoolean();
		AtomicBoolean coordsAreGreen = new AtomicBoolean();
		AtomicBoolean statsHoverHasCard = new AtomicBoolean();
		AtomicBoolean touchEffIsGreen = new AtomicBoolean();
		AtomicBoolean durationIsRed = new AtomicBoolean();
		rendered.visit((style, content) -> {
			Integer rgb = style.getColor() != null ? style.getColor().getValue() : null;
			if (content.contains("Honoured Commander's Medal") && style.isBold() && Integer.valueOf(ChatFormatting.AQUA.getColor()).equals(rgb)) {
				titleIsAquaBold.set(true);
			}
			if ("(585, 35, -4974)".equals(content) && Integer.valueOf(ChatFormatting.GREEN.getColor()).equals(rgb)) {
				coordsAreGreen.set(true);
			}
			if ("+50%".equals(content) && Integer.valueOf(ChatFormatting.GREEN.getColor()).equals(rgb)) {
				touchEffIsGreen.set(true);
			}
			if ("-315s".equals(content) && Integer.valueOf(ChatFormatting.RED.getColor()).equals(rgb)) {
				durationIsRed.set(true);
			}
			if ("[Stats]".equals(content) && style.getHoverEvent() instanceof net.minecraft.network.chat.HoverEvent.ShowText show) {
				String cardText = show.value().getString();
				if (cardText.contains("[★★★]") && cardText.contains("-170 Durability or -315s Duration")) {
					statsHoverHasCard.set(true);
				}
			}
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		assertTrue(titleIsAquaBold.get());
		assertTrue(coordsAreGreen.get());
		assertTrue(touchEffIsGreen.get());
		assertTrue(durationIsRed.get());
		assertTrue(statsHoverHasCard.get());
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
