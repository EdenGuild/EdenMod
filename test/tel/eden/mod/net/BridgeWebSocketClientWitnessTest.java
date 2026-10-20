package tel.eden.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class BridgeWebSocketClientWitnessTest {

	@Test
	void witnessKillCarriesPartyAndNoGroupKey() {
		JsonObject obj = BridgeWebSocketClient.witnessKillPayload(List.of("Alice", " Bob ", ""));

		assertEquals("witnessKill", obj.get("type").getAsString());
		assertEquals(2, obj.getAsJsonArray("party").size());
		assertEquals("Bob", obj.getAsJsonArray("party").get(1).getAsString());
		assertFalse(obj.has("groupKey"), "The Radiance group key would relay raid kills to war teammates");
	}
}
