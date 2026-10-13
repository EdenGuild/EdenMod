package tel.eden.mod.raid.tcc;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.net.BridgeWebSocketClient;

/**
 * Tracks player positions, golem defeats, and exit status for The Canyon Colossus
 * Room 2: The Labyrinth with zero entity scanning and zero world scanning.
 */
public final class TccLabyrinthTracker {
	public enum ExitPoint {
		BLUE_TOP_RIGHT(1, "Blue Top Right", 399, 67), BLUE_TOP_MID(2, "Blue Top Mid", 345, 75), BLUE_TOP_LEFT(3, "Blue Top Left", 278, 83), BLUE_FAR_LEFT(4, "Blue Far Left", 256, 116), BLUE_FAR_RIGHT(5, "Blue Far Right", 407, 119),

		YELLOW_UPPER(6, "Yellow Upper", 441, 220), YELLOW_FAR_RIGHT(7, "Yellow Far Right", 509, 260), YELLOW_LOWER(8, "Yellow Lower", 441, 266),

		GREEN_UPPER(9, "Green Upper", 178, 516), GREEN_MID(10, "Green Mid", 207, 567), RED_UPPER_LEFT(11, "Red Upper Left", 87, 599), GREEN_LOWER(12, "Green Lower", 121, 599),

		RED_MID_EAST(13, "Red Mid East", 360, 644), RED_LOWER_MID(14, "Red Lower Mid", 135, 668), RED_FAR_SOUTHWEST(15, "Red Far Southwest", 43, 669), RED_BOTTOM(16, "Red Bottom", 243, 713);

		public final int id;
		public final String displayName;
		public final int mapPixelX;
		public final int mapPixelY;

		ExitPoint(int id, String displayName, int mapPixelX, int mapPixelY) {
			this.id = id;
			this.displayName = displayName;
			this.mapPixelX = mapPixelX;
			this.mapPixelY = mapPixelY;
		}

		public static ExitPoint findClosest(float mapX, float mapY) {
			ExitPoint best = null;
			double bestDistSq = Double.MAX_VALUE;
			for (ExitPoint ep : values()) {
				double dx = mapX - ep.mapPixelX;
				double dy = mapY - ep.mapPixelY;
				double distSq = dx * dx + dy * dy;
				if (distSq < bestDistSq) {
					bestDistSq = distSq;
					best = ep;
				}
			}
			return best;
		}

		public static ExitPoint fromName(String name) {
			if (name == null) {
				return null;
			}
			for (ExitPoint ep : values()) {
				if (ep.name().equalsIgnoreCase(name)) {
					return ep;
				}
			}
			return null;
		}
	}

	public enum Golem {
		B1("B1", "Blue", 10996, 3477, 300, 336), B2("B2", "Blue", 11081, 3479, 296, 170), B3("B3", "Blue", 11106, 3502, 337, 124),

		Y1("Y1", "Yellow", 10937, 3539, 416, 441), Y2("Y2", "Yellow", 10988, 3544, 433, 349), Y3("Y3", "Yellow", 11008, 3564, 479, 300),

		G1("G1", "Green", 10934, 3404, 159, 444), G2("G2", "Green", 10888, 3395, 130, 537), G3("G3", "Green", 10886, 3428, 183, 541), G4("G4", "Green", 10866, 3409, 163, 581),

		R1("R1", "Red", 10889, 3481, 300, 505), R2("R2", "Red", 10823, 3500, 336, 668), R3("R3", "Red", 10832, 3453, 252, 644), R4("R4", "Red", 10847, 3414, 183, 620), R5("R5", "Red", 10842, 3376, 105, 636);

		public static final double SPAWN_X = 10934.0;
		public static final double SPAWN_Z = 3479.0;
		public static final double MAX_TRIGGER_DIST_SQ = 56.25;

		public final String id;
		public final String path;
		public final int worldX;
		public final int worldZ;
		public final int mapPixelX;
		public final int mapPixelY;
		public final double dirX;
		public final double dirZ;

		Golem(String id, String path, int worldX, int worldZ, int mapPixelX, int mapPixelY) {
			this.id = id;
			this.path = path;
			this.worldX = worldX;
			this.worldZ = worldZ;
			this.mapPixelX = mapPixelX;
			this.mapPixelY = mapPixelY;
			double vx = worldX - SPAWN_X;
			double vz = worldZ - SPAWN_Z;
			double len = Math.sqrt(vx * vx + vz * vz);
			this.dirX = len > 0 ? (vx / len) : 1.0;
			this.dirZ = len > 0 ? (vz / len) : 0.0;
		}

		/**
		 * Checks whether the given coordinate has crossed this barrier moving away from spawn.
		 * The trigger zone extends strictly away from spawn into the hallway (not towards spawn).
		 */
		public boolean isPastBarrier(double px, double pz) {
			double dx = px - worldX;
			double dz = pz - worldZ;
			double distSq = dx * dx + dz * dz;
			if (distSq > MAX_TRIGGER_DIST_SQ) {
				return false;
			}
			double projOut = dx * dirX + dz * dirZ;
			double projLat = Math.abs(dx * (-dirZ) + dz * dirX);
			return projOut >= -0.2 && projOut <= 7.0 && projLat <= 4.5;
		}

		public static Golem fromId(String id) {
			for (Golem g : values()) {
				if (g.id.equalsIgnoreCase(id)) {
					return g;
				}
			}
			return null;
		}
	}

	public record PlayerEntry(String name, UUID uuid, double x, double y, double z, float yaw, boolean isWings, boolean isLocal) {
	}

	// Bounding box for Room 2 (Spawn ~ 10934, 45, 3479)
	private static final double MIN_X = 10750.0;
	private static final double MAX_X = 11180.0;
	private static final double MIN_Z = 3320.0;
	private static final double MAX_Z = 3620.0;
	private static final double MIN_Y = 20.0;
	private static final double MAX_Y = 120.0;
	private static final double WINGS_THRESHOLD_Y = 74.0;
	private static final long POSITION_BROADCAST_INTERVAL_MS = 100L;
	private static final long PEER_TIMEOUT_MS = 10_000L;

	private static final Set<String> DEFEATED_GOLEMS = ConcurrentHashMap.newKeySet();
	private static final Map<UUID, PlayerEntry> PEERS = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> PEER_LAST_SEEN = new ConcurrentHashMap<>();

	private static UUID wingsPlayerUUID = null;
	private static boolean exitReached = false;
	private static ExitPoint confirmedExit = null;
	private static long lastBroadcastMs = 0L;
	private static double lastSentX = 0.0;
	private static double lastSentZ = 0.0;
	private static float lastSentYaw = 0.0f;
	private static boolean debugForceShow = false;
	private static boolean wasInRoom2 = false;
	private static boolean hasExitTitle = false;
	private static long lastExitTitleMs = 0L;
	private static long lastReportKeyMs = 0L;
	private static final long REPORT_COOLDOWN_MS = 5000L;
	private static KeyMapping reportExitKey;

	private TccLabyrinthTracker() {
	}

	public static KeyMapping getReportExitKey() {
		return reportExitKey;
	}

	public static void registerKeybind(KeyMapping.Category category) {
		reportExitKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.edenmod.tcc_report_exit", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
	}

	public static boolean isDebugForceShow() {
		return debugForceShow;
	}

	public static void setDebugForceShow(boolean value) {
		debugForceShow = value;
	}

	public static boolean isInRoom2() {
		if (debugForceShow) {
			return true;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null) {
			return false;
		}
		double px = mc.player.getX();
		double py = mc.player.getY();
		double pz = mc.player.getZ();
		return px >= MIN_X && px <= MAX_X && pz >= MIN_Z && pz <= MAX_Z && py >= MIN_Y && py <= MAX_Y;
	}

	public static void tick(BridgeConfig config, BridgeWebSocketClient socket) {
		while (reportExitKey != null && reportExitKey.consumeClick()) {
			onReportExitKeybind(config, socket);
		}

		if (config == null || !config.tccLabyrinthMap) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null) {
			return;
		}

		boolean inRoom = isInRoom2();
		if (inRoom && !wasInRoom2) {
			reset();
		} else if (!inRoom && wasInRoom2) {
			reset();
		}
		wasInRoom2 = inRoom;

		if (!inRoom) {
			return;
		}

		long now = System.currentTimeMillis();
		double px = mc.player.getX();
		double py = mc.player.getY();
		double pz = mc.player.getZ();
		float yaw = mc.player.getYRot();
		UUID myUuid = mc.player.getUUID();

		// Wings Player Lock-in: First player to reach Y >= 74 is designated Wings
		if (wingsPlayerUUID == null && py >= WINGS_THRESHOLD_Y) {
			wingsPlayerUUID = myUuid;
		}
		boolean isWings = myUuid.equals(wingsPlayerUUID);

		// Ground player traversal golem check
		if (!isWings) {
			checkTraversalGolems(px, pz, socket);
		}

		// Prune timed-out peers
		PEER_LAST_SEEN.entrySet().removeIf(entry -> {
			if (now - entry.getValue() > PEER_TIMEOUT_MS) {
				PEERS.remove(entry.getKey());
				return true;
			}
			return false;
		});

		// Broadcast own position to peers (throttled, with keepalive heartbeat for stationary players)
		if (now - lastBroadcastMs >= POSITION_BROADCAST_INTERVAL_MS) {
			boolean moved = Math.abs(px - lastSentX) > 0.08 || Math.abs(pz - lastSentZ) > 0.08 || Math.abs(yaw - lastSentYaw) > 1.5f;
			boolean keepalive = (now - lastBroadcastMs >= 2500L);
			if ((moved || keepalive) && socket != null && socket.isConnected()) {
				java.util.List<String> party = getRaidPartyMembers();
				socket.sendTccMapPos(px, py, pz, yaw, isWings, party);
				lastSentX = px;
				lastSentZ = pz;
				lastSentYaw = yaw;
				lastBroadcastMs = now;
			}
		}
	}

	public static void onReportExitKeybind(BridgeConfig config, BridgeWebSocketClient socket) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null) {
			return;
		}
		if (!isInRoom2()) {
			return;
		}
		if (!hasExitTitle) {
			mc.player.displayClientMessage(Component.literal("Cannot report exit: you must reach the exit first (Waiting for players...).").withStyle(ChatFormatting.RED), true);
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastReportKeyMs < REPORT_COOLDOWN_MS) {
			long remainingSec = (REPORT_COOLDOWN_MS - (now - lastReportKeyMs) + 999L) / 1000L;
			mc.player.displayClientMessage(Component.literal("Exit report is on cooldown (" + remainingSec + "s)").withStyle(ChatFormatting.RED), true);
			return;
		}

		ExitPoint prev = confirmedExit;
		double px = mc.player.getX();
		double pz = mc.player.getZ();
		ExitPoint ep = reportExit(px, pz, config, socket);
		if (ep != null) {
			lastReportKeyMs = now;
			String myName = mc.player.getScoreboardName();
			mc.player.displayClientMessage(Component.literal(myName + " has reported an exit!").withStyle(ChatFormatting.GREEN), false);
			if (prev != null && prev != ep) {
				mc.player.displayClientMessage(Component.literal("Corrected TCC Exit: " + ep.displayName).withStyle(ChatFormatting.YELLOW), true);
			} else {
				mc.player.displayClientMessage(Component.literal("Reported TCC Exit: " + ep.displayName).withStyle(ChatFormatting.GREEN), true);
			}
			if (prev == ep && config != null && config.tccExitPing) {
				playDiscoveryPing();
			}
		}
	}

	public static ExitPoint reportExit(double worldX, double worldZ, BridgeConfig config, BridgeWebSocketClient socket) {
		if (!isInRoom2()) {
			return null;
		}
		if (!hasExitTitle) {
			return null;
		}
		float[] mapPix = worldToMapPixels(worldX, worldZ);
		ExitPoint ep = ExitPoint.findClosest(mapPix[0], mapPix[1]);
		if (ep != null) {
			double dx = mapPix[0] - ep.mapPixelX;
			double dy = mapPix[1] - ep.mapPixelY;
			// 80 map pixels (~42 world blocks) max proximity to the exit location (spawn is 140+ pixels away)
			if (dx * dx + dy * dy > 6400.0) {
				Minecraft mc = Minecraft.getInstance();
				if (mc != null && mc.player != null) {
					mc.player.displayClientMessage(Component.literal("Cannot report exit: you are not near an exit location.").withStyle(ChatFormatting.RED), true);
				}
				return null;
			}
			confirmExit(ep, config);
			if (socket != null && socket.isConnected()) {
				java.util.List<String> party = getRaidPartyMembers();
				socket.sendTccExitReached(ep.name(), worldX, worldZ, party);
			}
		}
		return ep;
	}

	private static void checkTraversalGolems(double px, double pz, BridgeWebSocketClient socket) {
		for (Golem g : Golem.values()) {
			if (DEFEATED_GOLEMS.contains(g.id)) {
				continue;
			}
			if (g.isPastBarrier(px, pz)) {
				markGolemDefeated(g.id, socket);
			}
		}
	}

	public static void onTitle(Component component) {
		onTitle(component, null, null);
	}

	public static void onTitle(Component component, BridgeWebSocketClient socket) {
		onTitle(component, null, socket);
	}

	public static void onTitle(Component component, BridgeConfig config, BridgeWebSocketClient socket) {
		if (component == null || !isInRoom2()) {
			return;
		}
		String text = component.getString().toLowerCase();
		if (text.contains("waiting for players")) {
			hasExitTitle = true;
			lastExitTitleMs = System.currentTimeMillis();
		}
	}

	public static void confirmExit(ExitPoint ep, BridgeConfig config) {
		if (ep == null) {
			return;
		}
		boolean wasAlreadyConfirmed = (confirmedExit != null);
		boolean changed = (confirmedExit != ep);
		confirmedExit = ep;
		exitReached = true;
		if ((!wasAlreadyConfirmed || changed) && config != null && config.tccExitPing) {
			playDiscoveryPing();
		}
	}

	private static void playDiscoveryPing() {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.getSoundManager() != null) {
			mc.execute(() -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.25f)));
		}
	}

	public static void markGolemDefeated(String golemId, BridgeWebSocketClient socket) {
		if (golemId == null) {
			return;
		}
		String id = golemId.toUpperCase();
		if (DEFEATED_GOLEMS.add(id)) {
			if (socket != null && socket.isConnected()) {
				socket.sendTccGolemDefeated(id);
			}
		}
	}

	public static void onPeerPosition(String name, String uuidStr, double x, double y, double z, float yaw, boolean isWings) {
		if (!isInRoom2()) {
			return;
		}
		if (x < MIN_X || x > MAX_X || z < MIN_Z || z > MAX_Z) {
			return;
		}
		UUID uuid = null;
		if (uuidStr != null && !uuidStr.isBlank()) {
			try {
				uuid = UUID.fromString(uuidStr);
			} catch (IllegalArgumentException ignored) {
			}
		}
		if (uuid == null && name != null) {
			uuid = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		if (uuid == null) {
			return;
		}

		// First player to reach high Y lock-in
		if (wingsPlayerUUID == null && (isWings || y >= WINGS_THRESHOLD_Y)) {
			wingsPlayerUUID = uuid;
		}
		boolean wingsRole = uuid.equals(wingsPlayerUUID);

		// If this peer is a ground player, their position can verify dead golems
		if (!wingsRole) {
			checkTraversalGolems(x, z, null);
		}

		PEERS.put(uuid, new PlayerEntry(name != null ? name : "Unknown", uuid, x, y, z, yaw, wingsRole, false));
		PEER_LAST_SEEN.put(uuid, System.currentTimeMillis());
	}

	public static void onPeerGolemDefeated(String golemId) {
		if (!isInRoom2()) {
			return;
		}
		if (golemId != null) {
			DEFEATED_GOLEMS.add(golemId.toUpperCase());
		}
	}

	public static void onPeerExitReached(String sender, String path, double x, double z) {
		onPeerExitReached(sender, path, x, z, null);
	}

	public static void onPeerExitReached(String sender, String path, double x, double z, BridgeConfig config) {
		if (!isInRoom2()) {
			return;
		}
		if (x >= MIN_X && x <= MAX_X && z >= MIN_Z && z <= MAX_Z) {
			ExitPoint ep = ExitPoint.fromName(path);
			if (ep == null) {
				float[] mapPix = worldToMapPixels(x, z);
				ep = ExitPoint.findClosest(mapPix[0], mapPix[1]);
			}
			confirmExit(ep, config);
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.player != null) {
				String reporter = (sender != null && !sender.isBlank()) ? sender : "A player";
				mc.player.displayClientMessage(Component.literal(reporter + " has reported an exit!").withStyle(ChatFormatting.GREEN), false);
			}
		}
	}

	public static Set<String> getDefeatedGolems() {
		return Collections.unmodifiableSet(DEFEATED_GOLEMS);
	}

	public static boolean isExitReached() {
		return exitReached;
	}

	public static ExitPoint getConfirmedExit() {
		return confirmedExit;
	}

	public static void setConfirmedExitForTest(ExitPoint ep) {
		confirmedExit = ep;
	}

	public static Collection<PlayerEntry> getPeers() {
		return Collections.unmodifiableCollection(PEERS.values());
	}

	public static UUID getWingsPlayerUUID() {
		return wingsPlayerUUID;
	}

	/**
	 * Calibrated least-squares affine formula mapping Minecraft world coordinates
	 * to pixel coordinates on the 556x775 map texture.
	 */
	public static float[] worldToMapPixels(double worldX, double worldZ) {
		double pixelX = -6461.0940 - 0.011214 * worldX + 1.978794 * worldZ;
		double pixelY = 21347.6896 - 1.895976 * worldX - 0.048631 * worldZ;
		return new float[]{(float) pixelX, (float) pixelY};
	}

	public static void reset() {
		DEFEATED_GOLEMS.clear();
		PEERS.clear();
		PEER_LAST_SEEN.clear();
		wingsPlayerUUID = null;
		exitReached = false;
		confirmedExit = null;
		hasExitTitle = false;
		lastExitTitleMs = 0L;
		lastReportKeyMs = 0L;
		lastBroadcastMs = 0L;
		lastSentX = 0.0;
		lastSentZ = 0.0;
		lastSentYaw = 0.0f;
	}

	public static boolean hasExitTitle() {
		return hasExitTitle;
	}

	public static void setHasExitTitleForTest(boolean value) {
		hasExitTitle = value;
	}

	public static long getLastReportKeyMs() {
		return lastReportKeyMs;
	}

	public static void setLastReportKeyMsForTest(long ms) {
		lastReportKeyMs = ms;
	}

	public static java.util.List<String> getRaidPartyMembers() {
		java.util.Set<String> members = new java.util.LinkedHashSet<>();
		java.util.List<String> wynntilsMembers = tel.eden.mod.party.WynntilsPartyBridge.getPartyMembers();
		if (wynntilsMembers != null) {
			members.addAll(wynntilsMembers);
		}
		java.util.List<String> tracked = tel.eden.mod.party.PartyHealthTracker.getTrackedPartyNames();
		if (tracked != null) {
			members.addAll(tracked);
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.player != null) {
			String myName = mc.player.getScoreboardName();
			if (myName != null && !myName.isBlank()) {
				members.add(myName);
			}
		}
		return new java.util.ArrayList<>(members);
	}
}
