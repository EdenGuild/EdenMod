package tel.eden.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class BridgeWebSocketClientBloodPoolTest {
	@Test
	void carriesLevelCapAndParty() {
		JsonObject obj = BridgeWebSocketClient.bloodPoolPayload(120, 160, List.of("Alice", "Bob"));
		assertEquals("bloodPool", obj.get("type").getAsString());
		assertEquals(120, obj.get("pct").getAsInt());
		assertEquals(160, obj.get("max").getAsInt());
		assertEquals(2, obj.getAsJsonArray("party").size());
	}

	@Test
	void noPoolSendsOnlyTheParty() {
		JsonObject obj = BridgeWebSocketClient.bloodPoolPayload(-1, 0, List.of("Alice", "Bob"));
		assertFalse(obj.has("pct"));
		assertFalse(obj.has("max"));
		assertEquals(2, obj.getAsJsonArray("party").size());
	}

	@Test
	void nothingIsBuiltWithoutAParty() {
		assertNull(BridgeWebSocketClient.bloodPoolPayload(50, 160, List.of()));
		assertNull(BridgeWebSocketClient.bloodPoolPayload(50, 160, null));
	}
}
