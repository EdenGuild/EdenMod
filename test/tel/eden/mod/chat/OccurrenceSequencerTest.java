package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OccurrenceSequencerTest {
	@Test
	void rewardModeCountsRapidIdenticalTickerPacketsSeparately() {
		OccurrenceSequencer sequencer = new OccurrenceSequencer(60_000L, false);

		assertEquals(1, sequencer.next("giver|1024 Emeralds|receiver"));
		assertEquals(2, sequencer.next("giver|1024 Emeralds|receiver"));
	}

	@Test
	void defaultModeStillCoalescesRapidBankReemits() {
		OccurrenceSequencer sequencer = new OccurrenceSequencer(10_000L);

		assertEquals(1, sequencer.next("deposit|player|item"));
		assertEquals(1, sequencer.next("deposit|player|item"));
	}
}
