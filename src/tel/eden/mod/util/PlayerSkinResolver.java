package tel.eden.mod.util;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Resolves player skins for HUDs and menus, serving from the local client player list
 * or an off-thread Mojang profile cache, falling back to a Steve/Alex skin.
 */
public final class PlayerSkinResolver {
	private static final Map<UUID, PlayerSkin> RESOLVED_SKINS = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> SKIN_NEXT_ATTEMPT = new ConcurrentHashMap<>();
	private static final long SKIN_RETRY_MS = 30_000L;
	private static final ExecutorService SKIN_WORKER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "eden-skin-resolver");
		thread.setDaemon(true);
		return thread;
	});

	private PlayerSkinResolver() {
	}

	public static PlayerSkin skinFor(Minecraft mc, UUID uuid, String name) {
		if (uuid == null) {
			if (name != null && !name.isBlank()) {
				uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
			} else {
				return DefaultPlayerSkin.getDefaultSkin();
			}
		}
		if (mc != null && mc.getConnection() != null) {
			PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
			if (info == null && name != null && !name.isBlank()) {
				info = mc.getConnection().getPlayerInfo(name);
			}
			if (info != null) {
				return info.getSkin();
			}
		}
		PlayerSkin resolved = RESOLVED_SKINS.get(uuid);
		if (resolved != null) {
			return resolved;
		}
		fetchSkin(mc, uuid);
		return DefaultPlayerSkin.get(uuid);
	}

	private static void fetchSkin(Minecraft mc, UUID uuid) {
		if (mc == null) {
			return;
		}
		long now = System.currentTimeMillis();
		Long next = SKIN_NEXT_ATTEMPT.get(uuid);
		if (next != null && now < next) {
			return;
		}
		SKIN_NEXT_ATTEMPT.put(uuid, now + SKIN_RETRY_MS);
		SKIN_WORKER.submit(() -> {
			try {
				var result = mc.services().sessionService().fetchProfile(uuid, false);
				if (result != null) {
					com.mojang.authlib.GameProfile profile = result.profile();
					mc.execute(() -> mc.getSkinManager().get(profile).thenAccept(skin -> skin.ifPresent(value -> RESOLVED_SKINS.put(uuid, value))));
				}
			} catch (Exception ignored) {
			}
		});
	}

	public static void reset() {
		RESOLVED_SKINS.clear();
		SKIN_NEXT_ATTEMPT.clear();
	}
}
