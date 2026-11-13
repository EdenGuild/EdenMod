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
import tel.eden.mod.net.OutboundPolicy.Standing;

class OutboundPolicyTest {
	private static final Set<String> TRAVELER = Set.of("authResponse", "presence", "mailSend", "mailAck", "tccMapPos", "tccGolemDefeated", "tccExitReached", "witnessKill", "partyOpen", "partyJoin", "partyLeave", "partyList", "partyManage", "travelerChat", "travelerPrefs", "coinflip", "diceroll", "attestationFailure");

	// Every type BWSC sends that a traveler must NOT be able to send. A new send* type that is
	// in neither set fails classifiesEveryTypeBwscCanSend until someone decides.
	private static final Set<String> GUILD_ONLY = Set.of("allyActivityRequest", "annihilation", "aspectGiveawayRequest", "aspectsPendingRequest", "bankEvent", "emeraldsPendingRequest", "giftLockAcquire", "giftLockRelease", "guildAlliances", "guildAnnounce", "guildEvent", "guildLogSnapshot", "guildManageStatus", "guildReward", "guildStorage", "itemCard", "login", "longEventMysteryGuess", "longEventReroll", "longEventRewardsRequest", "longEventStateRequest", "onlineRequest", "radianceCast", "radianceGroup", "raidCompletion", "rankChange", "rewardDeductRequest", "rewardOptOutRequest", "rewardStatusRequest", "territoryDefended", "warAttended", "warCountsRequest", "warDefense", "warGoing", "warGoingInside", "warQueued", "warTimerEnded", "wynnMessage");

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
		for (Standing standing : new Standing[]{Standing.UNKNOWN, Standing.NOT_LINKED, Standing.NOT_MEMBER}) {
			for (String type : union()) {
				assertEquals("authResponse".equals(type), OutboundPolicy.allowed(standing, type), standing + " " + type);
			}
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
		JsonObject chat = BridgeWebSocketClient.travelerChatPayload("hello");
		assertEquals("travelerChat", chat.get("type").getAsString());
		assertEquals("hello", chat.get("content").getAsString());

		JsonObject prefs = BridgeWebSocketClient.travelerPrefsPayload(false, "guild");
		assertEquals("travelerPrefs", prefs.get("type").getAsString());
		assertFalse(prefs.get("chat").getAsBoolean());
		assertEquals("guild", prefs.get("games").getAsString());
	}

	@Test
	void gamesPrefPerStanding() {
		assertEquals("guild", BridgeWebSocketClient.gamesPref(false, false, true));
		assertEquals("all", BridgeWebSocketClient.gamesPref(false, true, false));
		assertEquals("all", BridgeWebSocketClient.gamesPref(true, false, true));
		assertEquals("off", BridgeWebSocketClient.gamesPref(true, true, false));
	}

	private static Set<String> union() {
		Set<String> all = new TreeSet<>(TRAVELER);
		all.addAll(GUILD_ONLY);
		return all;
	}
}
