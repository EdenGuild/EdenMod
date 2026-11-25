package tel.eden.mod.raid;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import tel.eden.mod.EdenLogger;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.chat.ChatText;
import tel.eden.mod.net.BridgeWebSocketClient;
import tel.eden.mod.util.Wynncraft;

/**
 * Passively reads the 4 daily raid gambits whenever a linked player (member or traveler)
 * opens Wynncraft's Party Finder menu and reports them to EdenBot.
 *
 * <p>Performs zero inventory interaction — it only observes the already-open container
 * screen, matching WynnExtras's Party Finder title and tooltip parsing.
 */
public final class PartyFinderGambitScraper {
	private PartyFinderGambitScraper() {
	}

	public static final String PARTY_FINDER_TITLE = "\uDAFF\uDFE1\uE00C";
	public static final int EXPECTED_GAMBIT_COUNT = 4;
	private static final int PLAYER_INVENTORY_SLOTS = 36;
	private static final long STABLE_MS = 500L;
	private static final int MAX_NAME_LENGTH = 100;
	private static final int MAX_DESCRIPTION_LENGTH = 500;
	private static final EdenLogger LOGGER = EdenLogger.get();

	private static List<GambitEntry> pendingGambits = List.of();
	private static long pendingSince;
	private static List<GambitEntry> lastSent = List.of();

	/** One daily gambit parsed from Party Finder: its display name and effect description. */
	public record GambitEntry(String name, String description) {
	}

	/** Client-thread tick: if the Party Finder menu is open on live Wynncraft, read and relay the 4 gambits. */
	public static void onTick(Minecraft mc) {
		if (mc == null || !(mc.screen instanceof AbstractContainerScreen<?> screen) || !isPartyFinderTitle(screen)) {
			pendingGambits = List.of();
			pendingSince = 0L;
			return;
		}
		var server = mc.getCurrentServer();
		if (mc.getSingleplayerServer() != null || server == null || Wynncraft.isBeta(server.ip)) {
			return;
		}
		List<GambitEntry> gambits = parseGambits(screen.getMenu());
		if (gambits == null) {
			pendingGambits = List.of();
			pendingSince = 0L;
			return;
		}
		if (!gambits.equals(pendingGambits)) {
			pendingGambits = gambits;
			pendingSince = System.currentTimeMillis();
			return;
		}
		if (System.currentTimeMillis() - pendingSince < STABLE_MS) {
			return;
		}
		if (gambits.equals(lastSent)) {
			return;
		}
		BridgeWebSocketClient socket = EdenModClient.instance().socket();
		if (socket != null && socket.sendGambitReport(gambits)) {
			lastSent = gambits;
			LOGGER.info("Party Finder gambits reported ({})", gambits.size());
		}
	}

	/** Forget cached readings on world change or disconnect. */
	public static void reset() {
		pendingGambits = List.of();
		pendingSince = 0L;
		lastSent = List.of();
	}

	static boolean isPartyFinderTitle(AbstractContainerScreen<?> screen) {
		return PARTY_FINDER_TITLE.equals(screen.getTitle().getString());
	}

	/**
	 * Parse the 4 daily gambits from the Party Finder container, or {@code null} if
	 * incomplete/unrecognised. Sorted by lowercased name for deterministic comparisons.
	 */
	public static List<GambitEntry> parseGambits(AbstractContainerMenu menu) {
		if (menu == null) {
			return null;
		}
		int containerSlots = menu.slots.size() > PLAYER_INVENTORY_SLOTS ? menu.slots.size() - PLAYER_INVENTORY_SLOTS : menu.slots.size();
		Map<String, GambitEntry> unique = new LinkedHashMap<>();
		for (int i = 0; i < containerSlots; i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (stack.isEmpty()) {
				continue;
			}
			Component customName = stack.get(DataComponents.CUSTOM_NAME);
			if (customName == null) {
				continue;
			}
			String name = cleanText(customName.getString());
			if (!name.contains("Gambit") || name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
				continue;
			}
			ItemLore lore = stack.get(DataComponents.LORE);
			if (lore == null) {
				continue;
			}
			String description = extractDescription(lore);
			if (description.isEmpty() || description.length() > MAX_DESCRIPTION_LENGTH) {
				continue;
			}
			unique.putIfAbsent(name.toLowerCase(Locale.ROOT), new GambitEntry(name, description));
		}
		if (unique.size() != EXPECTED_GAMBIT_COUNT) {
			return null;
		}
		return unique.values().stream().sorted(Comparator.comparing(g -> g.name().toLowerCase(Locale.ROOT))).toList();
	}

	private static String extractDescription(ItemLore lore) {
		StringBuilder description = new StringBuilder();
		boolean inDescription = false;
		for (Component lineComponent : lore.lines()) {
			String line = cleanText(lineComponent.getString());
			String lower = line.toLowerCase(Locale.ROOT);
			if (!inDescription) {
				if (lower.contains("refreshes in")) {
					inDescription = true;
				}
				continue;
			}
			if (lower.contains("rewards for enabling")) {
				break;
			}
			if (!line.isEmpty()) {
				if (!description.isEmpty()) {
					description.append(' ');
				}
				description.append(line);
			}
		}
		return description.toString().trim();
	}

	private static String cleanText(String raw) {
		if (raw == null || raw.isEmpty()) {
			return "";
		}
		String stripped = ChatFormatting.stripFormatting(raw);
		return ChatText.normalizeWhitespace(stripped);
	}
}
