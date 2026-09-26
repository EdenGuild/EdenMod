package tel.eden.mod.guild;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import tel.eden.mod.EdenLogger;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.net.BridgeWebSocketClient;
import tel.eden.mod.reward.GuildRewards;

/**
 * Passively reads whichever guild menu a member has open themselves — Manage, Members
 * (reward storage), or the Guild Log — and relays it, exactly like {@code
 * AllianceMenuScraper} already does for the Diplomacy menu. This performs no inventory
 * interaction of its own: no clicks, no page flips, no filter switches. It only reads
 * packets Wynncraft is already sending to render the player's own screen, so it stays
 * outside the mod-team rule this whole feature otherwise has to respect ("inventory
 * actions cannot be conducted while the player is freely moving") — there's no
 * *interaction* here at all, foreground or background, just observation of one that the
 * player themselves is already doing.
 *
 * <p>Reuses {@link GuildLogSync}'s own parsing/reporting methods (they take items
 * directly rather than reading its background session) so the same code path also
 * backs the background scan whenever that's re-enabled — see those methods' own docs.
 */
public final class GuildMenuScraper {
	private GuildMenuScraper() {
	}

	private static final EdenLogger LOGGER = EdenLogger.get();
	// Hotbar + main inventory, appended to every container menu after its own slots —
	// same constant AllianceMenuScraper uses for the same reason.
	private static final int PLAYER_INVENTORY_SLOTS = 36;
	private static final int MEMBERS_SLOTS = 45;
	private static final Pattern COLOR_CODE = Pattern.compile("[§&][0-9a-fk-orA-FK-OR]");

	private static GuildManageSnapshot lastManageSent;
	private static List<GuildManageSnapshot.Alliance> lastManageAlliancesSent;
	private static GuildRewardStorageSnapshot lastStorageSent;
	private static final Map<String, List<GuildLogEntry>> lastLogEntriesSent = new HashMap<>();

	/** Client-thread tick: read whatever guild menu is currently open, if any. */
	public static void onTick(Minecraft mc) {
		if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
			return;
		}
		GuildRewards guildRewards = EdenModClient.instance().guildRewards();
		if (!guildRewards.isChiefOwnerOrStrategist()) {
			return;
		}
		String title = stripCodes(screen.getTitle().getString()).trim();
		AbstractContainerMenu menu = screen.getMenu();
		int logIndex = title.indexOf("Log: ");
		List<ItemStack> items;
		if (title.contains("Manage") && (items = containerItems(menu, GuildLogSync.MANAGE_SLOTS)) != null) {
			scrapeManage(items);
		} else if (title.contains("Members") && (items = containerItems(menu, MEMBERS_SLOTS)) != null) {
			scrapeStorage(items);
		} else if (logIndex >= 0 && guildRewards.isChief() && (items = containerItems(menu, GuildLogSync.LOG_SLOTS)) != null) {
			// Guild Log rows feed reward/pending-balance reconciliation, unlike the
			// merely informational Manage/Members/allies reads above, so this stays at
			// the same Chief/Owner trust tier the rest of guild-log syncing already uses.
			scrapeLog(title.substring(logIndex + "Log: ".length()).trim(), items);
		}
	}

	/** Forget everything relayed so far (world change / disconnect) — reopening re-reports. */
	public static void reset() {
		lastManageSent = null;
		lastManageAlliancesSent = null;
		lastStorageSent = null;
		lastLogEntriesSent.clear();
	}

	private static void scrapeManage(List<ItemStack> items) {
		GuildManageMenuParser.parse(items).ifPresent(parsed -> {
			if (!parsed.equals(lastManageSent)) {
				lastManageSent = parsed;
				EdenModClient.instance().guildLogSync().reportManageSnapshot(items);
			}
			relayAlliancesFromManage(parsed.alliances());
		});
	}

	/**
	 * The root Manage menu's Diplomacy button already lists every ally in its own
	 * tooltip, so a member opening just the Manage screen — without drilling into the
	 * separate Diplomacy sub-menu {@code AllianceMenuScraper} watches — still keeps the
	 * alliance list fresh. Sent the same way that scraper sends it (there is deliberately
	 * no cross-dedup between the two; the backend already treats every reading as
	 * "newest wins", so a harmless duplicate costs nothing).
	 */
	private static void relayAlliancesFromManage(List<GuildManageSnapshot.Alliance> alliances) {
		if (alliances.isEmpty()) {
			// The Diplomacy button's tooltip can render its "Guild Alliance:" heading
			// before Wynncraft finishes populating the entries under it, which reads
			// exactly like "no allies" — confirmed in production (a real alliance list
			// briefly got wiped to 0 this way). Unlike the dedicated Diplomacy screen
			// (AllianceMenuScraper), this quicker read has no multi-tick stability
			// check, so treat an empty reading here as "not loaded yet", not "genuinely
			// no allies" (implausible for this guild). The dedicated screen still
			// correctly reports a genuine drop to zero.
			return;
		}
		if (alliances.equals(lastManageAlliancesSent)) {
			return;
		}
		BridgeWebSocketClient socket = EdenModClient.instance().socket();
		if (socket == null) {
			return;
		}
		List<String> names = new ArrayList<>();
		List<String> tags = new ArrayList<>();
		for (GuildManageSnapshot.Alliance ally : alliances) {
			names.add(ally.name());
			tags.add(ally.tag());
		}
		if (socket.sendGuildAlliances(names, tags)) {
			lastManageAlliancesSent = alliances;
		}
	}

	private static void scrapeStorage(List<ItemStack> items) {
		GuildRewardStorageParser.parse(items).ifPresent(snapshot -> {
			if (snapshot.equals(lastStorageSent)) {
				return;
			}
			lastStorageSent = snapshot;
			EdenModClient.instance().guildLogSync().reportRewardStorage(items);
		});
	}

	private static void scrapeLog(String category, List<ItemStack> items) {
		List<GuildLogEntry> entries = GuildLogSync.entriesFrom(items);
		if (entries.isEmpty() || entries.stream().anyMatch(GuildLogSync::isLoadingPlaceholder)) {
			return;
		}
		if (entries.equals(lastLogEntriesSent.get(category))) {
			return;
		}
		lastLogEntriesSent.put(category, entries);
		EdenModClient.instance().guildLogSync().reportEntries(category, entries);
	}

	/**
	 * The container's own slots as a flat list matching {@code expectedContainerSlots},
	 * or null when the menu isn't that size (wrong screen) or hasn't populated its
	 * contents yet (every slot still empty — the screen exists from the open packet,
	 * but item contents arrive in a later one).
	 */
	private static List<ItemStack> containerItems(AbstractContainerMenu menu, int expectedContainerSlots) {
		if (menu.slots.size() != expectedContainerSlots + PLAYER_INVENTORY_SLOTS) {
			return null;
		}
		List<ItemStack> items = new ArrayList<>(expectedContainerSlots);
		boolean anyOccupied = false;
		for (int slot = 0; slot < expectedContainerSlots; slot++) {
			ItemStack item = menu.getSlot(slot).getItem();
			items.add(item);
			anyOccupied |= !item.isEmpty();
		}
		return anyOccupied ? items : null;
	}

	private static String stripCodes(String text) {
		return COLOR_CODE.matcher(text).replaceAll("");
	}
}
