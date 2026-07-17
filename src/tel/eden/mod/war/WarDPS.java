package tel.eden.mod.war;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.hud.HudPanel;
import tel.eden.mod.hud.HudRender;
import tel.eden.mod.hud.RectangleElement;
import tel.eden.mod.hud.TextElement;
import tel.eden.mod.mixin.BossHealthOverlayAccessor;

/**
 * Live war-tower stats. While in a guild war, Wynncraft shows the tower's stats in a
 * boss bar ("... Tower - ❤ health (def%) - ☠ dmg (attacks/s)"); this parses that bar
 * each tick, derives the tower's effective HP and the team's DPS over 1s/5s/the whole
 * fight plus an estimated time to kill, and shows them as a HUD panel during the war.
 * On the war-end chat line a summary (time in war, average DPS, initial/final tower
 * stats) is printed to chat.
 */
public final class WarDPS {
	private WarDPS() {
	}

	private static final String PANEL = "warInfo";
	private static final float DEFAULT_X = 0.015f;
	private static final float DEFAULT_Y = 0.30f;
	private static final int ROW_HEIGHT = 10;
	private static final int PANEL_BG = 0x64000000;
	// A tower bar seen within this window means "currently in a war".
	private static final long IN_WAR_WINDOW_MS = 2000L;
	// A war-end chat line only counts within this window of the last tower bar (kept
	// generous: the result line can lag the tower's last update by several seconds).
	private static final long END_CHAT_WINDOW_MS = 15_000L;

	private static long warStartTime = -1;
	private static long firstDamageTime = -1;
	private static long lastTimeInWar;
	private static String territoryName = "";
	private static long previousSecond;
	private static double previousEhp;
	private static double dpsOneSec;
	private static double dpsFiveSec;
	private static double dpsSinceStart;
	private static double maxEhp;
	private static double timeRemaining;
	private static final List<Double> lastFiveEhp = new ArrayList<>();
	private static String initialStats = "";
	private static String latestStats = "";
	private static double ehpDisplay;
	private static double lowerDpsDisplay;
	private static double higherDpsDisplay;

	/** Scan the active boss bars for a war tower and update the stats. */
	public static void onTick(BridgeConfig config) {
		if (!config.warDpsHud) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		var events = ((BossHealthOverlayAccessor) mc.gui.getBossOverlay()).edenmod$events();
		for (LerpingBossEvent event : events.values()) {
			String text = clean(event.getName().getString());
			if (text.contains("Tower")) {
				String[] words = text.split(" ");
				if (words.length >= 6) {
					parseTowerBar(words, text);
				}
			}
		}
	}

	/** War-end detection from chat; may be called off-thread (display is marshalled). */
	public static void onChat(String rawLine) {
		if (warStartTime < 0 || System.currentTimeMillis() - lastTimeInWar > END_CHAT_WINDOW_MS) {
			return;
		}
		// Match on key phrases (not the exact territory) so a slightly different result
		// wording still fires. "contains" tolerates any guild/war prefix on the line.
		String line = clean(rawLine);
		if (line.contains("taken control of")) {
			warEnded(true);
		} else if (line.contains("lost the war") || line.contains("canceled and refunded") || line.contains("failed to take control")) {
			warEnded(false);
		}
	}

	public static void render(BridgeConfig config, GuiGraphics graphics) {
		if (!config.warDpsHud || System.currentTimeMillis() - lastTimeInWar > IN_WAR_WINDOW_MS) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		long seconds = warStartTime > 0 ? (System.currentTimeMillis() - warStartTime) / 1000 : 0;
		List<String> rows = new ArrayList<>();
		rows.add("§bWar Info §7(" + seconds + "s)");
		rows.add("Tower EHP: §b" + readable(ehpDisplay));
		rows.add("Tower DPS: §b" + readable(lowerDpsDisplay) + "-" + readable(higherDpsDisplay));
		rows.add("Team DPS/1s: §c" + readable(dpsOneSec));
		rows.add("Team DPS/5s: §c" + readable(dpsFiveSec));
		rows.add("Team DPS (total): §e" + readable(dpsSinceStart));
		rows.add(dpsSinceStart > 0 ? "Est. time left: §a" + (int) timeRemaining + "s" : "Est. time left: Unknown");

		int maxWidth = 0;
		for (String row : rows) {
			maxWidth = Math.max(maxWidth, font.width(row.replaceAll("§.", "")));
		}
		int width = maxWidth + 6;
		int height = rows.size() * ROW_HEIGHT + 4;
		HudPanel panel = new HudPanel();
		panel.add(new RectangleElement(0, 0, width, height, PANEL_BG));
		int rowY = 3;
		for (String row : rows) {
			panel.add(new TextElement(row, 3, rowY, 0xFFFFFFFF));
			rowY += ROW_HEIGHT;
		}
		HudRender.draw(config, PANEL, DEFAULT_X, DEFAULT_Y, width, height, panel, graphics);
	}

	private static void parseTowerBar(String[] words, String fullText) {
		try {
			if (System.currentTimeMillis() - lastTimeInWar > 119_000L) {
				// Last war ended over two minutes ago; treat a new bar as a fresh war
				// even if it's for the same territory.
				territoryName = "";
			}
			lastTimeInWar = System.currentTimeMillis();
			List<String> wordList = Arrays.asList(words);
			int firstDash = wordList.indexOf("-");
			int lastDash = wordList.lastIndexOf("-");
			if (firstDash < 2 || lastDash <= firstDash || lastDash + 3 >= words.length + 1) {
				return;
			}
			StringBuilder name = new StringBuilder();
			for (int i = 1; i < firstDash - 1; i++) {
				name.append(words[i]).append(" ");
			}
			if (!name.toString().equals(territoryName)) {
				resetWar();
				territoryName = name.toString();
				warStartTime = System.currentTimeMillis();
				initialStats = fullText;
			}
			latestStats = fullText;

			String health = words[firstDash + 2];
			String defense = words[firstDash + 3].replace("(", "").split("\\)")[0].replace("%", "");
			String damage = words[lastDash + 2];
			String attacks = words[lastDash + 3].replace("(", "").split("\\)")[0].replace("x", "");

			ehpDisplay = Math.round(parseNumber(health) / (1.0 - (Double.parseDouble(defense) / 100.0)));
			String[] damageRange = damage.split("-");
			lowerDpsDisplay = parseNumber(damageRange[0]) * Double.parseDouble(attacks);
			higherDpsDisplay = parseNumber(damageRange[damageRange.length - 1]) * Double.parseDouble(attacks);

			if (maxEhp == 0) {
				maxEhp = ehpDisplay;
				previousEhp = ehpDisplay;
				lastFiveEhp.add(ehpDisplay);
			}
			long second = (System.currentTimeMillis() - warStartTime) / 1000;
			if (second != previousSecond) {
				dpsOneSec = previousEhp - ehpDisplay;
				previousEhp = ehpDisplay;
				if (firstDamageTime == -1 && dpsOneSec > 0) {
					firstDamageTime = System.currentTimeMillis();
				}
				if (lastFiveEhp.size() == 5) {
					lastFiveEhp.remove(0);
				}
				lastFiveEhp.add(ehpDisplay);
				dpsFiveSec = Math.floor((lastFiveEhp.get(0) - ehpDisplay) / 5);
				if (firstDamageTime != -1 && System.currentTimeMillis() > firstDamageTime) {
					dpsSinceStart = (maxEhp - previousEhp) / ((System.currentTimeMillis() - firstDamageTime) / 1000.0);
					if (dpsSinceStart > 0) {
						timeRemaining = Math.floor(previousEhp / dpsSinceStart);
					}
				}
				previousSecond = second;
			}
		} catch (RuntimeException ignored) {
			// A malformed/unexpected bar layout must never break the render loop.
		}
	}

	private static void warEnded(boolean won) {
		long timeInWar = warStartTime > 0 ? (System.currentTimeMillis() - warStartTime) / 1000 : 0;
		double fightSeconds = firstDamageTime > 0 ? (System.currentTimeMillis() - firstDamageTime) / 1000.0 : 0;
		double averageDps = fightSeconds > 0 ? (won ? maxEhp : maxEhp - previousEhp) / fightSeconds : 0;
		String initial = statsTail(initialStats);
		String fin = statsTail(latestStats);
		Minecraft.getInstance().execute(() -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null) {
				return;
			}
			mc.player.displayClientMessage(Component.literal("Time in war: " + timeInWar + "s").withStyle(ChatFormatting.LIGHT_PURPLE), false);
			mc.player.displayClientMessage(Component.literal("Average DPS: " + readable(averageDps)).withStyle(ChatFormatting.LIGHT_PURPLE), false);
			if (!initial.isEmpty()) {
				mc.player.displayClientMessage(Component.literal("Initial tower: " + initial).withStyle(ChatFormatting.GRAY), false);
			}
			if (!won && !fin.isEmpty()) {
				mc.player.displayClientMessage(Component.literal("Final tower: " + fin).withStyle(ChatFormatting.GRAY), false);
			}
		});
		// Attendance is reported only on a captured war (see WarTracker).
		WarTracker.onWarEnded(won);
		resetWar();
	}

	/** The stat portion of a tower bar ("❤ ... - ☠ ..."), without the territory name. */
	private static String statsTail(String stats) {
		String[] split = stats.split(" - ");
		if (split.length < 2) {
			return "";
		}
		return String.join(" - ", Arrays.copyOfRange(split, 1, split.length));
	}

	private static void resetWar() {
		warStartTime = -1;
		firstDamageTime = -1;
		previousSecond = 0;
		previousEhp = 0;
		lastFiveEhp.clear();
		dpsOneSec = 0;
		dpsFiveSec = 0;
		dpsSinceStart = 0;
		maxEhp = 0;
		timeRemaining = 0;
		territoryName = "";
		initialStats = "";
		latestStats = "";
	}

	/** Parse a number that may carry thousands separators ("1,234,567"). */
	private static double parseNumber(String raw) {
		return Double.parseDouble(raw.replace(",", ""));
	}

	private static String readable(double value) {
		if (value >= 1_000_000) {
			return String.format("%.2fM", value / 1_000_000);
		}
		if (value >= 1_000) {
			return String.format("%.1fk", value / 1_000);
		}
		return String.format("%.0f", value);
	}

	/** Strip colour codes and private-use glyphs (war-message prefixes) from a line. */
	static String clean(String raw) {
		StringBuilder out = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length();) {
			int cp = raw.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == 0xA7) { // section sign: skip it and the following code char
				if (i < raw.length()) {
					i++;
				}
				continue;
			}
			if ((cp >= 0xE000 && cp <= 0xF8FF) || cp >= 0xF0000) { // private-use glyphs
				continue;
			}
			out.appendCodePoint(cp);
		}
		return out.toString().replaceAll("\\s+", " ").trim();
	}
}
