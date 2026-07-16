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

	private record Upcoming(String time, String territory) {
	}

	/** Territory with the earliest upcoming attack, or null. */
	public static String soonestTerritory() {
		return soonestTerritory;
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
		List<Upcoming> attacks = upcomingAttacks();
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
		int x = clamp(HudLayout.x(config, PANEL, DEFAULT_X, DEFAULT_Y), width, mc.getWindow().getGuiScaledWidth());
		int y = clamp(HudLayout.y(config, PANEL, DEFAULT_X, DEFAULT_Y), height, mc.getWindow().getGuiScaledHeight());

		HudPanel panel = new HudPanel();
		panel.add(new RectangleElement(x, y, width, height, PANEL_BG));
		int rowY = y + 3;
		for (int i = 0; i < attacks.size(); i++) {
			panel.add(new TextElement(lines.get(i), x + 3, rowY, 0xFFFFFFFF));
			clickBoxes.put(attacks.get(i).territory(), new int[]{x, rowY - 1, x + width, rowY + ROW_HEIGHT - 1});
			rowY += ROW_HEIGHT;
		}
		panel.draw(graphics);
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
		String defense = TerritoryData.defense(territory);
		ChatDefenseInfo chat = chatDefenses.get(territory);
		if (chat != null && chat.isRecent()) {
			return colorFor(defense) + "§f/" + colorFor(chat.defense());
		}
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
			String line = stripCodes(entry.ownerName().getString());
			Matcher matcher = TIMER.matcher(line);
			if (matcher.find()) {
				String territory = matcher.group(2).trim();
				if (!seen.contains(territory)) {
					seen.add(territory);
					attacks.add(new Upcoming(matcher.group(1), territory));
				}
			}
		}
		attacks.sort((a, b) -> a.time().compareTo(b.time()));
		return attacks;
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
