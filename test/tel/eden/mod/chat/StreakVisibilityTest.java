package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig.LoginLogoutMode;

class StreakVisibilityTest {

	@Test
	void streakPillRespectsLoginLogoutVisibility() {
		assertTrue(EdenModClient.shouldDisplayStreakPill(LoginLogoutMode.ALL, false));
		assertTrue(EdenModClient.shouldDisplayStreakPill(LoginLogoutMode.ALL, true));
		assertTrue(EdenModClient.shouldDisplayStreakPill(LoginLogoutMode.HIDE_MINE, false));
		assertTrue(EdenModClient.shouldDisplayStreakPill(LoginLogoutMode.HIDE_MINE, true));
		assertFalse(EdenModClient.shouldDisplayStreakPill(LoginLogoutMode.HIDE_ALL, false));
		assertTrue(EdenModClient.shouldDisplayStreakPill(LoginLogoutMode.HIDE_ALL, true));
	}
}
