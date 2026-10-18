package tel.eden.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
	void golemDefeatWithoutAPartyOmitsTheField() {
		assertFalse(BridgeWebSocketClient.tccGolemDefeatedPayload("B1", null).has("party"));
		assertFalse(BridgeWebSocketClient.tccGolemDefeatedPayload("B1", List.of()).has("party"));
	}
}
