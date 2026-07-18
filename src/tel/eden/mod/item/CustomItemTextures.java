package tel.eden.mod.item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import tel.eden.mod.EdenModClient;

/**
 * Client-side custom textures for crafted consumables (food/potions/scrolls), ported from
 * avomod2. On every GUI item render (via the item-render mixin) we inspect the item's
 * Wynncraft lore and name; if it matches one of the {@link CustomItemData} rules we swap
 * its {@code ITEM_MODEL} data component to an {@code edenmod:<texture>} model. The models
 * and textures ship under {@code assets/edenmod/{items,models,textures}}.
 *
 * <p>This runs for every rendered item every frame — including overlays (e.g. bank views)
 * that rebuild hundreds of stacks per frame — so it is heavily optimised: regexes are
 * precompiled and grouped by consumable type, lore is read straight from the {@code LORE}
 * component (no full-tooltip build), the type is determined in a single pass, and the final
 * texture decision is cached by a cheap (name, lore) fingerprint so repeated/rebuilt items
 * resolve once instead of re-scanning.
 */
public final class CustomItemTextures {
	private CustomItemTextures() {
	}

	// Wynncraft prefixes each consumable's lore with a run of private-use glyphs spelling
	// its type ("Crafted ... Food/Scroll/Potion"); these are the exact code points (avomod2).
	private static final String FOOD_GLYPHS = "\uE035\uDAFF\uDFFF\uE03E\uDAFF\uDFFF\uE03E\uDAFF\uDFFF\uE033\uDAFF\uDFFF\uE062\uDAFF\uDFE6\uE005\uE00E\uE00E\uE003\uDB00\uDC02";
	private static final String SCROLL_GLYPHS = "\uE042\uDAFF\uDFFF\uE032\uDAFF\uDFFF\uE041\uDAFF\uDFFF\uE03E\uDAFF\uDFFF\uE03B\uDAFF\uDFFF\uE03B\uDAFF\uDFFF\uE062\uDAFF\uDFDA\uE012\uE002\uE011\uE00E\uE00B\uE00B\uDB00\uDC02";
	private static final String POTION_GLYPHS = "\uE03F\uDAFF\uDFFF\uE03E\uDAFF\uDFFF\uE043\uDAFF\uDFFF\uE038\uDAFF\uDFFF\uE03E\uDAFF\uDFFF\uE03D\uDAFF\uDFFF\uE062\uDAFF\uDFDC\uE00F\uE00E\uE013\uE008\uE00E\uE00D\uDB00\uDC02";

	private static final String NAMESPACE = "edenmod";

	/** A rule with its name/lore regexes precompiled and its model id resolved once. */
	private record Rule(List<Pattern> names, List<Pattern> lores, Identifier texture) {
	}

	// Rules grouped by consumable type, built once at class load.
	private static final Map<String, List<Rule>> RULES_BY_TYPE = compileRules();

	// Sentinel meaning "scanned, matched nothing" in the decision cache (never set on a stack).
	private static final Identifier NO_MATCH = Identifier.fromNamespaceAndPath(NAMESPACE, "no_match");
	// Texture decision cached by a cheap (name, lore) fingerprint: a bank full of identical
	// items — and overlays that rebuild stacks each frame — then resolve once, not per frame.
	private static final int CACHE_LIMIT = 4096;
	private static final Map<Long, Identifier> DECISION_CACHE = new HashMap<>();

	private static Map<String, List<Rule>> compileRules() {
		Map<String, List<Rule>> byType = new HashMap<>();
		for (CustomItem item : CustomItemData.CUSTOM_ITEMS) {
			List<Pattern> names = new ArrayList<>();
			for (String regex : item.names()) {
				names.add(Pattern.compile(regex));
			}
			List<Pattern> lores = new ArrayList<>();
			for (String regex : item.lores()) {
				lores.add(Pattern.compile(regex));
			}
			byType.computeIfAbsent(item.type().toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(new Rule(names, lores, Identifier.fromNamespaceAndPath(NAMESPACE, item.texture())));
		}
		return byType;
	}

	/** Called for each rendered item (render thread only); swaps in a custom model on match. */
	public static void applyCustomTexture(ItemStack stack) {
		if (!EdenModClient.instance().config().customItemTextures) {
			return;
		}
		// Cheap gate first: skip anything already ours or not a base model we texture, so the
		// vast majority of items exit before any lore work.
		if (Minecraft.getInstance().player == null || !isEligible(stack)) {
			return;
		}
		String name = stack.getHoverName().getString();
		if (name.equals("Air")) {
			return;
		}
		List<String> lore = loreStrings(stack);

		long key = fingerprint(name, lore);
		Identifier cached = DECISION_CACHE.get(key);
		if (cached != null) {
			if (cached != NO_MATCH) {
				stack.set(DataComponents.ITEM_MODEL, cached);
			}
			return;
		}
		Identifier match = resolve(stack, name, lore);
		if (DECISION_CACHE.size() >= CACHE_LIMIT) {
			DECISION_CACHE.clear();
		}
		DECISION_CACHE.put(key, match == null ? NO_MATCH : match);
		if (match != null) {
			stack.set(DataComponents.ITEM_MODEL, match);
		}
	}

	/** Full rule scan for an item (only on a cache miss). Returns the model id or null. */
	private static Identifier resolve(ItemStack stack, String name, List<String> lore) {
		String type = itemType(stack, lore);
		if (type == null) {
			return null;
		}
		List<Rule> rules = RULES_BY_TYPE.get(type);
		if (rules == null) {
			return null;
		}
		for (Rule rule : rules) {
			if (nameMatches(rule.names(), name) && loreMatches(rule.lores(), lore)) {
				return rule.texture();
			}
		}
		return null;
	}

	/**
	 * Cheap gate: only base models we texture (crafted food renders as a diamond axe,
	 * potions as a potion, and splash potions) are eligible, and anything already swapped to
	 * our namespace is skipped.
	 */
	private static boolean isEligible(ItemStack itemStack) {
		Identifier model = itemStack.get(DataComponents.ITEM_MODEL);
		if (model != null && model.getNamespace().equals(NAMESPACE)) {
			return false;
		}
		return (model != null && (model.getPath().equals("diamond_axe") || model.getPath().equals("potion"))) || itemStack.is(Items.SPLASH_POTION);
	}

	/** The item's lore as plain strings, read from the LORE component (no tooltip build). */
	private static List<String> loreStrings(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) {
			return List.of();
		}
		List<Component> lines = lore.lines();
		List<String> out = new ArrayList<>(lines.size());
		for (Component line : lines) {
			out.add(line.getString());
		}
		return out;
	}

	/** Determine the consumable type in a single lore pass, or null if it isn't one. */
	private static String itemType(ItemStack stack, List<String> lore) {
		for (String line : lore) {
			if (line.contains(FOOD_GLYPHS)) {
				return "food";
			}
			if (line.contains(SCROLL_GLYPHS)) {
				return "scroll";
			}
			if (line.contains(POTION_GLYPHS)) {
				return "potion";
			}
		}
		// "Lutho"-style potions render as a vanilla potion and list three "Effect:" lines.
		if (stack.is(Items.POTION)) {
			int effects = 0;
			for (String line : lore) {
				if (line.contains("Effect:") && ++effects == 3) {
					return "potion";
				}
			}
		}
		return null;
	}

	/** Cheap fingerprint of an item's identity for the decision cache. */
	private static long fingerprint(String name, List<String> lore) {
		long hash = name.hashCode();
		for (String line : lore) {
			hash = hash * 31 + line.hashCode();
		}
		return hash;
	}

	private static boolean nameMatches(List<Pattern> patterns, String name) {
		for (Pattern pattern : patterns) {
			if (!pattern.matcher(name).matches()) {
				return false;
			}
		}
		return true;
	}

	private static boolean loreMatches(List<Pattern> patterns, List<String> lore) {
		for (Pattern pattern : patterns) {
			boolean found = false;
			for (String line : lore) {
				if (pattern.matcher(line).find()) {
					found = true;
					break;
				}
			}
			if (!found) {
				return false;
			}
		}
		return true;
	}
}
