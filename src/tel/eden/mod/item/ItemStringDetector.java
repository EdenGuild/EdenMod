package tel.eden.mod.item;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a Wynntils item-sharing string inside raw chat text.
 *
 * <p>A shared item is a run of Supplementary Private Use Area code points
 * (PUA-A {@code U+F0000..U+FFFFD} or PUA-B {@code U+100000..U+10FFFD}), optionally
 * followed by a clear-text {@code "name"} for crafted items. Wynncraft also pads
 * guild chat with short private-use rank/banner glyphs, so the <em>longest</em> run
 * above a minimum length is taken to be the item (rank badges are only a glyph or
 * two), avoiding false positives on the chat decoration.
 */
public final class ItemStringDetector {
    // A run of one or more private-use code points, then an optional ` "craftedName"`.
    private static final Pattern ITEM_STRING = Pattern.compile(
            "(?<data>([\\x{F0000}-\\x{FFFFD}]|[\\x{100000}-\\x{10FFFD}])+)( \"(?<name>.+?)\")?");
    // Item encodings are always several blocks long; this comfortably clears the
    // one- or two-glyph rank/banner decoration on guild chat lines.
    private static final int MIN_ITEM_CODEPOINTS = 6;

    private ItemStringDetector() {}

    /** A detected shared item: the encoded string and an optional crafted name. */
    public record Detected(String itemString, String craftedName) {}

    /** The longest qualifying item string in {@code rawText}, if any. */
    public static Optional<Detected> detect(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = ITEM_STRING.matcher(rawText);
        String bestData = null;
        String bestName = null;
        int bestLength = 0;
        while (matcher.find()) {
            String data = matcher.group("data");
            int codePoints = data.codePointCount(0, data.length());
            if (codePoints >= MIN_ITEM_CODEPOINTS && codePoints > bestLength) {
                bestLength = codePoints;
                bestData = data;
                bestName = matcher.group("name");
            }
        }
        return bestData == null ? Optional.empty() : Optional.of(new Detected(bestData, bestName));
    }
}
