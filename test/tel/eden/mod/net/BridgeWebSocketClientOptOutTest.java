package tel.eden.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class BridgeWebSocketClientOptOutTest {

	@Test
	void rewardOptOutPayloadFormatsCorrectly() {
		JsonObject obj = BridgeWebSocketClient.rewardOptOutPayload("aspects", true);

		assertEquals("rewardOptOutRequest", obj.get("type").getAsString());
		assertEquals("aspects", obj.get("rewardKind").getAsString());
		assertTrue(obj.get("blocked").getAsBoolean());
	}

	@Test
	void warpayOptOutPayloadFormatsCorrectly() {
		JsonObject obj = BridgeWebSocketClient.rewardOptOutPayload("warpay", true);

		assertEquals("rewardOptOutRequest", obj.get("type").getAsString());
		assertEquals("warpay", obj.get("rewardKind").getAsString());
		assertTrue(obj.get("blocked").getAsBoolean());
	}
}
