package tel.eden.mod.war;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.war.radiance.RadianceCooldownParser;
import tel.eden.mod.war.radiance.RadianceSyncEntry;
import tel.eden.mod.war.radiance.RadianceTracker;

class RadianceTrackerTest {

	@BeforeEach
	void setUp() {
		RadianceTracker.onJoin();
	}

	@Test
	void testCooldownParserVariousFormats() {
		assertEquals(15.0, RadianceCooldownParser.remainingSeconds("Radiance (0:15)"));
		assertEquals(75.0, RadianceCooldownParser.remainingSeconds("§6Radiance §7(1:15)"));
		assertEquals(20.0, RadianceCooldownParser.remainingSeconds("§cRadiance (20s)"));
		assertEquals(14.0, RadianceCooldownParser.remainingSeconds("Radiance 0:14"));
		assertNull(RadianceCooldownParser.remainingSeconds("Bash (0:03)"));
		assertNull(RadianceCooldownParser.remainingSeconds(""));
		assertNull(RadianceCooldownParser.remainingSeconds(null));

		// Disconnected or adjacent abilities on same line must not be misattributed
		assertNull(RadianceCooldownParser.remainingSeconds("Radiance: Ready • War Scream: 12s"));
		assertNull(RadianceCooldownParser.remainingSeconds("No Radiance - Uppercut: 5s"));
		assertEquals(15.0, RadianceCooldownParser.remainingSeconds("Radiance (0:15) • Bash: 3s"));
		assertEquals(15.0, RadianceCooldownParser.remainingSeconds("Bash: 3s • Radiance (0:15)"));
	}

	@Test
	void testCooldownParserMultilineFooter() {
		String footer = "§eServer: WC1\n§6Radiance (0:18)\n§aWar in progress";
		assertEquals(18.0, RadianceCooldownParser.remainingSeconds(footer));
	}

	@Test
	void testAspectTierDurations() {
		assertEquals(8.0, RadianceTracker.getDurationForTier(0));
		assertEquals(9.0, RadianceTracker.getDurationForTier(1));
		assertEquals(9.65, RadianceTracker.getDurationForTier(2));
		assertEquals(10.0, RadianceTracker.getDurationForTier(3));
	}

	@Test
	void testCastCountdownDurationCalculations() {
		// Countdown alerts 1.25s before partner's radiance expires (default 1.25s grace period)
		assertEquals(6.75, RadianceTracker.getCastCountdownDurationSeconds(8.0), 0.001);
		assertEquals(7.75, RadianceTracker.getCastCountdownDurationSeconds(9.0), 0.001);
		assertEquals(8.40, RadianceTracker.getCastCountdownDurationSeconds(9.65), 0.001);
		assertEquals(8.75, RadianceTracker.getCastCountdownDurationSeconds(10.0), 0.001);

		// When remaining is <= 1.25s, countdown is 0.0s (immediate alert)
		assertEquals(0.0, RadianceTracker.getCastCountdownDurationSeconds(1.0), 0.001);
		assertEquals(0.0, RadianceTracker.getCastCountdownDurationSeconds(0.5), 0.001);
		assertEquals(0.0, RadianceTracker.getCastCountdownDurationSeconds(0.0), 0.001);

		// Config-specified grace period
		BridgeConfig config = new BridgeConfig();
		config.radianceAdvanceNoticeSeconds = 2.0;
		assertEquals(6.0, RadianceTracker.getCastCountdownDurationSeconds(config, 8.0), 0.001);
		config.radianceAdvanceNoticeSeconds = 1.0;
		assertEquals(7.0, RadianceTracker.getCastCountdownDurationSeconds(config, 8.0), 0.001);
		config.radianceAdvanceNoticeSeconds = 0.25;
		assertEquals(7.75, RadianceTracker.getCastCountdownDurationSeconds(config, 8.0), 0.001);
		config.radianceAdvanceNoticeSeconds = 0.0;
		assertEquals(8.0, RadianceTracker.getCastCountdownDurationSeconds(config, 8.0), 0.001);
	}

	@Test
	void testWarStateTransitionsFromChat() {
		assertFalse(RadianceTracker.isInWar());

		// 25 second warning
		RadianceTracker.onChat("§6The war battle will start in 25 seconds.");
		assertTrue(RadianceTracker.isInWar());

		// Entering arena / subserver sends Welcome to Wynncraft, which must not cancel war state
		RadianceTracker.onChat("Welcome to Wynncraft!");
		assertTrue(RadianceTracker.isInWar());

		// 5-4-3-2-1 countdown warnings
		RadianceTracker.onChat("5 seconds...");
		assertTrue(RadianceTracker.isInWar());
		RadianceTracker.onChat("4 seconds...");
		assertTrue(RadianceTracker.isInWar());
		RadianceTracker.onChat("1 second...");
		assertTrue(RadianceTracker.isInWar());

		// War start countdown with territory name
		RadianceTracker.onChat("The war for Detlas will start in 10 seconds.");
		assertTrue(RadianceTracker.isInWar());

		// Player death does not terminate war state
		RadianceTracker.onChat("You have died at [-450, 67, -1200]!");
		assertTrue(RadianceTracker.isInWar());

		// Unrelated guilds losing wars does not terminate our war
		RadianceTracker.onChat("[ABC] has lost the war for Detlas!");
		assertTrue(RadianceTracker.isInWar());
		RadianceTracker.onChat("[XYZ] failed to take control of Detlas!");
		assertTrue(RadianceTracker.isInWar());

		// Eden's loss terminates war
		RadianceTracker.onChat("§cYour guild has lost the war for Detlas!");
		assertFalse(RadianceTracker.isInWar());

		// Starting another war
		RadianceTracker.onChat("... The battle has begun!");
		assertTrue(RadianceTracker.isInWar());

		// Personal capture ends war
		RadianceTracker.onChat("You have taken control of Detlas from [ABC]!");
		assertFalse(RadianceTracker.isInWar());

		// EDN tag lost the war ends war
		RadianceTracker.onChat("The war battle will start in 10 seconds.");
		assertTrue(RadianceTracker.isInWar());
		RadianceTracker.onChat("[EDN] has lost the war!");
		assertFalse(RadianceTracker.isInWar());

		// Territory defense victory ends war
		RadianceTracker.onChat("The war battle will start in 10 seconds.");
		assertTrue(RadianceTracker.isInWar());
		RadianceTracker.onChat("Your guild has successfully defended Detlas.");
		assertFalse(RadianceTracker.isInWar());
	}

	@Test
	void testRemoteRemainingDurationCalculation() {
		long now = System.currentTimeMillis();
		RadianceSyncEntry entry = new RadianceSyncEntry(3, 10000L, now - 1000L);
		// Remaining 10000ms - 100ms latency - 1000ms elapsed = 8900ms = 8.9s
		double duration = RadianceTracker.getRemoteRemainingDurationSeconds(now, entry, 3, 100L);
		assertEquals(8.9, duration, 0.05);
	}

	@Test
	void testActiveGatingRequiresTwoOrMoreMembersWithSameKey() {
		BridgeConfig config = new BridgeConfig();
		// Off by default
		assertFalse(config.radianceTracker);
		assertFalse(RadianceTracker.isActive(config));

		// Enabled but no group key
		config.radianceTracker = true;
		config.radianceGroupKey = "";
		assertFalse(RadianceTracker.isActive(config));

		// Group key set but 0 or 1 members
		config.radianceGroupKey = "war1";
		RadianceTracker.setGroupMemberCount(1);
		assertFalse(RadianceTracker.isActive(config));

		// 2 members, but require war and not in war
		RadianceTracker.setGroupMemberCount(2);
		config.radianceRequireWar = true;
		assertFalse(RadianceTracker.isActive(config));

		// Now in war -> active!
		RadianceTracker.onChat("§6The war battle will start in 10 seconds.");
		assertTrue(RadianceTracker.isActive(config));

		// If requireWar is false, active without war
		config.radianceRequireWar = false;
		RadianceTracker.onChat("§cYour guild has lost the war for Detlas!");
		assertTrue(RadianceTracker.isActive(config));

		// Member drops to 1 -> inactive
		RadianceTracker.setGroupMemberCount(1);
		assertFalse(RadianceTracker.isActive(config));
	}

	@Test
	void testGuildChatDoesNotTriggerWarState() {
		assertFalse(RadianceTracker.isInWar());
		RadianceTracker.onChat("[Guild] [EDN] ... The battle has begun!");
		assertFalse(RadianceTracker.isInWar());
		RadianceTracker.onChat("[Guild] The war battle will start in 15 seconds.");
		assertFalse(RadianceTracker.isInWar());
		RadianceTracker.onChat("[Party] The battle has begun!");
		assertFalse(RadianceTracker.isInWar());
	}

	@Test
	void testRemoteCastGatedByRequireWar() {
		assertFalse(RadianceTracker.isInWar());
		BridgeConfig config = new BridgeConfig();
		config.radianceTracker = true;
		config.radianceGroupKey = "war1";
		config.radianceRequireWar = true;

		// Not in war -> remote cast ignored, does not trigger war state
		RadianceTracker.onRemoteCast(config, "war1", 2, 8000L, System.currentTimeMillis(), 0L);
		assertFalse(RadianceTracker.isInWar());

		// Enter war via local start cue
		RadianceTracker.onChat("The war battle will start in 10 seconds.");
		assertTrue(RadianceTracker.isInWar());

		// In war -> remote cast accepted
		RadianceTracker.onRemoteCast(config, "war1", 2, 8000L, System.currentTimeMillis(), 0L);
		assertTrue(RadianceTracker.isInWar());
	}

	@Test
	void testResolveEffectiveTier() {
		// When configured tier > 0, it takes precedence
		assertEquals(3, RadianceTracker.resolveEffectiveTier(3, null));
		assertEquals(3, RadianceTracker.resolveEffectiveTier(3, 15.0));
		assertEquals(2, RadianceTracker.resolveEffectiveTier(2, 10.0));
		assertEquals(1, RadianceTracker.resolveEffectiveTier(1, null));

		// When configured tier is 0, auto-detect from cooldown/duration if in range
		assertEquals(3, RadianceTracker.resolveEffectiveTier(0, 10.0));
		assertEquals(3, RadianceTracker.resolveEffectiveTier(0, 9.9));
		assertEquals(2, RadianceTracker.resolveEffectiveTier(0, 9.65));
		assertEquals(1, RadianceTracker.resolveEffectiveTier(0, 9.0));
		assertEquals(0, RadianceTracker.resolveEffectiveTier(0, 8.0));

		// Cooldowns above 10.5 (e.g. 15.0s cooldown) default to base tier 0
		assertEquals(0, RadianceTracker.resolveEffectiveTier(0, 15.0));
		assertEquals(0, RadianceTracker.resolveEffectiveTier(0, null));
	}

	@Test
	void testInterruptRotationFlowAndCooldownIsolation() {
		BridgeConfig config = new BridgeConfig();
		config.radianceTracker = true;
		config.radianceGroupKey = "war1";
		config.radianceRequireWar = false;
		config.radianceSelfTier = 3; // Local player is Tier 3 (10.0s)
		RadianceTracker.setGroupMemberCount(2);

		long t0 = System.currentTimeMillis();

		// Initial tick primes state with player ready (not on cooldown)
		RadianceTracker.processCooldownTick(config, null, t0, null);
		assertFalse(RadianceTracker.isCastCountdownActive());
		assertEquals(0L, RadianceTracker.getLocalRadianceActiveUntilMs());
		assertNull(RadianceTracker.getCachedCooldownSeconds());

		// Partner casts their Radiance (Tier 2 -> 9.65s)
		RadianceTracker.onRemoteCast(config, "war1", 2, 9650L, t0, 0L);
		assertTrue(RadianceTracker.isCastCountdownActive());
		// 9.65s - 1.25s = 8.40s countdown displayed accounting for grace
		assertEquals(8.40, RadianceTracker.getRemainingCountdownSeconds(t0), 0.01);
		assertEquals(0L, RadianceTracker.getLocalRadianceActiveUntilMs());
		// Partner cast does NOT affect local player's cooldown
		assertNull(RadianceTracker.getCachedCooldownSeconds());

		// 3 seconds later, local player interrupts by casting Bash (off cooldown)
		long tInterrupt = t0 + 3000L;
		// Tab list footer now reports local cooldown of 15s
		RadianceTracker.processCooldownTick(config, 15.0, tInterrupt, null);

		// Local cast executed:
		// 1. Pending countdown is cleared
		assertFalse(RadianceTracker.isCastCountdownActive());
		// 2. Active radiance counter gets set to aspect tier duration minus grace (Tier 3 = 10.0s - 1.25s = 8.75s)
		assertEquals(tInterrupt + 8750L, RadianceTracker.getLocalRadianceActiveUntilMs());
		// 3. Local cooldown is set to 15s
		assertEquals(15.0, RadianceTracker.getCachedCooldownSeconds());

		// Single-tick footer flicker to null must not cause a false cast or reset timer
		long tFlicker = tInterrupt + 500L;
		RadianceTracker.processCooldownTick(config, null, tFlicker, null);
		assertEquals(tInterrupt + 8750L, RadianceTracker.getLocalRadianceActiveUntilMs());

		// Footer reappears at 14s (continuing downwards) - must NOT trigger new cast
		long tSpam = tInterrupt + 1000L;
		RadianceTracker.processCooldownTick(config, 14.0, tSpam, null);
		// Active radiance until timestamp MUST NOT reset
		assertEquals(tInterrupt + 8750L, RadianceTracker.getLocalRadianceActiveUntilMs());
		assertEquals(14.0, RadianceTracker.getCachedCooldownSeconds());

		// When partner casts next (interrupting back), local cooldown remains unchanged
		long tPartnerCast = tInterrupt + 5000L;
		RadianceTracker.onRemoteCast(config, "war1", 1, 9000L, tPartnerCast, 0L);
		// Local active radiance cleared
		assertEquals(0L, RadianceTracker.getLocalRadianceActiveUntilMs());
		// New countdown starts (9.0 - 1.25 = 7.75s displayed)
		assertTrue(RadianceTracker.isCastCountdownActive());
		assertEquals(7.75, RadianceTracker.getRemainingCountdownSeconds(tPartnerCast), 0.01);
		// Local cooldown must NOT change based off partner casting
		assertEquals(14.0, RadianceTracker.getCachedCooldownSeconds());
	}

	@Test
	void testGracePeriodAlertTiming() {
		BridgeConfig config = new BridgeConfig();
		config.radianceTracker = true;
		config.radianceGroupKey = "war1";
		config.radianceRequireWar = false;
		config.radianceAdvanceNoticeSeconds = 1.25;
		RadianceTracker.setGroupMemberCount(2);

		long t0 = System.currentTimeMillis();
		// Remote cast with Tier 3 (10.0s) -> timer displayed is 10.0s - 1.25s = 8.75s
		RadianceTracker.onRemoteCast(config, "war1", 3, 10_000L, t0, 0L);
		assertTrue(RadianceTracker.isCastCountdownActive());
		assertEquals(8.75, RadianceTracker.getRemainingCountdownSeconds(t0), 0.01);

		// At t0 + 8000ms (0.75s remaining before alert), countdown has not expired -> no cast prompt
		RadianceTracker.processCooldownTick(config, null, t0 + 8000L, null);
		assertFalse(RadianceTracker.isCastPromptActive(t0 + 8000L));

		// At t0 + 8750ms (timer reaches 0.0s, which is 1.25s before 10s radiance expires) -> cast prompt triggers
		RadianceTracker.processCooldownTick(config, null, t0 + 8750L, null);
		assertTrue(RadianceTracker.isCastPromptActive(t0 + 8750L));

		// Test local cast accounting for grace post counting aspect+base
		RadianceTracker.onJoin();
		RadianceTracker.setGroupMemberCount(2);
		config.radianceSelfTier = 3; // 10.0s
		RadianceTracker.processCooldownTick(config, null, t0, null);
		RadianceTracker.processCooldownTick(config, 15.0, t0, null);
		// Local radiance timer is set to 10.0s - 1.25s = 8.75s
		assertEquals(t0 + 8750L, RadianceTracker.getLocalRadianceActiveUntilMs());
		assertFalse(RadianceTracker.isCastPromptActive(t0 + 8000L));
		RadianceTracker.processCooldownTick(config, 14.0, t0 + 8750L, null);
		// Local cast must NOT trigger cast prompt on caster (only on partner's turn)
		assertFalse(RadianceTracker.isCastPromptActive(t0 + 8750L));
		assertEquals(0L, RadianceTracker.getLocalRadianceActiveUntilMs());
	}
}
