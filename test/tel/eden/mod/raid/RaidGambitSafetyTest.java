package tel.eden.mod.raid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tel.eden.mod.config.BridgeConfig;

class RaidGambitSafetyTest {
	private BridgeConfig config;

	@BeforeAll
	static void setupMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@BeforeEach
	void setUp() {
		config = new BridgeConfig();
		config.raidGambitSafety = true;
		config.raidGambitMinimum = 1;
		RaidGambitSafety.clearPopupWarning();
	}

	private static ItemStack createItem(String name, String... loreLines) {
		ItemStack stack = new ItemStack(Items.SNOWBALL);
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

	private static AbstractContainerMenu createMenu(ItemStack... items) {
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
		SimpleContainer container = new SimpleContainer(Math.max(RaidGambitSafety.TOTAL_SLOTS, items.length));
		for (int i = 0; i < Math.max(RaidGambitSafety.TOTAL_SLOTS, items.length); i++) {
			ItemStack stack = i < items.length && items[i] != null ? items[i] : ItemStack.EMPTY;
			container.setItem(i, stack);
			menu.slots.add(new Slot(container, i, 0, 0));
		}
		return menu;
	}

	@Test
	void detectsGambitActiveStateFromLore() {
		assertTrue(RaidGambitSafety.isGambitEnabledLore(List.of("§8Refreshes in 19 hours", " ", "§eClick to Disable")));
		assertTrue(RaidGambitSafety.isGambitEnabledLore(List.of("Click to Disable")));
		assertFalse(RaidGambitSafety.isGambitEnabledLore(List.of("§8Refreshes in 19 hours", " ", "§aClick to Enable")));
		assertFalse(RaidGambitSafety.isGambitEnabledLore(List.of("§cYou may only enable 3 gambits")));
		assertFalse(RaidGambitSafety.isGambitEnabledLore(List.of()));
		assertFalse(RaidGambitSafety.isGambitEnabledLore(null));
	}

	@Test
	void recognizesGambitLore() {
		assertTrue(RaidGambitSafety.isGambitLore(List.of("§bRewards for Enabling", "§eClick to Disable")));
		assertTrue(RaidGambitSafety.isGambitLore(List.of("§aClick to Enable")));
		assertTrue(RaidGambitSafety.isGambitLore(List.of("§cYou may only enable 3 gambits")));
		assertFalse(RaidGambitSafety.isGambitLore(List.of("§7Damage: 100-200", "§8Common Dagger")));
	}

	@Test
	void blocksUnreadyClickOnReadySlotsWhenBelowMinimum() {
		for (int slot = RaidGambitSafety.READY_SLOT_MIN; slot <= RaidGambitSafety.READY_SLOT_MAX; slot++) {
			boolean blocked = RaidGambitSafety.evaluateSafetyBlock(slot, true, false, 0, ClickType.PICKUP, false, config);
			assertTrue(blocked, "Normal click on slot " + slot + " should be blocked when 0 gambits enabled and min is 1");
		}
	}

	@Test
	void allowsShiftClickOnReadySlotsWhenBelowMinimum() {
		for (int slot = RaidGambitSafety.READY_SLOT_MIN; slot <= RaidGambitSafety.READY_SLOT_MAX; slot++) {
			boolean quickMoveBlocked = RaidGambitSafety.evaluateSafetyBlock(slot, true, false, 0, ClickType.QUICK_MOVE, false, config);
			assertFalse(quickMoveBlocked, "Shift click (QUICK_MOVE) on slot " + slot + " should pass through");

			boolean shiftKeyDownBlocked = RaidGambitSafety.evaluateSafetyBlock(slot, true, false, 0, ClickType.PICKUP, true, config);
			assertFalse(shiftKeyDownBlocked, "Click with shift held on slot " + slot + " should pass through");
		}
	}

	@Test
	void allowsNormalClickWhenGambitThresholdMet() {
		for (int slot = RaidGambitSafety.READY_SLOT_MIN; slot <= RaidGambitSafety.READY_SLOT_MAX; slot++) {
			boolean blocked = RaidGambitSafety.evaluateSafetyBlock(slot, true, false, 1, ClickType.PICKUP, false, config);
			assertFalse(blocked, "Normal click should be allowed when threshold met on slot " + slot);
		}

		config.raidGambitMinimum = 2;
		boolean blockedAbove = RaidGambitSafety.evaluateSafetyBlock(33, true, false, 3, ClickType.PICKUP, false, config);
		assertFalse(blockedAbove, "Normal click should be allowed when exceeding threshold");
	}

	@Test
	void blocksWhenMinimumIsHigherThanEnabledCount() {
		config.raidGambitMinimum = 2;
		boolean blocked = RaidGambitSafety.evaluateSafetyBlock(34, true, false, 1, ClickType.PICKUP, false, config);
		assertTrue(blocked, "Normal click should be blocked when 1 enabled and min is 2");

		boolean shiftAllowed = RaidGambitSafety.evaluateSafetyBlock(34, true, false, 1, ClickType.QUICK_MOVE, false, config);
		assertFalse(shiftAllowed, "Shift click should be allowed even if below min=2");
	}

	@Test
	void neverBlocksUnreadying() {
		for (int slot = RaidGambitSafety.READY_SLOT_MIN; slot <= RaidGambitSafety.READY_SLOT_MAX; slot++) {
			boolean blocked = RaidGambitSafety.evaluateSafetyBlock(slot, true, true, 0, ClickType.PICKUP, false, config);
			assertFalse(blocked, "Clicking to unready on slot " + slot + " must never be blocked");
		}
	}

	@Test
	void neverBlocksIndicatorOrOtherSlots() {
		boolean slot27Blocked = RaidGambitSafety.evaluateSafetyBlock(RaidGambitSafety.STATUS_INDICATOR_SLOT, true, false, 0, ClickType.PICKUP, false, config);
		assertFalse(slot27Blocked, "Slot 27 is an indicator and must not be intercepted");

		boolean slot1Blocked = RaidGambitSafety.evaluateSafetyBlock(1, true, false, 0, ClickType.PICKUP, false, config);
		assertFalse(slot1Blocked, "Clicking a gambit slot must not be blocked");
	}

	@Test
	void liveReadyUpScreenCountsEnabledGambitsAndDoesNotBlock() {
		// Layout and lore from a live container dump: gambits at 1/3/5/7, status 27, ready 33-35.
		ItemStack[] items = new ItemStack[36];
		items[1] = createItem("Burdened Pacifist's Gambit", "§8Refreshes in 20 hours", "§bRewards for Enabling", "§eClick to Disable");
		items[3] = createItem("Anemic's Gambit", "§8Refreshes in 20 hours", "§8You may only enable 3 gambits");
		items[5] = createItem("Eroded Speedster's Gambit", "§8Refreshes in 20 hours", "§bRewards for Enabling", "§eClick to Disable");
		items[7] = createItem("Sadist's Gambit", "§8Refreshes in 20 hours", "§bRewards for Enabling", "§eClick to Disable");
		items[27] = createItem("Not Ready!");
		items[33] = createItem("Ready Up!");
		AbstractContainerMenu menu = createMenu(items);

		assertTrue(RaidGambitSafety.isRaidReadyMenu(menu));
		assertEquals(3, RaidGambitSafety.countEnabledGambits(menu));
		assertTrue(RaidGambitSafety.getActiveBlockReasons(menu, menu.getSlot(33), ClickType.PICKUP, config).isEmpty());
	}

	@Test
	void neverBlocksInUnrelatedMenus() {
		boolean blocked = RaidGambitSafety.evaluateSafetyBlock(33, false, false, 0, ClickType.PICKUP, false, config);
		assertFalse(blocked, "Must not block outside raid menus");
	}

	@Test
	void disabledWhenMasterToggleOffOrMinimumZero() {
		config.raidGambitSafety = false;
		boolean blockedToggleOff = RaidGambitSafety.evaluateSafetyBlock(33, true, false, 0, ClickType.PICKUP, false, config);
		assertFalse(blockedToggleOff, "Must not block when master toggle is off");

		config.raidGambitSafety = true;
		config.raidGambitMinimum = 0;
		boolean blockedMinZero = RaidGambitSafety.evaluateSafetyBlock(33, true, false, 0, ClickType.PICKUP, false, config);
		assertFalse(blockedMinZero, "Must not block when minimum is 0");
	}

	@Test
	void generatesAccurateWarningMessagesWithSingularAndPlural() {
		assertEquals("You have less than 1 gambit selected, Shift-Click to ready up anyways.", RaidGambitSafety.getWarningMessage(1));
		assertEquals("§cYou have less than 1 gambit selected, Shift-Click to ready up anyways.", RaidGambitSafety.getTooltipWarning(1).getString());

		assertEquals("You have less than 2 gambits selected, Shift-Click to ready up anyways.", RaidGambitSafety.getWarningMessage(2));
		assertEquals("§cYou have less than 2 gambits selected, Shift-Click to ready up anyways.", RaidGambitSafety.getTooltipWarning(2).getString());

		assertEquals("You have less than 3 gambits selected, Shift-Click to ready up anyways.", RaidGambitSafety.getWarningMessage(3));
	}

	@Test
	void guildRaidSafetyBlocksWhenNotGuildRaidWithoutShiftBypass() {
		config.requireGuildRaid = true;
		config.raidGambitSafety = false;

		boolean blocked = RaidGambitSafety.evaluateSafetyBlock(33, true, false, 0, false, ClickType.PICKUP, false, config);
		assertTrue(blocked, "Normal click must be blocked when requireGuildRaid is true and raid is not a guild raid");

		boolean quickMoveBlocked = RaidGambitSafety.evaluateSafetyBlock(33, true, false, 0, false, ClickType.QUICK_MOVE, false, config);
		assertTrue(quickMoveBlocked, "QUICK_MOVE must be blocked when requireGuildRaid is true and raid is not a guild raid");

		boolean shiftHeldBlocked = RaidGambitSafety.evaluateSafetyBlock(33, true, false, 0, false, ClickType.PICKUP, true, config);
		assertTrue(shiftHeldBlocked, "Click with shift held must be blocked when requireGuildRaid is true and raid is not a guild raid");

		boolean graidAllowed = RaidGambitSafety.evaluateSafetyBlock(33, true, false, 0, true, ClickType.PICKUP, false, config);
		assertFalse(graidAllowed, "Normal click must be allowed when raid is a guild raid");
	}

	@Test
	void guildRaidSafetyDisabledByDefault() {
		assertFalse(config.requireGuildRaid, "requireGuildRaid must be false by default");

		config.raidGambitSafety = false;
		boolean blocked = RaidGambitSafety.evaluateSafetyBlock(33, true, false, 0, false, ClickType.PICKUP, false, config);
		assertFalse(blocked, "Must not block when requireGuildRaid is disabled");
	}

	@Test
	void detectsAllGuildMembersOptedInFromLore() {
		List<String> allOptedIn = List.of("§7All members of this party", "§7✔ altaqska has opted in", "§7✔ agency agency has opted in", "§7✔ blob has opted in", "§8All players must opt in");
		assertTrue(RaidGambitSafety.isAllGuildMembersOptedIn(allOptedIn), "Must be true when all players opted in");

		List<String> hasOptedOut = List.of("§7All members of this party", "§7✔ altaqska has opted in", "§7✖ Liu Ruyan has opted out", "§7✔ agency agency has opted in", "§7✔ blob has opted in", "§8All players must opt in");
		assertFalse(RaidGambitSafety.isAllGuildMembersOptedIn(hasOptedOut), "Must be false when a player has ✖ / opted out");

		List<String> hasOptedOutUnicode = List.of("§7All members of this party", "§7\u2716 someone has opted out");
		assertFalse(RaidGambitSafety.isAllGuildMembersOptedIn(hasOptedOutUnicode), "Must be false when a player has unicode \u2716");

		assertFalse(RaidGambitSafety.isAllGuildMembersOptedIn(List.of()));
		assertFalse(RaidGambitSafety.isAllGuildMembersOptedIn(null));
	}

	@Test
	void generatesGuildRaidWarningMessage() {
		assertEquals("This raid is not a Guild Raid, disable Require guild raid to ready in settings to ready up anyways.", RaidGambitSafety.getGuildRaidWarningMessage());
		assertEquals("§cThis raid is not a Guild Raid, disable Require guild raid to ready in settings to ready up anyways.", RaidGambitSafety.getGuildRaidTooltipWarning().getString());
	}

	@Test
	void detectsGuildRaidItemCaseInsensitiveFromHoverNameAndLore() {
		ItemStack nameStack = createItem("§a[Guild raid available]");
		assertTrue(RaidGambitSafety.isGuildRaidItem(nameStack), "Should match formatted case-insensitive hover name");

		ItemStack exactName = createItem("Guild Raid Available");
		assertTrue(RaidGambitSafety.isGuildRaidItem(exactName), "Should match exact hover name");

		ItemStack upperName = createItem("GUILD RAID AVAILABLE");
		assertTrue(RaidGambitSafety.isGuildRaidItem(upperName), "Should match uppercase hover name");

		ItemStack loreStack = createItem("Snowball", "§7Difficulty Modifiers:", "§aGuild Raid Available", "§7✔ Player has opted in");
		assertTrue(RaidGambitSafety.isGuildRaidItem(loreStack), "Should match 'guild raid available' in lore lines");

		ItemStack upperLoreStack = createItem("Snowball", "§bGUILD RAID AVAILABLE");
		assertTrue(RaidGambitSafety.isGuildRaidItem(upperLoreStack), "Should match uppercase 'GUILD RAID AVAILABLE' in lore");

		ItemStack nonGraid = createItem("Anemic's Gambit", "§8Refreshes in 4 hours", "§aClick to Enable");
		assertFalse(RaidGambitSafety.isGuildRaidItem(nonGraid), "Gambit item must not be detected as guild raid item");

		assertFalse(RaidGambitSafety.isGuildRaidItem(ItemStack.EMPTY), "Empty item must not be guild raid item");
		assertFalse(RaidGambitSafety.isGuildRaidItem(null), "Null item must not be guild raid item");
	}

	@Test
	void isGuildRaidAvailableWithMenu() {
		ItemStack graidItem = createItem("Guild Raid Available", "§7✔ player1 has opted in", "§7✔ player2 has opted in");
		AbstractContainerMenu validMenu = createMenu(graidItem);
		assertTrue(RaidGambitSafety.isGuildRaidAvailable(validMenu), "Menu with graid item and all opted in must return true");

		ItemStack graidOptOut = createItem("Guild Raid Available", "§7✔ player1 has opted in", "§7✖ player2 has opted out");
		AbstractContainerMenu optOutMenu = createMenu(graidOptOut);
		assertFalse(RaidGambitSafety.isGuildRaidAvailable(optOutMenu), "Menu with opted out player must return false");

		AbstractContainerMenu emptyMenu = createMenu(createItem("Regular Item"));
		assertFalse(RaidGambitSafety.isGuildRaidAvailable(emptyMenu), "Menu without graid item must return false");

		assertFalse(RaidGambitSafety.isGuildRaidAvailable(null), "Null menu must return false");
	}

	@Test
	void evaluatesActiveBlockReasonsAndOrdering() {
		config.raidGambitSafety = true;
		config.raidGambitMinimum = 2;
		config.requireGuildRaid = true;

		// Normal click when both fail: gambit warning must be strictly above guild raid warning
		List<String> bothFail = RaidGambitSafety.getActiveBlockReasons(33, true, false, 0, false, ClickType.PICKUP, false, config);
		assertEquals(2, bothFail.size());
		assertEquals(RaidGambitSafety.getWarningMessage(2), bothFail.get(0), "Gambit error must be above guild raid error");
		assertEquals(RaidGambitSafety.getGuildRaidWarningMessage(), bothFail.get(1));

		// Shift click when both fail: gambit check is bypassed, but guild raid safety still blocks
		List<String> shiftBothFail = RaidGambitSafety.getActiveBlockReasons(33, true, false, 0, false, ClickType.QUICK_MOVE, false, config);
		assertEquals(1, shiftBothFail.size());
		assertEquals(RaidGambitSafety.getGuildRaidWarningMessage(), shiftBothFail.get(0), "Shift click must still report guild raid error");

		// Only gambit fails, normal click: returns gambit error
		List<String> gambitOnly = RaidGambitSafety.getActiveBlockReasons(33, true, false, 1, true, ClickType.PICKUP, false, config);
		assertEquals(List.of(RaidGambitSafety.getWarningMessage(2)), gambitOnly);

		// Only gambit fails, shift click: returns empty (bypassed, no popup)
		List<String> shiftGambitOnly = RaidGambitSafety.getActiveBlockReasons(33, true, false, 1, true, ClickType.QUICK_MOVE, false, config);
		assertTrue(shiftGambitOnly.isEmpty(), "Shift click must bypass when only gambit safety is failing");

		// Only guild raid fails, shift click: returns guild raid error (cannot bypass, popup triggers)
		List<String> shiftGraidOnly = RaidGambitSafety.getActiveBlockReasons(33, true, false, 2, false, ClickType.QUICK_MOVE, false, config);
		assertEquals(List.of(RaidGambitSafety.getGuildRaidWarningMessage()), shiftGraidOnly);

		// Everything satisfied: returns empty
		List<String> allGood = RaidGambitSafety.getActiveBlockReasons(33, true, false, 2, true, ClickType.PICKUP, false, config);
		assertTrue(allGood.isEmpty());
	}

	@Test
	void popupWarningLifecycle() {
		assertNull(RaidGambitSafety.getActivePopupMessages());

		List<String> messages = List.of("Warning 1", "Warning 2");
		RaidGambitSafety.showPopupWarning(messages);
		assertEquals(messages, RaidGambitSafety.getActivePopupMessages());

		RaidGambitSafety.clearPopupWarning();
		assertNull(RaidGambitSafety.getActivePopupMessages());
	}
}
