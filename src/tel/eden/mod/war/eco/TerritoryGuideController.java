package tel.eden.mod.war.eco;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;

/**
 * Orchestrates preset application: storage management, Fake Medium, Fake High, and individual territory upgrades.
 */
public final class TerritoryGuideController {
	private TerritoryGuideController() {
	}

	public static final TerritoryPreset.TerritoryLoadout FAKE_MEDIUM = new TerritoryPreset.TerritoryLoadout(2, 2, 3, 2, Map.of("Tower Aura", 1, "Tower Volley", 1));
	public static final TerritoryPreset.TerritoryLoadout FAKE_HIGH = new TerritoryPreset.TerritoryLoadout(5, 4, 5, 5, Map.of("Tower Aura", 1, "Tower Volley", 3));

	private static TerritoryPreset activePreset = TerritoryPreset.empty("Default");
	private static final List<String> skippedQueue = new ArrayList<>();
	private static boolean skippedFakeMedium = false;
	private static boolean skippedFakeHigh = false;
	private static ActionType lastEvaluatedAction = ActionType.NONE;
	private static String activeTarget = null;
	private static String lastSelectedTerritory = null;
	private static final Map<String, TerritoryPreset.TerritoryLoadout> sessionLoadouts = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	private static List<String> pendingBatchTerritories = null;
	private static TerritoryPreset.TerritoryLoadout pendingBatchLoadout = null;

	public enum ActionType {
		NONE, CLICK_LOADOUTS_BUTTON, CLICK_FAKE_MEDIUM_OPTION, CLICK_FAKE_HIGH_OPTION, SELECT_TERRITORIES, DOUBLE_CLICK_APPLY_BUTTON, OPEN_TERRITORY, CLICK_UPGRADES_BUTTON, CLICK_BONUSES_BUTTON, UPGRADE_SLOTS, CLICK_BACK_BUTTON, ALL_COMPLETE
	}

	public record GuideStep(ActionType action, String instruction, List<String> targetTerritories, String highlightSlotName, int totalCount, int completedCount) {
		public static final GuideStep NONE = new GuideStep(ActionType.NONE, "", List.of(), null, 0, 0);
	}

	public static TerritoryPreset activePreset() {
		return activePreset;
	}

	public static void setActivePreset(TerritoryPreset preset) {
		activePreset = (preset != null) ? preset : TerritoryPreset.empty("Default");
		skippedQueue.clear();
		skippedFakeMedium = false;
		skippedFakeHigh = false;
		lastEvaluatedAction = ActionType.NONE;
		activeTarget = null;
		pendingBatchTerritories = null;
		pendingBatchLoadout = null;
		sessionLoadouts.clear();
	}

	public static void reset() {
		skippedQueue.clear();
		skippedFakeMedium = false;
		skippedFakeHigh = false;
		lastEvaluatedAction = ActionType.NONE;
		activeTarget = null;
		lastSelectedTerritory = null;
		pendingBatchTerritories = null;
		pendingBatchLoadout = null;
		sessionLoadouts.clear();
	}

	public static String lastSelectedTerritory() {
		return lastSelectedTerritory;
	}

	public static String activeTarget() {
		return activeTarget;
	}

	public static void setActiveTarget(String target) {
		activeTarget = target;
	}

	public static void setLastSelectedTerritory(String territory) {
		lastSelectedTerritory = territory;
	}

	public static boolean isSkipped(String name) {
		if (name == null)
			return false;
		for (String s : skippedQueue) {
			if (s.equalsIgnoreCase(name))
				return true;
		}
		return false;
	}

	public static boolean isSkippedFakeMedium() {
		return skippedFakeMedium;
	}

	public static boolean isSkippedFakeHigh() {
		return skippedFakeHigh;
	}

	public static void commitPendingBatch() {
		if (pendingBatchTerritories != null && pendingBatchLoadout != null) {
			for (String t : pendingBatchTerritories) {
				sessionLoadouts.put(canonicalTerritoryName(t), pendingBatchLoadout);
			}
			pendingBatchTerritories = null;
			pendingBatchLoadout = null;
		}
	}

	public static void clearPendingBatch() {
		pendingBatchTerritories = null;
		pendingBatchLoadout = null;
	}

	public static void skipCurrentTarget() {
		String target = (activeTarget != null) ? activeTarget : lastSelectedTerritory;
		if (target != null) {
			if (!isSkipped(target)) {
				skippedQueue.add(target);
			}
			activeTarget = null;
		}
	}

	public static void skipForward() {
		if (lastEvaluatedAction == ActionType.CLICK_FAKE_MEDIUM_OPTION || (!skippedFakeMedium && (lastEvaluatedAction == ActionType.CLICK_LOADOUTS_BUTTON || lastEvaluatedAction == ActionType.SELECT_TERRITORIES || lastEvaluatedAction == ActionType.DOUBLE_CLICK_APPLY_BUTTON))) {
			skippedFakeMedium = true;
			pendingBatchTerritories = null;
			pendingBatchLoadout = null;
		} else if (lastEvaluatedAction == ActionType.CLICK_FAKE_HIGH_OPTION || (!skippedFakeHigh && (lastEvaluatedAction == ActionType.CLICK_LOADOUTS_BUTTON || lastEvaluatedAction == ActionType.SELECT_TERRITORIES || lastEvaluatedAction == ActionType.DOUBLE_CLICK_APPLY_BUTTON))) {
			skippedFakeHigh = true;
			pendingBatchTerritories = null;
			pendingBatchLoadout = null;
		} else if (lastEvaluatedAction == ActionType.ALL_COMPLETE) {
			skippedFakeMedium = false;
			skippedFakeHigh = false;
			skippedQueue.clear();
			activeTarget = null;
		} else {
			skipCurrentTarget();
		}
	}

	public static void skipBackwards() {
		if (!skippedQueue.isEmpty()) {
			String prev = skippedQueue.remove(skippedQueue.size() - 1);
			activeTarget = prev;
			return;
		}

		if (lastEvaluatedAction == ActionType.OPEN_TERRITORY || lastEvaluatedAction == ActionType.UPGRADE_SLOTS || lastEvaluatedAction == ActionType.CLICK_UPGRADES_BUTTON || lastEvaluatedAction == ActionType.CLICK_BONUSES_BUTTON || lastEvaluatedAction == ActionType.CLICK_BACK_BUTTON || lastEvaluatedAction == ActionType.ALL_COMPLETE) {
			if (skippedFakeHigh) {
				skippedFakeHigh = false;
				activeTarget = null;
				return;
			}
			if (skippedFakeMedium) {
				skippedFakeMedium = false;
				activeTarget = null;
				return;
			}
		}

		if (lastEvaluatedAction == ActionType.CLICK_FAKE_HIGH_OPTION || (!skippedFakeHigh && (lastEvaluatedAction == ActionType.CLICK_LOADOUTS_BUTTON || lastEvaluatedAction == ActionType.SELECT_TERRITORIES || lastEvaluatedAction == ActionType.DOUBLE_CLICK_APPLY_BUTTON))) {
			if (skippedFakeMedium) {
				skippedFakeMedium = false;
				activeTarget = null;
				return;
			}
		}

		// At start: loop backwards to the end
		skippedFakeMedium = true;
		skippedFakeHigh = true;
		List<String> cands = getPhase3Candidates();
		if (!cands.isEmpty()) {
			for (int i = 0; i < cands.size() - 1; i++) {
				skippedQueue.add(cands.get(i));
			}
			activeTarget = cands.get(cands.size() - 1);
		} else {
			skippedFakeHigh = false;
			activeTarget = null;
		}
	}

	private static List<String> getPhase3Candidates() {
		if (activePreset == null)
			return List.of();
		Set<String> owned = new HashSet<>();
		for (String k : sessionLoadouts.keySet()) {
			owned.add(cleanName(k).toLowerCase());
		}
		List<String> result = new ArrayList<>();
		for (Map.Entry<String, TerritoryPreset.TerritoryLoadout> entry : activePreset.territories().entrySet()) {
			String name = entry.getKey();
			if (activePreset.isExcluded(name))
				continue;
			if (!owned.isEmpty() && !owned.contains(cleanName(name).toLowerCase()))
				continue;
			if (isFakeMedium(entry.getValue()) || isFakeHigh(entry.getValue()))
				continue;
			TerritoryPreset.TerritoryLoadout current = getCaseInsensitive(sessionLoadouts, name);
			if (remainingClicks(current, entry.getValue()) > 0) {
				result.add(name);
			}
		}
		return result;
	}

	public static void skipCurrentInstruction() {
		skipForward();
	}

	public static Map<String, TerritoryPreset.TerritoryLoadout> sessionLoadouts() {
		return Collections.unmodifiableMap(sessionLoadouts);
	}

	public static void recordScannedUpgrades(String territory, List<TerritoryContainerScanner.UpgradeSlotInfo> upgrades) {
		if (territory == null || upgrades == null)
			return;
		TerritoryPreset.TerritoryLoadout base = getLiveOrSessionLoadout(territory);
		int dmg = base.damage(), atk = base.attack(), hp = base.health(), def = base.defence();
		Map<String, Integer> bonuses = new HashMap<>(base.bonuses());
		for (TerritoryContainerScanner.UpgradeSlotInfo up : upgrades) {
			switch (up.upgradeName()) {
				case "Damage" -> dmg = up.currentLevel();
				case "Attack" -> atk = up.currentLevel();
				case "Health" -> hp = up.currentLevel();
				case "Defence" -> def = up.currentLevel();
				default -> {
					if (up.currentLevel() > 0) {
						bonuses.put(up.upgradeName(), up.currentLevel());
					} else {
						bonuses.remove(up.upgradeName());
					}
				}
			}
		}
		sessionLoadouts.put(canonicalTerritoryName(territory), new TerritoryPreset.TerritoryLoadout(dmg, atk, hp, def, bonuses));
	}

	public static TerritoryPreset.TerritoryLoadout getLiveOrSessionLoadout(String territory) {
		if (territory == null)
			return TerritoryPreset.TerritoryLoadout.ZERO;
		TerritoryPreset.TerritoryLoadout s = sessionLoadouts.get(canonicalTerritoryName(territory));
		if (s != null)
			return s;
		return TerritoryPreset.TerritoryLoadout.ZERO;
	}

	public static TerritoryPreset.TerritoryLoadout getCaseInsensitive(Map<String, TerritoryPreset.TerritoryLoadout> map, String key) {
		if (map == null || key == null)
			return TerritoryPreset.TerritoryLoadout.ZERO;
		TerritoryPreset.TerritoryLoadout exact = map.get(key);
		if (exact != null)
			return exact;
		for (Map.Entry<String, TerritoryPreset.TerritoryLoadout> e : map.entrySet()) {
			if (e.getKey().equalsIgnoreCase(key))
				return e.getValue();
		}
		TerritoryPreset.TerritoryLoadout s = sessionLoadouts.get(canonicalTerritoryName(key));
		if (s != null)
			return s;
		return TerritoryPreset.TerritoryLoadout.ZERO;
	}

	// ---- Preset Storage Management ----

	private static BridgeConfig config() {
		try {
			EdenModClient client = EdenModClient.instance();
			return client != null ? client.config() : null;
		} catch (Throwable ignored) {
			return null;
		}
	}

	public static Map<String, String> savedPresets() {
		BridgeConfig config = config();
		if (config == null)
			return Map.of();
		if (config.territorySavedPresets == null) {
			config.territorySavedPresets = new LinkedHashMap<>();
		}
		return config.territorySavedPresets;
	}

	public static boolean hasPresetName(Map<String, String> map, String name) {
		if (map == null || name == null) {
			return false;
		}
		for (String key : map.keySet()) {
			if (key.equalsIgnoreCase(name.trim())) {
				return true;
			}
		}
		return false;
	}

	public static String getUniquePresetName(Map<String, String> map, String desiredName) {
		String base = (desiredName != null && !desiredName.isBlank()) ? desiredName.trim() : "Default";
		if (map != null) {
			for (String key : map.keySet()) {
				if (key.equalsIgnoreCase(base)) {
					base = key;
					break;
				}
			}
		}
		if (!hasPresetName(map, base)) {
			return base;
		}
		int counter = 2;
		while (hasPresetName(map, base + " (" + counter + ")")) {
			counter++;
		}
		return base + " (" + counter + ")";
	}

	public static boolean isKnownTerritory(String name) {
		if (name == null || name.isBlank()) {
			return false;
		}
		String clean = cleanName(name);
		if (clean.isEmpty()) {
			return false;
		}
		if (activePreset != null && activePreset.territories() != null) {
			for (String presetTerr : activePreset.territories().keySet()) {
				if (cleanName(presetTerr).equalsIgnoreCase(clean)) {
					return true;
				}
			}
		}
		for (String t : tel.eden.mod.war.TerritoryData.territoryNames()) {
			if (cleanName(t).equalsIgnoreCase(clean)) {
				return true;
			}
		}
		if (lastSelectedTerritory != null && cleanName(lastSelectedTerritory).equalsIgnoreCase(clean)) {
			return true;
		}
		return false;
	}

	public static void savePresetToStorage(String name, String code) {
		BridgeConfig config = config();
		if (config == null || code == null || code.isBlank())
			return;
		try {
			TerritoryPreset preset = TerritoryPresetCodec.parse(code);
			String rawName = (name != null && !name.isBlank()) ? name.trim() : preset.name();
			String finalName = getUniquePresetName(config.territorySavedPresets, rawName);
			if (!finalName.equals(preset.name())) {
				preset = new TerritoryPreset(finalName, preset.notes(), preset.excludedTerritories(), preset.territories());
				code = TerritoryPresetCodec.encodeBase64(preset);
			}
			config.territorySavedPresets.put(finalName, code);
			config.territoryActivePreset = code;
			setActivePreset(preset);
			config.save();
		} catch (Exception ignored) {
		}
	}

	public static boolean selectSavedPreset(String name) {
		BridgeConfig config = config();
		if (config == null || name == null)
			return false;
		String code = config.territorySavedPresets.get(name);
		if (code == null)
			return false;
		try {
			TerritoryPreset preset = TerritoryPresetCodec.parse(code);
			config.territoryActivePreset = code;
			setActivePreset(preset);
			config.save();
			return true;
		} catch (Exception ignored) {
			return false;
		}
	}

	public static boolean hasActivePreset() {
		return activePreset != null && !activePreset.territories().isEmpty();
	}

	public static void unselectPreset() {
		BridgeConfig config = config();
		if (config != null) {
			config.territoryActivePreset = "";
			config.save();
		}
		setActivePreset(TerritoryPreset.empty("None"));
	}

	public static boolean renameSavedPreset(String oldName, String newName) {
		BridgeConfig config = config();
		if (config == null || oldName == null)
			return false;
		String cleanOld = oldName.trim();
		String cleanNew = (newName != null) ? newName.trim() : "";
		if (cleanNew.isEmpty()) {
			cleanNew = "Default";
		}
		if (cleanOld.equalsIgnoreCase(cleanNew))
			return false;

		String code = config.territorySavedPresets.get(cleanOld);
		if (code == null)
			return false;

		// Prevent clobbering existing presets: if new name matches another preset, make unique
		if (hasPresetName(config.territorySavedPresets, cleanNew)) {
			int counter = 2;
			String base = cleanNew;
			while (hasPresetName(config.territorySavedPresets, base + " (" + counter + ")")) {
				counter++;
			}
			cleanNew = base + " (" + counter + ")";
		}

		String newCode = code;
		try {
			TerritoryPreset preset = TerritoryPresetCodec.parse(code);
			TerritoryPreset renamed = new TerritoryPreset(cleanNew, preset.notes(), preset.excludedTerritories(), preset.territories());
			newCode = TerritoryPresetCodec.encodeBase64(renamed);
			if (activePreset != null && activePreset.name().equalsIgnoreCase(cleanOld)) {
				setActivePreset(renamed);
				config.territoryActivePreset = newCode;
			}
		} catch (Exception ignored) {
		}

		Map<String, String> updated = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : config.territorySavedPresets.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(cleanOld)) {
				updated.put(cleanNew, newCode);
			} else {
				updated.put(entry.getKey(), entry.getValue());
			}
		}
		config.territorySavedPresets = updated;
		config.save();
		return true;
	}

	public static void deleteSavedPreset(String name) {
		BridgeConfig config = config();
		if (config == null || name == null)
			return;
		config.territorySavedPresets.remove(name);
		if (activePreset != null && activePreset.name().equalsIgnoreCase(name)) {
			if (!config.territorySavedPresets.isEmpty()) {
				String nextName = config.territorySavedPresets.keySet().iterator().next();
				selectSavedPreset(nextName);
			} else {
				unselectPreset();
			}
		}
		config.save();
	}

	public static String cycleSavedPreset(int offset) {
		Map<String, String> presets = savedPresets();
		List<String> keys = new ArrayList<>(presets.keySet());
		keys.add(0, "None");
		String currentName = hasActivePreset() ? activePreset().name() : "None";
		int idx = 0;
		for (int i = 0; i < keys.size(); i++) {
			if (keys.get(i).equalsIgnoreCase(currentName)) {
				idx = i;
				break;
			}
		}
		int nextIdx = (idx + offset) % keys.size();
		if (nextIdx < 0)
			nextIdx += keys.size();
		String nextName = keys.get(nextIdx);
		if (nextName.equalsIgnoreCase("None")) {
			unselectPreset();
			return "None";
		}
		selectSavedPreset(nextName);
		return nextName;
	}

	public static boolean isFakeMedium(TerritoryPreset.TerritoryLoadout loadout) {
		if (loadout == null) {
			return false;
		}
		long activeBonuses = loadout.bonuses().values().stream().filter(v -> v != null && v > 0).count();
		return loadout.damage() == 2 && loadout.attack() == 2 && loadout.health() == 3 && loadout.defence() == 2 && loadout.bonus("Tower Aura") == 1 && loadout.bonus("Tower Volley") == 1 && activeBonuses == 2;
	}

	public static boolean isFakeHigh(TerritoryPreset.TerritoryLoadout loadout) {
		if (loadout == null) {
			return false;
		}
		long activeBonuses = loadout.bonuses().values().stream().filter(v -> v != null && v > 0).count();
		return loadout.damage() == 5 && loadout.attack() == 4 && loadout.health() == 5 && loadout.defence() == 5 && loadout.bonus("Tower Aura") == 1 && loadout.bonus("Tower Volley") == 3 && activeBonuses == 2;
	}

	private static final java.util.regex.Pattern CLEAN_NAME_REGEX = java.util.regex.Pattern.compile("([A-Za-z0-9][A-Za-z0-9'\\-\\.\\s]+)");

	public static String cleanName(String name) {
		if (name == null) {
			return "";
		}
		String clean = net.minecraft.ChatFormatting.stripFormatting(name).trim();
		if (clean.endsWith("(HQ)")) {
			clean = clean.substring(0, clean.length() - 4).trim();
		}
		java.util.regex.Matcher m = CLEAN_NAME_REGEX.matcher(clean);
		if (m.find()) {
			String cand = m.group(1).trim();
			if (cand.endsWith("(HQ)")) {
				cand = cand.substring(0, cand.length() - 4).trim();
			}
			return cand;
		}
		return clean;
	}

	public static String canonicalTerritoryName(String name) {
		if (name == null || name.isBlank()) {
			return "";
		}
		String clean = cleanName(name);
		for (String known : tel.eden.mod.war.TerritoryData.territoryNames()) {
			if (cleanName(known).equalsIgnoreCase(clean)) {
				return cleanName(known);
			}
		}
		if (activePreset != null && activePreset.territories() != null) {
			for (String presetTerr : activePreset.territories().keySet()) {
				if (cleanName(presetTerr).equalsIgnoreCase(clean)) {
					return cleanName(presetTerr);
				}
			}
		}
		return clean;
	}

	public static int remainingClicks(TerritoryPreset.TerritoryLoadout current, TerritoryPreset.TerritoryLoadout target) {
		if (target == null) {
			return 0;
		}
		if (current == null) {
			current = TerritoryPreset.TerritoryLoadout.ZERO;
		}
		int c = 0;
		c += Math.abs(target.damage() - current.damage());
		c += Math.abs(target.attack() - current.attack());
		c += Math.abs(target.health() - current.health());
		c += Math.abs(target.defence() - current.defence());

		Set<String> allBonuses = new HashSet<>();
		if (target.bonuses() != null)
			allBonuses.addAll(target.bonuses().keySet());
		if (current.bonuses() != null)
			allBonuses.addAll(current.bonuses().keySet());

		for (String bName : allBonuses) {
			c += Math.abs(target.bonus(bName) - current.bonus(bName));
		}
		return c;
	}

	/**
	 * Computes the active instruction step based on the preset and live scanned territories.
	 * Territories not on the player's menu or not owned by Eden are automatically voided.
	 */
	public static GuideStep evaluate(Map<String, TerritoryPreset.TerritoryLoadout> currentLoadouts, Set<String> liveSelectedTerritories, String containerTitle, boolean isSelectionMode) {
		GuideStep step = evaluateInternal(currentLoadouts, liveSelectedTerritories, containerTitle, isSelectionMode);
		lastEvaluatedAction = (step != null) ? step.action() : ActionType.NONE;
		return step;
	}

	private static GuideStep evaluateInternal(Map<String, TerritoryPreset.TerritoryLoadout> currentLoadouts, Set<String> liveSelectedTerritories, String containerTitle, boolean isSelectionMode) {
		if (activePreset == null || activePreset.territories().isEmpty()) {
			return new GuideStep(ActionType.NONE, "No active preset loaded. Click [Paste Preset] to import one.", List.of(), null, 0, 0);
		}

		String cleanTitle = (containerTitle != null) ? containerTitle.trim() : "";
		boolean isLoadoutsMenu = cleanTitle.contains("Territory Loadouts") || cleanTitle.equalsIgnoreCase("Loadouts");
		boolean isSelectScreen = isSelectionMode || cleanTitle.contains("Select Territories");
		boolean isUpgradesMenu = cleanTitle.contains("Upgrades") || cleanTitle.contains("Guild Tower");
		boolean isBonusesMenu = cleanTitle.contains("Bonuses") || cleanTitle.contains("Bonus");

		if ((currentLoadouts == null || currentLoadouts.isEmpty()) && sessionLoadouts.isEmpty()) {
			return new GuideStep(ActionType.NONE, "Waiting for territory menu data...", List.of(), null, 0, 0);
		}

		if (!isSelectScreen && pendingBatchTerritories != null && pendingBatchLoadout != null) {
			commitPendingBatch();
		}

		// Cache live loadouts into session memory. In "Select Territories", selected slots preview the
		// new loadout in their lore, so do not overwrite pre-selection sessionLoadouts there.
		if (currentLoadouts != null) {
			for (Map.Entry<String, TerritoryPreset.TerritoryLoadout> e : currentLoadouts.entrySet()) {
				String key = canonicalTerritoryName(e.getKey());
				if (isSelectScreen || isLoadoutsMenu) {
					sessionLoadouts.putIfAbsent(key, e.getValue());
				} else {
					sessionLoadouts.put(key, e.getValue());
				}
			}
		}

		Set<String> owned = new HashSet<>();
		for (String k : sessionLoadouts.keySet()) {
			owned.add(cleanName(k).toLowerCase());
		}
		if (currentLoadouts != null) {
			for (String k : currentLoadouts.keySet()) {
				owned.add(cleanName(k).toLowerCase());
			}
		}

		Map<String, TerritoryPreset.TerritoryLoadout> lookupMap = (isSelectScreen || isLoadoutsMenu) ? Map.of() : currentLoadouts;

		// Phase 1: Bulk Fake Medium (Only for territories owned by Eden)
		List<String> needFakeMedium = new ArrayList<>();
		if (!skippedFakeMedium) {
			for (Map.Entry<String, TerritoryPreset.TerritoryLoadout> entry : activePreset.territories().entrySet()) {
				String name = entry.getKey();
				if (activePreset.isExcluded(name))
					continue;
				if (!owned.contains(cleanName(name).toLowerCase()))
					continue; // Voided: not owned / not in menu
				if (isFakeMedium(entry.getValue())) {
					TerritoryPreset.TerritoryLoadout current = getCaseInsensitive(lookupMap, name);
					if (!isFakeMedium(current)) {
						needFakeMedium.add(name);
					}
				}
			}
		}

		if (!needFakeMedium.isEmpty()) {
			if (isSelectScreen) {
				List<String> unselected = new ArrayList<>();
				for (String name : needFakeMedium) {
					boolean isSelected = false;
					if (liveSelectedTerritories != null) {
						for (String sel : liveSelectedTerritories) {
							if (cleanName(sel).equalsIgnoreCase(cleanName(name))) {
								isSelected = true;
								break;
							}
						}
					}
					if (!isSelected) {
						unselected.add(name);
					}
				}
				if (!unselected.isEmpty()) {
					pendingBatchTerritories = null;
					pendingBatchLoadout = null;
					int done = needFakeMedium.size() - unselected.size();
					return new GuideStep(ActionType.SELECT_TERRITORIES, "Click every Fake Medium territory (" + done + "/" + needFakeMedium.size() + " selected): " + String.join(", ", unselected), unselected, null, needFakeMedium.size(), done);
				} else {
					pendingBatchTerritories = new ArrayList<>(needFakeMedium);
					pendingBatchLoadout = FAKE_MEDIUM;
					return new GuideStep(ActionType.DOUBLE_CLICK_APPLY_BUTTON, "All Fake Medium territories selected! Click to confirm selection (Apply ✔).", List.of(), "Apply", needFakeMedium.size(), needFakeMedium.size());
				}
			} else if (isLoadoutsMenu) {
				return new GuideStep(ActionType.CLICK_FAKE_MEDIUM_OPTION, "Click on the [Fake Medium] option in this menu.", needFakeMedium, "Fake Medium", needFakeMedium.size(), 0);
			} else {
				return new GuideStep(ActionType.CLICK_LOADOUTS_BUTTON, "Step 1: Click the [Territory Loadouts] button to apply Fake Medium to " + needFakeMedium.size() + " territories.", needFakeMedium, "Territory Loadouts", needFakeMedium.size(), 0);
			}
		}

		// Phase 2: Bulk Fake High (Only for territories owned by Eden)
		List<String> needFakeHigh = new ArrayList<>();
		if (!skippedFakeHigh) {
			for (Map.Entry<String, TerritoryPreset.TerritoryLoadout> entry : activePreset.territories().entrySet()) {
				String name = entry.getKey();
				if (activePreset.isExcluded(name))
					continue;
				if (!owned.contains(cleanName(name).toLowerCase()))
					continue; // Voided: not owned / not in menu
				if (isFakeHigh(entry.getValue())) {
					TerritoryPreset.TerritoryLoadout current = getCaseInsensitive(lookupMap, name);
					if (!isFakeHigh(current)) {
						needFakeHigh.add(name);
					}
				}
			}
		}

		if (!needFakeHigh.isEmpty()) {
			if (isSelectScreen) {
				List<String> unselected = new ArrayList<>();
				for (String name : needFakeHigh) {
					boolean isSelected = false;
					if (liveSelectedTerritories != null) {
						for (String sel : liveSelectedTerritories) {
							if (cleanName(sel).equalsIgnoreCase(cleanName(name))) {
								isSelected = true;
								break;
							}
						}
					}
					if (!isSelected) {
						unselected.add(name);
					}
				}
				if (!unselected.isEmpty()) {
					pendingBatchTerritories = null;
					pendingBatchLoadout = null;
					int done = needFakeHigh.size() - unselected.size();
					return new GuideStep(ActionType.SELECT_TERRITORIES, "Click every Fake High territory (" + done + "/" + needFakeHigh.size() + " selected): " + String.join(", ", unselected), unselected, null, needFakeHigh.size(), done);
				} else {
					pendingBatchTerritories = new ArrayList<>(needFakeHigh);
					pendingBatchLoadout = FAKE_HIGH;
					return new GuideStep(ActionType.DOUBLE_CLICK_APPLY_BUTTON, "All Fake High territories selected! Click to confirm selection (Apply ✔).", List.of(), "Apply", needFakeHigh.size(), needFakeHigh.size());
				}
			} else if (isLoadoutsMenu) {
				return new GuideStep(ActionType.CLICK_FAKE_HIGH_OPTION, "Click on the [Fake High] option in this menu.", needFakeHigh, "Fake High", needFakeHigh.size(), 0);
			} else {
				return new GuideStep(ActionType.CLICK_LOADOUTS_BUTTON, "Step 2: Click the [Territory Loadouts] button to apply Fake High to " + needFakeHigh.size() + " territories.", needFakeHigh, "Territory Loadouts", needFakeHigh.size(), 0);
			}
		}

		// Phase 3: Individual Territories (Only for territories owned by Eden)
		int totalOwnedInPreset = 0;
		List<String> needIndividual = new ArrayList<>();
		for (Map.Entry<String, TerritoryPreset.TerritoryLoadout> entry : activePreset.territories().entrySet()) {
			String name = entry.getKey();
			if (activePreset.isExcluded(name))
				continue;
			if (!owned.contains(cleanName(name).toLowerCase()))
				continue; // Voided: not owned / not in menu
			if (isFakeMedium(entry.getValue()) && skippedFakeMedium)
				continue;
			if (isFakeHigh(entry.getValue()) && skippedFakeHigh)
				continue;
			totalOwnedInPreset++;
			TerritoryPreset.TerritoryLoadout current = getCaseInsensitive(lookupMap, name);
			if (remainingClicks(current, entry.getValue()) > 0) {
				needIndividual.add(name);
			}
		}

		if (needIndividual.isEmpty()) {
			return new GuideStep(ActionType.ALL_COMPLETE, "✔ All " + totalOwnedInPreset + " territories match the preset!", List.of(), null, totalOwnedInPreset, totalOwnedInPreset);
		}

		// Determine active target territory
		if (activeTarget == null || !needIndividual.contains(activeTarget) || isSkipped(activeTarget)) {
			activeTarget = null;
			for (String cand : needIndividual) {
				if (!isSkipped(cand)) {
					activeTarget = cand;
					break;
				}
			}
			if (activeTarget == null) {
				skippedFakeMedium = false;
				skippedFakeHigh = false;
				skippedQueue.clear();
				return evaluateInternal(currentLoadouts, liveSelectedTerritories, containerTitle, isSelectionMode);
			}
		}

		String targetTerritory = activeTarget;
		TerritoryPreset.TerritoryLoadout targetLoadout = activePreset.loadout(targetTerritory);
		TerritoryPreset.TerritoryLoadout currentLoadout = getCaseInsensitive(lookupMap, targetTerritory);

		if (isUpgradesMenu) {
			int upDiff = Math.abs(targetLoadout.damage() - currentLoadout.damage()) + Math.abs(targetLoadout.attack() - currentLoadout.attack()) + Math.abs(targetLoadout.health() - currentLoadout.health()) + Math.abs(targetLoadout.defence() - currentLoadout.defence());
			if (upDiff <= 0) {
				return new GuideStep(ActionType.CLICK_BACK_BUTTON, "✔ Upgrades match! Click [Back].", List.of(targetTerritory), "Back", needIndividual.size(), 0);
			}
			return new GuideStep(ActionType.UPGRADE_SLOTS, "Click highlighted upgrade slots to match preset (" + targetTerritory + ").", List.of(targetTerritory), null, needIndividual.size(), 0);
		}

		if (isBonusesMenu) {
			int bonusClicks = 0;
			Set<String> allBonuses = new HashSet<>();
			if (targetLoadout.bonuses() != null)
				allBonuses.addAll(targetLoadout.bonuses().keySet());
			if (currentLoadout.bonuses() != null)
				allBonuses.addAll(currentLoadout.bonuses().keySet());
			for (String bName : allBonuses) {
				bonusClicks += Math.abs(targetLoadout.bonus(bName) - currentLoadout.bonus(bName));
			}
			if (bonusClicks <= 0) {
				return new GuideStep(ActionType.CLICK_BACK_BUTTON, "✔ Bonuses match! Click [Back].", List.of(targetTerritory), "Back", needIndividual.size(), 0);
			}
			return new GuideStep(ActionType.UPGRADE_SLOTS, "Click highlighted bonus slots to match preset (" + targetTerritory + ").", List.of(targetTerritory), null, needIndividual.size(), 0);
		}

		// Check if inside target territory's overview menu (<Territory>: Manage)
		String titleTerritory = TerritoryContainerScanner.parseTerritoryFromUpgradeTitle(cleanTitle);
		if (titleTerritory != null && titleTerritory.equalsIgnoreCase(targetTerritory)) {
			boolean needUpgrades = targetLoadout.damage() != currentLoadout.damage() || targetLoadout.attack() != currentLoadout.attack() || targetLoadout.health() != currentLoadout.health() || targetLoadout.defence() != currentLoadout.defence();
			if (needUpgrades) {
				return new GuideStep(ActionType.CLICK_UPGRADES_BUTTON, "Click [Guild Tower] to configure tower defense.", List.of(targetTerritory), "Guild Tower", needIndividual.size(), 0);
			}
			boolean needBonuses = false;
			Set<String> checkBonuses = new HashSet<>();
			if (targetLoadout.bonuses() != null)
				checkBonuses.addAll(targetLoadout.bonuses().keySet());
			if (currentLoadout.bonuses() != null)
				checkBonuses.addAll(currentLoadout.bonuses().keySet());
			for (String b : checkBonuses) {
				if (targetLoadout.bonus(b) != currentLoadout.bonus(b)) {
					needBonuses = true;
					break;
				}
			}
			if (needBonuses) {
				return new GuideStep(ActionType.CLICK_BONUSES_BUTTON, "Click [Bonus] to configure territory bonuses.", List.of(targetTerritory), "Bonus", needIndividual.size(), 0);
			}
			return new GuideStep(ActionType.CLICK_BACK_BUTTON, "✔ All upgrades complete for " + targetTerritory + "! Click [Back].", List.of(targetTerritory), "Back", needIndividual.size(), 0);
		}

		return new GuideStep(ActionType.OPEN_TERRITORY, "Next territory: Click [" + targetTerritory + "] (" + remainingClicks(currentLoadout, targetLoadout) + " clicks needed).", List.of(targetTerritory), null, needIndividual.size(), 0);
	}
}
