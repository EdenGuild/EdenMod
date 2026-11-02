package tel.eden.mod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

class BridgeConfigTest {
	@Test
	void defaultsLoginLogoutModeToAll() {
		BridgeConfig config = new BridgeConfig();
		assertEquals(BridgeConfig.LoginLogoutMode.ALL, config.loginLogoutMode);
	}

	@Test
	void preservesLoginLogoutModeAcrossSerialization() {
		Gson gson = new Gson();
		BridgeConfig config = new BridgeConfig();
		config.loginLogoutMode = BridgeConfig.LoginLogoutMode.HIDE_ALL;

		String json = gson.toJson(config);
		BridgeConfig loaded = gson.fromJson(json, BridgeConfig.class);

		assertEquals(BridgeConfig.LoginLogoutMode.HIDE_ALL, loaded.loginLogoutMode);
	}

	@Test
	void migratesLegacyAnnounceSelfPresenceWhenPresent() {
		Gson gson = new Gson();
		String falseJson = "{\"announceSelfPresence\":false}";
		BridgeConfig loadedFalse = gson.fromJson(falseJson, BridgeConfig.class);
		BridgeConfig.migrateLegacyFields(loadedFalse);
		assertEquals(BridgeConfig.LoginLogoutMode.HIDE_MINE, loadedFalse.loginLogoutMode);

		String trueJson = "{\"announceSelfPresence\":true}";
		BridgeConfig loadedTrue = gson.fromJson(trueJson, BridgeConfig.class);
		BridgeConfig.migrateLegacyFields(loadedTrue);
		assertEquals(BridgeConfig.LoginLogoutMode.ALL, loadedTrue.loginLogoutMode);
	}
}
