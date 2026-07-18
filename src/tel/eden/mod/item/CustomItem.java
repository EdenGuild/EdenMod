package tel.eden.mod.item;

import java.util.List;

/**
 * One custom-texture rule (ported from avomod2): a consumable {@code type}
 * ("food"/"potion"/"scroll") plus name and lore regexes that must <em>all</em> match,
 * mapping the item to a {@code edenmod:<texture>} model.
 */
public record CustomItem(String type, List<String> names, List<String> lores, String texture) {
}
