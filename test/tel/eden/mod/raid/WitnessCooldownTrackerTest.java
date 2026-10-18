package tel.eden.mod.raid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WitnessCooldownTrackerTest {

	private final AtomicLong currentTime = new AtomicLong(100_000L);

	@BeforeEach
	void setUp() {
		currentTime.set(100_000L);
		WitnessCooldownTracker.setTimeSupplier(currentTime::get);
		WitnessCooldownTracker.reset();
	}

	@AfterEach
	void tearDown() {
		WitnessCooldownTracker.reset();
		WitnessCooldownTracker.setTimeSupplier(null);
	}

	@Test
	void testMessageMatching() {
		assertTrue(WitnessCooldownTracker.isMatchingMessage("[+2 Light Crystal]"));
		assertTrue(WitnessCooldownTracker.isMatchingMessage("prefix [+2 Light Crystal] suffix"));
		assertTrue(WitnessCooldownTracker.isMatchingMessage("[+2  Light   Crystal]"));
		assertTrue(WitnessCooldownTracker.isMatchingMessage("[+2 light crystal]"));

		assertFalse(WitnessCooldownTracker.isMatchingMessage("[+1 Light Crystal]"));
		assertFalse(WitnessCooldownTracker.isMatchingMessage("[+2 Dark Crystal]"));
		assertFalse(WitnessCooldownTracker.isMatchingMessage("Light Crystal"));
		assertFalse(WitnessCooldownTracker.isMatchingMessage(""));
		assertFalse(WitnessCooldownTracker.isMatchingMessage(null));
	}

	@Test
	void testComponentParsingWithFormattingCodes() {
		assertEquals(0L, WitnessCooldownTracker.getCooldownExpiresAtMs());

		WitnessCooldownTracker.onMessage(Component.literal("§a[+2 Light Crystal]§r"));
		assertEquals(105_000L, WitnessCooldownTracker.getCooldownExpiresAtMs());
		assertEquals(100_000L, WitnessCooldownTracker.getLastTriggerMs());
	}

	@Test
	void testCountdownTimingAndFormatting() {
		WitnessCooldownTracker.onCrystalAcquired();
		long now = currentTime.get();

		assertEquals(5.0, WitnessCooldownTracker.getRemainingSeconds(now), 0.001);
		assertEquals("Witness: 5.0s", WitnessCooldownTracker.getStatusText(now));

		currentTime.addAndGet(800L); // 4.2s remaining
		now = currentTime.get();
		assertEquals(4.2, WitnessCooldownTracker.getRemainingSeconds(now), 0.001);
		assertEquals("Witness: 4.2s", WitnessCooldownTracker.getStatusText(now));

		currentTime.addAndGet(3700L); // 0.5s remaining
		now = currentTime.get();
		assertEquals(0.5, WitnessCooldownTracker.getRemainingSeconds(now), 0.001);
		assertEquals("Witness: 0.5s", WitnessCooldownTracker.getStatusText(now));

		currentTime.addAndGet(500L); // exactly expired
		now = currentTime.get();
		assertEquals(0.0, WitnessCooldownTracker.getRemainingSeconds(now), 0.001);
		assertEquals("Witness: READY", WitnessCooldownTracker.getStatusText(now));

		currentTime.addAndGet(1000L); // past expiry
		now = currentTime.get();
		assertEquals(0.0, WitnessCooldownTracker.getRemainingSeconds(now), 0.001);
		assertEquals("Witness: READY", WitnessCooldownTracker.getStatusText(now));
	}

	@Test
	void testColorInterpolation() {
		WitnessCooldownTracker.onCrystalAcquired();
		long start = currentTime.get();

		// At start (5.0s remaining -> progress 0.0 -> Red: RGB(255, 85, 85))
		int redColor = WitnessCooldownTracker.getStatusColor(start);
		assertEquals(0xFFFF5555, redColor);

		// At mid (2.5s remaining -> progress 0.5 -> Orange: RGB(255, 170, 85))
		int midColor = WitnessCooldownTracker.getStatusColor(start + 2500L);
		assertEquals(0xFFFFAA55, midColor);

		// Near end (0.0s remaining but before expired -> progress 1.0 -> Yellow: RGB(255, 255, 85))
		int endYellow = WitnessCooldownTracker.getStatusColor(start + 4999L);
		// progress ~ 1.0 -> green = 85 + 169 = 254
		int greenChannel = (endYellow >> 8) & 0xFF;
		assertTrue(greenChannel >= 250, "Green channel near end should be near 255 (yellow)");

		// Expired -> Green: RGB(85, 255, 85)
		int readyColor = WitnessCooldownTracker.getStatusColor(start + 5000L);
		assertEquals(0xFF55FF55, readyColor);
	}

	@Test
	void testDebounceSuppressesRapidDuplicates() {
		WitnessCooldownTracker.onCrystalAcquired();
		assertEquals(105_000L, WitnessCooldownTracker.getCooldownExpiresAtMs());

		// Duplicate packet arrives 150ms later (within 1000ms debounce window)
		currentTime.addAndGet(150L);
		WitnessCooldownTracker.onMessage(Component.literal("[+2 Light Crystal]"));
		assertEquals(105_000L, WitnessCooldownTracker.getCooldownExpiresAtMs(), "Expiration timestamp must not reset on duplicate packet");

		// After debounce window (1500ms total), new drop triggers freshly
		currentTime.addAndGet(1350L);
		WitnessCooldownTracker.onMessage(Component.literal("[+2 Light Crystal]"));
		assertEquals(currentTime.get() + 5000L, WitnessCooldownTracker.getCooldownExpiresAtMs(), "Fresh trigger allowed after debounce window");
	}

	@Test
	void testRemoteWitnessKill() {
		long packetTimestamp = currentTime.get() - 1000L; // 1.0s ago on remote sender
		WitnessCooldownTracker.onRemoteWitnessKill("Ally", packetTimestamp);

		// Elapsed is 1000ms -> remaining is 4000ms
		assertEquals(currentTime.get() + 4000L, WitnessCooldownTracker.getCooldownExpiresAtMs());
		assertEquals(4.0, WitnessCooldownTracker.getRemainingSeconds(currentTime.get()), 0.001);
		assertEquals("Witness: 4.0s", WitnessCooldownTracker.getStatusText(currentTime.get()));
	}

	@Test
	void testRemoteWitnessKillExpiredIgnored() {
		long packetTimestamp = currentTime.get() - 6000L; // 6.0s ago (stale)
		WitnessCooldownTracker.onRemoteWitnessKill("Ally", packetTimestamp);

		assertEquals(0L, WitnessCooldownTracker.getCooldownExpiresAtMs(), "Stale remote kill should not activate cooldown");
	}

	@Test
	void testReset() {
		WitnessCooldownTracker.onCrystalAcquired();
		assertTrue(WitnessCooldownTracker.getCooldownExpiresAtMs() > 0L);

		WitnessCooldownTracker.reset();
		assertEquals(0L, WitnessCooldownTracker.getCooldownExpiresAtMs());
		assertEquals(0L, WitnessCooldownTracker.getLastTriggerMs());
	}
}
