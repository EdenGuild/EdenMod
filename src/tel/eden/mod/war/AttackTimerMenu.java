package tel.eden.mod.war;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.hud.HudLayout;
import tel.eden.mod.hud.HudPanel;
import tel.eden.mod.hud.RectangleElement;
import tel.eden.mod.hud.TextElement;

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

	/** Latest fresher-than-scrape defense reports keyed by territory. */
	private static final Map<String, ChatDefenseInfo> chatDefenses = new HashMap<>();
	/** Screen rectangles [x1,y1,x2,y2] of the last-rendered rows, for click hit-testing. */
	private static final Map<String, int[]> clickBoxes = new HashMap<>();
	private static volatile String soonestTerritory = null;
	// The scoreboard sidebar changes at most once per tick, but both the HUD (render()) and
	// the territory wall (attackedTerritories()) query it every frame. Memoize the scan per
	// game tick so the two callers, and multiple frames within a tick, share one parse.
	private static long cachedAttacksTick = Long.MIN_VALUE;
	private static List<Upcoming> cachedAttacks = List.of();

	private record Upcoming(String time, String territory) {
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

	public static void render(BridgeConfig config, GuiGraphics graphics) {
		clickBoxes.clear();
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

		List<String> lines = new ArrayList<>();
		for (Upcoming attack : attacks) {
			lines.add(rowText(attack, currentTerritory));
		}
		int maxWidth = 0;
		for (String line : lines) {
			maxWidth = Math.max(maxWidth, font.width(stripCodes(line)));
		}
		int width = maxWidth + 6;
		int height = attacks.size() * ROW_HEIGHT + 4;
		float scale = HudLayout.scale(config, PANEL);
		int x = clamp(HudLayout.x(config, PANEL, DEFAULT_X, DEFAULT_Y), Math.round(width * scale), mc.getWindow().getGuiScaledWidth());
		int y = clamp(HudLayout.y(config, PANEL, DEFAULT_X, DEFAULT_Y), Math.round(height * scale), mc.getWindow().getGuiScaledHeight());

		// Panel is built at the origin and placed/scaled by the pose transform, so the
		// click boxes must be recorded in final screen coords (origin + relative*scale).
		HudPanel panel = new HudPanel();
		panel.add(new RectangleElement(0, 0, width, height, PANEL_BG));
		int relY = 3;
		for (int i = 0; i < attacks.size(); i++) {
			panel.add(new TextElement(lines.get(i), 3, relY, 0xFFFFFFFF));
			clickBoxes.put(attacks.get(i).territory(), new int[]{x, Math.round(y + (relY - 1) * scale), Math.round(x + width * scale), Math.round(y + (relY + ROW_HEIGHT - 1) * scale)});
			relY += ROW_HEIGHT;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		panel.draw(graphics);
		graphics.pose().popMatrix();
	}

	/** Handle a click while the chat screen is open; true if a row was hit. */
	public static boolean mouseClicked(BridgeConfig config, double mouseX, double mouseY) {
		if (!config.warAttackTimers) {
			return false;
		}
		for (Map.Entry<String, int[]> entry : clickBoxes.entrySet()) {
			int[] box = entry.getValue();
			if (mouseX >= box[0] && mouseX <= box[2] && mouseY >= box[1] && mouseY <= box[3]) {
				int[] middle = TerritoryData.middle(entry.getKey());
				if (middle != null && Minecraft.getInstance().getConnection() != null) {
					Minecraft.getInstance().getConnection().sendCommand("compass " + middle[0] + " " + middle[1]);
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
		// Prefer a recent guild-chat report (fresher intel) over the scraped value;
		// show a single rating rather than both.
		ChatDefenseInfo chat = chatDefenses.get(territory);
		String defense = (chat != null && chat.isRecent()) ? chat.defense() : TerritoryData.defense(territory);
		return colorFor(defense);
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
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return List.of();
		}
		Scoreboard scoreboard = mc.level.getScoreboard();
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

	/** Diagnostic: dump every scoreboard slot/objective and its scores, to locate the
	 * timer lines when they aren't in the standard sidebar. */
	public static List<String> debugSidebarLines() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return List.of("(no world)");
		}
		Scoreboard scoreboard = mc.level.getScoreboard();
		List<String> out = new ArrayList<>();
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
