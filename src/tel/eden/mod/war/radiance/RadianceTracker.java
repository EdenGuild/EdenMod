package tel.eden.mod.war.radiance;

import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.hud.HudPanel;
import tel.eden.mod.hud.HudRender;
import tel.eden.mod.hud.RectangleElement;
import tel.eden.mod.hud.TextElement;
import tel.eden.mod.mixin.PlayerTabOverlayAccessor;
import tel.eden.mod.net.BridgeWebSocketClient;
import tel.eden.mod.war.WarDPS;
import tel.eden.mod.war.WarTracker;

/**
 * Client-to-client Radiance Tracker for Eden members in guild wars.
 *
 * <p>Off by default. Turns on and syncs only when two or more Eden members share the same
 * group key, communicating via EdenBot's WebSocket room relay. Does not rely on status
 * effect overlays or potion packets, remaining fully functional when potion HUD is hidden.
 * Coordinates continuous Radiance rotation by alerting the next Guardian 1 second before
 * the previous Guardian's aura expires.
 */
public final class RadianceTracker {
	private RadianceTracker() {
	}

	private static final String PANEL = "radianceTracker";
	private static final float DEFAULT_X = 0.50f;
	private static final float DEFAULT_Y = 0.35f;
	private static final int PANEL_BG = 0x64000000;
	private static final long OVERLAY_MAX_MS = 20_000L;
	public static final double DEFAULT_ADVANCE_NOTICE_SECONDS = 1.25;
	public static final double CAST_ADVANCE_NOTICE_SECONDS = DEFAULT_ADVANCE_NOTICE_SECONDS;

	private static final Pattern WAR_COUNTDOWN_PATTERN = Pattern.compile("^The war (?:battle|for .+?) will start in \\d+ seconds?\\.", Pattern.CASE_INSENSITIVE);
	private static final Pattern WAR_FIVE_SECOND_PATTERN = Pattern.compile("^[1-5]\\s+seconds?\\.\\.\\.", Pattern.CASE_INSENSITIVE);
	private static final Pattern WAR_BEGUN_PATTERN = Pattern.compile("^(?:\\.\\.\\.\\s+)?The battle has begun!", Pattern.CASE_INSENSITIVE);
	private static final Pattern WAR_END_PATTERN = Pattern.compile("(?:(?:Your guild|\\[(?:EDN|EDEN)\\])\\s+has\\s+(?:lost|won)\\s+the war.*|Your guild has successfully defended\\s+.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern WAR_FAIL_PATTERN = Pattern.compile("(?:Your guild|\\[(?:EDN|EDEN)\\])\\s+failed to take control.*", Pattern.CASE_INSENSITIVE);
	private static final String SERVER_CONNECT_MARKER = "Connected to server:";

	private static volatile int groupMemberCount;
	private static volatile long warActiveUntilMs;
	private static volatile boolean wasActive;
	private static volatile boolean wasOnCooldown;
	private static volatile Double lastCooldownSeconds;
	private static volatile Double cachedCooldownSeconds;
	private static volatile long lastCooldownExpiresAtMs;

	// Local player's active Radiance effect
	private static volatile long localRadianceActiveUntilMs;

	// Rotation countdown (waiting to cast partner's rotation)
	private static volatile boolean castCountdownActive;
	private static volatile long castCountdownStartMs;
	private static volatile double castCountdownDurationSeconds;
	private static volatile boolean pinged;

	// Visual prompt
	private static volatile long castPromptUntilMs;

	public static void setGroupMemberCount(int count) {
		groupMemberCount = Math.max(0, count);
	}

	public static int getGroupMemberCount() {
		return groupMemberCount;
	}

	public static boolean isConfigured(BridgeConfig config) {
		return config != null && config.radianceTracker && config.radianceGroupKey != null && !config.radianceGroupKey.isBlank();
	}

	public static boolean isActive(BridgeConfig config) {
		return isConfigured(config) && groupMemberCount >= 2 && (!config.radianceRequireWar || isInWar());
	}

	public static void onJoin() {
		warActiveUntilMs = 0L;
		wasActive = false;
		wasOnCooldown = false;
		lastCooldownSeconds = null;
		cachedCooldownSeconds = null;
		lastCooldownExpiresAtMs = 0L;
		clearCountdown();
		clearCastPrompt();
		localRadianceActiveUntilMs = 0L;
	}

	public static void onDisconnect() {
		groupMemberCount = 0;
		onJoin();
	}

	public static boolean isInWar() {
		long now = System.currentTimeMillis();
		if (WarDPS.isActivelyFighting()) {
			warActiveUntilMs = Math.max(warActiveUntilMs, now + 45_000L);
			return true;
		}
		return now < warActiveUntilMs;
	}

	/** Chat cues for war state management. Called on client thread. */
	public static void onChat(String rawLine) {
		if (rawLine == null || rawLine.isBlank()) {
			return;
		}
		String clean = WarDPS.clean(rawLine);
		if (isGuildOrNonWarChat(clean)) {
			return;
		}
		long now = System.currentTimeMillis();
		if (WAR_COUNTDOWN_PATTERN.matcher(clean).find() || WAR_FIVE_SECOND_PATTERN.matcher(clean).find() || WAR_BEGUN_PATTERN.matcher(clean).find()) {
			warActiveUntilMs = now + 90_000L;
		} else if (WAR_END_PATTERN.matcher(clean).find() || WAR_FAIL_PATTERN.matcher(clean).find() || clean.contains(SERVER_CONNECT_MARKER) || clean.contains("You have taken control of") || clean.contains("canceled and refunded")) {
			warActiveUntilMs = 0L;
		}
	}

	private static boolean isGuildOrNonWarChat(String clean) {
		return clean.startsWith("[Guild") || clean.startsWith("[GUILD") || clean.startsWith("[Allies") || clean.startsWith("[ALLIES") || clean.startsWith("[Party") || clean.startsWith("[PARTY");
	}

	public static void onClientTick(BridgeConfig config, Minecraft client) {
		if (!isConfigured(config) || client == null || client.player == null || groupMemberCount < 2) {
			clearCountdown();
			clearCastPrompt();
			localRadianceActiveUntilMs = 0L;
			wasActive = false;
			wasOnCooldown = false;
			lastCooldownSeconds = null;
			cachedCooldownSeconds = null;
			return;
		}

		long now = System.currentTimeMillis();
		boolean warActive = isInWar();

		if (config.radianceRequireWar && !warActive) {
			clearCountdown();
			clearCastPrompt();
			localRadianceActiveUntilMs = 0L;
			wasActive = false;
			wasOnCooldown = false;
			lastCooldownSeconds = null;
			cachedCooldownSeconds = null;
			return;
		}

		// Cooldown parsing runs on the 20 Hz client tick loop rather than per-frame in render
		Double cooldownSecondsBoxed = getCooldownRemainingSeconds(client);
		processCooldownTick(config, cooldownSecondsBoxed, now, client);
	}

	public static void processCooldownTick(BridgeConfig config, Double cooldownSecondsBoxed, long now, Minecraft client) {
		cachedCooldownSeconds = cooldownSecondsBoxed;
		boolean onCooldown = cooldownSecondsBoxed != null && cooldownSecondsBoxed > 0.05;

		// Prime initial state on activation to avoid firing a false cast transition when already on cooldown
		if (!wasActive) {
			wasActive = true;
			wasOnCooldown = onCooldown;
			lastCooldownSeconds = cooldownSecondsBoxed;
			if (onCooldown) {
				lastCooldownExpiresAtMs = now + toMillis(cooldownSecondsBoxed);
			} else {
				lastCooldownExpiresAtMs = 0L;
			}
			return;
		}

		// Detect genuine local cast transition:
		// A new cast is only permitted if the player was genuinely off-cooldown:
		// 1) The cooldown was verified expired (lastCooldownSeconds was null/<=0.05 or cooldown expiration timestamp passed), AND
		// 2) Cooldown did not merely drop/continue downwards (cooldownSecondsBoxed must be a fresh jump or off-cooldown start).
		// Cooldown cannot change or restart from bashing while on cooldown, nor does radiance reset.
		boolean wasExpired = (!wasOnCooldown && now >= lastCooldownExpiresAtMs) || (lastCooldownSeconds != null && lastCooldownSeconds <= 0.05);
		boolean isJumpToNewCooldown = onCooldown && (lastCooldownSeconds == null || cooldownSecondsBoxed > (lastCooldownSeconds + 1.0));
		boolean genuineCast = onCooldown && wasExpired && isJumpToNewCooldown;

		if (genuineCast) {
			int effectiveTier = resolveEffectiveTier(config.radianceSelfTier, cooldownSecondsBoxed);
			double selfDuration = getDurationForTier(effectiveTier);
			long remainingMs = toMillis(selfDuration);

			BridgeWebSocketClient socket = EdenModClient.instance() != null ? EdenModClient.instance().socket() : null;
			if (socket != null) {
				socket.sendRadianceCast(config.radianceGroupKey, effectiveTier, remainingMs);
			}

			// Local cast executed: clear pending countdown & prompt, mark local effect active
			clearCountdown();
			clearCastPrompt();
			double countdownSeconds = getCastCountdownDurationSeconds(config, selfDuration);
			localRadianceActiveUntilMs = now + toMillis(countdownSeconds);
			lastCooldownExpiresAtMs = now + toMillis(cooldownSecondsBoxed);
		} else if (onCooldown) {
			// Update ongoing cooldown expiration estimate while on cooldown
			lastCooldownExpiresAtMs = Math.max(lastCooldownExpiresAtMs, now + toMillis(cooldownSecondsBoxed));
		} else if (cooldownSecondsBoxed != null && cooldownSecondsBoxed <= 0.05) {
			lastCooldownExpiresAtMs = 0L;
		}

		wasOnCooldown = onCooldown;
		lastCooldownSeconds = cooldownSecondsBoxed;

		// Countdown ticking towards cast time (grace period before partner's radiance expires)
		if (castCountdownActive) {
			double remaining = getRemainingCountdownSeconds(now);
			if (!pinged && remaining <= 0.0) {
				if (client != null) {
					playPing(client, config);
				}
				pinged = true;
				showCastPrompt(now, config.radianceCastPromptSeconds);
			}
			if (remaining <= 0.0 && pinged && !isCastPromptActive(now)) {
				clearCountdown();
			}
		}

		if (castCountdownActive && now - castCountdownStartMs > OVERLAY_MAX_MS) {
			clearCountdown();
		}
		if (!castCountdownActive && !isCastPromptActive(now)) {
			clearCastPrompt();
		}
		if (localRadianceActiveUntilMs > 0L && now >= localRadianceActiveUntilMs) {
			localRadianceActiveUntilMs = 0L;
		}
	}

	public static void onRemoteCast(BridgeConfig config, String senderGroupKey, int tier, long remainingMs, long receivedAtMs, long oneWayLatencyMs) {
		if (!isConfigured(config)) {
			return;
		}
		if (senderGroupKey == null || !senderGroupKey.trim().equalsIgnoreCase(config.radianceGroupKey.trim())) {
			return;
		}
		if (tier < 0 || tier > 3 || remainingMs <= 0L) {
			return;
		}
		if (config.radianceRequireWar && !isInWar()) {
			return;
		}

		if (groupMemberCount < 2) {
			groupMemberCount = 2;
		}

		long now = receivedAtMs > 0 ? receivedAtMs : System.currentTimeMillis();
		RadianceSyncEntry entry = new RadianceSyncEntry(tier, remainingMs, now);
		double remoteRemaining = getRemoteRemainingDurationSeconds(now, entry, tier, oneWayLatencyMs);
		if (remoteRemaining > 0.0) {
			// Partner cast their Radiance: clear local radiance status and start countdown for our turn
			localRadianceActiveUntilMs = 0L;
			double countdownSeconds = getCastCountdownDurationSeconds(config, remoteRemaining);
			startCountdownAt(now, countdownSeconds);
		}
	}

	public static void render(BridgeConfig config, GuiGraphics graphics) {
		if (!isActive(config)) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options.hideGui) {
			return;
		}

		long now = System.currentTimeMillis();
		boolean castPromptActive = isCastPromptActive(now);

		String line1Text = "";
		int line1Color = 0xFFFFFFFF;

		if (castPromptActive) {
			line1Text = "CAST RADIANCE";
			line1Color = 0xFFFF5555;
		} else if (castCountdownActive) {
			double remaining = Math.max(0.0, getRemainingCountdownSeconds(now));
			line1Text = String.format(Locale.ROOT, "RADIANCE IN %.1fs", remaining);
			line1Color = 0xFFFFFF55;
		} else if (localRadianceActiveUntilMs > now) {
			double remaining = Math.max(0.0, (localRadianceActiveUntilMs - now) / 1000.0);
			line1Text = String.format(Locale.ROOT, "RADIANCE: %.1fs", remaining);
			line1Color = 0xFF55FF55;
		}

		// Read cached cooldown computed on client tick, avoiding per-frame regex/component allocations
		Double cooldownSeconds = cachedCooldownSeconds;
		String line2Text;
		int line2Color;
		if (cooldownSeconds == null || cooldownSeconds <= 0.05) {
			line2Text = "RADIANCE READY";
			line2Color = 0xFF55FF55;
		} else {
			line2Text = String.format(Locale.ROOT, "COOLDOWN: %.0fs", Math.max(0.0, cooldownSeconds));
			line2Color = 0xFF55FFFF;
		}

		int textW1 = line1Text.isEmpty() ? 0 : client.font.width(line1Text);
		int textW2 = client.font.width(line2Text);
		int contentW = Math.max(textW1, textW2);
		int panelW = Math.max(100, contentW + 10);
		int panelH = line1Text.isEmpty() ? 14 : 24;

		HudPanel panel = new HudPanel();
		panel.add(new RectangleElement(0, 0, panelW, panelH, PANEL_BG));

		if (!line1Text.isEmpty()) {
			int x1 = (panelW - textW1) / 2;
			panel.add(new TextElement(line1Text, x1, 2, line1Color));
			int x2 = (panelW - textW2) / 2;
			panel.add(new TextElement(line2Text, x2, 13, line2Color));
		} else {
			int x2 = (panelW - textW2) / 2;
			panel.add(new TextElement(line2Text, x2, 3, line2Color));
		}

		HudRender.draw(config, PANEL, DEFAULT_X, DEFAULT_Y, panelW, panelH, panel, graphics);
	}

	private static void startCountdownAt(long startMs, double durationSeconds) {
		castCountdownActive = true;
		castCountdownStartMs = startMs;
		castCountdownDurationSeconds = durationSeconds;
		clearCastPrompt();
		pinged = false;
	}

	private static void clearCountdown() {
		castCountdownActive = false;
		pinged = false;
	}

	private static void showCastPrompt(long now, double durationSeconds) {
		long durationMs = Math.round(durationSeconds * 1000.0);
		castPromptUntilMs = now + Math.max(0L, durationMs);
	}

	public static boolean isCastPromptActive(long now) {
		return now < castPromptUntilMs;
	}

	private static void clearCastPrompt() {
		castPromptUntilMs = 0L;
	}

	public static double getRemainingCountdownSeconds(long nowMs) {
		double durationMs = castCountdownDurationSeconds * 1000.0;
		return (durationMs - (nowMs - castCountdownStartMs)) / 1000.0;
	}

	public static boolean isCastCountdownActive() {
		return castCountdownActive;
	}

	public static long getLocalRadianceActiveUntilMs() {
		return localRadianceActiveUntilMs;
	}

	public static Double getCachedCooldownSeconds() {
		return cachedCooldownSeconds;
	}

	public static int resolveEffectiveTier(int configuredTier, Double cooldownSeconds) {
		if (configuredTier > 0) {
			return configuredTier;
		}
		if (cooldownSeconds != null) {
			if (cooldownSeconds >= 9.8 && cooldownSeconds <= 10.5) {
				return 3;
			}
			if (cooldownSeconds >= 9.3 && cooldownSeconds < 9.8) {
				return 2;
			}
			if (cooldownSeconds >= 8.5 && cooldownSeconds < 9.3) {
				return 1;
			}
		}
		return 0;
	}

	private static void playPing(Minecraft client, BridgeConfig config) {
		if (!config.radiancePing || client == null) {
			return;
		}
		float pitch = config.radiancePingPitch;
		float volume = Math.max(1.5f, config.radiancePingVolume * 1.5f);
		try {
			if (client.getSoundManager() != null) {
				client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), pitch, volume));
				return;
			}
		} catch (Throwable ignored) {
		}
		if (client.player != null) {
			client.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), volume, pitch);
		}
	}

	public static double getDurationForTier(int tier) {
		return switch (tier) {
			case 1 -> 9.0;
			case 2 -> 9.65;
			case 3 -> 10.0;
			default -> 8.0;
		};
	}

	public static double getAdvanceNoticeSeconds(BridgeConfig config) {
		return config != null ? Math.max(0.0, config.radianceAdvanceNoticeSeconds) : DEFAULT_ADVANCE_NOTICE_SECONDS;
	}

	public static double getCastCountdownDurationSeconds(BridgeConfig config, double remoteRemainingDuration) {
		return Math.max(0.0, remoteRemainingDuration - getAdvanceNoticeSeconds(config));
	}

	public static double getCastCountdownDurationSeconds(double remoteRemainingDuration) {
		return Math.max(0.0, remoteRemainingDuration - DEFAULT_ADVANCE_NOTICE_SECONDS);
	}

	public static double applyCastModePairBuffer(int selfTier, int partnerTier, double durationSeconds) {
		return getCastCountdownDurationSeconds(durationSeconds);
	}

	public static double getDetectedRemainingDurationSeconds(int tier, double cooldownSeconds) {
		return Math.max(0.0, Math.min(cooldownSeconds, getDurationForTier(tier)));
	}

	public static double getRemoteRemainingDurationSeconds(long now, RadianceSyncEntry entry, int tier, long oneWayLatencyMs) {
		if (entry.remainingMs() > 0L) {
			long remainingMs = entry.remainingMs();
			remainingMs -= oneWayLatencyMs;
			if (entry.receivedAtMs() > 0L && now > entry.receivedAtMs()) {
				remainingMs -= (now - entry.receivedAtMs());
			}
			if (remainingMs <= 0L) {
				return 0.0;
			}
			return remainingMs / 1000.0;
		}
		return getDurationForTier(tier);
	}

	public static Double getCooldownRemainingSeconds(Minecraft client) {
		if (client == null || client.gui == null) {
			return null;
		}
		PlayerTabOverlay tabList = client.gui.getTabList();
		if (!(tabList instanceof PlayerTabOverlayAccessor accessor)) {
			return null;
		}
		Component footer = accessor.edenmod$getFooter();
		if (footer == null) {
			return null;
		}
		return RadianceCooldownParser.remainingSeconds(footer.getString());
	}

	private static long toMillis(double seconds) {
		return Math.max(0L, Math.round(seconds * 1000.0));
	}
}
