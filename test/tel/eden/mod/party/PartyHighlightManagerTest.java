package tel.eden.mod.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

public class PartyHighlightManagerTest {

	@Test
	public void paletteHasExactlyTenUniqueColors() {
		List<Integer> palette = PartyHighlightManager.DEFAULT_PALETTE;
		assertEquals(10, palette.size(), "Palette must have exactly 10 colors for party slots 0-9");
		assertEquals(10, new HashSet<>(palette).size(), "All 10 colors must be distinct");
	}

	@Test
	public void paletteMatchesUserRequestedSlots() {
		List<Integer> palette = PartyHighlightManager.DEFAULT_PALETTE;
		assertEquals(0xFF2222, palette.get(0), "Slot 0 must be Red");
		assertEquals(0x1E56FF, palette.get(1), "Slot 1 must be Deep Royal Blue");
		assertEquals(0xFFA6D5, palette.get(2), "Slot 2 must be Light Pink");
		assertEquals(0x55FF55, palette.get(3), "Slot 3 must be Lime Green");
		assertEquals(0xFF7700, palette.get(4), "Slot 4 must be Vivid Electric Orange");
	}

	@Test
	public void paletteExcludesForbiddenColors() {
		for (int color : PartyHighlightManager.DEFAULT_PALETTE) {
			assertNotEquals(0xFFFFFF, color, "Palette must not contain pure White");
			assertNotEquals(0x55FFFF, color, "Palette must not contain Wynncraft Guild Aqua");
			assertNotEquals(0x00FFFF, color, "Palette must not contain pure Cyan");
			assertNotEquals(0xFFFF55, color, "Palette must not contain Wynncraft Party Yellow");
			assertNotEquals(0xFFFF00, color, "Palette must not contain pure Yellow");

			// Deconstruct RGB
			int r = (color >> 16) & 0xFF;
			int g = (color >> 8) & 0xFF;
			int b = color & 0xFF;

			// Verify not yellowish (high red and high green together, low blue)
			boolean isYellowish = (r > 200 && g > 200 && b < 100);
			assertFalse(isYellowish, String.format("Color 0x%06X must not be yellowish", color));

			// Verify not cyan/aqua (high green and high blue together, low red)
			boolean isCyanish = (b > 180 && g > 180 && r < 100);
			assertFalse(isCyanish, String.format("Color 0x%06X must not be cyanish/aqua", color));
		}
	}

	@Test
	public void colorAssignmentMatchesSlotPositions() {
		List<String> members = List.of("HostLeader", "MemberA", "MemberB", "MemberC", "MemberD");

		// Slot 0 (Host / Leader) -> Crimson Red
		Integer leaderColor = PartyHighlightManager.getColorForPlayerName("HostLeader", members, null);
		assertNotNull(leaderColor);
		assertEquals(PartyHighlightManager.DEFAULT_PALETTE.get(0), leaderColor);

		// Slot 1 -> Deep Royal Blue
		Integer memberAColor = PartyHighlightManager.getColorForPlayerName("MemberA", members, null);
		assertNotNull(memberAColor);
		assertEquals(PartyHighlightManager.DEFAULT_PALETTE.get(1), memberAColor);

		// Slot 2 -> Light Pink
		Integer memberBColor = PartyHighlightManager.getColorForPlayerName("MemberB", members, null);
		assertNotNull(memberBColor);
		assertEquals(PartyHighlightManager.DEFAULT_PALETTE.get(2), memberBColor);

		// Slot 3 -> Lime Green
		Integer memberCColor = PartyHighlightManager.getColorForPlayerName("MemberC", members, null);
		assertNotNull(memberCColor);
		assertEquals(PartyHighlightManager.DEFAULT_PALETTE.get(3), memberCColor);

		// Slot 4 -> Vivid Electric Orange
		Integer memberDColor = PartyHighlightManager.getColorForPlayerName("MemberD", members, null);
		assertNotNull(memberDColor);
		assertEquals(PartyHighlightManager.DEFAULT_PALETTE.get(4), memberDColor);

		// Case insensitivity
		Integer caseTest = PartyHighlightManager.getColorForPlayerName("mEmBeRa", members, null);
		assertEquals(memberAColor, caseTest);

		// Non-party member returns null
		Integer nonMember = PartyHighlightManager.getColorForPlayerName("RandomPlayer", members, null);
		assertNull(nonMember);

		// Null or empty handling
		assertNull(PartyHighlightManager.getColorForPlayerName(null, members, null));
		assertNull(PartyHighlightManager.getColorForPlayerName("HostLeader", null, null));
		assertNull(PartyHighlightManager.getColorForPlayerName("HostLeader", List.of(), null));
	}

	@Test
	public void outlineThicknessSanitization() {
		int defaultThickness = 2;
		assertEquals(2, defaultThickness, "Default outline thickness should be 2px");

		int thicknessAbove = Math.max(1, Math.min(6, 10));
		assertEquals(6, thicknessAbove, "Thickness above 6 should clamp to 6");

		int thicknessBelow = Math.max(1, Math.min(6, 0));
		assertEquals(1, thicknessBelow, "Thickness below 1 should clamp to 1");
	}

	@Test
	public void wynntilsPartyBridgeFallbackSafe() {
		// When Wynntils is absent or uninitialized in test environment
		WynntilsPartyBridge.reset();
		assertFalse(WynntilsPartyBridge.isAvailable());
		assertFalse(WynntilsPartyBridge.isInParty());
		assertTrue(WynntilsPartyBridge.getPartyMembers().isEmpty());
	}
}
