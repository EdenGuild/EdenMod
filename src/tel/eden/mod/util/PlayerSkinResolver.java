package tel.eden.mod.util;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import tel.eden.mod.EdenLogger;

/**
 * Shared player skin resolver for HUDs and menus, checking the live player list (when textures are
 * populated) then the shared Mojang-resolved cache, with an off-thread rate-limited session lookup.
 */
public final class PlayerSkinResolver {
	private static final EdenLogger LOGGER = EdenLogger.get();
	private static final Pattern DASHLESS_UUID = Pattern.compile("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})");
	private static final Map<UUID, PlayerSkin> RESOLVED_SKINS = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> SKIN_NEXT_ATTEMPT = new ConcurrentHashMap<>();
	private static final long SKIN_RETRY_MS = 30_000L;
	private static final long SKIN_FAIL_RETRY_MS = 10_000L;
	private static final long WORKER_PACE_MS = 200L;
	private static final ExecutorService SKIN_WORKER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "eden-skin-resolver");
		thread.setDaemon(true);
		return thread;
	});

	private PlayerSkinResolver() {
	}

	/** Parse either a hyphenated or 32-char dashless UUID string, returning {@code null} if invalid. */
	public static UUID parseUuid(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String trimmed = raw.trim();
		if (trimmed.length() == 32 && trimmed.indexOf('-') < 0) {
			trimmed = DASHLESS_UUID.matcher(trimmed).replaceFirst("$1-$2-$3-$4-$5");
		}
		try {
			return UUID.fromString(trimmed);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Resolve a skin from a raw UUID string (optional) and fallback IGN. */
	public static PlayerSkin skinForId(Minecraft mc, String uuidStr, String name) {
		return skinFor(mc, parseUuid(uuidStr), name);
	}

	/** Resolve a skin by parsed UUID and fallback IGN. */
	public static PlayerSkin skinFor(Minecraft mc, UUID uuid, String name) {
		if (uuid == null) {
			if (name == null || name.isBlank()) {
				return DefaultPlayerSkin.getDefaultSkin();
			}
			PlayerSkin online = onlineSkin(mc, null, name);
			if (online != null) {
				return online;
			}
			return DefaultPlayerSkin.get(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
		}
		PlayerSkin online = onlineSkin(mc, uuid, name);
		if (online != null) {
			return online;
		}
		PlayerSkin resolved = RESOLVED_SKINS.get(uuid);
		if (resolved != null) {
			return resolved;
		}
		if (uuid.version() != 3) {
			fetchSkin(mc, uuid, name);
		}
		return DefaultPlayerSkin.get(uuid);
	}

	private static PlayerSkin onlineSkin(Minecraft mc, UUID uuid, String name) {
		if (mc == null || mc.getConnection() == null) {
			return null;
		}
		PlayerInfo info = uuid != null ? mc.getConnection().getPlayerInfo(uuid) : null;
		if (info == null && name != null && !name.isBlank()) {
			info = mc.getConnection().getPlayerInfo(name);
		}
		// Wynncraft cross-world tab/guild entries often omit texture properties; fall back to
		// Mojang session lookup unless the live entry actually carries a texture property.
		if (info != null && !info.getProfile().properties().get("textures").isEmpty()) {
			return info.getSkin();
		}
		return null;
	}

	private static void fetchSkin(Minecraft mc, UUID uuid, String name) {
		if (mc == null) {
			return;
		}
		long now = System.currentTimeMillis();
		Long next = SKIN_NEXT_ATTEMPT.get(uuid);
		if (next != null && now < next) {
			return;
		}
		SKIN_NEXT_ATTEMPT.put(uuid, now + SKIN_RETRY_MS);
		LOGGER.debug("Resolving skin for {} ({})", name, uuid);
		SKIN_WORKER.submit(() -> {
			try {
				// requireSecure=true avoids Yggdrasil's 6h negative cache when rate-limited (HTTP 429).
				var result = mc.services().sessionService().fetchProfile(uuid, true);
				if (result != null && result.profile() != null) {
					GameProfile profile = result.profile();
					mc.execute(() -> mc.getSkinManager().get(profile).thenAccept(skin -> skin.ifPresent(value -> RESOLVED_SKINS.put(uuid, value))));
				} else {
					SKIN_NEXT_ATTEMPT.put(uuid, System.currentTimeMillis() + SKIN_FAIL_RETRY_MS);
				}
			} catch (RuntimeException e) {
				LOGGER.debug("Skin fetch failed for {}", uuid, e);
				SKIN_NEXT_ATTEMPT.put(uuid, System.currentTimeMillis() + SKIN_FAIL_RETRY_MS);
			} finally {
				try {
					Thread.sleep(WORKER_PACE_MS);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
				}
			}
		});
	}

	public static void reset() {
		RESOLVED_SKINS.clear();
		SKIN_NEXT_ATTEMPT.clear();
	}
}
