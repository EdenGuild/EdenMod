package tel.eden.mod.war;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.hud.HudLayout;
import tel.eden.mod.hud.HudPanel;
import tel.eden.mod.hud.RectangleElement;
import tel.eden.mod.hud.TextElement;
import tel.eden.mod.net.BridgeWebSocketClient;
import tel.eden.mod.net.WarBoardEntry;
import tel.eden.mod.net.WarGoer;

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
	// Head border: green once a goer is inside their target territory, red while en route.
	private static final int BORDER_GREEN = 0xFF44FF44;
	private static final int BORDER_RED = 0xFFFF5050;

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
	/** Who is heading to each territory (from the {@code warBoard} broadcast). */
	private static final Map<String, List<WarGoer>> going = new HashMap<>();
	/** Territories that had an attack timer last tick, to detect when one ends. */
	private static java.util.Set<String> previousActive = new java.util.HashSet<>();
	// Last inside-status this client reported for its own head, and whether it was
	// heading anywhere, so it only sends on a change (enter/leave the target territory).
	private static boolean wasGoing;
	private static boolean lastReportedInside;
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

	/**
	 * Parse a guild-chat line for a "&lt;territory&gt; defense is &lt;rating&gt;" report
	 * (as Wynntils announces) and fold it into the HUD's fresher-intel overlay.
	 */
	public static void intakeChat(String reporter, String message) {
		Matcher matcher = DEFENSE_REPORT.matcher(message.trim());
		if (matcher.find()) {
			reportDefense(reporter, matcher.group(1).trim(), canonicalDefense(matcher.group(2)));
		}
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
		}
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
			return;
		}
		java.util.Set<String> current = new java.util.HashSet<>();
		for (Upcoming attack : currentAttacks()) {
			current.add(attack.territory());
		}
		for (String territory : previousActive) {
			if (!current.contains(territory)) {
				chatDefenses.remove(territory);
				if (socket != null) {
					socket.sendWarTimerEnded(territory);
				}
			}
		}
		previousActive = current;
		reportInside(socket);
	}

	/**
	 * If this client marked itself heading to a territory, report whether it's standing
	 * inside it (only on a change), so its head border flips green/red for everyone. The
	 * goer's own client is the authority on its position — no one else knows it.
	 */
	private static void reportInside(BridgeWebSocketClient socket) {
		Minecraft mc = Minecraft.getInstance();
		String myTerritory = mc.player == null ? null : myGoingTerritory(mc.player.getUUID().toString());
		if (myTerritory == null) {
			wasGoing = false;
			return;
		}
		BlockPos pos = mc.player.blockPosition();
		boolean inside = myTerritory.equals(TerritoryData.territoryAt(pos.getX(), pos.getZ()));
		if (!wasGoing || inside != lastReportedInside) {
			wasGoing = true;
			lastReportedInside = inside;
			if (socket != null) {
				socket.sendWarGoingInside(inside);
			}
		}
	}

	/** The territory this client is currently marked as heading to (from the board), or null. */
	private static String myGoingTerritory(String myUuid) {
		for (Map.Entry<String, List<WarGoer>> entry : going.entrySet()) {
			for (WarGoer goer : entry.getValue()) {
				if (goer.uuid().equals(myUuid)) {
					return entry.getKey();
				}
			}
		}
		return null;
	}

	/** Clear all local war-board state (on world change / disconnect). */
	public static void reset() {
		chatDefenses.clear();
		scrapedDefenses.clear();
		defenseConflict.clear();
		going.clear();
		previousActive.clear();
		clickBoxes.clear();
		headBoxes.clear();
		soonestTerritory = null;
		wasGoing = false;
		lastReportedInside = false;
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
		List<String> lines = new ArrayList<>();
		int[] textWidths = new int[shown.size()];
		int maxWidth = 0;
		for (int i = 0; i < shown.size(); i++) {
			String line = rowText(shown.get(i), currentTerritory);
			lines.add(line);
			textWidths[i] = font.width(stripCodes(line));
			maxWidth = Math.max(maxWidth, textWidths[i] + headsWidth(going.get(shown.get(i).territory())));
		}
		String footer = overflowWars > 0 ? "§7+" + overflowWars + " more war" + (overflowWars == 1 ? "" : "s") : null;
		if (footer != null) {
			maxWidth = Math.max(maxWidth, font.width(stripCodes(footer)));
		}
		int rows = shown.size() + (footer != null ? 1 : 0);
		int width = maxWidth + 6;
		int height = rows * ROW_HEIGHT + 4;
		float scale = HudLayout.scale(config, PANEL);
		int x = clamp(HudLayout.x(config, PANEL, DEFAULT_X, DEFAULT_Y), Math.round(width * scale), mc.getWindow().getGuiScaledWidth());
		int y = clamp(HudLayout.y(config, PANEL, DEFAULT_X, DEFAULT_Y), Math.round(height * scale), mc.getWindow().getGuiScaledHeight());

		// Panel is built at the origin and placed/scaled by the pose transform, so the
		// click boxes must be recorded in final screen coords (origin + relative*scale).
		HudPanel panel = new HudPanel();
		panel.add(new RectangleElement(0, 0, width, height, PANEL_BG));
		int relY = 3;
		for (int i = 0; i < shown.size(); i++) {
			panel.add(new TextElement(lines.get(i), 3, relY, 0xFFFFFFFF));
			clickBoxes.put(shown.get(i).territory(), new int[]{x, Math.round(y + (relY - 1) * scale), Math.round(x + width * scale), Math.round(y + (relY + ROW_HEIGHT - 1) * scale)});
			relY += ROW_HEIGHT;
		}
		if (footer != null) {
			panel.add(new TextElement(footer, 3, relY, 0xFFFFFFFF));
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		panel.draw(graphics);
		// Heads draw over the panel, in the same transformed space, just past each row's text.
		relY = 3;
		for (int i = 0; i < shown.size(); i++) {
			drawHeads(graphics, font, 3 + textWidths[i] + 2, relY, going.get(shown.get(i).territory()), x, y, scale);
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

	/** A goer's current IGN from the tab list (fresh after a rename), else the board name. */
	private static String nameFor(Minecraft mc, WarGoer goer) {
		try {
			UUID uuid = UUID.fromString(goer.uuid());
			if (mc.getConnection() != null) {
				PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
				if (info != null) {
					return info.getProfile().name();
				}
			}
		} catch (IllegalArgumentException ignored) {
			// Fall through to the board-provided name.
		}
		return goer.name();
	}

	private static final Logger LOGGER = LoggerFactory.getLogger("edenmod");
	/** Skins resolved by uuid from Mojang, for goers not in our player list (cross-world). */
	private static final Map<UUID, PlayerSkin> resolvedSkins = new ConcurrentHashMap<>();
	/** Uuids with a resolve in flight, so each is fetched at most once. */
	private static final Set<UUID> skinFetching = ConcurrentHashMap.newKeySet();
	private static final ExecutorService skinWorker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "eden-war-skins");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * The skin to draw for a goer. A guildmate on our own world is in the player list with
	 * their skin already loaded (free). One on a <em>different</em> Wynncraft world instance
	 * (routine during a war rush) isn't in our list, so their profile carries no textures and
	 * the game would only give a default skin — we resolve those by uuid from Mojang in the
	 * background (cached), showing the default until it lands.
	 */
	private static PlayerSkin skinFor(Minecraft mc, WarGoer goer) {
		UUID uuid;
		try {
			uuid = UUID.fromString(goer.uuid());
		} catch (IllegalArgumentException e) {
			return DefaultPlayerSkin.get(UUID.nameUUIDFromBytes(goer.name().getBytes(StandardCharsets.UTF_8)));
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
		fetchSkin(mc, uuid, goer.name());
		return DefaultPlayerSkin.get(uuid);
	}

	/**
	 * Resolve a goer's skin by uuid off-thread (Mojang session lookup → texture load),
	 * caching the result. Runs once per uuid; a transient failure clears the guard so a
	 * later frame retries, a successful profile fetch does not (the skin loads on its own).
	 */
	private static void fetchSkin(Minecraft mc, UUID uuid, String name) {
		if (!skinFetching.add(uuid)) {
			return;
		}
		LOGGER.debug("Resolving war-head skin for {} ({}) — not in the player list", name, uuid);
		skinWorker.submit(() -> {
			try {
				var result = mc.services().sessionService().fetchProfile(uuid, false);
				if (result == null) {
					skinFetching.remove(uuid);
					return;
				}
				GameProfile profile = result.profile();
				mc.execute(() -> mc.getSkinManager().get(profile).thenAccept(skin -> skin.ifPresent(value -> resolvedSkins.put(uuid, value))));
			} catch (RuntimeException e) {
				skinFetching.remove(uuid);
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
						socket.sendWarGoing(entry.getKey());
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

	private static int clamp(int value, int size, int screen) {
		return Math.max(0, Math.min(value, screen - size));
	}

	private static String stripCodes(String text) {
		return text.replaceAll("§.", "");
	}
}
