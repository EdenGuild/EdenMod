package tel.eden.mod.war;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.lwjgl.glfw.GLFW;
import tel.eden.mod.EdenLogger;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.hud.HudLayout;
import tel.eden.mod.hud.HudPanel;
import tel.eden.mod.hud.RectangleElement;
import tel.eden.mod.hud.TextElement;
import tel.eden.mod.net.BridgeWebSocketClient;
import tel.eden.mod.net.WarBoardEntry;
import tel.eden.mod.net.WarGoer;
import tel.eden.mod.net.WarQueuer;

/**
 * HUD list of upcoming territory attacks read from the scoreboard sidebar, each row
 * annotated with the territory's defense rating (from {@link TerritoryData}, overlaid
 * with fresher guild-chat intel). Clicking a row while chat is open points Wynncraft's
 * native compass at the territory. Feeds {@link #soonestTerritory()} for the beacon and
 * outline of the earliest attack.
 */
public final class AttackTimerMenu {
	private AttackTimerMenu() {
	}

	private static final String PANEL = "attackTimers";
	private static final float DEFAULT_X = 0.40f;
	private static final float DEFAULT_Y = 0.14f;
	private static final int ROW_HEIGHT = 11;
	private static final int PANEL_BG = 0x64000000;

	// Sidebar attack-timer line: "- 13:47 Territory Name" (dash/whitespace optional).
	private static final Pattern TIMER = Pattern.compile("^[-\\s]*(\\d{1,2}:\\d{2})\\s+(.+?)\\s*$");
	// A guild-chat defense report, e.g. "Detlas defense is High" (Wynntils' format).
	private static final Pattern DEFENSE_REPORT = Pattern.compile("^(.+?) defense is (Very Low|Low|Medium|High|Very High)\\b", Pattern.CASE_INSENSITIVE);

	private static final int HEAD_SIZE = 8;
	private static final int HEAD_GAP = 1;
	// A war party caps at 5, so at most five distinct heads ever need showing.
	private static final int MAX_HEADS = 5;
	// Head border: green once a goer/queuer is inside their target territory, red while en route.
	private static final int BORDER_GREEN = 0xFF44FF44;
	private static final int BORDER_RED = 0xFFFF5050;

	// Layout coordinates for the queuer head on the left and row text.
	private static final int QUEUER_HEAD_X = 3;
	private static final int TEXT_OFFSET_X = QUEUER_HEAD_X + HEAD_SIZE + 3; // 14px

	/** Latest fresher-than-scrape defense reports keyed by territory. */
	private static final Map<String, ChatDefenseInfo> chatDefenses = new HashMap<>();
	/**
	 * Defence scraped from the {@code /guild attack} menu, keyed by territory — from this
	 * client's own scrape or the backend's {@code warBoard} broadcast of another member's.
	 * Normally preferred over the chat report; the chat report wins only on a conflict.
	 */
	private static final Map<String, String> scrapedDefenses = new HashMap<>();
	/** Territories where scouts reported conflicting scraped ratings (from the board). */
	private static final Map<String, Boolean> defenseConflict = new HashMap<>();
	/** Who queued each attack (from the {@code warBoard} broadcast or local detection). */
	private static final Map<String, WarQueuer> queuers = new HashMap<>();
	/**
	 * Territories whose queuer only this client knows of (a chat report, or an attack the
	 * board hasn't echoed yet). Board-owned queuers follow the board; these are kept across
	 * board updates until the board names a queuer for them or they go stale.
	 */
	private static final java.util.Set<String> localQueuers = new java.util.HashSet<>();
	// When each local-only queuer was first seen with no attack timer on the sidebar.
	private static final Map<String, Long> localQueuerNoTimerSince = new HashMap<>();
	// How long a local-only queuer may sit with no attack timer before it is dropped: the
	// timer appears on the sidebar as soon as an attack is queued, so one that never shows
	// (or has since ended unseen) was never a real queue.
	private static final long LOCAL_QUEUER_NO_TIMER_MS = 30_000L;
	/** Who is heading to each territory (from the {@code warBoard} broadcast). */
	private static final Map<String, List<WarGoer>> going = new HashMap<>();
	/** Territories that had an attack timer last tick, to detect when one ends. */
	private static java.util.Set<String> previousActive = new java.util.HashSet<>();
	// When each absent territory was last seen, so a timer has to stay gone before it
	// counts as ended. Wynncraft rewrites a timer line every second as it counts down, and
	// removing then re-adding the scoreboard entry leaves a frame where the line is simply
	// not there — indistinguishable, on a single frame, from the war starting.
	private static final java.util.Map<String, Long> missingSince = new java.util.HashMap<>();
	// How long a territory must stay off the sidebar to be believed gone. A re-render blip
	// is sub-second; a timer that really ended never comes back.
	private static final long TIMER_GONE_MS = 3_000L;
	// The countdown last read for each live timer, and when it was read.
	private static final Map<String, Integer> lastSeenSeconds = new HashMap<>();
	private static final Map<String, Long> lastSeenAt = new HashMap<>();
	// A timer that leaves the sidebar with this little time left went because its war
	// started. One that leaves with more left is only hidden here (the sidebar is showing
	// something else, such as a war or a raid) and must not end the war for everyone.
	private static final long ENDING_SOON_MS = 15_000L;
	// A hidden timer is held this long past the moment its war was due, then ended.
	private static final long HIDDEN_GRACE_MS = 10_000L;
	// When this client last clicked the attack slot of each territory's "Attacking:" menu.
	// A timer that appears for that territory soon after is this client's queue, even when
	// the attack sound never arrives. Requiring the click (not just an open menu) keeps a
	// scout who is only reading the defence from claiming someone else's queue.
	private static final Map<String, Long> attackClickAt = new HashMap<>();
	private static final long QUEUE_MATCH_MS = 5_000L;
	// Queues waiting to be reported, so the report can carry the new timer's countdown.
	// One whose timer never shows is sent without it after QUEUE_SEND_WAIT_MS.
	private static final Map<String, Long> pendingQueueSend = new HashMap<>();
	private static final long QUEUE_SEND_WAIT_MS = 3_000L;
	// Last inside-status this client reported per territory, and settle debounce state.
	private static final Map<String, Boolean> lastReportedInsideByTerritory = new HashMap<>();
	private static final Map<String, Boolean> pendingInsideByTerritory = new HashMap<>();
	private static final Map<String, Long> pendingSinceByTerritory = new HashMap<>();
	// A crossing must hold this long before it's reported, so wiggling on a territory edge
	// never spams updates. The settled value is what gets sent, so state can't desync.
	private static final long INSIDE_SETTLE_MS = 500L;
	/** Screen rectangles [x1,y1,x2,y2] of the last-rendered rows, for click hit-testing. */
	private static final Map<String, int[]> clickBoxes = new HashMap<>();
	/** Screen rectangles of the last-rendered heads and the names to show on hover. */
	private static final List<HeadBox> headBoxes = new ArrayList<>();
	private static volatile String soonestTerritory = null;
	// The scoreboard sidebar changes at most once per tick, but both the HUD (render()) and
	// the territory wall (attackedTerritories()) query it every frame. Memoize the scan per
	// game tick so the two callers, and multiple frames within a tick, share one parse.
	private static long cachedAttacksTick = Long.MIN_VALUE;
	private static List<Upcoming> cachedAttacks = List.of();

	private record Upcoming(String time, String territory) {
	}

	/** A drawn head's screen rectangle and the names it represents (for hover tooltips). */
	private record HeadBox(int x1, int y1, int x2, int y2, List<String> names) {
	}

	/** Territory with the earliest upcoming attack, or null. */
	public static String soonestTerritory() {
		return soonestTerritory;
	}

	/** Territories with an active queued attack right now, soonest first (from the
	 * scoreboard, independent of whether the HUD is shown). Empty when none. */
	public static List<String> attackedTerritories() {
		List<String> out = new ArrayList<>();
		for (Upcoming attack : currentAttacks()) {
			out.add(attack.territory());
		}
		return out;
	}

	/** The upcoming attacks, scanned at most once per game tick (shared across callers). */
	private static List<Upcoming> currentAttacks() {
		Minecraft mc = Minecraft.getInstance();
		long tick = mc.level != null ? mc.level.getGameTime() : Long.MIN_VALUE;
		if (tick != cachedAttacksTick) {
			cachedAttacks = upcomingAttacks();
			cachedAttacksTick = tick;
		}
		return cachedAttacks;
	}

	/** Record a guild-reported defense rating (from the chat-defense intake). */
	public static void reportDefense(String username, String territory, String defense) {
		chatDefenses.put(territory, new ChatDefenseInfo(username, territory, defense, System.currentTimeMillis()));
	}

	/** Record a war queuer locally (called when attack is confirmed or from chat fallback). */
	public static void reportQueued(String territory, String name, String uuid) {
		Minecraft mc = Minecraft.getInstance();
		boolean inside = false;
		if (mc.player != null && mc.player.getUUID().toString().equals(uuid)) {
			BlockPos pos = mc.player.blockPosition();
			inside = territory.equals(TerritoryData.territoryAt(pos.getX(), pos.getZ()));
		}
		queuers.put(territory, new WarQueuer(name, uuid, inside));
		localQueuers.add(territory);
	}

	/** Note that this client clicked the attack slot for {@code territory} (client thread). */
	public static void noteAttackClick(String territory) {
		attackClickAt.put(territory, System.currentTimeMillis());
	}

	/**
	 * This client queued an attack on {@code territory} (client thread). Shown at once;
	 * reported to the backend once the new timer is on the sidebar, so the report carries
	 * its countdown, or after {@link #QUEUE_SEND_WAIT_MS} without it.
	 */
	public static void confirmQueued(String territory) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		reportQueued(territory, mc.player.getGameProfile().name(), mc.player.getUUID().toString());
		pendingQueueSend.putIfAbsent(territory, System.currentTimeMillis());
		attackClickAt.remove(territory);
	}

	/** Whether this client queued the attack on {@code territory}. */
	public static boolean isQueuedByPlayer(String territory) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null) {
			return false;
		}
		return isQueuedBy(territory, mc.player.getUUID().toString(), mc.player.getGameProfile().name());
	}

	static boolean isQueuedBy(String territory, String uuid, String name) {
		if (territory == null) {
			return false;
		}
		WarQueuer q = queuers.get(territory);
		if (q == null) {
			for (Map.Entry<String, WarQueuer> entry : queuers.entrySet()) {
				if (entry.getKey().equalsIgnoreCase(territory)) {
					q = entry.getValue();
					break;
				}
			}
		}
		if (q == null) {
			return false;
		}
		return (uuid != null && uuid.equals(q.uuid())) || (name != null && name.equalsIgnoreCase(q.name()));
	}

	/**
	 * Parse a guild-chat line for a "&lt;territory&gt; defense is &lt;rating&gt;" report
	 * (as Wynntils announces) and fold it into the HUD's fresher-intel overlay.
	 */
	public static void intakeChat(String reporter, String message) {
		Matcher matcher = DEFENSE_REPORT.matcher(message.trim());
		if (matcher.find()) {
			String territory = matcher.group(1).trim();
			reportDefense(reporter, territory, canonicalDefense(matcher.group(2)));
			if (!queuers.containsKey(territory) && reporter != null && !reporter.isBlank()) {
				UUID uuid = resolvePlayerUuid(reporter.trim());
				if (uuid != null) {
					reportQueued(territory, reporter.trim(), uuid.toString());
				}
			}
		}
	}

	private static UUID resolvePlayerUuid(String name) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.getName().getString().equalsIgnoreCase(name)) {
			return mc.player.getUUID();
		}
		if (mc.getConnection() != null) {
			for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
				if (info.getProfile() != null && info.getProfile().name().equalsIgnoreCase(name)) {
					return info.getProfile().id();
				}
			}
		}
		return null;
	}

	private static String canonicalDefense(String defense) {
		return switch (defense.toLowerCase()) {
			case "very low" -> "Very Low";
			case "low" -> "Low";
			case "medium" -> "Medium";
			case "high" -> "High";
			case "very high" -> "Very High";
			default -> defense;
		};
	}

	/** Record a defence scraped locally from this client's {@code /guild attack} menu. */
	public static void reportScrapedDefense(String territory, String defense) {
		scrapedDefenses.put(territory, canonicalDefense(defense));
	}

	/**
	 * Apply a full {@code warBoard} snapshot from the backend. A full replace (not a merge)
	 * of who's-going, scraped defence, and the conflict flag, so a territory the backend
	 * dropped (its timer ended) also drops here.
	 */
	public static void updateBoard(List<WarBoardEntry> entries) {
		going.clear();
		scrapedDefenses.clear();
		defenseConflict.clear();
		// Only local-only queuers survive a board update; a board-owned one the board no
		// longer lists was dropped by the backend (its timer ended) and must go here too.
		Map<String, WarQueuer> localOnly = new HashMap<>();
		for (String territory : localQueuers) {
			WarQueuer queuer = queuers.get(territory);
			if (queuer != null) {
				localOnly.put(territory, queuer);
			}
		}
		queuers.clear();
		for (WarBoardEntry entry : entries) {
			if (!entry.defense().isEmpty()) {
				scrapedDefenses.put(entry.territory(), canonicalDefense(entry.defense()));
			}
			if (entry.conflict()) {
				defenseConflict.put(entry.territory(), Boolean.TRUE);
			}
			if (!entry.going().isEmpty()) {
				going.put(entry.territory(), entry.going());
			}
			if (entry.queuer() != null) {
				queuers.put(entry.territory(), entry.queuer());
			}
		}
		// A territory the board now names a queuer for is board-owned from here on.
		localOnly.keySet().removeIf(queuers::containsKey);
		localQueuers.retainAll(localOnly.keySet());
		localQueuerNoTimerSince.keySet().retainAll(localQueuers);
		queuers.putAll(localOnly);
	}

	/**
	 * Detect attack timers that have just ended and clear their state. When a territory
	 * leaves the scoreboard (its timer expired), drop this client's chat report for it and
	 * tell the backend to clear the shared defence/conflict/who's-going, so the next war on
	 * that territory starts fresh. Skipped entirely when the whole sidebar is gone (a world
	 * switch / disconnect), so that never looks like every war ending at once.
	 */
	public static void onTick(BridgeWebSocketClient socket) {
		Scoreboard scoreboard = sidebarScoreboard();
		if (scoreboard == null || scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) == null) {
			previousActive.clear();
			missingSince.clear();
			lastSeenSeconds.clear();
			lastSeenAt.clear();
			return;
		}
		Map<String, Integer> current = new HashMap<>();
		for (Upcoming attack : currentAttacks()) {
			current.put(attack.territory(), seconds(attack.time()));
		}
		long now = System.currentTimeMillis();
		TimerChanges changes = advanceTimers(current, now);
		for (String territory : changes.ended()) {
			chatDefenses.remove(territory);
			queuers.remove(territory);
			localQueuers.remove(territory);
			localQueuerNoTimerSince.remove(territory);
			lastReportedInsideByTerritory.remove(territory);
			pendingInsideByTerritory.remove(territory);
			pendingSinceByTerritory.remove(territory);
			pendingQueueSend.remove(territory);
			if (socket != null) {
				socket.sendWarTimerEnded(territory);
			}
		}
		for (String territory : matchQueues(changes.appeared(), now)) {
			confirmQueued(territory);
		}
		flushQueueSends(current, now, socket);
		dropStaleLocalQueuers(previousActive, now);
		reportInside(socket);
	}

	/** What one sidebar reading changed: timers that have ended, and ones that just appeared. */
	record TimerChanges(List<String> ended, List<String> appeared) {
	}

	/**
	 * Fold one sidebar reading ({@code territory -> seconds left}) into the live-timer
	 * state. Ending one clears everybody's heads for it on every client, so a timer only
	 * ends once it has stayed off the sidebar for {@link #TIMER_GONE_MS} and either it was
	 * about to run out when it went or its war is well past due.
	 */
	static TimerChanges advanceTimers(Map<String, Integer> current, long now) {
		// Anything back on the board plainly did not end.
		missingSince.keySet().removeAll(current.keySet());
		List<String> appeared = new ArrayList<>();
		for (Map.Entry<String, Integer> timer : current.entrySet()) {
			if (!previousActive.contains(timer.getKey())) {
				appeared.add(timer.getKey());
			}
			if (timer.getValue() != Integer.MAX_VALUE) {
				lastSeenSeconds.put(timer.getKey(), timer.getValue());
				lastSeenAt.put(timer.getKey(), now);
			}
		}
		java.util.Set<String> active = new java.util.HashSet<>(current.keySet());
		List<String> ended = new ArrayList<>();
		for (String territory : previousActive) {
			if (current.containsKey(territory)) {
				continue;
			}
			long since = missingSince.computeIfAbsent(territory, t -> now);
			if (now - since < TIMER_GONE_MS || !timerOver(territory, since, now)) {
				active.add(territory);
				continue;
			}
			missingSince.remove(territory);
			lastSeenSeconds.remove(territory);
			lastSeenAt.remove(territory);
			ended.add(territory);
		}
		previousActive = active;
		return new TimerChanges(ended, appeared);
	}

	/** Whether a timer missing from the sidebar since {@code since} has really ended. */
	private static boolean timerOver(String territory, long since, long now) {
		Integer seconds = lastSeenSeconds.get(territory);
		Long readAt = lastSeenAt.get(territory);
		if (seconds == null || readAt == null) {
			return true;
		}
		long dueAt = readAt + seconds * 1000L;
		return dueAt - since <= ENDING_SOON_MS || now >= dueAt + HIDDEN_GRACE_MS;
	}

	/**
	 * Newly appeared timers that are this client's own queue: its attack slot was clicked
	 * here within {@link #QUEUE_MATCH_MS}, and nobody is known to have queued it.
	 */
	static List<String> matchQueues(List<String> appeared, long now) {
		attackClickAt.values().removeIf(at -> now - at > QUEUE_MATCH_MS);
		List<String> mine = new ArrayList<>();
		for (String territory : appeared) {
			if (attackClickAt.containsKey(territory) && !queuers.containsKey(territory)) {
				mine.add(territory);
			}
		}
		return mine;
	}

	/** Report each pending queue once its timer is readable, or after the wait without it. */
	private static void flushQueueSends(Map<String, Integer> current, long now, BridgeWebSocketClient socket) {
		for (java.util.Iterator<Map.Entry<String, Long>> it = pendingQueueSend.entrySet().iterator(); it.hasNext();) {
			Map.Entry<String, Long> pending = it.next();
			Integer seconds = current.get(pending.getKey());
			boolean timed = seconds != null && seconds != Integer.MAX_VALUE;
			if (!timed && now - pending.getValue() < QUEUE_SEND_WAIT_MS) {
				continue;
			}
			it.remove();
			if (socket != null) {
				String territory = pending.getKey();
				socket.sendWarQueued(territory, territory.equals(currentTerritory()), timed ? seconds : null);
			}
		}
	}

	/**
	 * Drop a local-only queuer once its territory has had no live timer for
	 * {@link #LOCAL_QUEUER_NO_TIMER_MS}: a chat report for a territory nobody queued, or a
	 * war that ended while this client was on another world, would otherwise never clear.
	 * {@code current} is the live set, which keeps a timer the sidebar is only hiding.
	 */
	private static void dropStaleLocalQueuers(java.util.Set<String> current, long now) {
		for (java.util.Iterator<String> it = localQueuers.iterator(); it.hasNext();) {
			String territory = it.next();
			if (current.contains(territory)) {
				localQueuerNoTimerSince.remove(territory);
				continue;
			}
			long since = localQueuerNoTimerSince.computeIfAbsent(territory, t -> now);
			if (now - since >= LOCAL_QUEUER_NO_TIMER_MS) {
				it.remove();
				localQueuerNoTimerSince.remove(territory);
				queuers.remove(territory);
			}
		}
	}

	/**
	 * If this client marked itself heading to a territory or queued it, report whether
	 * it's standing inside it (only on a change), so its head border flips green/red for
	 * everyone. The client is the authority on its position.
	 */
	private static void reportInside(BridgeWebSocketClient socket) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			lastReportedInsideByTerritory.clear();
			pendingInsideByTerritory.clear();
			pendingSinceByTerritory.clear();
			return;
		}
		String myUuid = mc.player.getUUID().toString();
		java.util.Set<String> myTerritories = myRelevantTerritories(myUuid);
		if (myTerritories.isEmpty()) {
			lastReportedInsideByTerritory.clear();
			pendingInsideByTerritory.clear();
			pendingSinceByTerritory.clear();
			return;
		}

		lastReportedInsideByTerritory.keySet().retainAll(myTerritories);
		pendingInsideByTerritory.keySet().retainAll(myTerritories);
		pendingSinceByTerritory.keySet().retainAll(myTerritories);

		BlockPos pos = mc.player.blockPosition();
		String playerTerritory = TerritoryData.territoryAt(pos.getX(), pos.getZ());
		long now = System.currentTimeMillis();

		for (String terr : myTerritories) {
			boolean isInside = terr.equals(playerTerritory);
			WarQueuer q = queuers.get(terr);
			if (q != null && q.uuid().equals(myUuid) && q.inside() != isInside) {
				queuers.put(terr, new WarQueuer(q.name(), q.uuid(), isInside));
			}
			Boolean last = lastReportedInsideByTerritory.get(terr);
			if (last == null) {
				lastReportedInsideByTerritory.put(terr, isInside);
				pendingInsideByTerritory.put(terr, isInside);
				sendInside(socket, terr, isInside);
				continue;
			}
			if (isInside == last) {
				pendingInsideByTerritory.put(terr, isInside);
				continue;
			}
			Boolean pending = pendingInsideByTerritory.get(terr);
			if (pending == null || isInside != pending) {
				pendingInsideByTerritory.put(terr, isInside);
				pendingSinceByTerritory.put(terr, now);
			} else {
				Long since = pendingSinceByTerritory.get(terr);
				if (since != null && now - since >= INSIDE_SETTLE_MS) {
					lastReportedInsideByTerritory.put(terr, isInside);
					sendInside(socket, terr, isInside);
				}
			}
		}
	}

	private static void sendInside(BridgeWebSocketClient socket, String territory, boolean inside) {
		if (socket != null) {
			socket.sendWarGoingInside(territory, inside);
		}
	}

	/** Territories where this client is either a marked goer or the queuer. */
	private static java.util.Set<String> myRelevantTerritories(String myUuid) {
		java.util.Set<String> set = new java.util.HashSet<>();
		for (Map.Entry<String, List<WarGoer>> entry : going.entrySet()) {
			for (WarGoer goer : entry.getValue()) {
				if (goer.uuid().equals(myUuid)) {
					set.add(entry.getKey());
					break;
				}
			}
		}
		for (Map.Entry<String, WarQueuer> entry : queuers.entrySet()) {
			if (entry.getValue() != null && entry.getValue().uuid().equals(myUuid)) {
				set.add(entry.getKey());
			}
		}
		return set;
	}

	/**
	 * A world swap keeps the bridge socket open, and the backend only sends the board to a
	 * new connection, so the board state (queuers, goers, scraped defence) is kept here: the
	 * live socket keeps it current. Only state read from this world's sidebar is cleared.
	 * {@link #reset()} still clears everything on disconnect.
	 */
	public static void onWorldJoin() {
		chatDefenses.clear();
		previousActive.clear();
		missingSince.clear();
		lastSeenSeconds.clear();
		lastSeenAt.clear();
		localQueuerNoTimerSince.clear();
		clickBoxes.clear();
		headBoxes.clear();
		soonestTerritory = null;
		// Re-report inside status from this world's position.
		lastReportedInsideByTerritory.clear();
		pendingInsideByTerritory.clear();
		pendingSinceByTerritory.clear();
	}

	/** Clear all local war-board state (on disconnect). */
	public static void reset() {
		chatDefenses.clear();
		scrapedDefenses.clear();
		defenseConflict.clear();
		going.clear();
		queuers.clear();
		localQueuers.clear();
		localQueuerNoTimerSince.clear();
		previousActive.clear();
		missingSince.clear();
		lastSeenSeconds.clear();
		lastSeenAt.clear();
		attackClickAt.clear();
		pendingQueueSend.clear();
		clickBoxes.clear();
		headBoxes.clear();
		soonestTerritory = null;
		lastReportedInsideByTerritory.clear();
		pendingInsideByTerritory.clear();
		pendingSinceByTerritory.clear();
	}

	public static void render(BridgeConfig config, GuiGraphics graphics) {
		clickBoxes.clear();
		headBoxes.clear();
		if (!config.warAttackTimers) {
			soonestTerritory = null;
			return;
		}
		List<Upcoming> attacks = currentAttacks();
		if (attacks.isEmpty()) {
			soonestTerritory = null;
			return;
		}
		soonestTerritory = attacks.get(0).territory();

		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		String currentTerritory = currentTerritory();

		// Cap the visible rows to the soonest configured max so a 30-40 war spree doesn't
		// run the HUD off-screen; the rest collapse into a "+N more wars" footer. The
		// territory wall and beacon still use the full, uncapped list.
		int maxRows = Math.max(1, Math.min(50, config.warAttackTimerMaxRows));
		int overflowWars = Math.max(0, attacks.size() - maxRows);
		List<Upcoming> shown = overflowWars > 0 ? attacks.subList(0, maxRows) : attacks;

		// Per-row text width, so heads sit just past each row's text and the panel is
		// widened to fit them (keeping the whole row — text and heads — clickable).
		// Note: We use font.width(line) directly without stripping codes so that §l (bold)
		// text metrics are correctly accounted for, avoiding heads overlapping countdown seconds.
		List<String> lines = new ArrayList<>();
		int[] textWidths = new int[shown.size()];
		int maxWidth = 0;
		for (int i = 0; i < shown.size(); i++) {
			String line = rowText(shown.get(i), currentTerritory);
			lines.add(line);
			textWidths[i] = font.width(line);
			int goersW = headsWidth(going.get(shown.get(i).territory()));
			int rowW = TEXT_OFFSET_X + textWidths[i] + (goersW > 0 ? 4 + goersW : 0);
			maxWidth = Math.max(maxWidth, rowW);
		}
		String footer = overflowWars > 0 ? "§7+" + overflowWars + " more war" + (overflowWars == 1 ? "" : "s") : null;
		if (footer != null) {
			maxWidth = Math.max(maxWidth, TEXT_OFFSET_X + font.width(footer));
		}
		int rows = shown.size() + (footer != null ? 1 : 0);
		int width = maxWidth + 6;
		int height = rows * ROW_HEIGHT + 4;
		float scale = HudLayout.scale(config, PANEL);
		int x = HudLayout.x(config, PANEL, DEFAULT_X, DEFAULT_Y);
		int y = HudLayout.y(config, PANEL, DEFAULT_X, DEFAULT_Y);

		// Panel is built at the origin and placed/scaled by the pose transform, so the
		// click boxes must be recorded in final screen coords (origin + relative*scale).
		HudPanel panel = new HudPanel();
		panel.add(new RectangleElement(0, 0, width, height, PANEL_BG));
		int relY = 3;
		for (int i = 0; i < shown.size(); i++) {
			panel.add(new TextElement(lines.get(i), TEXT_OFFSET_X, relY, 0xFFFFFFFF));
			clickBoxes.put(shown.get(i).territory(), new int[]{x, Math.round(y + (relY - 1) * scale), Math.round(x + width * scale), Math.round(y + (relY + ROW_HEIGHT - 1) * scale)});
			relY += ROW_HEIGHT;
		}
		if (footer != null) {
			panel.add(new TextElement(footer, TEXT_OFFSET_X, relY, 0xFFFFFFFF));
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		panel.draw(graphics);

		// Queuer heads (left) and goer heads (right) drawn over the panel.
		relY = 3;
		for (int i = 0; i < shown.size(); i++) {
			String terr = shown.get(i).territory();
			WarQueuer queuer = queuers.get(terr);
			if (queuer != null) {
				drawQueuerHead(graphics, mc, QUEUER_HEAD_X, relY, queuer, x, y, scale);
			}
			drawHeads(graphics, font, TEXT_OFFSET_X + textWidths[i] + 4, relY, going.get(terr), x, y, scale);
			relY += ROW_HEIGHT;
		}
		graphics.pose().popMatrix();
	}

	/**
	 * Show a goer's IGN (or the overflow's names) when the mouse is over their head.
	 * Called while the chat screen is open — that's when a cursor exists and clicking
	 * already works, so hovering fits the same interaction.
	 */
	public static void renderGoerTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
		for (HeadBox box : headBoxes) {
			if (mouseX >= box.x1() && mouseX <= box.x2() && mouseY >= box.y1() && mouseY <= box.y2()) {
				List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
				for (String name : box.names()) {
					lines.add(net.minecraft.network.chat.Component.literal(name));
				}
				graphics.setComponentTooltipForNextFrame(Minecraft.getInstance().font, lines, mouseX, mouseY);
				return;
			}
		}
	}

	/** Pixel width the heads (capped, plus a "+N" overflow) occupy after a row's text. */
	private static int headsWidth(List<WarGoer> goers) {
		if (goers == null || goers.isEmpty()) {
			return 0;
		}
		int shown = Math.min(goers.size(), MAX_HEADS);
		int width = 2 + shown * (HEAD_SIZE + HEAD_GAP);
		int overflow = goers.size() - shown;
		if (overflow > 0) {
			width += Minecraft.getInstance().font.width("+" + overflow) + 2;
		}
		return width;
	}

	/**
	 * Draw the going players' heads (capped at {@link #MAX_HEADS}, then "+N"), recording
	 * each head's screen-space rectangle (origin + relative*scale) for hover tooltips.
	 * {@code relX}/{@code relY} are panel-relative; {@code originX}/{@code originY}/{@code scale}
	 * map them to the screen for the boxes.
	 */
	private static void drawHeads(GuiGraphics graphics, Font font, int relX, int relY, List<WarGoer> goers, int originX, int originY, float scale) {
		if (goers == null || goers.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int shown = Math.min(goers.size(), MAX_HEADS);
		for (int i = 0; i < shown; i++) {
			WarGoer goer = goers.get(i);
			int headRelX = relX + i * (HEAD_SIZE + HEAD_GAP);
			PlayerFaceRenderer.draw(graphics, skinFor(mc, goer), headRelX, relY, HEAD_SIZE);
			// Border: green once they're inside the territory, red while heading there.
			drawBorder(graphics, headRelX, relY, HEAD_SIZE, goer.inside() ? BORDER_GREEN : BORDER_RED);
			headBoxes.add(headBox(headRelX, relY, HEAD_SIZE, HEAD_SIZE, originX, originY, scale, List.of(nameFor(mc, goer))));
		}
		int overflow = goers.size() - shown;
		if (overflow > 0) {
			// +2 matches the gap headsWidth() reserves before the overflow counter.
			int overflowRelX = relX + shown * (HEAD_SIZE + HEAD_GAP) + 2;
			graphics.drawString(font, "+" + overflow, overflowRelX, relY, 0xFFFFFFFF);
			List<String> rest = new ArrayList<>();
			for (int i = shown; i < goers.size(); i++) {
				rest.add(nameFor(mc, goers.get(i)));
			}
			headBoxes.add(headBox(overflowRelX, relY, font.width("+" + overflow), font.lineHeight, originX, originY, scale, rest));
		}
	}

	/** Draw a 1px frame on the edge of a head to mark a goer's inside/en-route status. */
	private static void drawBorder(GuiGraphics graphics, int x, int y, int size, int color) {
		graphics.fill(x, y, x + size, y + 1, color);
		graphics.fill(x, y + size - 1, x + size, y + size, color);
		graphics.fill(x, y, x + 1, y + size, color);
		graphics.fill(x + size - 1, y, x + size, y + size, color);
	}

	/** A screen-space hover box from a panel-relative (x, y) run of {@code width}x{@code height}px. */
	private static HeadBox headBox(int relX, int relY, int width, int height, int originX, int originY, float scale, List<String> names) {
		return new HeadBox(Math.round(originX + relX * scale), Math.round(originY + relY * scale), Math.round(originX + (relX + width) * scale), Math.round(originY + (relY + height) * scale), names);
	}

	private static void drawQueuerHead(GuiGraphics graphics, Minecraft mc, int relX, int relY, WarQueuer queuer, int originX, int originY, float scale) {
		PlayerFaceRenderer.draw(graphics, skinFor(mc, queuer.uuid(), queuer.name()), relX, relY, HEAD_SIZE);
		drawBorder(graphics, relX, relY, HEAD_SIZE, queuer.inside() ? BORDER_GREEN : BORDER_RED);
		String name = nameFor(mc, queuer.uuid(), queuer.name());
		headBoxes.add(headBox(relX, relY, HEAD_SIZE, HEAD_SIZE, originX, originY, scale, List.of(name + " (Queuer)")));
	}

	/** A goer's current IGN from the tab list (fresh after a rename), else the board name. */
	private static String nameFor(Minecraft mc, WarGoer goer) {
		return nameFor(mc, goer.uuid(), goer.name());
	}

	private static String nameFor(Minecraft mc, String uuidStr, String fallbackName) {
		try {
			UUID uuid = UUID.fromString(uuidStr);
			if (mc.getConnection() != null) {
				PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
				if (info != null) {
					return info.getProfile().name();
				}
			}
		} catch (IllegalArgumentException ignored) {
			// Fall through to the board-provided name.
		}
		return fallbackName;
	}

	private static final EdenLogger LOGGER = EdenLogger.get();
	/** Skins resolved by uuid from Mojang, for goers not in our player list (cross-world). */
	private static final Map<UUID, PlayerSkin> resolvedSkins = new ConcurrentHashMap<>();
	/**
	 * Earliest time (ms) each uuid may be (re)fetched. Set on every attempt so a failed or
	 * empty resolve backs off instead of re-submitting every frame; a success caches into
	 * {@link #resolvedSkins} and is served before this map is ever consulted again.
	 */
	private static final Map<UUID, Long> skinNextAttempt = new ConcurrentHashMap<>();
	private static final long SKIN_RETRY_MS = 30_000L;
	private static final ExecutorService skinWorker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "eden-war-skins");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * The skin to draw for a player head. A guildmate on our own world is in the player list with
	 * their skin already loaded (free). One on a <em>different</em> Wynncraft world instance
	 * (routine during a war rush) isn't in our list, so their profile carries no textures and
	 * the game would only give a default skin — we resolve those by uuid from Mojang in the
	 * background (cached), showing the default until it lands.
	 */
	private static PlayerSkin skinFor(Minecraft mc, WarGoer goer) {
		return skinFor(mc, goer.uuid(), goer.name());
	}

	private static PlayerSkin skinFor(Minecraft mc, String uuidStr, String fallbackName) {
		UUID uuid;
		try {
			uuid = UUID.fromString(uuidStr);
		} catch (IllegalArgumentException e) {
			return DefaultPlayerSkin.get(UUID.nameUUIDFromBytes(fallbackName.getBytes(StandardCharsets.UTF_8)));
		}
		if (mc.getConnection() != null) {
			PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
			if (info != null) {
				return info.getSkin();
			}
		}
		PlayerSkin resolved = resolvedSkins.get(uuid);
		if (resolved != null) {
			return resolved;
		}
		fetchSkin(mc, uuid, fallbackName);
		return DefaultPlayerSkin.get(uuid);
	}

	/**
	 * Resolve a goer's skin by uuid off-thread (Mojang session lookup → texture load),
	 * caching the result. Rate-limited per uuid ({@link #SKIN_RETRY_MS}) so a failed or
	 * empty resolve backs off instead of re-submitting every frame the head is drawn; a
	 * success caches the skin, which is then served before this method is called again.
	 */
	private static void fetchSkin(Minecraft mc, UUID uuid, String name) {
		long now = System.currentTimeMillis();
		Long next = skinNextAttempt.get(uuid);
		if (next != null && now < next) {
			return;
		}
		skinNextAttempt.put(uuid, now + SKIN_RETRY_MS);
		LOGGER.debug("Resolving war-head skin for {} ({}) — not in the player list", name, uuid);
		skinWorker.submit(() -> {
			try {
				var result = mc.services().sessionService().fetchProfile(uuid, false);
				if (result != null) {
					GameProfile profile = result.profile();
					mc.execute(() -> mc.getSkinManager().get(profile).thenAccept(skin -> skin.ifPresent(value -> resolvedSkins.put(uuid, value))));
				}
			} catch (RuntimeException e) {
				LOGGER.debug("War-head skin fetch failed for {}", uuid, e);
			}
		});
	}

	/**
	 * Handle a click on a timer row while the chat screen is open; true if a row was hit.
	 * Left-click points Wynncraft's compass at the territory; right-click toggles this
	 * player's "heading here" marker (a head next to the row, shared with the guild).
	 */
	public static boolean mouseClicked(BridgeConfig config, double mouseX, double mouseY, int button) {
		if (!config.warAttackTimers) {
			return false;
		}
		for (Map.Entry<String, int[]> entry : clickBoxes.entrySet()) {
			int[] box = entry.getValue();
			if (mouseX >= box[0] && mouseX <= box[2] && mouseY >= box[1] && mouseY <= box[3]) {
				if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
					BridgeWebSocketClient socket = EdenModClient.instance().socket();
					if (socket != null) {
						socket.sendWarGoing(entry.getKey(), secondsLeft(entry.getKey()));
						lastReportedInsideByTerritory.remove(entry.getKey());
						pendingInsideByTerritory.remove(entry.getKey());
						pendingSinceByTerritory.remove(entry.getKey());
					}
				} else {
					int[] middle = TerritoryData.middle(entry.getKey());
					if (middle != null && Minecraft.getInstance().getConnection() != null) {
						Minecraft.getInstance().getConnection().sendCommand("compass " + middle[0] + " " + middle[1]);
					}
				}
				return true;
			}
		}
		return false;
	}

	/** Seconds left on {@code territory}'s timer as the sidebar shows it, or null if unreadable. */
	private static Integer secondsLeft(String territory) {
		for (Upcoming attack : currentAttacks()) {
			if (attack.territory().equals(territory)) {
				int seconds = seconds(attack.time());
				return seconds == Integer.MAX_VALUE ? null : seconds;
			}
		}
		return null;
	}

	private static String rowText(Upcoming attack, String currentTerritory) {
		String defense = coloredDefense(attack.territory());
		String name = attack.territory().equals(currentTerritory) ? "§d§l" + attack.territory() : "§6" + attack.territory();
		return name + " §6(" + defense + "§6) §b" + attack.time();
	}

	private static String coloredDefense(String territory) {
		// Normal priority: attack-menu scrape > recent chat report > advancement scrape.
		// Exception: when scouts reported conflicting scraped ratings (an open attack GUI
		// freezes its value, so two scouts can disagree), the attacker's chat report
		// ("Olux defense is High", posted as they commit the war) is the tie-breaker, so
		// chat wins over scrape. All of this resets when the territory's timer ends.
		ChatDefenseInfo chat = chatDefenses.get(territory);
		boolean chatOk = chat != null && chat.isRecent();
		String scraped = scrapedDefenses.get(territory);
		boolean scrapeOk = scraped != null && !scraped.isEmpty();
		if (Boolean.TRUE.equals(defenseConflict.get(territory))) {
			if (chatOk) {
				return colorFor(chat.defense());
			}
			if (scrapeOk) {
				return colorFor(scraped);
			}
		} else {
			if (scrapeOk) {
				return colorFor(scraped);
			}
			if (chatOk) {
				return colorFor(chat.defense());
			}
		}
		return colorFor(TerritoryData.defense(territory));
	}

	private static String colorFor(String defense) {
		if (defense.equals("Low") || defense.equals("Very Low")) {
			return "§a" + defense;
		}
		if (defense.equals("Medium")) {
			return "§e" + defense;
		}
		return "§c" + defense;
	}

	private static List<Upcoming> upcomingAttacks() {
		Scoreboard scoreboard = sidebarScoreboard();
		if (scoreboard == null) {
			return List.of();
		}
		Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (sidebar == null) {
			return List.of();
		}
		List<Upcoming> attacks = new ArrayList<>();
		List<String> seen = new ArrayList<>();
		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
			Matcher matcher = TIMER.matcher(stripCodes(sidebarLineText(scoreboard, entry)));
			if (matcher.find()) {
				String territory = matcher.group(2).trim();
				if (!seen.contains(territory)) {
					seen.add(territory);
					attacks.add(new Upcoming(matcher.group(1), territory));
				}
			}
		}
		// Sort by actual remaining time, not lexicographically: "9:05" is sooner than "10:02"
		// even though '1' < '9' as text.
		attacks.sort((a, b) -> Integer.compare(seconds(a.time()), seconds(b.time())));
		return attacks;
	}

	/** Parse an "M:SS"/"MM:SS" timer to total seconds; malformed sorts last. */
	private static int seconds(String time) {
		String[] parts = time.split(":");
		if (parts.length != 2) {
			return Integer.MAX_VALUE;
		}
		try {
			return Integer.parseInt(parts[0].trim()) * 60 + Integer.parseInt(parts[1].trim());
		} catch (NumberFormatException e) {
			return Integer.MAX_VALUE;
		}
	}

	/**
	 * The visible text of a sidebar line. Servers (Wynncraft included) render sidebar
	 * text through the score holder's team prefix/suffix, so the bare owner name is not
	 * enough — the team formatting must be applied to see the real line.
	 */
	private static String sidebarLineText(Scoreboard scoreboard, PlayerScoreEntry entry) {
		PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
		if (team != null) {
			return PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString();
		}
		return entry.ownerName().getString();
	}

	/**
	 * The scoreboard to read timer lines from: the packet-captured shadow when it carries
	 * a sidebar (so Wynntils hiding the vanilla segment can't blind us), else the game's
	 * own scoreboard as a fallback.
	 */
	private static Scoreboard sidebarScoreboard() {
		if (ScoreboardCapture.hasSidebar()) {
			return ScoreboardCapture.scoreboard();
		}
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null ? mc.level.getScoreboard() : null;
	}

	/** Diagnostic: dump every scoreboard slot/objective and its scores, to locate the
	 * timer lines when they aren't in the standard sidebar. */
	public static List<String> debugSidebarLines() {
		Scoreboard scoreboard = sidebarScoreboard();
		if (scoreboard == null) {
			return List.of("(no world)");
		}
		List<String> out = new ArrayList<>();
		out.add("source: " + (ScoreboardCapture.hasSidebar() ? "captured shadow" : "vanilla scoreboard"));
		for (DisplaySlot slot : DisplaySlot.values()) {
			Objective shown = scoreboard.getDisplayObjective(slot);
			out.add("slot " + slot + " -> " + (shown == null ? "none" : shown.getName()));
		}
		for (Objective objective : scoreboard.getObjectives()) {
			out.add("obj '" + objective.getName() + "' title='" + stripCodes(objective.getDisplayName().getString()) + "'");
			for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
				out.add("  " + entry.value() + " | " + stripCodes(sidebarLineText(scoreboard, entry)));
			}
		}
		return out.isEmpty() ? List.of("(no scoreboard data)") : out;
	}

	private static String currentTerritory() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return null;
		}
		BlockPos pos = mc.player.blockPosition();
		return TerritoryData.territoryAt(pos.getX(), pos.getZ());
	}

	private static String stripCodes(String text) {
		return text.replaceAll("§.", "");
	}

	static void putLocalQueuerForTesting(String territory, WarQueuer queuer) {
		queuers.put(territory, queuer);
		localQueuers.add(territory);
	}

	static WarQueuer queuerForTesting(String territory) {
		return queuers.get(territory);
	}

	static List<WarGoer> goingForTesting(String territory) {
		return going.get(territory);
	}

	static void dropStaleLocalQueuersForTesting(java.util.Set<String> current, long now) {
		dropStaleLocalQueuers(current, now);
	}
}
