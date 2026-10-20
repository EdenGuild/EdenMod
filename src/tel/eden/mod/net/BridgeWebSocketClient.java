package tel.eden.mod.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import tel.eden.mod.EdenLogger;
import tel.eden.mod.chat.PlayerNameResolver;
import tel.eden.mod.guild.GuildLogEvent;
import tel.eden.mod.guild.GuildManageSnapshot;

/**
 * Raw-WebSocket client to the bridge backend.
 *
 * <p>Refuses to connect over non-TLS; proves the connection via a Mojang session
 * challenge/response (no bearer token); reconnects with exponential backoff while
 * running; forwards inbound
 * {@code discordMessage} events to a sink and sends captured guild chat outbound.
 */
public final class BridgeWebSocketClient {
	private static final EdenLogger LOGGER = EdenLogger.get();
	private static final int MAX_BACKOFF_SECONDS = 60;
	private static final int MAX_SESSION_VERIFY_RETRIES = 3;
	private static final Pattern SERVER_ID_PATTERN = Pattern.compile("[0-9a-fA-F\\-]{1,128}");
	/**
	 * Capability names this build understands. Declared to the server; never
	 * offered back. Kept as reusable feature-flag infrastructure even when
	 * empty — do not remove this mechanism just because no flag currently needs
	 * it (the last one, "guildLogSync", was retired once the server-side code
	 * that checked for it was removed).
	 */
	private static final java.util.Set<String> SUPPORTED_FEATURES = java.util.Set.of();

	/** Callbacks for inbound bridge events (delivered off the game thread). */
	public interface MessageSink {
		// Every display method takes an optional {@code color} ("RRGGBB", "" for none):
		// when set, the backend wants the whole rendered line painted in that colour, so
		// message colours can be retuned server-side without shipping a new mod.

		/**
		 * A relayed Discord message. {@code replyTo}/{@code replyExcerpt} are non-empty
		 * when the Discord message was a reply (the replied-to author and a short quote).
		 */
		default void onDiscordMessage(String author, String content, String replyTo, String replyExcerpt, String color) {
			onDiscordMessage(author, content, replyTo, replyExcerpt, color, null);
		}

		void onDiscordMessage(String author, String content, String replyTo, String replyExcerpt, String color, String messageId);

		/** A bridge user just logged in (presence notice). */
		void onLoginNotice(String username, String color);

		/** A bridge user fully disconnected (presence notice). */
		void onLogoutNotice(String username, String color);

		/** Response to a {@code /eden online} request: the connected bridge users. */
		void onOnlineList(java.util.List<String> users, String color);

		/**
		 * Response to {@code /eden aspects pending}: each member's pending aspects, or
		 * an {@code error} (e.g. not a Chief) when the request was refused.
		 */
		void onAspectsPending(java.util.List<PendingEntry> entries, String error, String color);

		/** Emerald counterpart of {@link #onAspectsPending} — real (not Liquid Emerald) units. */
		void onEmeraldsPending(java.util.List<PendingEntry> entries, String error, String color);

		/**
		 * Response to an {@code aspectGiveawayRequest}: the whole current member list (the
		 * screen filters it) plus the guild's current aspect stock, or an {@code error}
		 * (e.g. not a Chief) when the request was refused.
		 */
		void onAspectGiveaway(java.util.List<GiveawayCandidate> candidates, int storageAspects, String error, String color);

		/**
		 * Response to a {@code rewardDeductRequest}: on success {@code error} is empty
		 * and target/kind/amount/remaining carry the display-unit values the backend
		 * applied. On failure only {@code error} is set — the reply carries no target,
		 * kind or amount, so the caller has to remember what it asked for.
		 */
		void onRewardDeductReply(String target, String rewardKind, int amount, int remaining, String error, String color);

		/**
		 * Response to a {@code giftLockAcquire}: {@code granted} says whether the caller
		 * may (continue to) drive the in-game gifting automation. A refusal carries
		 * {@code error}; when refused because another Chief already holds it, {@code
		 * holder} names them and {@code retryAfterSeconds} is an upper bound on how long
		 * until their hold's own inactivity timeout would free it.
		 */
		void onGiftLockReply(boolean granted, String error, String holder, int retryAfterSeconds);

		/** A raid party changed state ({@code open}/{@code join}/{@code full}/etc.). */
		void onPartyUpdate(String event, String actor, PartyInfo party, String color);

		/** Response to a {@code /eden party list} request: the open raid parties. */
		void onPartyList(java.util.List<PartyInfo> parties, String color);

		/** A short result line for a party action the player just took in-game. */
		void onPartyFeedback(String message, String color);

		/** Feedback on game commands (e.g. cooldown). */
		void onGameFeedback(String message, String color);

		/**
		 * The bridge server rejected the connection. {@code code} is either the
		 * application-level error code from the server ({@code "version_rejected"},
		 * {@code "not_member"}) or {@code "http_<status>"} for a rejection at the
		 * HTTP handshake itself (e.g. a proxy or outage, not the mod's session).
		 */
		void onConnectionRejected(String code);

		/**
		 * A pill bridge line (Quick Reactions, {@code /eden cf}/{@code diceroll}, daily
		 * announcements): a pill labelled {@code label}, then {@code content}.
		 * {@code colorHex} is an optional {@code "RRGGBB"} override; empty means the
		 * default gold. {@code bold} makes a message that needs to stand out further
		 * than color alone (e.g. the storage-nearly-full warning).
		 */
		void onPillMessage(String label, String content, String colorHex, boolean bold);

		/**
		 * Response to a {@code warCountsRequest}: per-member war counts over the last
		 * {@code days} days (backend-authoritative, same source as the Discord
		 * {@code /eden wars}). {@code requester} is this player's username so their
		 * own row can be highlighted/cached.
		 */
		void onWarCounts(int days, java.util.List<WarCountEntry> entries, String requester, String color);

		/**
		 * The live war board changed: each attacked territory's scraped defence rating
		 * (from someone's {@code /guild attack} menu) and who is heading there. A full
		 * snapshot — replace, don't merge, the who's-going state.
		 */
		void onWarBoard(java.util.List<WarBoardEntry> entries);

		/**
		 * The Mojang session was verified ({@code authOk}); {@code linked} and
		 * {@code member} report this player's backend standing. Both true means full
		 * bridge access (begin the session); otherwise the socket stays open with no
		 * guild access and the mod should prompt the player (link / not in the guild).
		 * {@code guildRank} (e.g. "Chief") and {@code discordRank} (e.g. "Senate") are
		 * empty strings when not applicable.
		 */
		void onAuthStatus(boolean linked, boolean member, String guildRank, String discordRank);
	}

	/**
	 * Proves the connecting player holds a live Minecraft session (the v2 Mojang
	 * handshake). Given the server's {@code serverId} nonce it performs a Mojang
	 * {@code joinServer} for the current account and returns the IGN to report; the
	 * bridge then confirms that server-side via {@code hasJoined}. The access token
	 * goes only to Mojang, never to the bridge.
	 */
	public interface SessionAuthenticator {
		/** Complete {@code joinServer} for {@code serverId}; return the IGN to report. */
		String joinServer(String serverId) throws Exception;
	}

	private final URI uri;
	private final String modVersion;
	private final MessageSink sink;
	private final SessionAuthenticator authenticator;
	private final HttpClient http = HttpClient.newHttpClient();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "eden-bridge-ws");
		t.setDaemon(true);
		return t;
	});

	private volatile WebSocket socket;
	// Tail of the serialized outbound send queue — see enqueueSend().
	private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
	private volatile boolean running;
	private int backoffSeconds = 1;
	private boolean reconnectScheduled;
	private final AtomicBoolean authChallengeSeen = new AtomicBoolean(false);
	private volatile String pendingAuthUsername;
	private int sessionVerifyRetries = 0;

	private BridgeWebSocketClient(URI uri, String modVersion, MessageSink sink, SessionAuthenticator authenticator) {
		this.uri = uri;
		this.modVersion = modVersion;
		this.sink = sink;
		this.authenticator = authenticator;
	}

	/**
	 * Create a client for {@code backendBaseUrl} (an https:// URL). Connects to the
	 * Mojang-authenticated {@code /ws/v2} endpoint; the server verifies the Minecraft
	 * session and reports standing via {@link MessageSink#onAuthStatus} (on
	 * {@code authOk}). {@code authenticator} answers the server's session challenge.
	 *
	 * @throws IllegalArgumentException if the URL is not https (TLS is required)
	 */
	public static BridgeWebSocketClient create(String backendBaseUrl, String modVersion, MessageSink sink, SessionAuthenticator authenticator) {
		String base = backendBaseUrl.strip();
		if (!base.startsWith("https://")) {
			throw new IllegalArgumentException("bridge backend must be https (refusing non-TLS)");
		}
		String wss = "wss://" + base.substring("https://".length());
		if (wss.endsWith("/")) {
			wss = wss.substring(0, wss.length() - 1);
		}
		return new BridgeWebSocketClient(URI.create(wss + "/ws/v2"), modVersion, sink, authenticator);
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
		socket = null;
		if (current != null) {
			current.sendClose(WebSocket.NORMAL_CLOSURE, "client closing");
		}
		scheduler.shutdownNow();
	}

	/**
	 * Queue {@code text} to send on {@code ws}, serialized against every other send.
	 * {@code WebSocket.sendText} allows only one outstanding send at a time, but sends
	 * land here from several different threads (the gifting worker thread, the network
	 * thread relaying guild chat, the session-auth thread, ...) — without this, a race
	 * between two of them throws or silently drops whichever one loses, with no
	 * exception and no server-side trace, since the frame never actually goes out.
	 * Chaining instead of blocking keeps every caller non-blocking; {@code handle}
	 * swallows a prior failure so one bad send can't wedge every send after it.
	 */
	private synchronized void enqueueSend(WebSocket ws, String text) {
		sendChain = sendChain.handle((v, ex) -> null).thenCompose(ignored -> ws.sendText(text, true));
	}

	/** Send one captured guild-chat line to the backend. */
	public void sendGuildChat(String username, String nickname, String message, int seq) {
		sendGuildChat(username, nickname, message, seq, null);
	}

	/** Send one captured guild-chat line to the backend, optionally referencing a replied-to Discord message. */
	public void sendGuildChat(String username, String nickname, String message, int seq, String replyToMessageId) {
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
		if (replyToMessageId != null && !replyToMessageId.isEmpty()) {
			obj.addProperty("replyToMessageId", replyToMessageId);
		}
		enqueueSend(current, obj.toString());
	}

	/**
	 * Relay the guild's alliance list, read whole from the in-game Diplomacy menu. The
	 * backend replaces its stored list with this, so it is only ever sent for a menu the
	 * mod parsed in full.
	 *
	 * @return whether the message was queued on a connected socket
	 */
	public boolean sendGuildAlliances(java.util.List<String> guilds, java.util.List<String> guildTags) {
		WebSocket current = socket;
		if (current == null) {
			return false;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "guildAlliances");
		// Names and tags travel as two arrays paired by position; the backend drops the tags
		// outright if the lengths disagree rather than mislabelling a guild.
		JsonArray names = new JsonArray();
		for (String guild : guilds) {
			names.add(guild);
		}
		JsonArray tags = new JsonArray();
		for (String tag : guildTags) {
			tags.add(tag);
		}
		obj.add("guilds", names);
		obj.add("tags", tags);
		enqueueSend(current, obj.toString());
		return true;
	}

	/** Report one typed root Guild Manage snapshot — season/weekly/territory status, surfaced on "Eden Standings". */
	public void sendGuildManageStatus(GuildManageSnapshot snapshot) {
		WebSocket current = socket;
		if (current == null || snapshot == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "guildManageStatus");
		obj.addProperty("version", 1);
		if (snapshot.seasonNumber() != null) {
			obj.addProperty("seasonNumber", snapshot.seasonNumber());
		}
		if (snapshot.seasonEndsIn() != null) {
			obj.addProperty("seasonEndsIn", snapshot.seasonEndsIn());
		}
		if (snapshot.seasonRating() != null) {
			obj.addProperty("seasonRating", snapshot.seasonRating());
		}
		if (snapshot.seasonPosition() != null) {
			obj.addProperty("seasonPosition", snapshot.seasonPosition());
		}
		if (snapshot.weeklyCompleted() != null) {
			obj.addProperty("weeklyCompleted", snapshot.weeklyCompleted());
		}
		if (snapshot.weeklyGoal() != null) {
			obj.addProperty("weeklyGoal", snapshot.weeklyGoal());
		}
		if (snapshot.territoryCount() != null) {
			obj.addProperty("territoryCount", snapshot.territoryCount());
		}
		enqueueSend(current, obj.toString());
	}

	/**
	 * Report a raid completion. {@code extraPlayers} is a ranked shortlist of players seen
	 * around this client during the raid that the announcement did not name — candidates
	 * for the allied guild members Wynncraft omits. Only a client that was in the raid can
	 * observe them, so the backend deliberately keeps them out of its cross-client
	 * consensus (nobody else can corroborate what they never saw), verifies each against
	 * the alliance list, and uses the survivors only to complete the party for display.
	 */
	public void sendRaidCompletion(java.util.List<String> party, String raidName, int aspects, int emeralds, String guildExp, java.util.List<String> extraPlayers) {
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
		if (!extraPlayers.isEmpty()) {
			JsonArray extras = new JsonArray();
			for (String extra : extraPlayers) {
				extras.add(extra);
			}
			obj.add("extraPlayers", extras);
		}
		obj.addProperty("raidName", raidName);
		obj.addProperty("aspects", aspects);
		obj.addProperty("emeralds", emeralds);
		obj.addProperty("guildExp", guildExp);
		enqueueSend(current, obj.toString());
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
		enqueueSend(current, obj.toString());
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

	/** Ask the backend for each member's pending emeralds (Chiefs only). */
	public void sendEmeraldsPendingRequest() {
		sendType("emeraldsPendingRequest");
	}

	/** Ask the backend for the member list + aspect stock for the giveaway screen (Chiefs only). */
	public void sendAspectGiveawayRequest() {
		sendType("aspectGiveawayRequest");
	}

	/**
	 * Ask the backend to deduct {@code amount} pending rewards from {@code target}
	 * as a deliberate manual correction (Chiefs only; the backend authorises by this connection's
	 * verified guild rank, established at the {@code /ws/v2} handshake). The amount
	 * is in the same display units the Discord side shows, not internal sub-units.
	 * Returns false when the socket is down (mid-reconnect included), so the caller can
	 * offer the manual route instead of waiting for a reply that will never come.
	 */
	public boolean sendRewardDeductRequest(String rewardKind, String target, int amount, boolean manual) {
		WebSocket current = socket;
		if (current == null) {
			return false;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "rewardDeductRequest");
		obj.addProperty("rewardKind", rewardKind);
		obj.addProperty("target", target);
		obj.addProperty("amount", amount);
		if (manual) {
			obj.addProperty("source", "manual");
		}
		enqueueSend(current, obj.toString());
		return true;
	}

	/**
	 * Ask the backend for exclusive use of the in-game gifting automation (Chiefs
	 * only) — see {@link MessageSink#onGiftLockReply}. Also doubles as a renewal: a
	 * call from whoever already holds it just extends the hold rather than being
	 * refused as "already held". Returns false when the socket is down, so the
	 * caller can fail closed instead of waiting for a reply that will never come.
	 */
	public boolean sendGiftLockAcquire() {
		return sendTypeIfConnected("giftLockAcquire");
	}

	/**
	 * Tell the backend a gifting run has ended (success, failure, or interruption),
	 * releasing the lock if this player holds it. Best-effort and fire-and-forget:
	 * the lock also auto-expires server-side, so a socket that's already down here
	 * needs no special handling — that case is exactly what the expiry is for.
	 */
	public void sendGiftLockRelease() {
		sendType("giftLockRelease");
	}

	private boolean sendTypeIfConnected(String type) {
		WebSocket current = socket;
		if (current == null) {
			return false;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", type);
		enqueueSend(current, obj.toString());
		return true;
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
		enqueueSend(current, obj.toString());
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
		enqueueSend(current, obj.toString());
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
		enqueueSend(current, obj.toString());
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
		enqueueSend(current, obj.toString());
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
	 * Report a detected guild war: the territory and the players seen fighting in it
	 * (this client included). The backend merges reports from every attending client
	 * and attaches the party to the territory-capture embed; it never affects the
	 * authoritative war counts. Returns false when the socket is down (caller queues).
	 */
	public boolean sendWarAttended(String territory, java.util.List<String> members) {
		WebSocket current = socket;
		if (current == null) {
			return false;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "warAttended");
		obj.addProperty("territory", territory);
		com.google.gson.JsonArray array = new com.google.gson.JsonArray();
		for (String member : members) {
			array.add(member);
		}
		obj.add("members", array);
		enqueueSend(current, obj.toString());
		return true;
	}

	/**
	 * Report a territory's defence rating scraped from the {@code /guild attack} menu.
	 * The backend caches it and broadcasts it to every member's attack-timer HUD, so a
	 * member who never opened the menu still sees the freshest defence intel.
	 *
	 * @return whether the message was queued on a connected socket
	 */
	public boolean sendWarDefense(String territory, String defense) {
		WebSocket current = socket;
		if (current == null) {
			return false;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "warDefense");
		obj.addProperty("territory", territory);
		obj.addProperty("defense", defense);
		enqueueSend(current, obj.toString());
		return true;
	}

	/**
	 * Toggle this player's "heading to {@code territory}" marker (right-click on a timer
	 * row). One assignment per player: marking a new territory moves them, re-marking the
	 * current one clears it. The backend broadcasts the updated board to everyone.
	 */
	public void sendWarGoing(String territory) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "warGoing");
		obj.addProperty("territory", territory);
		enqueueSend(current, obj.toString());
	}

	/**
	 * Report whether this player is currently inside the territory they marked heading to,
	 * so their head border shows green (inside) or red (en route) on every member's HUD.
	 */
	public void sendWarGoingInside(boolean inside) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "warGoingInside");
		obj.addProperty("inside", inside);
		enqueueSend(current, obj.toString());
	}

	/**
	 * Tell the backend a territory's attack timer has ended (no longer on the scoreboard),
	 * so it clears that territory's cached defence/conflict/who's-going and the next war on
	 * it starts fresh.
	 */
	public void sendWarTimerEnded(String territory) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "warTimerEnded");
		obj.addProperty("territory", territory);
		enqueueSend(current, obj.toString());
	}

	/** Ask for the guild's per-member war counts over the last {@code days} days. */
	public void sendWarCountsRequest(int days) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "warCountsRequest");
		obj.addProperty("days", days);
		enqueueSend(current, obj.toString());
	}

	/**
	 * Tell the server whether this client is active in a game world ({@code true})
	 * or dormant (in queue, hub, or AFK with no recent guild activity — {@code false}).
	 */
	public void sendPresence(boolean active) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "presence");
		obj.addProperty("active", active);
		enqueueSend(current, obj.toString());
	}

	private void sendType(String type) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", type);
		enqueueSend(current, obj.toString());
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
		enqueueSend(current, obj.toString());
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
		enqueueSend(current, obj.toString());
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
		enqueueSend(current, obj.toString());
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
		enqueueSend(current, obj.toString());
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
		enqueueSend(current, obj.toString());
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
		enqueueSend(current, obj.toString());
	}

	/**
	 * Send a bounded, typed Guild Log snapshot for the (currently dormant) bank
	 * fallback path. Raw menu lore never leaves the client; raids intentionally
	 * remain local until their reconciliation rules are designed separately.
	 * Reward rows are still encoded below (the backend simply doesn't act on them
	 * — see WebSocketRelay._on_guild_log_snapshot) rather than filtered here, so
	 * this stays a faithful, general-purpose snapshot of what the mod actually saw.
	 */
	public void sendGuildLogSnapshot(String category, java.util.List<GuildLogEvent> events) {
		WebSocket current = socket;
		String source = switch (category) {
			case "General" -> "general";
			case "Public Bank" -> "publicBank";
			case "High Ranked Bank" -> "highRankedBank";
			default -> null;
		};
		if (current == null || source == null) {
			return;
		}
		JsonArray encoded = new JsonArray();
		for (GuildLogEvent event : events) {
			JsonObject entry = new JsonObject();
			if (event instanceof GuildLogEvent.Reward reward) {
				// Guild Log lore carries no hover data, so an unresolved name here is a
				// player never seen in chat and not currently colocated — better to omit
				// the reconciliation data point than send a raw nickname the backend can't
				// act on (it would fail the username lookup and silently no-op).
				java.util.Optional<String> giver = PlayerNameResolver.resolveKnown(reward.giver());
				java.util.Optional<String> receiver = PlayerNameResolver.resolveKnown(reward.receiver());
				if (giver.isEmpty() || receiver.isEmpty()) {
					continue;
				}
				entry.addProperty("kind", "reward");
				entry.addProperty("occurredAt", reward.occurredAt());
				entry.addProperty("giver", giver.get());
				entry.addProperty("receiver", receiver.get());
				entry.addProperty("reward", reward.kind());
				entry.addProperty("amount", reward.amount());
			} else if (event instanceof GuildLogEvent.Bank bank) {
				java.util.Optional<String> player = PlayerNameResolver.resolveKnown(bank.actor());
				if (player.isEmpty()) {
					continue;
				}
				entry.addProperty("kind", "bank");
				entry.addProperty("occurredAt", bank.occurredAt());
				entry.addProperty("player", player.get());
				entry.addProperty("action", bank.action());
				if (bank.quantity() != null) {
					entry.addProperty("quantity", bank.quantity());
				}
				entry.addProperty("item", bank.item());
				if (bank.charges() != null) {
					entry.addProperty("charges", bank.charges());
				}
				entry.addProperty("accessTier", bank.accessTier());
			} else if (event instanceof GuildLogEvent.Raid raid) {
				entry.addProperty("kind", "raid");
				entry.addProperty("occurredAt", raid.occurredAt());
				JsonArray participants = new JsonArray();
				for (String participant : raid.participants()) {
					participants.add(PlayerNameResolver.canonicalize(participant));
				}
				entry.add("participants", participants);
				entry.addProperty("raid", raid.raid());
				if (raid.aspects() != null) {
					entry.addProperty("aspects", raid.aspects());
				}
				if (raid.emeralds() != null) {
					entry.addProperty("emeralds", raid.emeralds());
				}
				if (raid.guildExperienceMillions() != null) {
					entry.addProperty("guildExperienceMillions", raid.guildExperienceMillions());
				}
				if (raid.seasonalRating() != null) {
					entry.addProperty("seasonalRating", raid.seasonalRating());
				}
			} else {
				continue;
			}
			encoded.add(entry);
		}
		if (encoded.isEmpty()) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "guildLogSnapshot");
		obj.addProperty("version", 1);
		obj.addProperty("source", source);
		obj.add("events", encoded);
		enqueueSend(current, obj.toString());
	}

	/** Report that the running jar failed its boot-time Sigstore attestation check. */
	public void sendAttestationFailure(String sha) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "attestationFailure");
		obj.addProperty("sha", sha);
		enqueueSend(current, obj.toString());
	}

	/** Relay the guild's current reward storage (aspects/tomes/emeralds) for the live counter. */
	public void sendGuildStorage(int aspects, int tomes, long emeralds) {
		WebSocket current = socket;
		if (current == null) {
			return;
		}
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "guildStorage");
		obj.addProperty("aspects", aspects);
		obj.addProperty("tomes", tomes);
		obj.addProperty("emeralds", emeralds);
		enqueueSend(current, obj.toString());
	}

	private void connect() {
		if (!running) {
			return;
		}
		http.newWebSocketBuilder().header("X-Mod-Version", modVersion).connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, new Listener()).whenComplete((ws, error) -> {
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
					// 4xx = permanent rejection at the handshake itself; version/membership/
					// session errors are all sent as application-level messages instead, so
					// only proxy- or outage-level failures reach here.
					rejectConnection("http_" + status);
					return;
				}
				LOGGER.warn("Bridge WebSocket connect failed: {}", error.toString());
				scheduleReconnect(null);
			} else {
				LOGGER.info("Bridge WebSocket connected; awaiting session challenge");
				// The socket is open but not yet trusted: the server sends an
				// authChallenge, and only on authOk do we reset backoff and hand the
				// verified standing to onAuthStatus. See handlePayload.
			}
		});
	}

	private synchronized void scheduleReconnect(WebSocket disconnected) {
		// Ignore terminal callbacks from an older socket. Without this identity check, a
		// delayed onClose can clear a replacement connection that is already authenticating.
		if (disconnected != null && socket != disconnected) {
			return;
		}
		socket = null;
		if (!running || reconnectScheduled) {
			return;
		}
		reconnectScheduled = true;
		int delay = backoffSeconds;
		backoffSeconds = Math.min(backoffSeconds * 2, MAX_BACKOFF_SECONDS);
		scheduler.schedule(this::runScheduledReconnect, delay, TimeUnit.SECONDS);
	}

	private void runScheduledReconnect() {
		synchronized (this) {
			reconnectScheduled = false;
			if (!running) {
				return;
			}
		}
		connect();
	}

	/** Permanently stop this client after a server rejection and release its executor. */
	private void rejectConnection(String code) {
		WebSocket current;
		synchronized (this) {
			running = false;
			current = socket;
			socket = null;
			scheduler.shutdownNow();
		}
		if (current != null) {
			current.sendClose(WebSocket.NORMAL_CLOSURE, "connection rejected");
		}
		sink.onConnectionRejected(code);
	}

	private final class Listener implements WebSocket.Listener {
		private final StringBuilder buffer = new StringBuilder();

		@Override
		public void onOpen(WebSocket webSocket) {
			boolean accepted;
			synchronized (BridgeWebSocketClient.this) {
				accepted = running && (socket == null || socket == webSocket);
				if (accepted) {
					authChallengeSeen.set(false);
					pendingAuthUsername = null;
					socket = webSocket;
				}
			}
			if (!accepted) {
				webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "stale connection");
				return;
			}
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
			scheduleReconnect(webSocket);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			LOGGER.warn("Bridge WebSocket error: {}", error.toString());
			scheduleReconnect(webSocket);
		}
	}

	private void handlePayload(String payload) {
		try {
			JsonObject obj = JsonParser.parseString(payload).getAsJsonObject();
			switch (get(obj, "type")) {
				case "discordMessage" -> sink.onDiscordMessage(get(obj, "author"), get(obj, "content"), get(obj, "replyTo"), get(obj, "replyExcerpt"), get(obj, "color"), get(obj, "messageId"));
				case "loginNotice" -> sink.onLoginNotice(get(obj, "username"), get(obj, "color"));
				case "logoutNotice" -> sink.onLogoutNotice(get(obj, "username"), get(obj, "color"));
				case "onlineList" -> sink.onOnlineList(getStringArray(obj, "users"), get(obj, "color"));
				case "aspectsPendingReply" -> sink.onAspectsPending(parsePendingEntries(obj, "aspects"), get(obj, "error"), get(obj, "color"));
				case "emeraldsPendingReply" -> sink.onEmeraldsPending(parsePendingEntries(obj, "emeralds"), get(obj, "error"), get(obj, "color"));
				case "aspectGiveawayReply" -> sink.onAspectGiveaway(parseGiveawayCandidates(obj), getInt(obj, "storageAspects", 0), get(obj, "error"), get(obj, "color"));
				case "rewardDeductReply" -> sink.onRewardDeductReply(get(obj, "target"), get(obj, "rewardKind"), getInt(obj, "amount", 0), getInt(obj, "remaining", 0), get(obj, "error"), get(obj, "color"));
				case "giftLockReply" -> sink.onGiftLockReply(getBool(obj, "granted"), get(obj, "error"), get(obj, "holder"), getInt(obj, "retryAfterSeconds", 0));
				case "partyUpdate" -> sink.onPartyUpdate(get(obj, "event"), get(obj, "actor"), parseParty(obj), get(obj, "color"));
				case "partyListReply" -> sink.onPartyList(parsePartyList(obj), get(obj, "color"));
				case "partyFeedback" -> sink.onPartyFeedback(get(obj, "message"), get(obj, "color"));
				case "gameFeedback" -> sink.onGameFeedback(get(obj, "message"), get(obj, "color"));
				case "pillMessage" -> sink.onPillMessage(get(obj, "label"), get(obj, "content"), get(obj, "color"), getBool(obj, "bold"));
				case "warCountsReply" -> sink.onWarCounts(getInt(obj, "days", 7), parseWarCounts(obj), get(obj, "requester"), get(obj, "color"));
				case "warBoard" -> sink.onWarBoard(parseWarBoard(obj));
				case "authChallenge" -> handleAuthChallenge(get(obj, "serverId"));
				case "authOk" -> {
					// Session verified: reset backoff and hand the backend-reported
					// standing (linked/member/ranks) to the sink, which begins the session
					// on full access or prompts the player otherwise.
					boolean linked = getBool(obj, "linked");
					boolean member = getBool(obj, "member");
					String guildRank = cap(get(obj, "guildRank"), 64);
					String discordRank = cap(get(obj, "discordRank"), 64);
					LOGGER.info("Bridge session verified as {} (linked={} member={})", pendingAuthUsername != null ? pendingAuthUsername : "?", linked, member);
					sessionVerifyRetries = 0;
					backoffSeconds = 1;
					try {
						sink.onAuthStatus(linked, member, guildRank, discordRank);
					} catch (RuntimeException e) {
						LOGGER.warn("onAuthStatus callback failed", e);
					}
				}
				case "error" -> {
					String code = get(obj, "code");
					if (isTransientAuthError(code)) {
						// Retryable (Mojang hiccup, rate limit, slow handshake): keep
						// running so the imminent onClose schedules a backoff reconnect.
						LOGGER.warn("Bridge transient auth error: {} (will retry)", code);
					} else if ("session_unverified".equals(code) && sessionVerifyRetries < MAX_SESSION_VERIFY_RETRIES) {
						sessionVerifyRetries++;
						LOGGER.warn("Session verification failed for {} (attempt {}/{}): Mojang hasJoined returned nothing — retrying after reconnect", pendingAuthUsername != null ? pendingAuthUsername : "?", sessionVerifyRetries, MAX_SESSION_VERIFY_RETRIES);
						// Leave running=true so the imminent onClose schedules a reconnect.
					} else {
						LOGGER.warn("Bridge rejected connection: {}", code);
						rejectConnection(code);
					}
				}
				default -> {
					/* ignore unknown types */ }
			}
		} catch (RuntimeException e) {
			LOGGER.debug("Ignoring malformed inbound payload", e);
		}
	}

	/**
	 * Answer the server's session challenge on a background thread: perform the
	 * Mojang {@code joinServer} for {@code serverId}, then report the IGN so the
	 * bridge can confirm it via {@code hasJoined}. Runs off the read thread because
	 * {@code joinServer} does network I/O; failures just leave the connection
	 * unverified (the server times it out and closes).
	 */
	private void handleAuthChallenge(String serverId) {
		if (serverId == null || serverId.isEmpty() || !SERVER_ID_PATTERN.matcher(serverId).matches()) {
			return;
		}
		if (!authChallengeSeen.compareAndSet(false, true)) {
			LOGGER.warn("Ignoring repeated authChallenge (one per connection)");
			return;
		}
		Thread thread = new Thread(() -> {
			WebSocket current = socket;
			if (current == null) {
				return;
			}
			try {
				String username = authenticator.joinServer(serverId);
				if (username == null || username.isEmpty()) {
					LOGGER.warn("Session authenticator returned no username; cannot verify");
					return;
				}
				pendingAuthUsername = username;
				JsonObject obj = new JsonObject();
				obj.addProperty("type", "authResponse");
				obj.addProperty("username", username);
				// Capability self-declaration — a flat, static list of whatever this
				// jar was compiled with, regardless of what the server does with it —
				// rides on this, the very first message the mod ever sends, so the
				// server can log it in the same "Mod connected" line instead of a
				// separately-timed one from its own message. The server never
				// announces anything back; it alone decides which declared names it
				// actually trusts and acts on.
				JsonArray supported = new JsonArray();
				for (String feature : SUPPORTED_FEATURES) {
					supported.add(feature);
				}
				obj.add("supported", supported);
				enqueueSend(current, obj.toString());
			} catch (Exception e) {
				LOGGER.warn("Mojang session join failed: {}", e.toString());
			}
		}, "eden-session-auth");
		thread.setDaemon(true);
		thread.start();
	}

	/** Whether a server {@code error} code is retryable rather than a hard rejection. */
	private static boolean isTransientAuthError(String code) {
		return "session_auth_unavailable".equals(code) || "session_auth_timeout".equals(code) || "rate_limited".equals(code);
	}

	private static String cap(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}

	private static String get(JsonObject obj, String key) {
		return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
	}

	private static boolean getBool(JsonObject obj, String key) {
		try {
			return obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).getAsBoolean();
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static int getInt(JsonObject obj, String key, int fallback) {
		try {
			return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static long getLong(JsonObject obj, String key, long fallback) {
		try {
			return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : fallback;
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

	private static java.util.List<WarCountEntry> parseWarCounts(JsonObject obj) {
		java.util.List<WarCountEntry> out = new java.util.ArrayList<>();
		if (obj.has("members") && obj.get("members").isJsonArray()) {
			for (var element : obj.get("members").getAsJsonArray()) {
				if (element.isJsonObject()) {
					JsonObject member = element.getAsJsonObject();
					out.add(new WarCountEntry(get(member, "name"), getInt(member, "wars", 0)));
				}
			}
		}
		return out;
	}

	private static java.util.List<WarBoardEntry> parseWarBoard(JsonObject obj) {
		java.util.List<WarBoardEntry> out = new java.util.ArrayList<>();
		if (obj.has("territories") && obj.get("territories").isJsonArray()) {
			for (var element : obj.get("territories").getAsJsonArray()) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject entry = element.getAsJsonObject();
				java.util.List<WarGoer> going = new java.util.ArrayList<>();
				if (entry.has("going") && entry.get("going").isJsonArray()) {
					for (var goerElement : entry.get("going").getAsJsonArray()) {
						if (goerElement.isJsonObject()) {
							JsonObject goer = goerElement.getAsJsonObject();
							going.add(new WarGoer(get(goer, "name"), get(goer, "uuid"), getBool(goer, "inside")));
						}
					}
				}
				out.add(new WarBoardEntry(get(entry, "territory"), get(entry, "defense"), getBool(entry, "conflict"), going));
			}
		}
		return out;
	}

	private static java.util.List<PendingEntry> parsePendingEntries(JsonObject obj, String amountKey) {
		java.util.List<PendingEntry> out = new java.util.ArrayList<>();
		if (obj.has("members") && obj.get("members").isJsonArray()) {
			for (var element : obj.get("members").getAsJsonArray()) {
				if (element.isJsonObject()) {
					JsonObject member = element.getAsJsonObject();
					out.add(new PendingEntry(get(member, "name"), getInt(member, amountKey, 0)));
				}
			}
		}
		return out;
	}

	private static java.util.List<GiveawayCandidate> parseGiveawayCandidates(JsonObject obj) {
		java.util.List<GiveawayCandidate> out = new java.util.ArrayList<>();
		if (obj.has("members") && obj.get("members").isJsonArray()) {
			for (var element : obj.get("members").getAsJsonArray()) {
				if (element.isJsonObject()) {
					JsonObject member = element.getAsJsonObject();
					out.add(new GiveawayCandidate(get(member, "name"), getLong(member, "contributedXp", 0L), get(member, "rank"), getLong(member, "lastSeenEpochMs", 0L), getBool(member, "blocked")));
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
