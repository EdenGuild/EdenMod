package tel.eden.mod.war.eco;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import tel.eden.mod.war.TerritoryData;

/**
 * Scans container menus for territory claims, loadouts, selection states, and upgrades.
 */
public final class TerritoryContainerScanner {
	private TerritoryContainerScanner() {
	}

	private static final Pattern UPGRADE_LINE_PATTERN = Pattern.compile("-\\s*(.+?)\\s*\\[Lv\\.\\s*(\\d+)\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern CLEAN_NAME_PATTERN = Pattern.compile("([A-Za-z0-9][A-Za-z0-9'\\-\\.\\s]+)");
	private static final Pattern LEVEL_PATTERN = Pattern.compile("(?i)(?:\\[(?:Lv\\.?\\s*)?([0-9]+|[IVXLCDM]+)\\]|Lv\\.?\\s*([0-9]+|[IVXLCDM]+)|Level:?\\s*([0-9]+|[IVXLCDM]+))");

	public record ScanResult(Set<String> claimedTerritories, String hqTerritory, Map<String, TerritoryPreset.TerritoryLoadout> currentLoadouts, Set<String> selectedTerritories, Map<String, Slot> territorySlots) {
		public static final ScanResult EMPTY = new ScanResult(Set.of(), null, Map.of(), Set.of(), Map.of());
	}

	public static List<String> getLoreLines(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return List.of();
		}
		ItemLore itemLore = stack.get(DataComponents.LORE);
		if (itemLore != null) {
			List<String> lines = new ArrayList<>();
			for (Component c : itemLore.lines()) {
				lines.add(ChatFormatting.stripFormatting(c.getString()).trim());
			}
			return lines;
		}
		try {
			List<Component> tooltip = stack.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.EMPTY, null, net.minecraft.world.item.TooltipFlag.NORMAL);
			if (tooltip != null && !tooltip.isEmpty()) {
				List<String> lines = new ArrayList<>();
				for (Component c : tooltip) {
					lines.add(ChatFormatting.stripFormatting(c.getString()).trim());
				}
				return lines;
			}
		} catch (Throwable ignored) {
		}
		return List.of();
	}

	public static String cleanTerritoryName(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		String raw = ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim();
		if (raw.isEmpty() || raw.equalsIgnoreCase("Back") || raw.contains("Page") || raw.contains("Territory Loadouts")) {
			return null;
		}
		if (raw.endsWith("(HQ)")) {
			raw = raw.substring(0, raw.length() - 4).trim();
		}
		Matcher m = CLEAN_NAME_PATTERN.matcher(raw);
		if (m.find()) {
			return m.group(1).trim();
		}
		return raw;
	}

	public static boolean isHeadquartersItem(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		String raw = ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim();
		return raw.endsWith("(HQ)");
	}

	public static boolean isTerritorySlot(Slot slot) {
		if (slot == null || !slot.hasItem()) {
			return false;
		}
		int idx = slot.index;
		if (idx < 0 || idx >= 45 || idx % 9 < 2) {
			return false;
		}
		ItemStack stack = slot.getItem();
		String name = cleanTerritoryName(stack);
		return name != null && !name.isBlank();
	}

	public static boolean isTerritorySelected(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		if ((stack.getItem() == Items.DIAMOND_AXE || stack.getItem() == Items.GOLDEN_SHOVEL) && stack.getDamageValue() == 20) {
			return true;
		}
		for (String line : getLoreLines(stack)) {
			if (line.contains("Click to Unselect") || line.contains("Click to unselect")) {
				return true;
			}
		}
		return false;
	}

	public static TerritoryPreset.TerritoryLoadout parseLoadout(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return TerritoryPreset.TerritoryLoadout.ZERO;
		}
		int damage = 0, attack = 0, health = 0, defence = 0;
		Map<String, Integer> bonuses = new HashMap<>();
		for (String str : getLoreLines(stack)) {
			Matcher upMatcher = UPGRADE_LINE_PATTERN.matcher(str);
			if (upMatcher.find()) {
				String upName = upMatcher.group(1).trim();
				int lvl = Integer.parseInt(upMatcher.group(2).trim());
				switch (upName) {
					case "Damage" -> damage = lvl;
					case "Attack" -> attack = lvl;
					case "Health" -> health = lvl;
					case "Defence" -> defence = lvl;
					default -> bonuses.put(upName, lvl);
				}
			}
		}
		return new TerritoryPreset.TerritoryLoadout(damage, attack, health, defence, bonuses);
	}

	public static ScanResult scanTerritoriesMenu(AbstractContainerMenu menu) {
		if (menu == null) {
			return ScanResult.EMPTY;
		}

		Set<String> claimed = new HashSet<>();
		String hq = null;
		Map<String, TerritoryPreset.TerritoryLoadout> loadouts = new HashMap<>();
		Set<String> selected = new HashSet<>();
		Map<String, Slot> slots = new HashMap<>();

		for (Slot slot : menu.slots) {
			if (!isTerritorySlot(slot)) {
				continue;
			}
			ItemStack stack = slot.getItem();
			String name = cleanTerritoryName(stack);
			if (name == null || name.isBlank()) {
				continue;
			}

			claimed.add(name);
			slots.put(name, slot);

			if (isHeadquartersItem(stack)) {
				hq = name;
			}
			if (isTerritorySelected(stack)) {
				selected.add(name);
			}

			loadouts.put(name, parseLoadout(stack));
		}

		return new ScanResult(Set.copyOf(claimed), hq, Map.copyOf(loadouts), Set.copyOf(selected), Map.copyOf(slots));
	}

	public static boolean isContainerSlot(AbstractContainerMenu menu, Slot slot) {
		if (slot == null || menu == null) {
			return false;
		}
		if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
			return false;
		}
		int containerSlots = menu.slots.size() - 36;
		return containerSlots <= 0 || slot.index < containerSlots;
	}

	public static Slot findSlotByName(AbstractContainerMenu menu, String targetName) {
		if (menu == null || targetName == null || targetName.isBlank()) {
			return null;
		}
		String target = targetName.toLowerCase().trim();
		for (Slot slot : menu.slots) {
			if (!isContainerSlot(menu, slot) || !slot.hasItem()) {
				continue;
			}
			String name = ChatFormatting.stripFormatting(slot.getItem().getHoverName().getString()).toLowerCase().trim();
			if (name.contains(target)) {
				return slot;
			}
		}
		for (Slot slot : menu.slots) {
			if (!isContainerSlot(menu, slot) || !slot.hasItem()) {
				continue;
			}
			for (String line : getLoreLines(slot.getItem())) {
				if (line.toLowerCase().contains(target)) {
					return slot;
				}
			}
		}
		return null;
	}

	public record UpgradeSlotInfo(String upgradeName, int currentLevel, Slot slot) {
	}

	private static int parseLevel(String str) {
		if (str == null)
			return -1;
		str = str.trim();
		try {
			return Integer.parseInt(str);
		} catch (NumberFormatException ignored) {
		}
		return switch (str.toUpperCase()) {
			case "I" -> 1;
			case "II" -> 2;
			case "III" -> 3;
			case "IV" -> 4;
			case "V" -> 5;
			case "VI" -> 6;
			case "VII" -> 7;
			case "VIII" -> 8;
			case "IX" -> 9;
			case "X" -> 10;
			default -> -1;
		};
	}

	public static List<UpgradeSlotInfo> scanUpgradeMenu(AbstractContainerMenu menu) {
		if (menu == null) {
			return List.of();
		}

		List<UpgradeSlotInfo> result = new ArrayList<>();
		for (Slot slot : menu.slots) {
			if (!isContainerSlot(menu, slot) || !slot.hasItem()) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (stack.getItem() instanceof net.minecraft.world.item.PotionItem) {
				continue;
			}
			List<String> lore = getLoreLines(stack);
			boolean isIngredient = false;
			for (String line : lore) {
				String low = line.toLowerCase();
				if (low.contains("crafting level") || low.contains("recipes for") || low.contains("duration")) {
					isIngredient = true;
					break;
				}
			}
			if (isIngredient) {
				continue;
			}

			String rawName = ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim();
			if (rawName.isEmpty() || rawName.equalsIgnoreCase("Back") || rawName.contains("Page")) {
				continue;
			}

			int lvl = -1;
			String cleanName = rawName;
			Matcher lvlMatcher = LEVEL_PATTERN.matcher(rawName);
			if (lvlMatcher.find()) {
				String g1 = lvlMatcher.group(1);
				String g2 = lvlMatcher.group(2);
				String g3 = lvlMatcher.group(3);
				String numStr = g1 != null ? g1 : (g2 != null ? g2 : g3);
				lvl = parseLevel(numStr);
				cleanName = rawName.substring(0, lvlMatcher.start()).trim();
			}

			if (lvl == -1) {
				for (String line : lore) {
					Matcher lm = LEVEL_PATTERN.matcher(line);
					if (lm.find()) {
						String g1 = lm.group(1);
						String g2 = lm.group(2);
						String g3 = lm.group(3);
						String numStr = g1 != null ? g1 : (g2 != null ? g2 : g3);
						lvl = parseLevel(numStr);
						break;
					}
				}
			}

			Matcher nameMatcher = CLEAN_NAME_PATTERN.matcher(cleanName);
			if (nameMatcher.find()) {
				cleanName = nameMatcher.group(1).trim();
			}

			String canonical = canonicalUpgradeName(cleanName);
			if (canonical != null) {
				result.add(new UpgradeSlotInfo(canonical, Math.max(0, lvl), slot));
			}
		}
		return result;
	}

	public static String canonicalUpgradeName(String raw) {
		if (raw == null) {
			return null;
		}
		String low = raw.toLowerCase().trim();
		if (low.contains("damage") && !low.contains("pvp") && !low.contains("mob"))
			return "Damage";
		if (low.contains("attack") && !low.contains("multi"))
			return "Attack";
		if (low.contains("health"))
			return "Health";
		if (low.contains("defen"))
			return "Defence";
		if (low.contains("aura") && (low.contains("tower") || low.equals("aura")))
			return "Tower Aura";
		if (low.contains("volley"))
			return "Tower Volley";
		if (low.contains("multi"))
			return "Tower Multi-Attacks";
		if (low.contains("minion") || low.contains("stronger mobs"))
			return "Stronger Minions";
		if (low.contains("larger resource") || low.contains("resource storage"))
			return "Larger Resource Storage";
		if (low.contains("larger emerald") || low.contains("emerald storage"))
			return "Larger Emerald Storage";
		if (low.contains("efficient resource"))
			return "Efficient Resources";
		if (low.contains("efficient emerald"))
			return "Efficient Emeralds";
		if (low.contains("resource rate"))
			return "Resource Rate";
		if (low.contains("emerald rate"))
			return "Emerald Rate";
		if (low.contains("tome seeking"))
			return "Tome Seeking";
		if (low.contains("emerald seeking"))
			return "Emerald Seeking";
		if (low.contains("xp seeking"))
			return "XP Seeking";
		if (low.contains("gathering exp"))
			return "Gathering Experience";
		if (low.contains("mob exp"))
			return "Mob Experience";
		if (low.contains("mob dam"))
			return "Mob Damage";
		if (low.contains("pvp dam"))
			return "PvP Damage";
		return null;
	}

	public static boolean isUpgradeOrBonusMenu(AbstractContainerMenu menu, String title) {
		if (menu == null) {
			return false;
		}
		if (title != null && (title.contains("Upgrades") || title.contains("Bonuses") || title.contains("Bonus"))) {
			return true;
		}
		if (title != null && title.contains("󏿰")) {
			return false;
		}
		return !scanUpgradeMenu(menu).isEmpty();
	}

	public static String parseTerritoryFromUpgradeTitle(String title) {
		if (title == null || title.isBlank()) {
			return null;
		}
		String clean = ChatFormatting.stripFormatting(title).trim();
		int idx = clean.lastIndexOf(':');
		if (idx > 0) {
			clean = clean.substring(0, idx).trim();
		}
		Matcher m = CLEAN_NAME_PATTERN.matcher(clean);
		if (m.find()) {
			String candidate = m.group(1).trim();
			if (candidate.endsWith("(HQ)")) {
				candidate = candidate.substring(0, candidate.length() - 4).trim();
			}
			return candidate;
		}
		return clean;
	}
}
