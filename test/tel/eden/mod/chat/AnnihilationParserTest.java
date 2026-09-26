package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class AnnihilationParserTest {
	@Test
	void parsesAnHourOnlyCountdown() {
		var seconds = AnnihilationParser.parse(Component.literal("Hateful echoes erupt from the Portal. Wynn faces Annihilation. Prepare to defend the province at the Corruption Portal in 1h!"));

		assertTrue(seconds.isPresent());
		assertEquals(3600, seconds.getAsInt());
	}

	@Test
	void parsesAMinuteOnlyCountdown() {
		var seconds = AnnihilationParser.parse(Component.literal("Wynn faces Annihilation. Prepare to defend the province in 30m!"));

		assertEquals(30 * 60, seconds.getAsInt());
	}

	@Test
	void parsesCombinedHoursAndMinutes() {
		var seconds = AnnihilationParser.parse(Component.literal("Wynn faces Annihilation. Prepare to defend the province in 1h 30m!"));

		assertEquals(3600 + 30 * 60, seconds.getAsInt());
	}

	@Test
	void rejectsAMessageWithNoParsableCountdown() {
		assertTrue(AnnihilationParser.parse(Component.literal("Wynn faces Annihilation. Prepare to defend the province soon!")).isEmpty());
	}

	@Test
	void rejectsUnrelatedMessagesAndNull() {
		assertFalse(AnnihilationParser.isCandidate(Component.literal("Just a regular message")));
		assertFalse(AnnihilationParser.isCandidate(null));
		assertTrue(AnnihilationParser.parse(Component.literal("Just a regular message")).isEmpty());
		assertTrue(AnnihilationParser.parse(null).isEmpty());
	}
}
