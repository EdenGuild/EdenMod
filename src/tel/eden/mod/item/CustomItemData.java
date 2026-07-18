package tel.eden.mod.item;

import java.util.List;

/**
 * The custom-texture rule table, ported verbatim from avomod2. Rules are checked top to
 * bottom and the first whose type + name-regexes + lore-regexes all match wins, so more
 * specific rules must precede broader ones. Texture names map to models/textures shipped
 * under {@code assets/edenmod/{items,models,textures}} (drop in avomod2's set renamed to
 * the {@code edenmod} namespace to use them).
 */
public final class CustomItemData {
	private CustomItemData() {
	}

	public static final List<CustomItem> CUSTOM_ITEMS = List.of(
		new CustomItem("food", List.of(), List.of(".*Attack Speed.*"), "food_-atk"),
		new CustomItem("food", List.of(), List.of(".*Mana Regen.*", ".*Combat Experience.*"), "food_cat"),
		new CustomItem("food", List.of(), List.of(".*Healing Efficiency.*"), "food_healing_efficiency"),
		new CustomItem("food", List.of(), List.of("\\bHealth\\b(?!\\s+Regen)", ".*Fire Damage.*", ".*Fire Defence.*"), "food_health_infernal"),
		new CustomItem("food", List.of(), List.of(".*Jump Height.*"), "food_jh"),
		new CustomItem("food", List.of(), List.of(".*Mana Regen.*", ".*% Health Regen.*", ".*Health Regen.*"), "food_mr_big"),
		new CustomItem("food", List.of(), List.of(".*Health Regen.*", ".*Mana Steal.*", ".*Main Attack Damage.*"), "food_ms"),
		new CustomItem("food", List.of(), List.of(".*Mana Steal.*", ".*Gathering Experience.*", ".*Gather Speed.*"), "food_ms"),
		new CustomItem("food", List.of(), List.of(".*Earth Damage.*", ".*Thunder Damage.*", ".*Water Damage.*", ".*Fire Damage.*", ".*Air Damage.*"), "food_rainbow"),
		new CustomItem("food", List.of(), List.of(".*Thunder Damage.*", ".*Water Damage.*"), "food_thunder_water"),
		new CustomItem("food", List.of(), List.of("\\bHealth\\b(?!\\s+Regen)"), "food_health"),
		new CustomItem("food", List.of(), List.of(".*Spell Damage.*"), "food_sd"),
		new CustomItem("food", List.of(), List.of(".*Intelligence.*", ".*Water Defence.*"), "food_intelligence"),
		new CustomItem("food", List.of(), List.of(".*Intelligence.*", ".*Strength.*"), "food_strint"),
		new CustomItem("food", List.of(), List.of(".*Strength.*"), "food_strength"),
		new CustomItem("food", List.of(), List.of(".*Walk Speed.*"), "food_ws"),
		new CustomItem("food", List.of(), List.of(".*Combat Experience.*"), "food_xp"),
		new CustomItem("food", List.of(), List.of(".*Agility.*"), "food_agility"),
		new CustomItem("potion", List.of(), List.of(".*Dexterity.*", ".*Strength.*", ".*Agility.*", ".*Intelligence.*", ".*Defence.*", ".*Gathering Experience.*", ".*Spell Damage.*"), "potion_gatherxptype1"),
		new CustomItem("potion", List.of(), List.of(".*Exploding.*"), "potion_exploding"),
		new CustomItem("potion", List.of(), List.of(".*Earth Damage.*", ".*Thunder Damage.*", ".*Water Damage.*", ".*Fire Damage.*", ".*Air Damage.*"), "potion_rainbow"),
		new CustomItem("potion", List.of(), List.of(".*Water Damage.*", ".*Thunder Damage.*", ".*Fire Damage.*", ".*Gather Speed.*", ".*Gathering Experience.*", ".*Earth Damage.*", ".*Air Damage.*"), "potion_gatherspeedtype1"),
		new CustomItem("potion", List.of(), List.of(".*Defence.*", ".*Gathering Experience.*", ".*Fire Defence.*"), "potion_gatherxptype1"),
		new CustomItem("potion", List.of(), List.of(".*Healing Efficiency.*"), "potion_healing_efficiency"),
		new CustomItem("potion", List.of(), List.of("\\bHealth\\b(?!\\s+Regen)"), "potion_health"),
		new CustomItem("potion", List.of(".*Potion of Mana.*"), List.of(".*regenerate your mana.*"), "potion_mana"),
		new CustomItem("potion", List.of(), List.of(".*Mana Regen.*", ".*% Spell Damage.*", ".*Spell Damage.*"), "potion_fairy"),
		new CustomItem("potion", List.of(), List.of(".*Mana Regen.*", ".*Spell Damage.*"), "potion_glacial_anomaly"),
		new CustomItem("potion", List.of(), List.of(".*Mana Regen.*", ".*Walk Speed.*"), "potion_mr_ws"),
		new CustomItem("potion", List.of(), List.of(".*Mana Regen.*"), "potion_mr"),
		new CustomItem("potion", List.of(), List.of(".*Mana Steal.*"), "potion_ms"),
		new CustomItem("potion", List.of(), List.of(".*Earth Damage.*", ".*Thunder Damage.*"), "potion_earth_thunder"),
		new CustomItem("potion", List.of(), List.of(".*Agility.*", ".{0,5}Defence.*"), "potion_def_agi"),
		new CustomItem("potion", List.of(), List.of(".*Main Attack Damage.*", ".*Spell Damage.*", ".*Walk Speed.*"), "potion_spell_bat_heart"),
		new CustomItem("potion", List.of(".*Potion of.*Agility.*"), List.of(), "potion_sp_agility"),
		new CustomItem("potion", List.of(".*Potion of.*Defence.*"), List.of(), "potion_sp_defence"),
		new CustomItem("potion", List.of(".*Potion of.*Dexterity.*"), List.of(), "potion_sp_dexterity"),
		new CustomItem("potion", List.of(".*Potion of.*Intelligence.*"), List.of(), "potion_sp_intelligence"),
		new CustomItem("potion", List.of(".*Potion of.*Strength.*"), List.of(), "potion_sp_strength"),
		new CustomItem("potion", List.of(), List.of(".*Dexterity.*", ".*Strength.*"), "potion_strength_dexterity"),
		new CustomItem("potion", List.of(), List.of(".*Main Attack Damage.*", ".*Spell Damage.*"), "potion_spell_melee"),
		new CustomItem("potion", List.of(), List.of(".*Strength.*"), "potion_strength"),
		new CustomItem("potion", List.of(), List.of(".*Water Damage.*"), "potion_water"),
		new CustomItem("potion", List.of(), List.of(".*Combat Experience.*"), "potion_xp"),
		new CustomItem("potion", List.of(), List.of(".*Walk Speed.*"), "potion_ws"),
		new CustomItem("scroll", List.of(), List.of(".*Dexterity.*"), "scroll_dex"),
		new CustomItem("scroll", List.of(), List.of(".*Elemental Damage.*", ".*Healing Efficiency.*"), "scroll_healing_efficiency"),
		new CustomItem("scroll", List.of(), List.of(".*Health Regen.*", ".*Healing Efficiency.*"), "scroll_healing_efficiency"),
		new CustomItem("scroll", List.of(), List.of(".*Health Regen.*", "\\bHealth\\b(?!\\s+Regen)"), "scroll_hp"),
		new CustomItem("scroll", List.of(), List.of(".*Intelligence.*"), "scroll_int"),
		new CustomItem("scroll", List.of(), List.of(".*Main Attack Neutral Damage.*"), "scroll_melee"),
		new CustomItem("scroll", List.of(), List.of(".*Mana Regen.*", ".*Mana Steal.*"), "scroll_mr"),
		new CustomItem("scroll", List.of(), List.of(".*Mana Steal.*"), "scroll_ms"),
		new CustomItem("scroll", List.of(), List.of(".*Earth Damage.*", ".*Thunder Damage.*", ".*Water Damage.*", ".*Fire Damage.*", ".*Air Damage.*", ".*Water Defence.*"), "scroll_rainbow_cheap"),
		new CustomItem("scroll", List.of(), List.of(".*Earth Damage.*", ".*Thunder Damage.*", ".*Water Damage.*", ".*Fire Damage.*", ".*Air Damage.*"), "scroll_rainbow"),
		new CustomItem("scroll", List.of(), List.of(".*Walk Speed.*", ".*Agility.*"), "scroll_agi"),
		new CustomItem("scroll", List.of(), List.of(".*Defence.*", "\\bHealth\\b(?!\\s+Regen)"), "scroll_def"),
		new CustomItem("scroll", List.of(), List.of(".*Spell Damage.*"), "scroll_sd"),
		new CustomItem("scroll", List.of(), List.of(".*Main Attack Damage.*", ".*Strength.*"), "scroll_str"),
		new CustomItem("scroll", List.of(), List.of(".*Thorns.*"), "scroll_thorns"),
		new CustomItem("scroll", List.of(), List.of(".*Combat Experience.*"), "scroll_xp")
	);
}
