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
import tel.eden.mod.util.IdentityPairCache;

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

	/** A rule with its name/lore regexes precompiled, and its model id + label resolved once. */
	private record Rule(List<Pattern> names, List<Pattern> lores, Identifier texture, String label) {
	}

	/** What a matched item renders as: a custom model, and the short slot label for it. */
	private record Decision(Identifier model, String label) {
	}

	// Rules grouped by consumable type, built once at class load.
	private static final Map<String, List<Rule>> RULES_BY_TYPE = compileRules();

	// Sentinel meaning "scanned, matched nothing" in the decision cache (never set on a stack).
	private static final Decision NO_MATCH = new Decision(Identifier.fromNamespaceAndPath(NAMESPACE, "no_match"), "");
	// Texture/label decision cached by the item's (name, lore) components: a bank full of
	// identical items — and overlays that rebuild stacks each frame — resolve once, not per
	// frame. Keyed by the components' own value equality (no hash-collision risk) and
	// computable without flattening the lore to strings, so a cache hit skips that per-item
	// work too. Both the model swap and the label read the same entry, so showing labels
	// costs no extra scanning.
	private static final int CACHE_LIMIT = 4096;
	private static final Map<CacheKey, Decision> DECISION_CACHE = new HashMap<>();
	// First level in front of DECISION_CACHE. Hashing a CacheKey walks every sibling and
	// style of the name and every lore line, and a stack that no rule matches is never
	// re-modelled, so it would pay that on every frame. An unchanged stack hands back the
	// same component instances, so look them up by reference and only fall through to the
	// value-keyed cache for components not seen before.
	private static final IdentityPairCache<Component, ItemLore, Decision> IDENTITY_CACHE = new IdentityPairCache<>(512);

	/** Value-equality cache key: the item's name and lore components. */
	private record CacheKey(Component name, ItemLore lore) {
	}

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
			// A label-only rule carries no model, so its key never has to name a shipped asset.
			Identifier model = item.labelOnly() ? null : Identifier.fromNamespaceAndPath(NAMESPACE, item.texture());
			byType.computeIfAbsent(item.type().toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(new Rule(names, lores, model, ConsumableLabels.forTexture(item.texture())));
		}
		return byType;
	}

	/** Called for each rendered item (render thread only); swaps in a custom model on match. */
	public static void applyCustomTexture(ItemStack stack) {
		if (!EdenModClient.instance().config().customItemTextures) {
			return;
		}
		Decision decision = decisionFor(stack);
		// A label-only match has no model: it is here to name the slot, not to reskin it.
		if (decision != null && decision.model() != null) {
			stack.set(DataComponents.ITEM_MODEL, decision.model());
		}
	}

	/**
	 * The short slot label for a consumable ({@code "MR"}, {@code "SD"}, …), or null when
	 * the item isn't one we recognise.
	 *
	 * <p>Once an item carries our model the texture name <em>is</em> the answer, so the
	 * label comes straight off the model id — no cache key, and none of the
	 * name/lore-component hashing that entails. That matters because this runs for every
	 * slot every frame, and a bank page of textured consumables would otherwise pay that
	 * hash twice per item (once here, once for the swap). The full scan below is only
	 * reached before the swap lands, or when custom textures are switched off.
	 */
	public static String labelFor(ItemStack stack) {
		Identifier model = stack.get(DataComponents.ITEM_MODEL);
		if (model != null && model.getNamespace().equals(NAMESPACE)) {
			// NO_MATCH is never written onto a stack, but it shares the namespace, so it is
			// excluded here rather than relying on that staying true.
			String texture = model.getPath();
			return texture.equals(NO_MATCH.model().getPath()) ? null : emptyToNull(ConsumableLabels.forTexture(texture));
		}
		Decision decision = decisionFor(stack);
		return decision == null ? null : emptyToNull(decision.label());
	}

	private static String emptyToNull(String label) {
		return label == null || label.isEmpty() ? null : label;
	}

	/**
	 * The cached texture/label decision for a stack, or null when it isn't a consumable we
	 * texture. Render thread only — {@link #DECISION_CACHE} is deliberately unsynchronised.
	 */
	private static Decision decisionFor(ItemStack stack) {
		// Cheap gate first: skip anything that isn't a base model we texture (or one we
		// already swapped), so the vast majority of items exit before any lore work.
		if (Minecraft.getInstance().player == null || !isEligible(stack)) {
			return null;
		}
		// Key off the components directly (cheap, no string flattening) and check the cache
		// before doing any lore/name string work.
		ItemLore loreComponent = stack.get(DataComponents.LORE);
		Component hoverName = stack.getHoverName();
		ItemLore loreKey = loreComponent == null ? ItemLore.EMPTY : loreComponent;
		Decision cached = IDENTITY_CACHE.get(hoverName, loreKey);
		if (cached == null) {
			cached = DECISION_CACHE.get(new CacheKey(hoverName, loreKey));
			if (cached != null) {
				IDENTITY_CACHE.put(hoverName, loreKey, cached);
			}
		}
		if (cached != null) {
			return cached == NO_MATCH ? null : cached;
		}
		// Cache miss: now pay for flattening the lore to strings and scanning the rules.
		String name = hoverName.getString();
		List<String> lore = loreStrings(stack);
		Decision match = name.equals("Air") ? null : resolve(stack, name, lore);
		if (DECISION_CACHE.size() >= CACHE_LIMIT) {
			DECISION_CACHE.clear();
		}
		Decision decision = match == null ? NO_MATCH : match;
		DECISION_CACHE.put(new CacheKey(hoverName, loreKey), decision);
		IDENTITY_CACHE.put(hoverName, loreKey, decision);
		return match;
	}

	/** Full rule scan for an item (only on a cache miss). Returns the decision or null. */
	private static Decision resolve(ItemStack stack, String name, List<String> lore) {
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
				return new Decision(rule.texture(), rule.label());
			}
		}
		return null;
	}

	/**
	 * Cheap gate: only base models we texture (crafted food renders as a diamond axe,
	 * potions as a potion, and splash potions) are eligible, and anything already swapped to
	 * our namespace is skipped — its model is settled, and {@link #labelFor} reads such
	 * items straight off the model id without coming through here.
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
