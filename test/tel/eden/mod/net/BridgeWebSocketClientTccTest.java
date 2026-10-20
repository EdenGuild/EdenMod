package tel.eden.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BridgeWebSocketClientTccTest {
	@Test
	void golemDefeatCarriesThePartySoTheBackendOnlyRelaysToIt() {
		JsonObject payload = BridgeWebSocketClient.tccGolemDefeatedPayload("B1", Arrays.asList("Alice", "", null, "Bob"));
		assertEquals("tccGolemDefeated", payload.get("type").getAsString());
		assertEquals("B1", payload.get("golemId").getAsString());
		JsonArray party = payload.getAsJsonArray("party");
		assertEquals(2, party.size());
		assertEquals("Alice", party.get(0).getAsString());
		assertEquals("Bob", party.get(1).getAsString());
	}

	@Test
	void positionAndExitCarryTheParty() {
		JsonObject pos = BridgeWebSocketClient.tccMapPosPayload(10934.0, 45.0, 3479.0, 90f, true, List.of("Alice", "Bob"));
		assertEquals("tccMapPos", pos.get("type").getAsString());
		assertEquals(10934.0, pos.get("x").getAsDouble());
		assertEquals(true, pos.get("isWings").getAsBoolean());
		assertEquals(2, pos.getAsJsonArray("party").size());

		JsonObject exit = BridgeWebSocketClient.tccExitReachedPayload("RR3", 10827.0, 3396.0, List.of("Alice", "Bob"));
		assertEquals("tccExitReached", exit.get("type").getAsString());
		assertEquals("RR3", exit.get("path").getAsString());
		assertEquals(2, exit.getAsJsonArray("party").size());
	}

	@Test
	void nothingIsBuiltWithoutAParty() {
		// A party-less TCC message would reach raid groups it doesn't belong to, so none is sent.
		for (List<String> noParty : Arrays.<List<String>>asList(null, List.of(), Arrays.asList("", " ", null))) {
			assertNull(BridgeWebSocketClient.tccGolemDefeatedPayload("B1", noParty));
			assertNull(BridgeWebSocketClient.tccMapPosPayload(10934.0, 45.0, 3479.0, 0f, false, noParty));
			assertNull(BridgeWebSocketClient.tccExitReachedPayload("RR3", 10827.0, 3396.0, noParty));
		}
	}
}
