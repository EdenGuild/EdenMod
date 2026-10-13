package tel.eden.mod.raid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.inventory.ClickType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tel.eden.mod.config.BridgeConfig;

class RaidGambitSafetyTest {
	private BridgeConfig config;

	@BeforeEach
	void setUp() {
		config = new BridgeConfig();
		config.raidGambitSafety = true;
		config.raidGambitMinimum = 1;
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
		// Chest slots 33, 34, and 35 are the ready buttons
		for (int slot = RaidGambitSafety.READY_SLOT_MIN; slot <= RaidGambitSafety.READY_SLOT_MAX; slot++) {
			boolean blocked = RaidGambitSafety.evaluateSafetyBlock(slot, true, false, 0, ClickType.PICKUP, false, config);
			assertTrue(blocked, "Normal click on slot " + slot + " should be blocked when 0 gambits enabled and min is 1");
		}
	}

	@Test
	void allowsShiftClickOnReadySlotsWhenBelowMinimum() {
		for (int slot = RaidGambitSafety.READY_SLOT_MIN; slot <= RaidGambitSafety.READY_SLOT_MAX; slot++) {
			// Quick move (shift click)
			boolean quickMoveBlocked = RaidGambitSafety.evaluateSafetyBlock(slot, true, false, 0, ClickType.QUICK_MOVE, false, config);
			assertFalse(quickMoveBlocked, "Shift click (QUICK_MOVE) on slot " + slot + " should pass through");

			// Physical shift key down
			boolean shiftKeyDownBlocked = RaidGambitSafety.evaluateSafetyBlock(slot, true, false, 0, ClickType.PICKUP, true, config);
			assertFalse(shiftKeyDownBlocked, "Click with shift held on slot " + slot + " should pass through");
		}
	}

	@Test
	void allowsNormalClickWhenGambitThresholdMet() {
		// 1 enabled, min is 1 -> allowed on slots 33, 34, 35
		for (int slot = RaidGambitSafety.READY_SLOT_MIN; slot <= RaidGambitSafety.READY_SLOT_MAX; slot++) {
			boolean blocked = RaidGambitSafety.evaluateSafetyBlock(slot, true, false, 1, ClickType.PICKUP, false, config);
			assertFalse(blocked, "Normal click should be allowed when threshold met on slot " + slot);
		}

		// 3 enabled, min is 2 -> allowed
		config.raidGambitMinimum = 2;
		boolean blockedAbove = RaidGambitSafety.evaluateSafetyBlock(33, true, false, 3, ClickType.PICKUP, false, config);
		assertFalse(blockedAbove, "Normal click should be allowed when exceeding threshold");
	}

	@Test
	void blocksWhenMinimumIsHigherThanEnabledCount() {
		config.raidGambitMinimum = 2;
		// 1 enabled, min is 2 -> blocked
		boolean blocked = RaidGambitSafety.evaluateSafetyBlock(34, true, false, 1, ClickType.PICKUP, false, config);
		assertTrue(blocked, "Normal click should be blocked when 1 enabled and min is 2");

		// Shift click -> allowed
		boolean shiftAllowed = RaidGambitSafety.evaluateSafetyBlock(34, true, false, 1, ClickType.QUICK_MOVE, false, config);
		assertFalse(shiftAllowed, "Shift click should be allowed even if below min=2");
	}

	@Test
	void neverBlocksUnreadying() {
		// When player is already readied, clicking to unready must always be allowed
		for (int slot = RaidGambitSafety.READY_SLOT_MIN; slot <= RaidGambitSafety.READY_SLOT_MAX; slot++) {
			boolean blocked = RaidGambitSafety.evaluateSafetyBlock(slot, true, true, 0, ClickType.PICKUP, false, config);
			assertFalse(blocked, "Clicking to unready on slot " + slot + " must never be blocked");
		}
	}

	@Test
	void neverBlocksIndicatorOrOtherSlots() {
		// Slot 27 is an indicator for other players, not the ready click button
		boolean slot27Blocked = RaidGambitSafety.evaluateSafetyBlock(RaidGambitSafety.STATUS_INDICATOR_SLOT, true, false, 0, ClickType.PICKUP, false, config);
		assertFalse(slot27Blocked, "Slot 27 is an indicator and must not be intercepted");

		// Clicking gambit slot 1 or 3 should never be blocked
		boolean slot1Blocked = RaidGambitSafety.evaluateSafetyBlock(1, true, false, 0, ClickType.PICKUP, false, config);
		assertFalse(slot1Blocked, "Clicking a gambit slot must not be blocked");
	}

	@Test
	void neverBlocksInUnrelatedMenus() {
		boolean blocked = RaidGambitSafety.evaluateSafetyBlock(33, false, false, 0, ClickType.PICKUP, false, config);
		assertFalse(blocked, "Must not block outside raid menus");
	}

	@Test
	void disabledWhenMasterToggleOffOrMinimumZero() {
		// Master toggle off
		config.raidGambitSafety = false;
		boolean blockedToggleOff = RaidGambitSafety.evaluateSafetyBlock(33, true, false, 0, ClickType.PICKUP, false, config);
		assertFalse(blockedToggleOff, "Must not block when master toggle is off");

		// Minimum 0
		config.raidGambitSafety = true;
		config.raidGambitMinimum = 0;
		boolean blockedMinZero = RaidGambitSafety.evaluateSafetyBlock(33, true, false, 0, ClickType.PICKUP, false, config);
		assertFalse(blockedMinZero, "Must not block when minimum is 0");
	}

	@Test
	void generatesAccurateWarningMessagesWithSingularAndPlural() {
		// 1 gambit (singular)
		assertEquals("You have less than 1 gambit selected, Shift-Click to ready up anyways.", RaidGambitSafety.getWarningMessage(1));
		assertEquals("§cYou have less than 1 gambit selected, Shift-Click to ready up anyways.", RaidGambitSafety.getTooltipWarning(1).getString());

		// 2 gambits (plural)
		assertEquals("You have less than 2 gambits selected, Shift-Click to ready up anyways.", RaidGambitSafety.getWarningMessage(2));
		assertEquals("§cYou have less than 2 gambits selected, Shift-Click to ready up anyways.", RaidGambitSafety.getTooltipWarning(2).getString());

		// 3 gambits (plural)
		assertEquals("You have less than 3 gambits selected, Shift-Click to ready up anyways.", RaidGambitSafety.getWarningMessage(3));
	}
}
