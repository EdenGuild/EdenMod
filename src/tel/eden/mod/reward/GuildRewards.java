package tel.eden.mod.reward;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gifts guild reward items (aspects/tomes/emeralds) to members by driving the
 * in-game guild-manage menu, and dumps all emeralds to a member. Chief/Owner only.
 *
 * <p>The menu is automated as follows: open
 * {@code /gu man}, click slot 0 to open member management, read the rewards summary
 * at slot 27, find the member's item (paging with slot 28), then swap-hotbar the
 * member item onto reward slot 0 (aspect) / 1 (tome) / 2 (emerald) once per unit,
 * 600ms apart. All container interaction runs on the client thread; the orchestration
 * runs on a dedicated background thread so the game is never blocked.
 */
public final class GuildRewards {
	private static final Logger LOGGER = LoggerFactory.getLogger("edenmod");
	private static final long WEEK_MS = 604_800_000L;
	private static final long REFRESH_INTERVAL_MS = 600_000L; // 10 min, like the script
	private static final long MENU_DELAY_MS = 600L;
	private static final long GIFT_DELAY_MS = 600L;
	private static final int REWARDS_SLOT = 27;
	private static final int OPEN_MEMBERS_SLOT = 0;
	private static final int NEXT_PAGE_SLOT = 28;
	private static final int MAX_PAGES = 15;
	private static final int EMERALDS_PER_ITEM = 1024;
	private static final Pattern COUNT = Pattern.compile("(\\d+)\\s*/\\s*\\d+");

	/** A reward kind and how it maps onto the guild-manage menu. */
	public enum RewardType {
		ASPECT(0, "Aspects:", "aspects", "aspects"), TOME(1, "Guild Tomes:", "tomes", null), EMERALD(2, "Emeralds:", "emeralds", "emeralds");

		private final int hotbar;
		private final String loreKey;
		private final String label;
		// The /manage reset kind for this reward (null = no reward-balance reset, e.g. tomes).
		private final String resetKind;

		RewardType(int hotbar, String loreKey, String label, String resetKind) {
			this.hotbar = hotbar;
			this.loreKey = loreKey;
			this.label = label;
			this.resetKind = resetKind;
		}

		/** The per-handout unit label the backend parses (matches the chat wording). */
		public String unitReward() {
			return switch (this) {
				case ASPECT -> "an Aspect";
				case TOME -> "a Guild Tome";
				case EMERALD -> EMERALDS_PER_ITEM + " Emeralds";
			};
		}
	}

	/** Notified once per completed gift run with the exact number of handouts. */
	public interface RewardReporter {
		void report(String receiver, RewardType type, int count);
	}

	/** Notified with the guild's current reward storage (aspects/tomes/emeralds). */
	public interface StorageReporter {
		void report(int aspects, int tomes, long emeralds);
	}

	private volatile RewardReporter reporter;
	private volatile StorageReporter storageReporter;
	// True while a gift run is driving the menu, so the passive tick-time reader in
	// EdenModClient doesn't relay a mid-gift (pre-swap) count; the run relays the exact
	// post-gift value itself.
	private volatile boolean giftInProgress;

	/** Attach the reporter used to send authoritative reward counts to the backend. */
	public void setReporter(RewardReporter reporter) {
		this.reporter = reporter;
	}

	/** Attach the reporter used to relay the guild's live reward storage counts. */
	public void setStorageReporter(StorageReporter storageReporter) {
		this.storageReporter = storageReporter;
	}

	/** Whether a gift run is currently driving the guild-manage menu. */
	public boolean isGiftInProgress() {
		return giftInProgress;
	}

	/**
	 * Whether the rewards summary is on screen right now (slot 27 carries the reward
	 * lore). Cheap enough to poll each client tick. Must run on the client thread.
	 */
	public boolean isRewardsMenuOpen() {
		AbstractContainerMenu menu = menu();
		if (menu == null || REWARDS_SLOT >= menu.slots.size()) {
			return false;
		}
		ItemLore lore = menu.getSlot(REWARDS_SLOT).getItem().get(DataComponents.LORE);
		if (lore == null) {
			return false;
		}
		for (Component line : lore.lines()) {
			if (line.getString().contains(RewardType.EMERALD.loreKey)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Read the current aspect/tome/emerald storage from the open rewards summary, or
	 * {@code null} if it isn't open. Must run on the client thread.
	 */
	public long[] readAllCounts() {
		if (!isRewardsMenuOpen()) {
			return null;
		}
		return new long[]{readRewardCount(RewardType.ASPECT.loreKey), readRewardCount(RewardType.TOME.loreKey), readRewardCount(RewardType.EMERALD.loreKey)};
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "eden-guild-rewards");
		t.setDaemon(true);
		return t;
	});

	private volatile String rank = "";
	private volatile Map<String, Long> members = Map.of();
	private volatile long lastRefresh = 0L;
	private volatile boolean refreshing;

	/** Whether the linked player is a Chief or Owner (only they may gift). */
	public boolean isChief() {
		return rank.equalsIgnoreCase("chief") || rank.equalsIgnoreCase("owner");
	}

	/** Current known member usernames (for command tab-completion). */
	public List<String> memberNames() {
		return new ArrayList<>(members.keySet());
	}

	/** Whether {@code name} is a current member of the player's guild (case-insensitive). */
	public boolean isMember(String name) {
		if (name == null || name.isBlank()) {
			return false;
		}
		for (String member : members.keySet()) {
			if (member.equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	/** Refresh the rank + member list from the API if stale, off-thread (non-blocking). */
	public void ensureFresh(String playerName) {
		long now = System.currentTimeMillis();
		if (refreshing || (now - lastRefresh < REFRESH_INTERVAL_MS && !members.isEmpty())) {
			return;
		}
		if (playerName == null || playerName.isBlank()) {
			return;
		}
		refreshing = true;
		worker.submit(() -> {
			try {
				refresh(playerName);
			} catch (Exception e) {
				LOGGER.warn("Guild rewards refresh failed: {}", e.toString());
			} finally {
				refreshing = false;
			}
		});
	}

	private void refresh(String playerName) throws Exception {
		JsonObject player = getJson("https://api.wynncraft.com/v3/player/" + URLEncoder.encode(playerName, StandardCharsets.UTF_8));
		if (player == null || !player.has("guild") || player.get("guild").isJsonNull()) {
			rank = "";
			members = Map.of();
			return;
		}
		JsonObject guild = player.getAsJsonObject("guild");
		rank = guild.has("rank") ? guild.get("rank").getAsString() : "";
		String guildName = guild.get("name").getAsString();
		JsonObject g = getJson("https://api.wynncraft.com/v3/guild/" + URLEncoder.encode(guildName, StandardCharsets.UTF_8));
		members = parseMembers(g);
		lastRefresh = System.currentTimeMillis();
	}

	private static Map<String, Long> parseMembers(JsonObject guild) {
		Map<String, Long> out = new HashMap<>();
		if (guild == null || !guild.has("members") || !guild.get("members").isJsonObject()) {
			return out;
		}
		for (var role : guild.getAsJsonObject("members").entrySet()) {
			if (role.getKey().equals("total") || !role.getValue().isJsonObject()) {
				continue;
			}
			for (var member : role.getValue().getAsJsonObject().entrySet()) {
				if (!member.getValue().isJsonObject()) {
					continue;
				}
				JsonObject data = member.getValue().getAsJsonObject();
				long joined = data.has("joined") ? Instant.parse(data.get("joined").getAsString()).toEpochMilli() : 0L;
				out.put(member.getKey(), joined);
			}
		}
		return out;
	}

	private JsonObject getJson(String url) throws Exception {
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IllegalStateException(url + " returned " + response.statusCode());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	/** Gift {@code amount} of {@code type} to {@code name} (one menu run, off-thread). */
	public void gift(String name, RewardType type, int amount) {
		worker.submit(() -> run(name, type, amount, false));
	}

	/** Gift all available emeralds to {@code name}. */
	public void dumpEmeralds(String name) {
		worker.submit(() -> run(name, RewardType.EMERALD, 0, true));
	}

	private void run(String name, RewardType type, int requested, boolean dump) {
		giftInProgress = true;
		try {
			if (!isChief()) {
				chat("Only guild Chiefs can gift rewards.", ChatFormatting.RED);
				return;
			}
			Long joined = members.get(name);
			if (joined == null) {
				chat("Unknown member: " + name, ChatFormatting.RED);
				return;
			}
			if (System.currentTimeMillis() - joined < WEEK_MS) {
				chat(name + " has not been in the guild for a week, and is not eligible " + "for rewards.", ChatFormatting.YELLOW);
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			onClientRun(() -> {
				if (mc.getConnection() != null) {
					mc.getConnection().sendCommand("gu man");
				}
			});
			sleep(MENU_DELAY_MS);
			onClientRun(() -> click(OPEN_MEMBERS_SLOT));
			sleep(MENU_DELAY_MS);
			if (!Boolean.TRUE.equals(onClient(this::containerOpen))) {
				chat("Couldn't open the guild manage menu — try again.", ChatFormatting.RED);
				return;
			}
			// Read all three counts up front (index == RewardType.hotbar): the gifted
			// type gives us `available`, and the trio becomes the post-gift snapshot.
			long[] counts = onClient(this::readAllCounts);
			if (counts == null) {
				counts = new long[]{0, 0, 0};
			}
			int available = (int) counts[type.hotbar];
			int availableItems = type == RewardType.EMERALD ? available / EMERALDS_PER_ITEM : available;
			// Never attempt to gift more than the guild actually has; this both avoids
			// wasted clicks and keeps the reported handout count exact.
			int amount = dump ? availableItems : Math.min(requested, availableItems);
			if (amount <= 0) {
				chat("There aren't any " + type.label + " to gift!", ChatFormatting.YELLOW);
				onClientRun(this::closeMenu);
				return;
			}
			int slot = findMemberSlot(name);
			if (slot < 0) {
				chat("Couldn't find " + name + "'s item in the menu.", ChatFormatting.RED);
				onClientRun(this::closeMenu);
				return;
			}
			int total = type == RewardType.EMERALD ? amount * EMERALDS_PER_ITEM : amount;
			chat("Gifting " + name + " " + total + " " + type.label + "...", ChatFormatting.GREEN);
			for (int i = 0; i < amount; i++) {
				final int target = slot;
				onClientRun(() -> swapHotbar(target, type.hotbar));
				sleep(GIFT_DELAY_MS);
			}
			onClientRun(this::closeMenu);
			// Report the exact handout count so the backend logs the right total even
			// if the server bunched some identical reward announcements together.
			RewardReporter currentReporter = reporter;
			if (currentReporter != null) {
				currentReporter.report(name, type, amount);
			}
			// Relay the exact storage left after this run (authoritative final value):
			// decrement the gifted type by what we just handed out.
			long[] finalCounts = counts.clone();
			finalCounts[type.hotbar] -= type == RewardType.EMERALD ? (long) amount * EMERALDS_PER_ITEM : amount;
			StorageReporter currentStorageReporter = storageReporter;
			if (currentStorageReporter != null) {
				currentStorageReporter.report((int) finalCounts[0], (int) finalCounts[1], finalCounts[2]);
			}
			if (type.resetKind != null) {
				// Show the matching /manage reset command, clickable to copy, so the
				// pending balance can be zeroed on Discord after the in-game payout.
				String command = "/manage reset kind:" + type.resetKind + " player:" + name;
				chatComponent(Component.literal(command).withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withUnderlined(true).withClickEvent(new ClickEvent.CopyToClipboard(command)).withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy this command")))));
			} else {
				chat("Done — gifted " + name + " " + total + " " + type.label + ".", ChatFormatting.GREEN);
			}
		} catch (Exception e) {
			LOGGER.warn("Gift run failed", e);
			chat("Gift failed: " + e.getMessage(), ChatFormatting.RED);
		} finally {
			giftInProgress = false;
		}
	}

	private int findMemberSlot(String name) {
		Set<String> seen = new LinkedHashSet<>();
		for (int page = 0; page < MAX_PAGES; page++) {
			Integer found = onClient(() -> findSlotByName(name, seen));
			if (found != null && found >= 0) {
				return found;
			}
			onClientRun(() -> click(NEXT_PAGE_SLOT));
			sleep(MENU_DELAY_MS);
		}
		// Not found across every page — log what names we did see so a mismatch in
		// how Wynncraft renders member-item names is easy to diagnose from the log.
		LOGGER.info("Gift: member '{}' not found. Item names seen: {}", name, seen);
		return -1;
	}

	// -- client-thread operations (must run on the render thread) ---------------

	private boolean containerOpen() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu;
	}

	private int findSlotByName(String name, Set<String> seen) {
		AbstractContainerMenu menu = menu();
		if (menu == null) {
			return -1;
		}
		String wanted = normalizeName(name);
		for (int i = 0; i < menu.slots.size(); i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (stack.isEmpty()) {
				continue;
			}
			String raw = stack.getHoverName().getString();
			seen.add(raw);
			// Wynncraft wraps item names in private-use font glyphs and styling, so
			// compare on just the username characters (case-insensitive).
			if (normalizeName(raw).equals(wanted)) {
				return i;
			}
		}
		return -1;
	}

	/** Reduce a rendered name to its bare username characters ([A-Za-z0-9_], lowercased).
	 *
	 * <p>Wynncraft member-item names carry literal legacy {@code §}-formatting codes
	 * (e.g. {@code §f§lPlayerName}) and font glyphs, so a {@code §} and the format char
	 * after it are skipped wholesale — otherwise the {@code f}/{@code l} from {@code §f§l}
	 * would leak into the username and break the match.
	 */
	private static String normalizeName(String raw) {
		StringBuilder out = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '§') { // section sign: skip it and the following format code
				i++;
				continue;
			}
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
				out.append(c);
			} else if (c >= 'A' && c <= 'Z') {
				out.append((char) (c + ('a' - 'A')));
			}
		}
		return out.toString();
	}

	private int readRewardCount(String loreKey) {
		AbstractContainerMenu menu = menu();
		if (menu == null || REWARDS_SLOT >= menu.slots.size()) {
			return 0;
		}
		ItemStack stack = menu.getSlot(REWARDS_SLOT).getItem();
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) {
			return 0;
		}
		for (Component line : lore.lines()) {
			String text = line.getString();
			if (text.contains(loreKey)) {
				Matcher matcher = COUNT.matcher(text);
				if (matcher.find()) {
					return Integer.parseInt(matcher.group(1));
				}
			}
		}
		return 0;
	}

	private void click(int slot) {
		Minecraft mc = Minecraft.getInstance();
		AbstractContainerMenu menu = menu();
		if (mc.gameMode != null && mc.player != null && menu != null) {
			mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.PICKUP, mc.player);
		}
	}

	private void swapHotbar(int slot, int hotbar) {
		Minecraft mc = Minecraft.getInstance();
		AbstractContainerMenu menu = menu();
		if (mc.gameMode != null && mc.player != null && menu != null) {
			mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, hotbar, ClickType.SWAP, mc.player);
		}
	}

	private void closeMenu() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.closeContainer();
		}
	}

	private static AbstractContainerMenu menu() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || player.containerMenu == player.inventoryMenu) {
			return null;
		}
		return player.containerMenu;
	}

	// -- threading helpers ------------------------------------------------------

	private static <T> T onClient(Supplier<T> action) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) {
			return action.get();
		}
		CompletableFuture<T> future = new CompletableFuture<>();
		mc.execute(() -> {
			try {
				future.complete(action.get());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		try {
			return future.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException("client action failed", e);
		}
	}

	private static void onClientRun(Runnable action) {
		onClient(() -> {
			action.run();
			return null;
		});
	}

	private static void chat(String message, ChatFormatting color) {
		chatComponent(Component.literal(message).withStyle(color));
	}

	private static void chatComponent(Component message) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.displayClientMessage(message, false);
			}
		});
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Shut down the worker thread (on mod disconnect/close). */
	public void shutdown() {
		worker.shutdownNow();
	}
}
