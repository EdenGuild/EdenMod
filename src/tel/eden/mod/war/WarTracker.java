package tel.eden.mod.war;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.hud.HudPanel;
import tel.eden.mod.hud.HudRender;
import tel.eden.mod.hud.RectangleElement;
import tel.eden.mod.hud.TextElement;
import tel.eden.mod.mixin.BossHealthOverlayAccessor;

/**
 * Detects guild wars the player actually fights and reports the attendance to the
 * backend on a <em>won</em> war only.
 *
 * <p>Attendance is captured only while the tower boss bar is up (i.e. at the territory
 * during the fight), never during the pre-war countdown — the countdown fires guild-wide
 * so a member sitting in a hub would otherwise scoop up dozens of bystanders. Players
 * near the tower are counted each second; only those seen repeatedly (persistent at the
 * fight, not passing through) are reported, and only when the war is captured. Losses and
 * abandoned wars report nothing. War counts stay backend-authoritative; the optional HUD
 * chip shows the player's own 7-day count from the last {@code warCountsReply}.
 */
public final class WarTracker {
	private WarTracker() {
	}

	private static final String PANEL = "weeklyWars";
	private static final float DEFAULT_X = 0.83f;
	private static final float DEFAULT_Y = 0.04f;
	private static final int PANEL_BG = 0x64000000;

	private static final Pattern IGN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
	// War fighters cluster on the tower; keep the radius tight to exclude passers-by.
	private static final int CAPTURE_RADIUS = 45;
	// Only players seen in at least this many one-second samples count as participants.
	private static final int MIN_SIGHTINGS = 2;
	// Drop a tracked war whose tower bar hasn't been seen for this long (abandoned).
	private static final long WAR_TIMEOUT_MS = 40_000L;

	/** Reports the detected war to the backend (or queues it while disconnected). */
	@FunctionalInterface
	public interface AttendanceReporter {
		void report(String territory, List<String> members);
	}

	private static AttendanceReporter reporter;
	private static String currentWar;
	private static long lastBarSeen;
	private static int tickCounter;
	private static final Map<String, Integer> sightings = new HashMap<>();
	/** Own war count over the last 7 days, from the backend; -1 until known. */
	private static volatile int weeklyWars = -1;

	public static void setReporter(AttendanceReporter attendanceReporter) {
		reporter = attendanceReporter;
	}

	public static void setWeeklyWars(int wars) {
		weeklyWars = wars;
	}

	/** Track the active war from the tower bar and sample nearby fighters each second. */
	public static void onTick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		String territory = activeTowerTerritory();
		if (territory != null) {
			if (!territory.equals(currentWar)) {
				// A new war started; forget the previous fight's samples.
				currentWar = territory;
				sightings.clear();
			}
			lastBarSeen = System.currentTimeMillis();
			tickCounter++;
			if (tickCounter % 20 == 0) {
				sampleNearbyFighters();
			}
		} else if (currentWar != null && System.currentTimeMillis() - lastBarSeen > WAR_TIMEOUT_MS) {
			// The war ended without a win chat line (abandoned/left); report nothing.
			reset();
		}
	}

	/** Called by {@link WarDPS} when a war ends; reports attendance only on a win. */
	public static void onWarEnded(boolean won) {
		if (won && currentWar != null) {
			reportWar(currentWar);
		}
		reset();
	}

	private static void reset() {
		currentWar = null;
		sightings.clear();
	}

	/** The territory of an active tower boss bar the player can see, or null. */
	private static String activeTowerTerritory() {
		Minecraft mc = Minecraft.getInstance();
		var events = ((BossHealthOverlayAccessor) mc.gui.getBossOverlay()).edenmod$events();
		for (LerpingBossEvent event : events.values()) {
			String text = WarDPS.clean(event.getName().getString());
			if (text.contains("Tower")) {
				String territory = territoryFromBar(text);
				if (territory != null && TerritoryData.territoryNames().contains(territory)) {
					return territory;
				}
			}
		}
		return null;
	}

	private static void sampleNearbyFighters() {
		Minecraft mc = Minecraft.getInstance();
		Player self = mc.player;
		AABB box = new AABB(self.getX() - CAPTURE_RADIUS, self.getY() - CAPTURE_RADIUS, self.getZ() - CAPTURE_RADIUS, self.getX() + CAPTURE_RADIUS, self.getY() + CAPTURE_RADIUS, self.getZ() + CAPTURE_RADIUS);
		List<Player> players = mc.level.getEntitiesOfClass(Player.class, box, p -> IGN.matcher(p.getName().getString()).matches());
		for (Player player : players) {
			String name = player.getName().getString();
			sightings.merge(name, 1, Integer::sum);
		}
	}

	/** Territory name from a tower bar ("[TAG] Some Territory Tower - ..."). */
	private static String territoryFromBar(String text) {
		String head = text.split(" - ")[0];
		String[] bracketSplit = head.split("] ");
		if (bracketSplit.length < 2) {
			return null;
		}
		String[] words = bracketSplit[1].trim().split(" ");
		if (words.length < 2) {
			return null;
		}
		// Drop the trailing "Tower" word.
		return String.join(" ", Arrays.copyOfRange(words, 0, words.length - 1)).trim();
	}

	private static void reportWar(String territory) {
		Minecraft mc = Minecraft.getInstance();
		List<String> members = new ArrayList<>();
		if (mc.player != null) {
			members.add(mc.player.getName().getString());
		}
		// Only persistent presences count; a passer-by seen once is excluded.
		for (Map.Entry<String, Integer> entry : sightings.entrySet()) {
			if (entry.getValue() >= MIN_SIGHTINGS && !members.contains(entry.getKey())) {
				members.add(entry.getKey());
			}
		}
		if (reporter != null && !members.isEmpty()) {
			reporter.report(territory, members);
		}
	}

	/** Small HUD chip with the player's own 7-day war count (backend-sourced). */
	public static void renderChip(BridgeConfig config, net.minecraft.client.gui.GuiGraphics graphics) {
		if (!config.warWeeklyCountHud || weeklyWars < 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		String text = weeklyWars + (weeklyWars == 1 ? " war" : " wars");
		int width = mc.font.width(text) + 6;
		int height = 12;
		HudPanel panel = new HudPanel();
		panel.add(new RectangleElement(0, 0, width, height, PANEL_BG));
		panel.add(new TextElement("§d" + text, 3, 2, 0xFFFFFFFF));
		HudRender.draw(config, PANEL, DEFAULT_X, DEFAULT_Y, width, height, panel, graphics);
	}
}
