package tel.eden.mod.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Uses real {@link ItemStack}/{@link ItemLore} objects (registry-bootstrapped, no live
 * client needed — the same {@code SharedConstants.tryDetectVersion()} +
 * {@code Bootstrap.bootStrap()} pattern the Wynntils reference repo uses for its own
 * item/text tests) rather than pre-extracted strings, so this exercises the same
 * {@code DataComponents.LORE} extraction path the real menu read does.
 */
class GuildRewardStorageParserTest {
	@BeforeAll
	static void setup() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static List<ItemStack> menuWithStorageLore(String... loreLines) {
		List<ItemStack> items = new ArrayList<>();
		for (int i = 0; i < GuildRewardStorageParser.STORAGE_SLOT; i++) {
			items.add(ItemStack.EMPTY);
		}
		ItemStack storage = new ItemStack(Items.PAPER);
		List<Component> lore = new ArrayList<>();
		for (String line : loreLines) {
			lore.add(Component.literal(line));
		}
		storage.set(DataComponents.LORE, new ItemLore(lore));
		items.add(storage);
		return items;
	}

	@Test
	void parsesAspectsTomesAndEmeraldsFromTheStorageSlot() {
		var snapshot = GuildRewardStorageParser.parse(menuWithStorageLore("§7Aspects: §f12/54", "§7Guild Tomes: §f3/20", "§7Emeralds: §f102400/204800")).orElseThrow();

		assertEquals(12, snapshot.aspects());
		assertEquals(54, snapshot.aspectsMax());
		assertEquals(3, snapshot.tomes());
		assertEquals(20, snapshot.tomesMax());
		assertEquals(102400L, snapshot.emeralds());
		assertEquals(204800L, snapshot.emeraldsMax());
	}

	@Test
	void parsesCommaSeparatedThousandsOnEitherSideOfTheSlash() {
		// Emeralds in particular can run into six figures; other large guild figures
		// (e.g. Season Rating) are shown comma-separated, so this must be too.
		var snapshot = GuildRewardStorageParser.parse(menuWithStorageLore("§7Aspects: §f12/54", "§7Guild Tomes: §f3/20", "§7Emeralds: §f102,400/1,204,800")).orElseThrow();

		assertEquals(102400L, snapshot.emeralds());
		assertEquals(1204800L, snapshot.emeraldsMax());
	}

	@Test
	void isIndifferentToLoreLineOrder() {
		var snapshot = GuildRewardStorageParser.parse(menuWithStorageLore("§7Emeralds: §f0/204800", "§7Aspects: §f0/54", "§7Guild Tomes: §f0/20")).orElseThrow();

		assertEquals(0, snapshot.aspects());
		assertEquals(0, snapshot.tomes());
		assertEquals(0L, snapshot.emeralds());
	}

	@Test
	void ignoresUnrelatedLoreLines() {
		var snapshot = GuildRewardStorageParser.parse(menuWithStorageLore("§7Click to view rewards", "§7Aspects: §f5/54", "§7Guild Tomes: §f1/20", "§7Emeralds: §f4096/204800", "§7Last updated: 2 minutes ago")).orElseThrow();

		assertEquals(5, snapshot.aspects());
	}

	@Test
	void refusesWhenAFieldIsMissing() {
		assertTrue(GuildRewardStorageParser.parse(menuWithStorageLore("§7Aspects: §f5/54", "§7Guild Tomes: §f1/20")).isEmpty());
	}

	@Test
	void refusesAnItemWithNoLoreAtAll() {
		List<ItemStack> items = new ArrayList<>();
		for (int i = 0; i < GuildRewardStorageParser.STORAGE_SLOT; i++) {
			items.add(ItemStack.EMPTY);
		}
		items.add(new ItemStack(Items.PAPER));

		assertTrue(GuildRewardStorageParser.parse(items).isEmpty());
	}

	@Test
	void refusesAMenuThatHasNotLoadedTheStorageSlotYet() {
		assertTrue(GuildRewardStorageParser.parse(List.of(ItemStack.EMPTY)).isEmpty());
		assertTrue(GuildRewardStorageParser.parse(null).isEmpty());
	}
}
