package tel.eden.mod.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GuildLogEntryParserTest {
	@Test
	void readsTimestampAndMultilineLogText() {
		var entry = GuildLogEntryParser.parseLines("§32 hours ago (09/22/26 09:30 AM EDT)", List.of("", "FadeDave rewarded 1024 Emeralds to Asthae", "from Guild Rewards", "")).orElseThrow();

		assertEquals("2 hours ago (09/22/26 09:30 AM EDT)", entry.occurredAt());
		assertEquals("FadeDave rewarded 1024 Emeralds to Asthae\nfrom Guild Rewards", entry.text());
	}

	@Test
	void ignoresSpacerAndIncompleteRows() {
		assertTrue(GuildLogEntryParser.parseLines("2 minutes ago", List.of()).isEmpty());
		assertTrue(GuildLogEntryParser.parseLines("not a timestamp", List.of("", "line", "")).isEmpty());
		assertTrue(GuildLogEntryParser.parseLines("1 minute ago (09/22/26 09:30 AM EDT)", List.of("", "")).isEmpty());
	}

	@Test
	void identifiesTheColoredLoadingPlaceholder() {
		assertTrue(GuildLogSync.isLoadingPlaceholder(new GuildLogEntry("§7Please wait while all logged", "§7messages are prepared")));
	}
}
