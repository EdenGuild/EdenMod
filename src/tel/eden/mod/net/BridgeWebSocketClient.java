package tel.eden.mod.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Raw-WebSocket client to the bridge backend.
 *
 * <p>Refuses to connect over non-TLS; carries the bridge JWT as a Bearer header;
 * reconnects with exponential backoff while running; forwards inbound
 * {@code discordMessage} events to a sink and sends captured guild chat outbound.
 */
public final class BridgeWebSocketClient {
	private static final Logger LOGGER = LoggerFactory.getLogger("edenmod");
	private static final int MAX_BACKOFF_SECONDS = 60;

	/** Callbacks for inbound bridge events (delivered off the game thread). */
	public interface MessageSink {
		/**
		 * A relayed Discord message. {@code replyTo}/{@code replyExcerpt} are non-empty
		 * when the Discord message was a reply (the replied-to author and a short quote).
		 */
		void onDiscordMessage(String author, String content, String replyTo, String replyExcerpt);

		/** A bridge user just logged in (presence notice). */
		void onLoginNotice(String username);

		/** A bridge user fully disconnected (presence notice). */
		void onLogoutNotice(String username);

		/** Response to a {@code /eden online} request: the connected bridge users. */
		void onOnlineList(java.util.List<String> users);

		/**
		 * Response to {@code /eden aspects pending}: each member's pending aspects, or
		 * an {@code error} (e.g. not a Chief) when the request was refused.
		 */
		void onAspectsPending(java.util.List<PendingEntry> entries, String error);

		/** A raid party changed state ({@code open}/{@code join}/{@code full}/etc.). */
		void onPartyUpdate(String event, String actor, PartyInfo party);

		/** Response to a {@code /eden party list} request: the open raid parties. */
		void onPartyList(java.util.List<PartyInfo> parties);

		/** A short result line for a party action the player just took in-game. */
		void onPartyFeedback(String message);

		/** Feedback on game commands (e.g. cooldown). */
		void onGameFeedback(String message);

		/**
		 * The bridge server rejected the connection. {@code code} is either the
		 * application-level error code from the server ({@code "version_rejected"},
		 * {@code "not_member"}) or {@code "http_<status>"} for HTTP-level rejections
		 * (e.g. {@code "http_401"} for an invalid JWT).
		 */
		void onConnectionRejected(String code);

		/**
		 * A gold-pill bridge line (Quick Reactions, {@code /eden cf}/{@code diceroll}):
		 * a gold pill labelled {@code label}, then {@code content}.
		 */
		void onPillMessage(String label, String content);
	}

	private final URI uri;
	private final String jwt;
	private final String modVersion;
	private final MessageSink sink;
	private final Runnable onConnected;
	private final HttpClient http = HttpClient.newHttpClient();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "eden-bridge-ws");
		t.setDaemon(true);
		return t;
	});

	private volatile WebSocket socket;
	private volatile boolean running;
	private int backoffSeconds = 1;

	private BridgeWebSocketClient(URI uri, String jwt, String modVersion, MessageSink sink, Runnable onConnected) {
		this.uri = uri;
		this.jwt = jwt;
		this.modVersion = modVersion;
		this.sink = sink;
		this.onConnected = onConnected;
	}

	/**
	 * Create a client for {@code backendBaseUrl} (an https:// URL). {@code onConnected}
	 * runs each time the socket (re)connects.
	 *
	 * @throws IllegalArgumentException if the URL is not https (TLS is required)
	 */
	public static BridgeWebSocketClient create(String backendBaseUrl, String jwt, String modVersion, MessageSink sink, Runnable onConnected) {
		String base = backendBaseUrl.strip();
		if (!base.startsWith("https://")) {
			throw new IllegalArgumentException("bridge backend must be https (refusing non-TLS)");
		}
		String wss = "wss://" + base.substring("https://".length());
		if (wss.endsWith("/")) {
			wss = wss.substring(0, wss.length() - 1);
		}
		return new BridgeWebSocketClient(URI.create(wss + "/ws"), jwt, modVersion, sink, onConnected);
	}

	/** Start connecting (and keep reconnecting until {@link #close()}). */
	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		connect();
	}

	/** Stop the client and close the socket. */
	public synchronized void close() {
		running = false;
		WebSocket current = socket;
		if (current != null) {
			current.sendClose(WebSocket.NORMAL_CLOSURE, "client closing");
		}
		scheduler.shutdownNow();
	}

	/** Send one captured guild-chat line to the backend. */
	public void sendGuildChat(String username, String nickname, String message, int seq) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "wynnMessage");
		obj.addProperty("username", username);
		if (nickname != null && !nickname.isEmpty()) {
			obj.addProperty("nickname", nickname);
		}
		obj.addProperty("message", message);
		obj.addProperty("seq", seq);
		current.sendText(obj.toString(), true);
	}

	/** Send one parsed guild raid completion to the backend. */
	public void sendRaidCompletion(java.util.List<String> party, String raidName, int aspects, int emeralds, String guildExp) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "raidCompletion");
		JsonArray members = new JsonArray();
		for (String member : party) {
			members.add(member);
		}
		obj.add("party", members);
		obj.addProperty("raidName", raidName);
		obj.addProperty("aspects", aspects);
		obj.addProperty("emeralds", emeralds);
		obj.addProperty("guildExp", guildExp);
		current.sendText(obj.toString(), true);
	}

	/** Send one parsed guild rank change to the backend. */
	public void sendRankChange(String target, String oldRank, String newRank, String setter) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "rankChange");
		obj.addProperty("target", target);
		obj.addProperty("oldRank", oldRank);
		obj.addProperty("newRank", newRank);
		obj.addProperty("setter", setter);
		current.sendText(obj.toString(), true);
	}

	/** Tell the backend this player just started a bridge session (for login notices). */
	public void sendLogin() {
		sendType("login");
	}

	/** Ask the backend who is currently connected to the bridge. */
	public void sendOnlineRequest() {
		sendType("onlineRequest");
	}

	/** Ask the backend for each member's pending aspects (Chiefs only). */
	public void sendAspectsPendingRequest() {
		sendType("aspectsPendingRequest");
	}

	/** Open a new party in-game for the given label (raid name or Annihilation). */
	public void sendPartyOpen(String raid, int maxSize, String note, int filled) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "partyOpen");
		obj.addProperty("raid", raid);
		obj.addProperty("size", maxSize);
		if (note != null && !note.isBlank()) {
			obj.addProperty("note", note);
		}
		if (filled > 0) {
			obj.addProperty("filled", filled);
		}
		current.sendText(obj.toString(), true);
	}

	/** Join the open raid party with the given id. */
	public void sendPartyJoin(int id) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "partyJoin");
		obj.addProperty("id", id);
		current.sendText(obj.toString(), true);
	}

	/** Leave a raid party ({@code null} id = whichever party you are in). */
	public void sendPartyLeave(Integer id) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "partyLeave");
		if (id != null) {
			obj.addProperty("id", id);
		}
		current.sendText(obj.toString(), true);
	}

	/** Ask the backend for the list of open raid parties. */
	public void sendPartyList() {
		sendType("partyList");
	}

	/** Manage an active raid party (note, filled, add, remove). */
	public void sendPartyManage(String action, String text, int value, String ign) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "partyManage");
		obj.addProperty("action", action);
		if (text != null && !text.isEmpty()) {
			obj.addProperty("text", text);
		}
		if (value > 0) {
			obj.addProperty("value", value);
		}
		if (ign != null && !ign.isEmpty()) {
			obj.addProperty("ign", ign);
		}
		current.sendText(obj.toString(), true);
	}

	/** Ask the backend to flip a coin and announce who flipped it + the result. */
	public void sendCoinflip() {
		sendType("coinflip");
	}

	/** Ask the backend to roll a die and announce who rolled it + the result. */
	public void sendDiceroll() {
		sendType("diceroll");
	}

	/**
	 * Tell the server whether this client is active in a game world ({@code true})
	 * or dormant (in queue, hub, or AFK with no recent guild activity — {@code false}).
	 * The server uses this to compute the consensus quorum without counting clients
	 * that cannot see guild chat.
	 */
	public void sendPresence(boolean active) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "presence");
		obj.addProperty("active", active);
		current.sendText(obj.toString(), true);
	}

	private void sendType(String type) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", type);
		current.sendText(obj.toString(), true);
	}

	/** Send one parsed guild-bank deposit/withdrawal to the backend. */
	public void sendBankEvent(String action, String player, Integer quantity, String item, String charges, String accessTier, int seq) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "bankEvent");
		obj.addProperty("action", action);
		obj.addProperty("player", player);
		if (quantity != null) {
			obj.addProperty("quantity", quantity);
		}
		obj.addProperty("item", item);
		if (charges != null && !charges.isEmpty()) {
			obj.addProperty("charges", charges);
		}
		obj.addProperty("accessTier", accessTier);
		obj.addProperty("seq", seq);
		current.sendText(obj.toString(), true);
	}

	/** Report an in-game Annihilation warning (seconds until it begins). */
	public void sendAnnihilation(int secondsUntil) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "annihilation");
		obj.addProperty("secondsUntil", secondsUntil);
		current.sendText(obj.toString(), true);
	}

	/** Mirror a guild flavour announcement (weekly objective/boost) into bridge chat. */
	public void sendGuildAnnounce(String message) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "guildAnnounce");
		obj.addProperty("message", message);
		current.sendText(obj.toString(), true);
	}

	/** Send one parsed guild-management/alliance event to the backend. */
	public void sendGuildEvent(String kind, String actor, String subject) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "guildEvent");
		obj.addProperty("kind", kind);
		obj.addProperty("actor", actor);
		obj.addProperty("subject", subject);
		current.sendText(obj.toString(), true);
	}

	/** Send one parsed guild reward handout to the backend. */
	public void sendGuildReward(String giver, String reward, String receiver, int seq) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "guildReward");
		obj.addProperty("giver", giver);
		obj.addProperty("reward", reward);
		obj.addProperty("receiver", receiver);
		obj.addProperty("seq", seq);
		current.sendText(obj.toString(), true);
	}

	/** Send a rendered shared-item card (base64 PNG) to be relayed as the sender. */
	public void sendItemCard(String username, String nickname, String imageBase64, String signature) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "itemCard");
		obj.addProperty("username", username);
		if (nickname != null && !nickname.isEmpty()) {
			obj.addProperty("nickname", nickname);
		}
		obj.addProperty("image", imageBase64);
		obj.addProperty("signature", signature);
		current.sendText(obj.toString(), true);
	}

	/** Send the authoritative handout count for a completed {@code /gift} run. */
	public void sendGuildRewardSummary(String giver, String receiver, String reward, int count) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "guildRewardSummary");
		obj.addProperty("giver", giver);
		obj.addProperty("receiver", receiver);
		obj.addProperty("reward", reward);
		obj.addProperty("count", count);
		current.sendText(obj.toString(), true);
	}

	private void connect() {
		if (!running) {
			return;
		}
		http.newWebSocketBuilder().header("Authorization", "Bearer " + jwt).header("X-Mod-Version", modVersion).connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, new Listener()).whenComplete((ws, error) -> {
			// close() may have been called while the connection was in flight; if so,
			// discard the socket so the server doesn't see a ghost connection.
			if (!running) {
				if (ws != null) {
					ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
				}
				return;
			}
			if (error != null) {
				Throwable cause = error;
				while (cause.getCause() != null)
					cause = cause.getCause();
				// instanceof java.net.http.WebSocketHandshakeException fails in the Fabric
				// Knot classloader even when the class names match; use string comparison.
				if (cause.getClass().getName().equals("java.net.http.WebSocketHandshakeException")) {
					int status = -1;
					try {
						Object resp = cause.getClass().getMethod("getResponse").invoke(cause);
						status = (Integer) resp.getClass().getMethod("statusCode").invoke(resp);
					} catch (Exception ignored) {
					}
					LOGGER.warn("Bridge WebSocket rejected: HTTP {}", status);
					// 4xx = permanent rejection; only 401 (bad JWT) reaches here now that
					// version/membership errors are sent as application-level messages.
					running = false;
					sink.onConnectionRejected("http_" + status);
					return;
				}
				LOGGER.warn("Bridge WebSocket connect failed: {}", error.toString());
				scheduleReconnect();
			} else {
				socket = ws;
				backoffSeconds = 1;
				LOGGER.info("Bridge WebSocket connected");
				try {
					onConnected.run();
				} catch (RuntimeException e) {
					LOGGER.warn("onConnected callback failed", e);
				}
			}
		});
	}

	private void scheduleReconnect() {
		socket = null;
		if (!running) {
			return;
		}
		int delay = backoffSeconds;
		backoffSeconds = Math.min(backoffSeconds * 2, MAX_BACKOFF_SECONDS);
		scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
	}

	private final class Listener implements WebSocket.Listener {
		private final StringBuilder buffer = new StringBuilder();

		@Override
		public void onOpen(WebSocket webSocket) {
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			buffer.append(data);
			if (last) {
				String payload = buffer.toString();
				buffer.setLength(0);
				handlePayload(payload);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			LOGGER.info("Bridge WebSocket closed ({}): {}", statusCode, reason);
			scheduleReconnect();
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			LOGGER.warn("Bridge WebSocket error: {}", error.toString());
			scheduleReconnect();
		}
	}

	private void handlePayload(String payload) {
		try {
			JsonObject obj = JsonParser.parseString(payload).getAsJsonObject();
			switch (get(obj, "type")) {
				case "discordMessage" -> sink.onDiscordMessage(get(obj, "author"), get(obj, "content"), get(obj, "replyTo"), get(obj, "replyExcerpt"));
				case "loginNotice" -> sink.onLoginNotice(get(obj, "username"));
				case "logoutNotice" -> sink.onLogoutNotice(get(obj, "username"));
				case "onlineList" -> sink.onOnlineList(getStringArray(obj, "users"));
				case "aspectsPendingReply" -> sink.onAspectsPending(parsePendingEntries(obj), get(obj, "error"));
				case "partyUpdate" -> sink.onPartyUpdate(get(obj, "event"), get(obj, "actor"), parseParty(obj));
				case "partyListReply" -> sink.onPartyList(parsePartyList(obj));
				case "partyFeedback" -> sink.onPartyFeedback(get(obj, "message"));
				case "gameFeedback" -> sink.onGameFeedback(get(obj, "message"));
				case "pillMessage" -> sink.onPillMessage(get(obj, "label"), get(obj, "content"));
				case "error" -> {
					String code = get(obj, "code");
					LOGGER.warn("Bridge rejected connection: {}", code);
					running = false;
					sink.onConnectionRejected(code);
				}
				default -> {
					/* ignore unknown types */ }
			}
		} catch (RuntimeException e) {
			LOGGER.debug("Ignoring malformed inbound payload", e);
		}
	}

	private static String get(JsonObject obj, String key) {
		return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
	}

	private static int getInt(JsonObject obj, String key, int fallback) {
		try {
			return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static PartyInfo parseParty(JsonObject obj) {
		return new PartyInfo(getInt(obj, "id", 0), get(obj, "raid"), get(obj, "host"), getStringArray(obj, "members"), getInt(obj, "max", 4), get(obj, "note"));
	}

	private static java.util.List<PartyInfo> parsePartyList(JsonObject obj) {
		java.util.List<PartyInfo> out = new java.util.ArrayList<>();
		if (obj.has("parties") && obj.get("parties").isJsonArray()) {
			for (var element : obj.get("parties").getAsJsonArray()) {
				if (element.isJsonObject()) {
					out.add(parseParty(element.getAsJsonObject()));
				}
			}
		}
		return out;
	}

	private static java.util.List<PendingEntry> parsePendingEntries(JsonObject obj) {
		java.util.List<PendingEntry> out = new java.util.ArrayList<>();
		if (obj.has("members") && obj.get("members").isJsonArray()) {
			for (var element : obj.get("members").getAsJsonArray()) {
				if (element.isJsonObject()) {
					JsonObject member = element.getAsJsonObject();
					out.add(new PendingEntry(get(member, "name"), getInt(member, "aspects", 0)));
				}
			}
		}
		return out;
	}

	private static java.util.List<String> getStringArray(JsonObject obj, String key) {
		java.util.List<String> out = new java.util.ArrayList<>();
		if (obj.has(key) && obj.get(key).isJsonArray()) {
			for (var element : obj.get(key).getAsJsonArray()) {
				if (!element.isJsonNull()) {
					out.add(element.getAsString());
				}
			}
		}
		return out;
	}
}
