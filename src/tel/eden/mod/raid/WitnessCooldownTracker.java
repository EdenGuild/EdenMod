package tel.eden.mod.raid;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.chat.ChatText;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.hud.HudPanel;
import tel.eden.mod.hud.HudRender;
import tel.eden.mod.hud.RectangleElement;
import tel.eden.mod.hud.TextElement;
import tel.eden.mod.net.BridgeWebSocketClient;
import tel.eden.mod.party.PartyHealthTracker;
import tel.eden.mod.party.WynntilsPartyBridge;
import tel.eden.mod.war.WarDPS;

/**
 * Tracks the 5.0-second cooldown on Athanatic Witness drops in Orphion's Nexus of Light Room 2.
 * Synchronizes with raid party members via EdenBot so that all 4 players in the raid see the countdown.
 */
public final class WitnessCooldownTracker {
	private WitnessCooldownTracker() {
	}

	public static final String PANEL = "witnessCooldown";
	public static final float DEFAULT_X = 0.45f;
	public static final float DEFAULT_Y = 0.40f;
	public static final long COOLDOWN_DURATION_MS = 5000L;
	public static final long DEBOUNCE_WINDOW_MS = 1000L;
	public static final long READY_TIMEOUT_MS = 30_000L;
	private static final int PANEL_BG = 0x80000000;

	private static final Pattern CRYSTAL_PATTERN = Pattern.compile("\\[\\+2\\s+Light\\s+Crystal\\]", Pattern.CASE_INSENSITIVE);

	private static volatile long cooldownExpiresAtMs = 0L;
	private static volatile long lastTriggerMs = 0L;
	private static LongSupplier timeSupplier = System::currentTimeMillis;

	public static void setTimeSupplier(LongSupplier supplier) {
		timeSupplier = supplier != null ? supplier : System::currentTimeMillis;
	}

	public static LongSupplier getTimeSupplier() {
		return timeSupplier;
	}

	public static long getCooldownExpiresAtMs() {
		return cooldownExpiresAtMs;
	}

	public static long getLastTriggerMs() {
		return lastTriggerMs;
	}

	public static boolean isMatchingMessage(String cleanText) {
		if (cleanText == null || cleanText.isBlank()) {
			return false;
		}
		return CRYSTAL_PATTERN.matcher(cleanText).find();
	}

	public static void onMessage(Component message) {
		if (message == null) {
			return;
		}
		BridgeConfig config = EdenModClient.instance() != null ? EdenModClient.instance().config() : null;
		if (config != null && !config.witnessCooldownTracker) {
			return;
		}
		String clean = ChatText.normalize(WarDPS.clean(message.getString()));
		if (isMatchingMessage(clean)) {
			onCrystalAcquired();
		}
	}

	public static void onCrystalAcquired() {
		BridgeConfig config = EdenModClient.instance() != null ? EdenModClient.instance().config() : null;
		if (config != null && !config.witnessCooldownTracker) {
			return;
		}
		long now = timeSupplier.getAsLong();
		if (now - lastTriggerMs < DEBOUNCE_WINDOW_MS) {
			return;
		}
		lastTriggerMs = now;
		cooldownExpiresAtMs = now + COOLDOWN_DURATION_MS;
		broadcastLocalKill();
	}

	public static void onRemoteWitnessKill(String sender, long timestamp) {
		BridgeConfig config = EdenModClient.instance() != null ? EdenModClient.instance().config() : null;
		if (config != null && !config.witnessCooldownTracker) {
			return;
		}
		long now = timeSupplier.getAsLong();
		if (now - lastTriggerMs < DEBOUNCE_WINDOW_MS) {
			return;
		}
		long elapsed = Math.max(0, now - timestamp);
		long remaining = Math.max(0, COOLDOWN_DURATION_MS - elapsed);
		if (remaining > 0) {
			lastTriggerMs = now;
			cooldownExpiresAtMs = now + remaining;
		}
	}

	private static void broadcastLocalKill() {
		EdenModClient mod = EdenModClient.instance();
		if (mod == null) {
			return;
		}
		BridgeConfig config = mod.config();
		if (config != null && !config.witnessCooldownTracker) {
			return;
		}
		BridgeWebSocketClient socket = mod.socket();
		if (socket == null) {
			return;
		}
		List<String> partyMembers = getRaidPartyMembers();
		String groupKey = config != null ? config.radianceGroupKey : null;
		socket.sendWitnessKill(partyMembers, groupKey);
	}

	public static List<String> getRaidPartyMembers() {
		Set<String> members = new LinkedHashSet<>();
		List<String> wynntilsMembers = WynntilsPartyBridge.getPartyMembers();
		if (wynntilsMembers != null) {
			members.addAll(wynntilsMembers);
		}
		List<String> tracked = PartyHealthTracker.getTrackedPartyNames();
		if (tracked != null) {
			members.addAll(tracked);
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.player != null && mc.player.getGameProfile() != null) {
			String name = mc.player.getGameProfile().name();
			if (name != null && !name.isBlank()) {
				members.add(name);
			}
		}
		return new ArrayList<>(members);
	}

	public static double getRemainingSeconds(long now) {
		return Math.max(0.0, (cooldownExpiresAtMs - now) / 1000.0);
	}

	public static String getStatusText(long now) {
		if (now < cooldownExpiresAtMs) {
			return String.format(Locale.ROOT, "Witness: %.1fs", getRemainingSeconds(now));
		}
		return "Witness: READY";
	}

	public static int getStatusColor(long now) {
		if (now >= cooldownExpiresAtMs) {
			return 0xFF55FF55; // Green
		}
		double remainingSec = getRemainingSeconds(now);
		float progress = (float) Math.min(1.0, Math.max(0.0, 1.0 - (remainingSec / 5.0)));
		int green = 85 + (int) (progress * 170.0f);
		return 0xFF000000 | (255 << 16) | (green << 8) | 85;
	}

	public static void render(BridgeConfig config, GuiGraphics graphics) {
		if (config == null || !config.witnessCooldownTracker) {
			return;
		}
		if (cooldownExpiresAtMs == 0L) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null || mc.level == null || mc.options.hideGui) {
			return;
		}
		long now = timeSupplier.getAsLong();
		if (now >= cooldownExpiresAtMs && (now - cooldownExpiresAtMs) > READY_TIMEOUT_MS) {
			return;
		}

		String text = getStatusText(now);
		int color = getStatusColor(now);
		int textW = mc.font.width(text);
		int panelW = Math.max(90, textW + 10);
		int panelH = 14;

		HudPanel panel = new HudPanel();
		panel.add(new RectangleElement(0, 0, panelW, panelH, PANEL_BG));
		int x = (panelW - textW) / 2;
		panel.add(new TextElement(text, x, 3, color));

		HudRender.draw(config, PANEL, DEFAULT_X, DEFAULT_Y, panelW, panelH, panel, graphics);
	}

	public static void reset() {
		cooldownExpiresAtMs = 0L;
		lastTriggerMs = 0L;
	}
}
