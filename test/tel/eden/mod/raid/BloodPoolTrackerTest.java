package tel.eden.mod.raid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.raid.BloodPoolTracker.Row;

class BloodPoolTrackerTest {
	private static final String ME = "0000000000000000000000000000000a";
	private final BridgeConfig config = new BridgeConfig();

	@BeforeEach
	@AfterEach
	void clear() {
		BloodPoolTracker.reset();
		BloodPoolTracker.forgetBar();
	}

	@Test
	void readsOwnBarFromPacketsWithoutTheOverlay() {
		UUID id = UUID.randomUUID();
		BossEvent bar = new BossEvent(id, Component.literal("Blood Pool [82%]"), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS) {
		};
		bar.setProgress(82f / 150);
		BossEvent other = new BossEvent(UUID.randomUUID(), Component.literal("Tower - Ragni"), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS) {
		};
		BloodPoolTracker.onBossPacket(ClientboundBossEventPacket.createAddPacket(bar));
		BloodPoolTracker.onBossPacket(ClientboundBossEventPacket.createAddPacket(other));
		BloodPoolTracker.readOwnBar();
		assertEquals(new Row("Me", 82, 150), BloodPoolTracker.visibleRows("Me").get(0));

		bar.setName(Component.literal("Blood Pool [30%]"));
		bar.setProgress(30f / 150);
		BloodPoolTracker.onBossPacket(ClientboundBossEventPacket.createUpdateNamePacket(bar));
		BloodPoolTracker.onBossPacket(ClientboundBossEventPacket.createUpdateProgressPacket(bar));
		BloodPoolTracker.onBossPacket(ClientboundBossEventPacket.createRemovePacket(other.getId()));
		BloodPoolTracker.readOwnBar();
		assertEquals(new Row("Me", 30, 150), BloodPoolTracker.visibleRows("Me").get(0));

		BloodPoolTracker.onBossPacket(ClientboundBossEventPacket.createRemovePacket(id));
		BloodPoolTracker.readOwnBar();
		assertEquals(new Row("Me", 0, 150), BloodPoolTracker.visibleRows("Me").get(0));
	}

	@Test
	void readsOnlyBloodPoolBars() {
		assertEquals(2, BloodPoolTracker.parsePct("Blood Pool [2%]"));
		assertEquals(160, BloodPoolTracker.parsePct("Blood Pool [160%]"));
		assertEquals(0, BloodPoolTracker.parsePct("Blood Pool [0%]"));
		assertEquals(-1, BloodPoolTracker.parsePct("Tower - Ragni"));
		assertEquals(-1, BloodPoolTracker.parsePct("Blood Pool"));
	}

	@Test
	void capComesFromTheFillRatio() {
		// From the dump: 80% shows at half fill, so the cap is 160.
		assertEquals(160, BloodPoolTracker.capFor(80, 0.5f, 0));
		assertEquals(160, BloodPoolTracker.capFor(2, 0.0125f, 0));
		assertEquals(120, BloodPoolTracker.capFor(60, 0.5f, 0));
		assertEquals(100, BloodPoolTracker.capFor(50, 0.5f, 0));
		assertEquals(80, BloodPoolTracker.capFor(40, 0.5f, 0));
		assertEquals(80, BloodPoolTracker.capFor(80, 1.0f, 0));
		assertEquals(150, BloodPoolTracker.capFor(75, 0.5f, 0));
		assertEquals(150, BloodPoolTracker.capFor(82, 82f / 150, 0));
		assertEquals(90, BloodPoolTracker.capFor(45, 0.5f, 0));
		assertEquals(200, BloodPoolTracker.capFor(100, 0.5f, 0));
		assertEquals(170, BloodPoolTracker.capFor(85, 0.5f, 0));
	}

	@Test
	void roundedBarNameStillReadsEveryCap() {
		// The name shows a whole percent; the fill carries the real pool, up to half a point off.
		for (int cap = BloodPoolTracker.MIN_CAP; cap <= BloodPoolTracker.MAX_CAP; cap += BloodPoolTracker.CAP_STEP) {
			for (int pct = BloodPoolTracker.MIN_PCT_FOR_CAP; pct <= cap; pct++) {
				for (float off : new float[]{-0.49f, 0f, 0.49f}) {
					float fill = Math.min(1f, (pct + off) / cap);
					assertEquals(cap, BloodPoolTracker.capFor(pct, fill, 0), "cap " + cap + " at " + pct + "% off " + off);
				}
			}
		}
	}

	@Test
	void lowOrEmptyPoolKeepsTheCapAlreadyRead() {
		assertEquals(160, BloodPoolTracker.capFor(0, 0f, 160));
		// 1% rounded from 1.4% of 160 would read as 114, nearest 120.
		assertEquals(160, BloodPoolTracker.capFor(1, 0.00875f, 160));
		assertEquals(0, BloodPoolTracker.capFor(0, 0f, 0));
	}

	@Test
	void capAlwaysHoldsThePool() {
		assertEquals(110, BloodPoolTracker.capFor(105, 0f, 100));
		assertEquals(200, BloodPoolTracker.capFor(200, 0.5f, 0));
	}

	@Test
	void eachAcolyteKeepsTheirOwnRow() {
		BloodPoolTracker.onRemote(config, ME, "Alice", "00000000-0000-0000-0000-000000000001", 40, 160);
		BloodPoolTracker.onRemote(config, ME, "Bob", "00000000-0000-0000-0000-000000000002", 95, 150);
		BloodPoolTracker.onRemote(config, ME, "Alice", "00000000-0000-0000-0000-000000000001", 0, 160);
		assertEquals(List.of(new Row("Alice", 0, 160), new Row("Bob", 95, 150)), BloodPoolTracker.visibleRows("Me"));
	}

	@Test
	void ownRowComesFirstOnceThePlayerHasAPool() {
		BloodPoolTracker.onRemote(config, ME, "Alice", "1", 40, 160);
		assertEquals(1, BloodPoolTracker.visibleRows("Me").size());
		BloodPoolTracker.setOwnForTesting(0, 120);
		assertEquals(new Row("Me", 0, 120), BloodPoolTracker.visibleRows("Me").get(0));
	}

	@Test
	void ignoresSelfBadValuesAndADisabledTracker() {
		BloodPoolTracker.onRemote(config, ME, "Me", "00000000-0000-0000-0000-00000000000A", 40, 160);
		BloodPoolTracker.onRemote(config, ME, "Alice", "1", 161, 160);
		BloodPoolTracker.onRemote(config, ME, "Alice", "1", 90, 80);
		BloodPoolTracker.onRemote(config, ME, "Alice", "1", 50, 155);
		BloodPoolTracker.onRemote(config, ME, "Alice", "1", 50, 210);
		BloodPoolTracker.onRemote(config, ME, "Alice", "1", -1, 0);
		BloodPoolTracker.onRemote(config, ME, "", "1", 10, 160);
		BloodPoolTracker.onRemote(config, ME, "Alice", null, 10, 160);
		config.bloodPoolTracker = false;
		BloodPoolTracker.onRemote(config, ME, "Alice", "1", 10, 160);
		assertTrue(BloodPoolTracker.visibleRows("Me").isEmpty());
	}

	@Test
	void resetClearsEveryRow() {
		BloodPoolTracker.onRemote(config, ME, "Alice", "1", 40, 160);
		BloodPoolTracker.setOwnForTesting(20, 160);
		BloodPoolTracker.reset();
		assertTrue(BloodPoolTracker.visibleRows("Me").isEmpty());
	}
}
