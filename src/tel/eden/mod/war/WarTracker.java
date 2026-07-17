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
 * <p>Capture opens on the "war battle will start in 25 seconds" line and runs through
 * the fight, sampling nearby players once a second; only those present for enough of it
 * (a persistent-sighting threshold) count, so passers-by are excluded. Crucially, a
 * report is only sent if this client actually saw the tower boss bar — i.e. it was at the
 * war, not just a guildmate who saw the countdown broadcast from a hub — and only when
 * the war is won. Losses and abandoned attempts report nothing. War counts stay
 * backend-authoritative; the optional HUD chip shows the player's own 7-day count from
 * the last {@code warCountsReply}.
 */
public final class WarTracker {
	private WarTracker() {
	}

	private static final String PANEL = "weeklyWars";
	private static final float DEFAULT_X = 0.83f;
	private static final float DEFAULT_Y = 0.04f;
	private static final int PANEL_BG = 0x64000000;

	private static final Pattern IGN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
	private static final String WAR_START_PHRASE = "war battle will start";
	// War fighters cluster on the tower; keep the radius tight to exclude passers-by.
	private static final int CAPTURE_RADIUS = 45;
	// A player must appear in at least this many one-second samples (~5s) to count.
	private static final int MIN_SIGHTINGS = 5;
	// If no tower bar appears within this long of the countdown, we aren't in this war.
	private static final long NO_WAR_TIMEOUT_MS = 40_000L;
	// Hard safety cap on how long one capture may run.
	private static final long MAX_CAPTURE_MS = 300_000L;

	/** Reports the detected war to the backend (or queues it while disconnected). */
	@FunctionalInterface
	public interface AttendanceReporter {
		void report(String territory, List<String> members);
	}

	private static AttendanceReporter reporter;
	private static volatile boolean capturing;
	private static volatile long captureStart;
	private static String currentWar;
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

	/** War-start countdown opens the capture window; may be called off-thread. */
	public static void onChat(String rawLine) {
		if (WarDPS.clean(rawLine).contains(WAR_START_PHRASE)) {
			capturing = true;
			captureStart = System.currentTimeMillis();
			currentWar = null;
			synchronized (sightings) {
				sightings.clear();
			}
		}
	}

	/** Sample nearby players each second while capturing; note the war's territory. */
	public static void onTick() {
		if (!capturing) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		long now = System.currentTimeMillis();
		String territory = activeTowerTerritory();
		if (territory != null) {
			currentWar = territory;
		}
		// A guildmate who only saw the countdown (no tower bar, not at the war) stops
		// capturing so their bystanders are never reported.
		if ((currentWar == null && now - captureStart > NO_WAR_TIMEOUT_MS) || now - captureStart > MAX_CAPTURE_MS) {
			reset();
			return;
		}
		tickCounter++;
		if (tickCounter % 20 == 0) {
			sampleNearbyFighters();
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
		capturing = false;
		currentWar = null;
		synchronized (sightings) {
			sightings.clear();
		}
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
		synchronized (sightings) {
			for (Player player : players) {
				sightings.merge(player.getName().getString(), 1, Integer::sum);
			}
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
		synchronized (sightings) {
			for (Map.Entry<String, Integer> entry : sightings.entrySet()) {
				if (entry.getValue() >= MIN_SIGHTINGS && !members.contains(entry.getKey())) {
					members.add(entry.getKey());
				}
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
		String text = weeklyWars + (weeklyWars == 1 ? " war" : " wars");
		int width = Minecraft.getInstance().font.width(text) + 6;
		int height = 12;
		HudPanel panel = new HudPanel();
		panel.add(new RectangleElement(0, 0, width, height, PANEL_BG));
		panel.add(new TextElement("§d" + text, 3, 2, 0xFFFFFFFF));
		HudRender.draw(config, PANEL, DEFAULT_X, DEFAULT_Y, width, height, panel, graphics);
	}
}
