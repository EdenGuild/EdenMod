package tel.eden.mod.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import tel.eden.mod.net.LongEventState;

class LongEventScreenTest {
	private static final ToIntFunction<String> SIX_PX_CHAR_WIDTH = s -> s.length() * 6;

	@Test
	void splitCardTitleNeverChopsWordsMidWord() {
		// Fits within 58 px -> stays on 1 line.
		assertEquals(List.of("Mob Hunt"), LongEventScreen.splitCardTitle("Mob Hunt", 58, SIX_PX_CHAR_WIDTH));

		// Single long word wider than 58 px -> stays on 1 line so drawFittedCenteredString scales smoothly.
		assertEquals(List.of("Catplushie"), LongEventScreen.splitCardTitle("Catplushie", 58, SIX_PX_CHAR_WIDTH));

		// Multi-word with first word > 58 px -> splits on word boundary, never inserts spaces inside first word.
		assertEquals(List.of("Adventurer", "Hunt"), LongEventScreen.splitCardTitle("Adventurer Hunt", 58, SIX_PX_CHAR_WIDTH));

		// Multi-word balanced split.
		assertEquals(List.of("Find The", "Secret Key"), LongEventScreen.splitCardTitle("Find   The  Secret Key", 58, SIX_PX_CHAR_WIDTH));
	}

	@Test
	void specialSlotImagesRespectHasCluesAndItemUnlockTimestamp() {
		long now = 10_000L;
		List<LongEventState.ScavengerItem> items = List.of(new LongEventState.ScavengerItem("Unlocked", "easy", true, 5_000L, "", "https://example.com/unlocked.png"), new LongEventState.ScavengerItem("Future", "hard", true, 20_000L, "", "https://example.com/future.png"), new LongEventState.ScavengerItem("Locked", "medium", false, 0L, "", "https://example.com/locked.png"));
		List<LongEventState.ClueEntry> clues = List.of(new LongEventState.ClueEntry("Clue", "https://example.com/clue.png"));

		LongEventState.SpecialSlot withClues = new LongEventState.SpecialSlot(1, "scavenger_hunt", "Scavenger Hunt", true, false, 0, 0L, 0, 0, 0L, 10, "", "https://example.com/main.png", items, clues, 0, 0, true, 0L, 0L, false, false, "", true);
		assertEquals(List.of("https://example.com/main.png", "https://example.com/clue.png", "https://example.com/unlocked.png"), LongEventScreen.getSpecialSlotImages(withClues, now));

		LongEventState.SpecialSlot withoutClues = new LongEventState.SpecialSlot(2, "riddle", "Riddle", true, false, 0, 0L, 0, 0, 0L, 10, "", "https://example.com/main.png", items, clues, 0, 0, true, 0L, 0L, false, false, "", false);
		assertEquals(List.of("https://example.com/main.png", "https://example.com/unlocked.png"), LongEventScreen.getSpecialSlotImages(withoutClues, now));
	}

	@Test
	void visibleWrappedLineCountFitsShrunkPanelsWithUnscaledPitch() {
		// Scavenger row at 1366x768 GUI scale 2 (panelScale 0.766) and minimum snap scale (0.75): 3 lines at 10px pitch fit inside the row.
		for (int[] virt : new int[][]{{683, 384}, {680, 376}}) {
			EdenPanelLayout layout = EdenPanelLayout.centered(virt[0], virt[1], 680, 496);
			int rowH = layout.h(64);
			int noteAvail = rowH - layout.h(21) - layout.h(2);
			int lines = LongEventScreen.visibleWrappedLineCount(noteAvail, 10, 3);
			assertEquals(3, lines);
			assertTrue(layout.h(21) + (lines - 1) * 10 + 9 <= rowH);
		}
	}
}
