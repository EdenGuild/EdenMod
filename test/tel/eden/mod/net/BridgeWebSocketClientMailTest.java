package tel.eden.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class BridgeWebSocketClientMailTest {

	@Test
	void parseMailKeepsValidEntriesAndDropsBadIds() {
		JsonObject obj = JsonParser.parseString("{\"type\":\"mailDelivery\",\"mails\":[" + "{\"id\":7,\"from\":\"Alice\",\"content\":\"hi\",\"sentAt\":1700000000000}," + "{\"id\":0,\"from\":\"Bob\",\"content\":\"no id\"}," + "\"not an object\"," + "{\"id\":9,\"from\":\"Carol\"}]}").getAsJsonObject();

		assertEquals(List.of(new MailEntry(7, "Alice", "hi", 1700000000000L), new MailEntry(9, "Carol", "", 0L)), BridgeWebSocketClient.parseMail(obj));
	}

	@Test
	void parseMailWithoutListIsEmpty() {
		assertEquals(List.of(), BridgeWebSocketClient.parseMail(new JsonObject()));
	}

	@Test
	void mailAckCarriesIds() {
		JsonObject obj = BridgeWebSocketClient.mailAckPayload(List.of(7L, 9L));

		assertEquals("mailAck", obj.get("type").getAsString());
		assertEquals(7L, obj.getAsJsonArray("ids").get(0).getAsLong());
		assertEquals(2, obj.getAsJsonArray("ids").size());
	}
}
