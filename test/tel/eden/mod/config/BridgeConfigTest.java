package tel.eden.mod.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

class BridgeConfigTest {
	@Test
	void defaultsDiscordBridgeMessagesToTrue() {
		BridgeConfig config = new BridgeConfig();
		assertTrue(config.discordBridgeMessages);
	}

	@Test
	void preservesDiscordBridgeMessagesStateAcrossSerialization() {
		Gson gson = new Gson();
		BridgeConfig config = new BridgeConfig();
		config.discordBridgeMessages = false;

		String json = gson.toJson(config);
		BridgeConfig loaded = gson.fromJson(json, BridgeConfig.class);

		assertFalse(loaded.discordBridgeMessages);
	}

	@Test
	void defaultsToTrueWhenOmittedFromSerializedJson() {
		Gson gson = new Gson();
		String jsonWithoutField = "{\"enabled\":true}";

		BridgeConfig loaded = gson.fromJson(jsonWithoutField, BridgeConfig.class);

		assertTrue(loaded.discordBridgeMessages);
	}
}
