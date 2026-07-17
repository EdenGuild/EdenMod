package tel.eden.mod.war;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.hud.HudLayout;
import tel.eden.mod.hud.HudPanel;
import tel.eden.mod.hud.RectangleElement;
import tel.eden.mod.hud.TextElement;
import tel.eden.mod.mixin.BossHealthOverlayAccessor;

/**
 * Detects guild wars the player attends and reports the attendance to the backend.
 *
 * <p>On the "war battle will start in 25 seconds" chat line, nearby players are
 * captured (once a second, 100-block box) as war companions. When the tower boss bar
 * then appears for a known territory (debounced so one war counts once), the war is
 * reported over the bridge as a {@code warAttended} event — the backend merges reports
 * from every attending client and attaches the party to the territory-capture embed.
 * War counts themselves stay backend-authoritative; the optional HUD chip shows the
 * player's own 7-day count from the last {@code warCountsReply}.
 */
public final class WarTracker {
	private WarTracker() {
	}

	private static final String PANEL = "weeklyWars";
	private static final float DEFAULT_X = 0.83f;
	private static final float DEFAULT_Y = 0.04f;
	private static final int PANEL_BG = 0x64000000;

	private static final Pattern IGN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
	private static final String WAR_START_LINE = "[WAR] The war battle will start in 25 seconds.";
	private static final long CAPTURE_WINDOW_MS = 25_000L;
	private static final long BAR_DEBOUNCE_MS = 25_000L;
	private static final int CAPTURE_RADIUS = 100;

	/** Reports the detected war to the backend (or queues it while disconnected). */
	@FunctionalInterface
	public interface AttendanceReporter {
		void report(String territory, List<String> members);
	}

	private static AttendanceReporter reporter;
	private static volatile long captureWindowStart = Long.MIN_VALUE;
	private static final Set<String> nearbyMembers = new LinkedHashSet<>();
	private static long lastWarBar = Long.MIN_VALUE;
	private static int tickCounter;
	/** Own war count over the last 7 days, from the backend; -1 until known. */
	private static volatile int weeklyWars = -1;

	public static void setReporter(AttendanceReporter attendanceReporter) {
		reporter = attendanceReporter;
	}

	public static void setWeeklyWars(int wars) {
		weeklyWars = wars;
	}

	/** War-start chat detection; may be called off-thread (only touches timestamps). */
	public static void onChat(String rawLine) {
		if (WarDPS.clean(rawLine).startsWith(WAR_START_LINE)) {
			captureWindowStart = System.currentTimeMillis();
			synchronized (nearbyMembers) {
				nearbyMembers.clear();
			}
		}
	}

	/** Capture nearby players during the pre-war window and watch for the tower bar. */
	public static void onTick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		tickCounter++;
		if (System.currentTimeMillis() - captureWindowStart <= CAPTURE_WINDOW_MS && tickCounter % 20 == 0) {
			captureNearbyPlayers();
		}
		detectTowerBar();
	}

	private static void captureNearbyPlayers() {
		Minecraft mc = Minecraft.getInstance();
		Player self = mc.player;
		AABB box = new AABB(self.getX() - CAPTURE_RADIUS, self.getY() - CAPTURE_RADIUS, self.getZ() - CAPTURE_RADIUS, self.getX() + CAPTURE_RADIUS, self.getY() + CAPTURE_RADIUS, self.getZ() + CAPTURE_RADIUS);
		List<Player> players = mc.level.getEntitiesOfClass(Player.class, box, p -> IGN.matcher(p.getName().getString()).matches());
		synchronized (nearbyMembers) {
			for (Player player : players) {
				nearbyMembers.add(player.getName().getString());
			}
		}
	}

	private static void detectTowerBar() {
		Minecraft mc = Minecraft.getInstance();
		var events = ((BossHealthOverlayAccessor) mc.gui.getBossOverlay()).edenmod$events();
		for (LerpingBossEvent event : events.values()) {
			String text = WarDPS.clean(event.getName().getString());
			if (!text.contains("Tower")) {
				continue;
			}
			long now = System.currentTimeMillis();
			if (now - lastWarBar < BAR_DEBOUNCE_MS) {
				lastWarBar = now;
				continue;
			}
			lastWarBar = now;
			String territory = territoryFromBar(text);
			if (territory != null && TerritoryData.territoryNames().contains(territory)) {
				reportWar(territory);
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
		synchronized (nearbyMembers) {
			for (String name : nearbyMembers) {
				if (!members.contains(name)) {
					members.add(name);
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
		Minecraft mc = Minecraft.getInstance();
		String text = weeklyWars + (weeklyWars == 1 ? " war" : " wars");
		int width = mc.font.width(text) + 6;
		int height = 12;
		int x = Math.max(0, Math.min(HudLayout.x(config, PANEL, DEFAULT_X, DEFAULT_Y), mc.getWindow().getGuiScaledWidth() - width));
		int y = Math.max(0, Math.min(HudLayout.y(config, PANEL, DEFAULT_X, DEFAULT_Y), mc.getWindow().getGuiScaledHeight() - height));
		HudPanel panel = new HudPanel();
		panel.add(new RectangleElement(x, y, width, height, PANEL_BG));
		panel.add(new TextElement("§d" + text, x + 3, y + 2, 0xFFFFFFFF));
		panel.draw(graphics);
	}
}
