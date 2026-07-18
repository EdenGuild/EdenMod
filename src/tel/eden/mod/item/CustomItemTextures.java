package tel.eden.mod.item;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import tel.eden.mod.EdenModClient;

/**
 * Client-side custom textures for crafted consumables (food/potions/scrolls), ported from
 * avomod2. On every GUI item render (via the item-render mixin) we inspect the item's
 * Wynncraft lore and name; if it matches one of the {@link CustomItemData} rules we swap
 * its {@code ITEM_MODEL} data component to an {@code edenmod:<texture>} model. The models
 * and textures themselves ship as a resource pack under
 * {@code assets/edenmod/{items,models,textures}} — without those assets the feature has
 * nothing to draw, so it defaults off.
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

	/** Called for each rendered item; swaps in a custom model when a rule matches. */
	public static void applyCustomTexture(ItemStack itemStack) {
		if (!EdenModClient.instance().config().customItemTextures) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || !isEligible(itemStack)) {
			return;
		}
		String itemName = itemStack.getHoverName().getString();
		if (itemName.equals("Air")) {
			return;
		}
		List<Component> lore = itemStack.getTooltipLines(Item.TooltipContext.EMPTY, player, TooltipFlag.ADVANCED);
		for (CustomItem customItem : CustomItemData.CUSTOM_ITEMS) {
			if (isOfType(itemStack, lore, customItem.type()) && loreMatches(customItem.lores(), lore) && nameMatches(customItem.names(), itemName)) {
				itemStack.set(DataComponents.ITEM_MODEL, Identifier.parse(NAMESPACE + ":" + customItem.texture()));
				break;
			}
		}
	}

	/**
	 * Cheap gate so this doesn't run the full lore scan for every rendered item: only
	 * base models we texture (crafted food renders as a diamond axe, potions as a potion,
	 * and splash potions) are eligible, and anything already swapped to our namespace is
	 * skipped.
	 */
	private static boolean isEligible(ItemStack itemStack) {
		Identifier model = itemStack.get(DataComponents.ITEM_MODEL);
		if (model != null && model.getNamespace().equals(NAMESPACE)) {
			return false;
		}
		return (model != null && (model.getPath().equals("diamond_axe") || model.getPath().equals("potion"))) || itemStack.is(Items.SPLASH_POTION);
	}

	private static boolean isOfType(ItemStack stack, List<Component> lore, String type) {
		return switch (type.toLowerCase(Locale.ROOT)) {
			case "food" -> containsGlyphs(lore, FOOD_GLYPHS);
			case "scroll" -> containsGlyphs(lore, SCROLL_GLYPHS);
			case "potion" -> containsGlyphs(lore, POTION_GLYPHS) || isLuthoPotion(stack, lore);
			default -> false;
		};
	}

	/** "Lutho"-style potions render as a vanilla potion and list three "Effect:" lines. */
	private static boolean isLuthoPotion(ItemStack stack, List<Component> lore) {
		return stack.is(Items.POTION) && lore.stream().filter(line -> line.getString().contains("Effect:")).limit(3).count() == 3;
	}

	private static boolean containsGlyphs(List<Component> lore, String glyphs) {
		return lore.stream().anyMatch(line -> line.getString().contains(glyphs));
	}

	private static boolean loreMatches(List<String> regexes, List<Component> lore) {
		return regexes.stream().allMatch(regex -> {
			Pattern pattern = Pattern.compile(regex);
			return lore.stream().anyMatch(line -> pattern.matcher(line.getString()).find());
		});
	}

	private static boolean nameMatches(List<String> regexes, String name) {
		for (String regex : regexes) {
			if (!Pattern.matches(regex, name)) {
				return false;
			}
		}
		return true;
	}
}
