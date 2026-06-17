package tel.eden.mod.item;

import java.util.List;

/**
 * A decoded Wynncraft item, reduced to just what the card renderer needs.
 *
 * @param name           the item name (e.g. {@code "Gale's Force"})
 * @param tier           the rarity/gear tier display name (e.g. {@code "Legendary"})
 * @param tierColor      the rarity colour as 0xRRGGBB (for the name + pill)
 * @param type           the gear type display name (e.g. {@code "Bow"}), may be empty
 * @param overallPercent the overall roll quality 0-100, or a negative value if absent
 * @param identifications the rolled identifications, top to bottom
 * @param powderSlots    the number of powder slots on the item
 */
public record DecodedItem(
        String name,
        String tier,
        int tierColor,
        String type,
        float overallPercent,
        List<Identification> identifications,
        int powderSlots) {

    /** Whether an overall roll quality is present (gear with variable stats). */
    public boolean hasOverall() {
        return overallPercent >= 0;
    }

    /**
     * One rolled identification line.
     *
     * @param name        stat display name (e.g. {@code "Walk Speed"})
     * @param valueText   the formatted value with sign + unit (e.g. {@code "+38%"})
     * @param rollPercent the roll quality 0-100, or a negative value if not applicable
     * @param positive    whether the roll is beneficial (green) vs detrimental (red)
     */
    public record Identification(String name, String valueText, float rollPercent, boolean positive) {

        /** Whether a roll percentage applies (variable, non pre-identified stats). */
        public boolean hasRoll() {
            return rollPercent >= 0;
        }
    }
}
