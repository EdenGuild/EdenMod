package tel.eden.mod.raid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tel.eden.mod.net.BridgeWebSocketClient;
import tel.eden.mod.util.Wynncraft;

class PartyFinderGambitScraperTest {
	@BeforeAll
	static void setupMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static ItemStack gambitItem(String name, String... loreLines) {
		ItemStack stack = new ItemStack(Items.PAPER);
		if (name != null) {
			stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		}
		if (loreLines != null && loreLines.length > 0) {
			List<Component> lore = new ArrayList<>();
			for (String line : loreLines) {
				lore.add(Component.literal(line));
			}
			stack.set(DataComponents.LORE, new ItemLore(lore));
		}
		return stack;
	}

	private static AbstractContainerMenu menuOf(int totalSlots, ItemStack... items) {
		AbstractContainerMenu menu = new AbstractContainerMenu(null, 1) {
			@Override
			public ItemStack quickMoveStack(Player player, int index) {
				return ItemStack.EMPTY;
			}

			@Override
			public boolean stillValid(Player player) {
				return true;
			}
		};
		SimpleContainer container = new SimpleContainer(totalSlots);
		for (int i = 0; i < totalSlots; i++) {
			ItemStack stack = i < items.length && items[i] != null ? items[i] : ItemStack.EMPTY;
			container.setItem(i, stack);
			Slot slot = new Slot(container, i, 0, 0);
			slot.index = i;
			menu.slots.add(slot);
		}
		return menu;
	}

	@Test
	void parsesFourPartyFinderGambitsSortedAndCleaned() {
		ItemStack[] slots = new ItemStack[90];
		slots[10] = gambitItem("§eSadist's Gambit", "§8Refreshes in 18 hours", "§7Take 25% more", "§7damage from mobs", "§bRewards for Enabling", "§eClick to Enable");
		slots[11] = gambitItem("§eAnemic's Gambit", "§8Refreshes in 18 hours", "§7Cannot heal more than 10% max health", "§bRewards for enabling");
		slots[12] = gambitItem("§eFarsighted's Gambit", "§8Refreshes in 18 hours", "§7Deal 90% less damage up close", "§bRewards for Enabling");
		slots[13] = gambitItem("§eGlutton's Gambit", "§8Refreshes in 18 hours", "§7Double consumable cooldown", "§bRewards for Enabling");

		List<PartyFinderGambitScraper.GambitEntry> parsed = PartyFinderGambitScraper.parseGambits(menuOf(90, slots));
		assertNotNull(parsed);
		assertEquals(4, parsed.size());
		assertEquals("Anemic's Gambit", parsed.get(0).name());
		assertEquals("Cannot heal more than 10% max health", parsed.get(0).description());
		assertEquals("Sadist's Gambit", parsed.get(3).name());
		assertEquals("Take 25% more damage from mobs", parsed.get(3).description());
	}

	@Test
	void rejectsPartialMenuOrPlayerInventoryGambitItems() {
		ItemStack[] slots = new ItemStack[90];
		slots[10] = gambitItem("Anemic's Gambit", "Refreshes in 18 hours", "desc 1", "Rewards for Enabling");
		slots[11] = gambitItem("Sadist's Gambit", "Refreshes in 18 hours", "desc 2", "Rewards for Enabling");
		slots[12] = gambitItem("Glutton's Gambit", "Refreshes in 18 hours", "desc 3", "Rewards for Enabling");
		// Slot 60 falls inside the trailing 36 player inventory slots and must be ignored.
		slots[60] = gambitItem("Farsighted's Gambit", "Refreshes in 18 hours", "desc 4", "Rewards for Enabling");

		assertNull(PartyFinderGambitScraper.parseGambits(menuOf(90, slots)));
	}

	@Test
	void rejectsGambitMissingDescriptionSection() {
		ItemStack[] slots = new ItemStack[90];
		slots[10] = gambitItem("Anemic's Gambit", "Refreshes in 18 hours", "desc 1", "Rewards for Enabling");
		slots[11] = gambitItem("Sadist's Gambit", "Refreshes in 18 hours", "desc 2", "Rewards for Enabling");
		slots[12] = gambitItem("Glutton's Gambit", "Refreshes in 18 hours", "desc 3", "Rewards for Enabling");
		slots[13] = gambitItem("Farsighted's Gambit", "Rewards for Enabling");

		assertNull(PartyFinderGambitScraper.parseGambits(menuOf(90, slots)));
	}

	@Test
	void buildsExpectedGambitReportJsonPayload() {
		List<PartyFinderGambitScraper.GambitEntry> entries = List.of(new PartyFinderGambitScraper.GambitEntry("Anemic's Gambit", "desc 1"), new PartyFinderGambitScraper.GambitEntry("Farsighted's Gambit", "desc 2"), new PartyFinderGambitScraper.GambitEntry("Glutton's Gambit", "desc 3"), new PartyFinderGambitScraper.GambitEntry("Sadist's Gambit", "desc 4"));
		JsonObject payload = BridgeWebSocketClient.gambitReportPayload(entries);
		assertEquals("gambitReport", payload.get("type").getAsString());
		JsonArray gambits = payload.getAsJsonArray("gambits");
		assertEquals(4, gambits.size());
		assertEquals("Anemic's Gambit", gambits.get(0).getAsJsonObject().get("name").getAsString());
		assertEquals("desc 1", gambits.get(0).getAsJsonObject().get("description").getAsString());
	}

	@Test
	void detectsBetaServersSafely() {
		assertTrue(Wynncraft.isBeta("beta.wynncraft.com"));
		assertTrue(Wynncraft.isBeta("BETA.WYNNCRAFT.NET:25565"));
		assertFalse(Wynncraft.isBeta("play.wynncraft.com"));
		assertFalse(Wynncraft.isBeta(null));
	}
}
