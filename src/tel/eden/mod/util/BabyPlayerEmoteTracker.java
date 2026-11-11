package tel.eden.mod.util;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import tel.eden.mod.render.EdenDisplayState;

/**
 * Render-only state shared by the player-head and display renderers used for Wynncraft
 * player-style composites. Minecraft submits body pieces and held items independently,
 * while EdenMod needs to treat them as one actor.
 */
public final class BabyPlayerEmoteTracker {
	// Keep display association alive across a couple of slow render frames, but clear it
	// quickly enough that the normal held-item layer returns as soon as the emote ends.
	private static final long EMOTE_ACTIVE_GRACE_NANOS = 150_000_000L;
	private static final long PIVOT_CACHE_NANOS = 2_000_000_000L;
	private static final double NEARBY_DISPLAY_DISTANCE_SQUARED = 25.0;
	private static final int FIRST_LEG_LIMB_INDEX = 4;
	private static final int COMPLETE_LEG_MASK = 0b11;
	private static final ConcurrentHashMap<UUID, Long> LAST_RENDERED = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, PivotState> PIVOTS = new ConcurrentHashMap<>();
	// A display can invoke the player-head renderer several layers below its own submit
	// call. Keep the source state available without depending on an optional renderer mod.
	private static final ThreadLocal<ArrayDeque<DisplayEntityRenderState>> DISPLAY_SUBMISSIONS = new ThreadLocal<>();
	// Player classification runs from several render hooks. Cache the address-derived
	// server gate so player-shaped NPCs do not repeatedly normalize the same address.
	private static String classifiedServerAddress;
	private static boolean classifiedServerIsWynncraft;

	private BabyPlayerEmoteTracker() {
	}

	/** Record that at least one body part was rendered for this player. */
	public static void markActive(UUID playerId) {
		LAST_RENDERED.put(playerId, System.nanoTime());
	}

	public static boolean isActive(UUID playerId) {
		Long lastRendered = LAST_RENDERED.get(playerId);
		if (lastRendered == null) {
			return false;
		}
		if (System.nanoTime() - lastRendered <= EMOTE_ACTIVE_GRACE_NANOS) {
			return true;
		}
		LAST_RENDERED.remove(playerId, lastRendered);
		return false;
	}

	public static boolean isActive(AbstractClientPlayer player) {
		// Passenger state alone does not imply an emote. Treating it that way leaves a
		// normal mount associated with an emote long after its final body part disappeared.
		// The map lookup is far cheaper than classifying the player, and most players are not active.
		return isActive(player.getUUID()) && isActualPlayer(player);
	}

	/**
	 * Distinguish account-backed players from player-shaped server actors.
	 *
	 * <p>Wynncraft keeps skin information for some NPCs, so presence in the normal
	 * player-info map is not enough there. Same-world players retain their version-4
	 * account UUID. Cross-server player projections and NPCs both use version-2 UUIDs,
	 * but only the former carry Wynncraft's cross-server scoreboard-team marker. Other
	 * servers keep the vanilla player-info check so offline-mode UUIDs are not rejected
	 * solely because they are not version 4.
	 */
	public static boolean isActualPlayer(AbstractClientPlayer player) {
		Minecraft minecraft = Minecraft.getInstance();
		if (player == minecraft.player) {
			return true;
		}
		if (isOnWynncraft(minecraft) && "?".equals(player.getScoreboardName())) {
			return false;
		}
		UUID playerId = player.getUUID();
		// Ordinary account-backed players retain version-4 UUIDs. Wynncraft's
		// player-shaped NPCs do not, so this remains stable without a render-time cache.
		if (playerId.version() == 4) {
			return true;
		}
		if (isOnWynncraft(minecraft)) {
			var team = player.getTeam();
			return team != null && isCrossServerPlayerTeam(team.getName());
		}

		// Outside Wynncraft, retain the vanilla player-info check so offline-mode
		// entities are accepted without weakening Wynncraft's NPC filtering.
		var connection = minecraft.getConnection();
		return connection != null && connection.getPlayerInfo(playerId) != null;
	}

	public static boolean isActualPlayer(AvatarRenderState state) {
		Minecraft minecraft = Minecraft.getInstance();
		Entity entity = minecraft.level == null ? null : minecraft.level.getEntity(state.id);
		return entity instanceof AbstractClientPlayer player && isActualPlayer(player);
	}

	/** Make the source display available to special item renderers called beneath it. */
	public static void beginDisplaySubmission(DisplayEntityRenderState state) {
		ArrayDeque<DisplayEntityRenderState> submissions = DISPLAY_SUBMISSIONS.get();
		if (submissions == null) {
			submissions = new ArrayDeque<>();
			DISPLAY_SUBMISSIONS.set(submissions);
		}
		submissions.push(state);
	}

	public static void endDisplaySubmission() {
		ArrayDeque<DisplayEntityRenderState> submissions = DISPLAY_SUBMISSIONS.get();
		if (submissions != null && !submissions.isEmpty()) {
			submissions.pop();
		}
		if (submissions != null && submissions.isEmpty()) {
			DISPLAY_SUBMISSIONS.remove();
		}
	}

	public static Display currentDisplaySubmission() {
		ArrayDeque<DisplayEntityRenderState> submissions = DISPLAY_SUBMISSIONS.get();
		return submissions == null || submissions.isEmpty() ? null : EdenDisplayState.sourceOf(submissions.peek());
	}

	/** Match Wynncraft's cross-server team names, for example {@code _WC12}. */
	private static boolean isCrossServerPlayerTeam(String teamName) {
		if (teamName == null || teamName.length() < 3 || teamName.charAt(0) != '_') {
			return false;
		}
		int index = 1;
		while (index < teamName.length() && teamName.charAt(index) >= 'A' && teamName.charAt(index) <= 'Z') {
			index++;
		}
		if (index == 1 || index == teamName.length()) {
			return false;
		}
		while (index < teamName.length()) {
			char character = teamName.charAt(index++);
			if (character < '0' || character > '9') {
				return false;
			}
		}
		return true;
	}

	private static boolean isOnWynncraft(Minecraft minecraft) {
		var server = minecraft.getCurrentServer();
		if (server == null) {
			return false;
		}
		String address = server.ip;
		if (!Objects.equals(address, classifiedServerAddress)) {
			classifiedServerAddress = address;
			classifiedServerIsWynncraft = Wynncraft.isWynncraft(address);
		}
		return classifiedServerIsWynncraft;
	}

	/** Find a real client player directly attached to this display/carrier hierarchy. */
	public static AbstractClientPlayer findPlayerInHierarchy(Entity entity) {
		Entity root = entity.getRootVehicle();
		if (root instanceof AbstractClientPlayer player && isActualPlayer(player)) {
			return player;
		}
		for (Entity passenger : root.getIndirectPassengers()) {
			if (passenger instanceof AbstractClientPlayer player && isActualPlayer(player)) {
				return player;
			}
		}
		return null;
	}

	public static AbstractClientPlayer findActiveOwner(Entity entity) {
		// Prefer the vehicle hierarchy used by Wynncraft emotes. The proximity fallback
		// covers companion displays that are spawned alongside, rather than mounted to, it.
		Entity root = entity.getRootVehicle();
		if (root instanceof AbstractClientPlayer player && isActive(player)) {
			return player;
		}
		for (Entity passenger : root.getIndirectPassengers()) {
			if (passenger instanceof AbstractClientPlayer player && isActive(player)) {
				return player;
			}
		}

		AbstractClientPlayer nearest = null;
		double nearestDistance = NEARBY_DISPLAY_DISTANCE_SQUARED;
		// Some effect and item displays are not passengers. Only consider active
		// emote players within five blocks so ordinary displays are left alone.
		// Walk the players that rendered recently instead of every player in the level: with
		// Wynncraft's ghosts that is hundreds of players per display per frame. Entries older
		// than the grace period are dropped here, as isActive would have done.
		long now = System.nanoTime();
		Level level = entity.level();
		for (Iterator<Map.Entry<UUID, Long>> iterator = LAST_RENDERED.entrySet().iterator(); iterator.hasNext();) {
			Map.Entry<UUID, Long> entry = iterator.next();
			if (now - entry.getValue() > EMOTE_ACTIVE_GRACE_NANOS) {
				iterator.remove();
				continue;
			}
			if (!(level.getPlayerByUUID(entry.getKey()) instanceof AbstractClientPlayer player) || !isActualPlayer(player)) {
				continue;
			}
			// A rider's mount is drawn in the same packed format as an emote, which keeps the
			// rider "active" for the whole ride. Its own parts are in the vehicle hierarchy and
			// were handled above; anything merely nearby (time-trial rings, mobs, other players'
			// cosmetics) is not part of it and must not be scaled.
			if (isGroundedMount(player.getRootVehicle())) {
				continue;
			}
			double distance = entity.distanceToSqr(player);
			if (distance < nearestDistance) {
				nearest = player;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	public static long currentRenderStamp() {
		Minecraft minecraft = Minecraft.getInstance();
		long gameTick = minecraft.level == null ? 0L : minecraft.level.getGameTime();
		int partialTick = Float.floatToRawIntBits(minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		// Partial tick distinguishes rendered frames within one game tick without a
		// counter that would depend on which mixin happened to render first.
		return gameTick << 32 ^ Integer.toUnsignedLong(partialTick);
	}

	public static synchronized void samplePivot(UUID playerId, int limbIndex, double minX, double maxX, double groundY, double minZ, double maxZ, long renderStamp) {
		long now = System.nanoTime();
		PivotState state = PIVOTS.computeIfAbsent(playerId, ignored -> new PivotState());
		if (state.pendingStamp != renderStamp) {
			// An incomplete leg pair cannot define a reliable centre, so discard it when
			// the next render pass begins instead of mixing samples from two passes.
			state.resetPending(renderStamp);
		}
		state.minX = Math.min(state.minX, minX);
		state.maxX = Math.max(state.maxX, maxX);
		state.groundY = Math.min(state.groundY, groundY);
		state.minZ = Math.min(state.minZ, minZ);
		state.maxZ = Math.max(state.maxZ, maxZ);
		state.legMask |= 1 << (limbIndex - FIRST_LEG_LIMB_INDEX);
		if (state.legMask == COMPLETE_LEG_MASK) {
			// Publish as soon as both legs are known. framePivot remains immutable for
			// this pass, while the following pass can start on the measured ground point.
			state.publishCompletePivot(now);
		}
	}

	public static synchronized Vec3 pivotForRender(UUID playerId, long renderStamp, Vec3 defaultPosition) {
		PivotState state = PIVOTS.computeIfAbsent(playerId, ignored -> new PivotState());
		if (state.frameStamp != renderStamp) {
			state.frameStamp = renderStamp;
			// Every part rendered in this pass must observe one immutable pivot. A
			// partially collected pair of legs is published only for a later pass.
			state.framePivot = state.stablePivot != null && System.nanoTime() - state.stableAt <= PIVOT_CACHE_NANOS ? state.stablePivot : defaultPosition;
		}
		if (state.framePivot == null) {
			return defaultPosition;
		}
		return state.framePivot;
	}

	/**
	 * Return the common scale origin for one fake-player render pass. Wynncraft's
	 * horse-mounted model is built around the mount, so scaling it around the rider's
	 * legs lifts the mount off the ground. Emote carriers are deliberately excluded:
	 * they also appear as vehicles, but their models still need the measured foot pivot.
	 */
	public static Vec3 scalePivotForRender(AbstractClientPlayer player, long renderStamp, Vec3 defaultPosition, float partialTick) {
		Entity rootVehicle = player.getRootVehicle();
		if (isGroundedMount(rootVehicle)) {
			return rootVehicle.getPosition(partialTick);
		}
		return pivotForRender(player.getUUID(), renderStamp, defaultPosition);
	}

	/** Wynncraft's rideable mounts use Minecraft's horse entity hierarchy. */
	public static boolean isGroundedMount(Entity entity) {
		return entity instanceof AbstractHorse;
	}

	public record EmoteMetadata(int limbIndex, int elementKind) {
	}

	private static final class PivotState {
		// pending* collects the two leg bounds for one frame. stablePivot is the last
		// complete result. framePivot is the immutable snapshot used by one render pass.
		private long pendingStamp = Long.MIN_VALUE;
		private int legMask;
		private double minX;
		private double maxX;
		private double groundY;
		private double minZ;
		private double maxZ;
		private Vec3 stablePivot;
		private long stableAt;
		private long frameStamp = Long.MIN_VALUE;
		private Vec3 framePivot;

		private void publishCompletePivot(long now) {
			stablePivot = new Vec3((minX + maxX) * 0.5, groundY, (minZ + maxZ) * 0.5);
			stableAt = now;
		}

		private void resetPending(long renderStamp) {
			pendingStamp = renderStamp;
			legMask = 0;
			minX = Double.POSITIVE_INFINITY;
			maxX = Double.NEGATIVE_INFINITY;
			groundY = Double.POSITIVE_INFINITY;
			minZ = Double.POSITIVE_INFINITY;
			maxZ = Double.NEGATIVE_INFINITY;
		}
	}
}
