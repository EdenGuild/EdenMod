package tel.eden.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.net.OutboundPolicy.Standing;

class OutboundPolicyTest {
	private static final Set<String> TRAVELER = Set.of("authResponse", "presence", "mailSend", "mailAck", "tccMapPos", "tccGolemDefeated", "tccExitReached", "witnessKill", "partyOpen", "partyJoin", "partyLeave", "partyList", "partyManage", "travelerChat", "travelerPrefs", "coinflip", "diceroll", "attestationFailure", "wikiRequest", "bloodPool");

	// Every type BWSC sends that a traveler must NOT be able to send. A new send* type that is
	// in neither set fails classifiesEveryTypeBwscCanSend until someone decides.
	private static final Set<String> GUILD_ONLY = Set.of("allyActivityRequest", "annihilation", "aspectGiveawayRequest", "aspectsPendingRequest", "bankEvent", "cycleLeaderboardRequest", "emeraldsPendingRequest", "giftLockAcquire", "giftLockRelease", "guildAlliances", "guildAnnounce", "guildEconomy", "guildEvent", "guildLogSnapshot", "guildManageStatus", "guildReward", "guildStorage", "guildTomeFound", "itemCard", "lifetimeLeaderboardRequest", "login", "longEventMysteryGuess", "longEventReroll", "longEventRewardsRequest", "longEventStateRequest", "onlineRequest", "radianceCast", "radianceGroup", "raidCompletion", "rankChange", "rewardDeductRequest", "rewardOptOutRequest", "rewardStatusRequest", "territoryDefended", "warAttended", "warCountsRequest", "warDefense", "warGoing", "warGoingInside", "warQueued", "warTimerEnded", "wynnMessage");

	@Test
	void travelerGetsExactlyTheAllowlist() {
		for (String type : TRAVELER) {
			assertTrue(OutboundPolicy.allowed(Standing.TRAVELER, type), type);
		}
		for (String type : GUILD_ONLY) {
			assertFalse(OutboundPolicy.allowed(Standing.TRAVELER, type), type);
		}
		assertEquals(TRAVELER, OutboundPolicy.TRAVELER_TYPES);
	}

	@Test
	void fullMemberMaySendEverything() {
		for (String type : Set.copyOf(union())) {
			assertTrue(OutboundPolicy.allowed(Standing.FULL, type), type);
		}
	}

	@Test
	void unverifiedAndIneligibleStandingsOnlyMayAuthenticate() {
		for (String type : union()) {
			assertEquals("authResponse".equals(type), OutboundPolicy.allowed(Standing.UNKNOWN, type), "UNKNOWN " + type);
			// Once verified, a player without access may also report a failed jar check.
			boolean observer = "authResponse".equals(type) || "attestationFailure".equals(type);
			assertEquals(observer, OutboundPolicy.allowed(Standing.NOT_LINKED, type), "NOT_LINKED " + type);
			assertEquals(observer, OutboundPolicy.allowed(Standing.NOT_MEMBER, type), "NOT_MEMBER " + type);
		}
	}

	@Test
	void standingFromAuthOk() {
		assertEquals(Standing.FULL, Standing.of(true, true, false));
		assertEquals(Standing.FULL, Standing.of(true, true, true));
		assertEquals(Standing.TRAVELER, Standing.of(true, false, true));
		assertEquals(Standing.NOT_MEMBER, Standing.of(true, false, false));
		assertEquals(Standing.NOT_LINKED, Standing.of(false, false, false));
		assertEquals(Standing.NOT_LINKED, Standing.of(false, false, true));
	}

	@Test
	void travelerIgnoresGuildOnlyInbound() {
		for (String type : new String[]{"warBoard", "discordMessage", "loginNotice", "logoutNotice", "onlineList"}) {
			assertFalse(OutboundPolicy.inboundAllowed(Standing.TRAVELER, type), type);
			assertTrue(OutboundPolicy.inboundAllowed(Standing.FULL, type), type);
		}
		assertTrue(OutboundPolicy.inboundAllowed(Standing.TRAVELER, "pillMessage"));
		assertTrue(OutboundPolicy.inboundAllowed(Standing.TRAVELER, "travelerChat"));
	}

	@Test
	void classifiesEveryTypeBwscCanSend() throws IOException {
		String source = Files.readString(Path.of(System.getProperty("edenmod.srcDir")).resolve("tel/eden/mod/net/BridgeWebSocketClient.java"));
		Matcher matcher = Pattern.compile("addProperty\\(\"type\", \"(\\w+)\"\\)|sendType(?:IfConnected)?\\(\"(\\w+)\"\\)").matcher(source);
		Set<String> found = new TreeSet<>();
		while (matcher.find()) {
			found.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
		}
		assertFalse(found.isEmpty());
		for (String type : found) {
			assertTrue(TRAVELER.contains(type) || GUILD_ONLY.contains(type), "unclassified outbound type: " + type);
		}
	}

	@Test
	void travelerPayloads() {
		JsonObject chat = BridgeWebSocketClient.travelerChatPayload("hello", "");
		assertEquals("travelerChat", chat.get("type").getAsString());
		assertEquals("hello", chat.get("content").getAsString());
		assertFalse(chat.has("replyToMessageId"));
		assertEquals("123", BridgeWebSocketClient.travelerChatPayload("hello", "123").get("replyToMessageId").getAsString());

		JsonObject prefs = BridgeWebSocketClient.travelerPrefsPayload(false, "guild", false);
		assertEquals("travelerPrefs", prefs.get("type").getAsString());
		assertFalse(prefs.get("chat").getAsBoolean());
		assertEquals("guild", prefs.get("games").getAsString());
		assertFalse(prefs.get("hideLogin").getAsBoolean());
		assertTrue(BridgeWebSocketClient.travelerPrefsPayload(true, "guild", true).get("hideLogin").getAsBoolean());
	}

	@Test
	void gamesPrefPerStanding() {
		assertEquals("guild", BridgeWebSocketClient.gamesPref(false, BridgeConfig.MemberGames.GUILD, true));
		assertEquals("all", BridgeWebSocketClient.gamesPref(false, BridgeConfig.MemberGames.ALL, false));
		assertEquals("off", BridgeWebSocketClient.gamesPref(false, BridgeConfig.MemberGames.OFF, true));
		assertEquals("all", BridgeWebSocketClient.gamesPref(true, BridgeConfig.MemberGames.GUILD, true));
		assertEquals("off", BridgeWebSocketClient.gamesPref(true, BridgeConfig.MemberGames.ALL, false));
	}

	private static Set<String> union() {
		Set<String> all = new TreeSet<>(TRAVELER);
		all.addAll(GUILD_ONLY);
		return all;
	}
}
