package tel.eden.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GuildLogSourceKeyTest {
	@Test
	void turnsAFilterLabelIntoACamelCaseWireKey() {
		assertEquals("general", BridgeWebSocketClient.sourceKey("General"));
		assertEquals("publicBank", BridgeWebSocketClient.sourceKey("Public Bank"));
		assertEquals("highRankedBank", BridgeWebSocketClient.sourceKey("High Ranked Bank"));
		assertEquals("territoryWars", BridgeWebSocketClient.sourceKey("  Territory / Wars! "));
	}

	@Test
	void fallsBackForALabelWithNothingUsable() {
		assertEquals("unknown", BridgeWebSocketClient.sourceKey("???"));
	}
}
