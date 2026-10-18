package tel.eden.mod.war.eco;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable preset holding target upgrade levels, notes, and exclusions.
 */
public record TerritoryPreset(String name, String notes, Set<String> excludedTerritories, Map<String, TerritoryLoadout> territories) {
	public TerritoryPreset {
		name = (name == null || name.isBlank()) ? "Unnamed Preset" : name.trim();
		notes = (notes == null) ? "" : notes.trim();
		excludedTerritories = (excludedTerritories == null) ? Set.of() : Set.copyOf(excludedTerritories);
		Map<String, TerritoryLoadout> cleanTerrs = new java.util.LinkedHashMap<>();
		if (territories != null) {
			Map<String, TerritoryLoadout> caseInsensitive = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			for (Map.Entry<String, TerritoryLoadout> entry : territories.entrySet()) {
				if (entry.getKey() == null)
					continue;
				String clean = entry.getKey().trim();
				if (clean.endsWith("(HQ)")) {
					clean = clean.substring(0, clean.length() - 4).trim();
				}
				if (!clean.isEmpty()) {
					if (!caseInsensitive.containsKey(clean) || Character.isUpperCase(clean.charAt(0))) {
						caseInsensitive.put(clean, entry.getValue());
					}
				}
			}
			cleanTerrs.putAll(caseInsensitive);
		}
		territories = Collections.unmodifiableMap(cleanTerrs);
	}

	public static TerritoryPreset empty(String name) {
		return new TerritoryPreset(name, "", Set.of(), Map.of());
	}

	public boolean isExcluded(String territory) {
		return excludedTerritories.contains(territory);
	}

	public TerritoryLoadout loadout(String territory) {
		if (territory == null)
			return TerritoryLoadout.ZERO;
		TerritoryLoadout exact = territories.get(territory);
		if (exact != null)
			return exact;
		for (Map.Entry<String, TerritoryLoadout> e : territories.entrySet()) {
			if (e.getKey().equalsIgnoreCase(territory)) {
				return e.getValue();
			}
		}
		return TerritoryLoadout.ZERO;
	}

	public TerritoryPreset withToggledExclusion(String territory) {
		Set<String> next = new HashSet<>(excludedTerritories);
		if (!next.add(territory)) {
			next.remove(territory);
		}
		return new TerritoryPreset(name, notes, next, territories);
	}

	public TerritoryPreset withNotes(String newNotes) {
		return new TerritoryPreset(name, newNotes, excludedTerritories, territories);
	}

	public TerritoryPreset withLoadout(String territory, TerritoryLoadout loadout) {
		Map<String, TerritoryLoadout> map = new HashMap<>(territories);
		if (loadout == null || loadout.isZero()) {
			map.remove(territory);
		} else {
			map.put(territory, loadout);
		}
		return new TerritoryPreset(name, notes, excludedTerritories, map);
	}

	public record TerritoryLoadout(int damage, int attack, int health, int defence, Map<String, Integer> bonuses) {
		public static final TerritoryLoadout ZERO = new TerritoryLoadout(0, 0, 0, 0, Map.of());

		public TerritoryLoadout {
			bonuses = (bonuses == null) ? Map.of() : Collections.unmodifiableMap(new HashMap<>(bonuses));
		}

		public int bonus(String name) {
			if (name == null || bonuses == null)
				return 0;
			Integer val = bonuses.get(name);
			if (val != null)
				return val;
			for (Map.Entry<String, Integer> e : bonuses.entrySet()) {
				if (e.getKey().equalsIgnoreCase(name)) {
					return e.getValue();
				}
			}
			return 0;
		}

		public boolean isZero() {
			return damage == 0 && attack == 0 && health == 0 && defence == 0 && bonuses.isEmpty();
		}
	}
}
