package tel.eden.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BridgeWebSocketClientAuthTest {
	private static final String NONCE = "00112233445566778899aabbccddeeff";

	@Test
	void joinIdMatchesTheBridgesDerivation() {
		// The same vector is asserted on the bridge (test_session_auth.py): if either side
		// drifts, every updated mod is rejected as unverified.
		assertEquals("7030075b9afbf453376628a27eac57abf7d6107b", BridgeWebSocketClient.joinId(NONCE));
	}

	@Test
	void joinIdIsNeverTheNonceAndIsHashSized() {
		String id = BridgeWebSocketClient.joinId(NONCE);

		assertNotEquals(NONCE, id);
		assertEquals(40, id.length());
		assertEquals(id, BridgeWebSocketClient.joinId(NONCE));
	}

	@Test
	void aLoginHashIsNotAcceptedAsANonce() {
		assertTrue(BridgeWebSocketClient.isServerNonce(NONCE));
		assertTrue(BridgeWebSocketClient.isServerNonce(NONCE.toUpperCase()));
		// A vanilla server hash: 40 hex characters, possibly negative.
		assertFalse(BridgeWebSocketClient.isServerNonce("7030075b9afbf453376628a27eac57abf7d6107b"));
		assertFalse(BridgeWebSocketClient.isServerNonce("-7030075b9afbf453376628a27eac57abf7d6107b"));
		assertFalse(BridgeWebSocketClient.isServerNonce(""));
		assertFalse(BridgeWebSocketClient.isServerNonce(null));
		assertFalse(BridgeWebSocketClient.isServerNonce(NONCE + "0"));
	}
}
