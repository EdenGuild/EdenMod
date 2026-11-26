package tel.eden.mod.raid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;
import tel.eden.mod.EdenLogger;
import tel.eden.mod.chat.RaidPartyTracker;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.hud.HudPanel;
import tel.eden.mod.hud.HudRender;
import tel.eden.mod.hud.RectangleElement;
import tel.eden.mod.hud.TextElement;
import tel.eden.mod.net.BridgeWebSocketClient;
import tel.eden.mod.raid.tcc.TccLabyrinthTracker;
import tel.eden.mod.war.WarDPS;

/**
 * Shares each Shaman Acolyte's Blood Pool with their raid party, for the whole raid.
 *
 * <p>Wynncraft shows the pool only to its owner, as a boss bar named {@code Blood Pool [N%]}
 * whose fill is N divided by the player's cap (80 to 200 in steps of 10), so the cap is read
 * from the ratio. The bar is removed when the pool hits 0. It is read from the boss bar
 * packets, not the overlay: Wynntils' Blood Pool overlay stops the vanilla bar being added.
 * Each client reads its own bar
 * and EdenBot relays the level to the party; rows are keyed by the sender's UUID, so any
 * number of Acolytes in one party stay apart. Everything runs on the client thread.
 */
public final class BloodPoolTracker {
	private BloodPoolTracker() {
	}

	public static final String PANEL = "bloodPool";
	public static final float DEFAULT_X = 0.45f;
	public static final float DEFAULT_Y = 0.46f;
	// Caps run 80 to 200 in steps of 10 (gear and abilities move it).
	static final int MIN_CAP = 80;
	static final int MAX_CAP = 200;
	static final int CAP_STEP = 10;
	static final long SEND_INTERVAL_MS = 100L;
	// The bot drops a party claim after 15s. Re-sending inside that keeps it, so teammates
	// with no pool of their own (travelers in particular) are still matched as recipients.
	static final long CLAIM_INTERVAL_MS = 10_000L;
	// Same gap RaidPartyTracker treats as a new raid: a shorter one is a room transition
	// briefly clearing the sidebar, and the rows carry on through it.
	static final long RAID_END_GRACE_MS = 10_000L;
	// Below this the bar name's rounding can move the ratio to the wrong cap, so a cap
	// already read is kept.
	static final int MIN_PCT_FOR_CAP = 20;
	private static final Pattern BAR_NAME = Pattern.compile("^Blood Pool \\[(\\d{1,3})%\\]$");
	private static final int PANEL_BG = 0x80000000;
	private static final int NAME_COLOR = 0xFFFFFFFF;
	private static final int BLOOD_COLOR = 0xFFFF3333;
	private static final int ROW_H = 11;
	private static final EdenLogger LOGGER = EdenLogger.get();
	// Last activity state logged, so the diagnostic lines fire on changes only.
	private static boolean loggedActive;
	private static boolean loggedOwnBar;

	record Row(String name, int pct, int cap) {
	}

	// Party members' pools, keyed by undashed lower-case UUID.
	private static final Map<String, Row> rows = new LinkedHashMap<>();
	// This player's pool: cap 0 until a bar has been seen this raid; pct 0 once it empties.
	private static int ownPct;
	private static int ownCap;
	private static int sentPct = Integer.MIN_VALUE;
	private static long lastSendMs;
	private static long lastClaimMs;
	private static long notRaidingSinceMs;
	// The Blood Pool bar as the server last sent it, kept across raids as the overlay keeps it.
	private static UUID barId;
	private static int barPct;
	private static float barProgress;
	private static final ClientboundBossEventPacket.Handler BAR_PACKETS = new ClientboundBossEventPacket.Handler() {
		@Override
		public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay, boolean darkenScreen, boolean playMusic, boolean createWorldFog) {
			onBarName(id, name);
			updateProgress(id, progress);
		}

		@Override
		public void updateName(UUID id, Component name) {
			onBarName(id, name);
		}

		@Override
		public void updateProgress(UUID id, float progress) {
			if (id.equals(barId)) {
				barProgress = progress;
			}
		}

		@Override
		public void remove(UUID id) {
			if (id.equals(barId)) {
				barId = null;
			}
		}
	};
	private static LongSupplier timeSupplier = System::currentTimeMillis;

	public static void setTimeSupplier(LongSupplier supplier) {
		timeSupplier = supplier != null ? supplier : System::currentTimeMillis;
	}

	/** Every boss bar packet, ahead of the overlay and of mods that hide bars from it. Client thread. */
	public static void onBossPacket(ClientboundBossEventPacket packet) {
		packet.dispatch(BAR_PACKETS);
	}

	private static void onBarName(UUID id, Component name) {
		int pct = parsePct(WarDPS.clean(name.getString()));
		if (pct >= 0) {
			barId = id;
			barPct = pct;
		} else if (id.equals(barId)) {
			barId = null;
		}
	}

	/** A new connection starts with no boss bars, as the overlay does. */
	public static void forgetBar() {
		barId = null;
	}

	/** The N in a {@code Blood Pool [N%]} bar name, or -1 for any other bar. */
	static int parsePct(String cleanName) {
		Matcher m = BAR_NAME.matcher(cleanName.trim());
		return m.matches() ? Integer.parseInt(m.group(1)) : -1;
	}

	/** The cap the bar's fill implies for {@code pct}, or {@code previous} when it can't tell. */
	static int capFor(int pct, float progress, int previous) {
		if (pct <= 0 || progress <= 0f || (previous > 0 && pct < MIN_PCT_FOR_CAP)) {
			return atLeast(previous, pct);
		}
		return atLeast(clampCap(Math.round(pct / progress / CAP_STEP) * CAP_STEP), pct);
	}

	/** {@code cap}, or the smallest real cap that holds {@code pct} if it doesn't. */
	private static int atLeast(int cap, int pct) {
		return pct <= cap ? cap : clampCap((pct + CAP_STEP - 1) / CAP_STEP * CAP_STEP);
	}

	private static int clampCap(int cap) {
		return Math.max(MIN_CAP, Math.min(MAX_CAP, cap));
	}

	public static void tick(BridgeConfig config, BridgeWebSocketClient socket) {
		if (config == null || !config.bloodPoolTracker) {
			if (loggedActive) {
				loggedActive = false;
				LOGGER.info("[BloodPool] off: tracker disabled in config");
			}
			reset();
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		long now = timeSupplier.getAsLong();
		boolean active = RaidPartyTracker.isRaiding();
		if (active != loggedActive) {
			loggedActive = active;
			LOGGER.info("[BloodPool] {} (party={})", active ? "active" : "inactive", party());
		}
		if (!active) {
			if (notRaidingSinceMs == 0L) {
				notRaidingSinceMs = now;
			} else if (now - notRaidingSinceMs >= RAID_END_GRACE_MS) {
				reset();
			}
			return;
		}
		notRaidingSinceMs = 0L;
		readOwnBar();
		maybeSend(socket, now);
	}

	/** This player's party with them in it, or empty with no teammate (TCC's builder). */
	private static List<String> party() {
		return TccLabyrinthTracker.getRaidPartyMembers();
	}

	static void readOwnBar() {
		if (barId == null) {
			// The bar is removed at 0; a cap already read keeps the row showing an empty pool.
			ownPct = 0;
			return;
		}
		ownCap = capFor(barPct, barProgress, ownCap);
		if (!loggedOwnBar) {
			loggedOwnBar = true;
			LOGGER.info("[BloodPool] reading own bar: {}% of cap {}", barPct, ownCap);
		}
		ownPct = barPct;
	}

	private static void maybeSend(BridgeWebSocketClient socket, long now) {
		// -1 is "no pool": the message then only names this player's party.
		int pct = ownCap > 0 ? ownPct : -1;
		boolean changed = pct != sentPct && now - lastSendMs >= SEND_INTERVAL_MS;
		if (!changed && now - lastClaimMs < CLAIM_INTERVAL_MS) {
			return;
		}
		if (socket == null || !socket.isConnected()) {
			return;
		}
		List<String> party = party();
		lastSendMs = now;
		lastClaimMs = now;
		// No party yet: retry at the send rate, not every tick, and resend this value later.
		if (party.isEmpty()) {
			return;
		}
		socket.sendBloodPool(pct, ownCap, party);
		if (sentPct == Integer.MIN_VALUE) {
			LOGGER.info("[BloodPool] first send: pct={} cap={} party={}", pct, ownCap, party);
		}
		sentPct = pct;
	}

	/** A party member's pool, relayed by EdenBot. Client thread. */
	public static void onRemote(BridgeConfig config, String ownUuid, String sender, String senderUuid, int pct, int cap) {
		if (config == null || !config.bloodPoolTracker || senderUuid == null || sender == null || sender.isBlank()) {
			return;
		}
		String key = senderUuid.replace("-", "").trim().toLowerCase(Locale.ROOT);
		if (key.isEmpty() || key.equalsIgnoreCase(ownUuid) || pct < 0 || pct > cap || !isCap(cap)) {
			return;
		}
		rows.put(key, new Row(sender, pct, cap));
	}

	static boolean isCap(int cap) {
		return cap >= MIN_CAP && cap <= MAX_CAP && cap % CAP_STEP == 0;
	}

	/** This player's row (if they have a pool this raid) first, then each party member's. */
	static List<Row> visibleRows(String ownName) {
		List<Row> out = new ArrayList<>(rows.size() + 1);
		if (ownCap > 0) {
			out.add(new Row(ownName, ownPct, ownCap));
		}
		out.addAll(rows.values());
		return out;
	}

	public static void render(BridgeConfig config, GuiGraphics graphics) {
		if (config == null || !config.bloodPoolTracker) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.options.hideGui) {
			return;
		}
		List<Row> visible = visibleRows(mc.player.getGameProfile().name());
		if (visible.isEmpty()) {
			return;
		}
		List<String> blood = new ArrayList<>(visible.size());
		int nameW = 0;
		int bloodW = 0;
		for (Row row : visible) {
			String value = row.pct() + " / " + row.cap();
			blood.add(value);
			nameW = Math.max(nameW, mc.font.width(row.name()));
			bloodW = Math.max(bloodW, mc.font.width(value));
		}
		int panelW = nameW + bloodW + 14;
		int panelH = visible.size() * ROW_H + 3;
		HudPanel panel = new HudPanel();
		panel.add(new RectangleElement(0, 0, panelW, panelH, PANEL_BG));
		for (int i = 0; i < visible.size(); i++) {
			int y = 2 + i * ROW_H;
			panel.add(new TextElement(visible.get(i).name(), 4, y, NAME_COLOR));
			panel.add(new TextElement(blood.get(i), nameW + 10, y, BLOOD_COLOR));
		}
		HudRender.draw(config, PANEL, DEFAULT_X, DEFAULT_Y, panelW, panelH, panel, graphics);
	}

	public static void reset() {
		rows.clear();
		ownPct = 0;
		ownCap = 0;
		sentPct = Integer.MIN_VALUE;
		lastSendMs = 0L;
		lastClaimMs = 0L;
		notRaidingSinceMs = 0L;
		loggedOwnBar = false;
	}

	static void setOwnForTesting(int pct, int cap) {
		ownPct = pct;
		ownCap = cap;
	}
}
