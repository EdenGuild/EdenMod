package tel.eden.mod.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/**
 * Detects Wynncraft guild-bank deposit/withdrawal messages and extracts a
 * structured {@link BankEvent}. The visible form is, e.g.:
 *
 * <pre>
 *   PlayerName withdrew 1x Dernic Axe T12 from the Guild Bank (Everyone)
 *   PlayerName deposited 64x Oak Wood to the Guild Bank (High Ranked)
 * </pre>
 *
 * <p>High-Ranked-tier messages are only visible to high-ranked members, so those
 * events are only captured when the mod user can see them. The player's real
 * account name is recovered from hover text like guild chat.
 */
public final class BankEventParser {
    private static final Pattern BANK_PATTERN = Pattern.compile(
            "^(.+?)\\s+(deposited|withdrew)\\s+(.+?)\\s+(?:to|from)\\s+the Guild Bank\\s+\\((.+)\\)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUANTITY = Pattern.compile("^(?:(\\d+)x\\s+)?(.+)$");
    private static final Pattern CHARGES = Pattern.compile("^(.*?)(?:\\s+\\[([^\\]]+)\\])?$");
    private static final Pattern HOVER_REAL_NAME = Pattern.compile(
            "(?:'(?:s)? real name is\\s+|Real Username:\\s*)([a-zA-Z0-9_]{3,16})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IGN = Pattern.compile("[a-zA-Z0-9_]{3,16}");

    private BankEventParser() {}

    /** Cheap keyword gate before the regex/cleanup runs. */
    public static boolean isCandidate(Component message) {
        if (message == null) {
            return false;
        }
        String plain = message.getString();
        return plain.contains("Guild Bank")
                && (plain.contains("deposited") || plain.contains("withdrew"));
    }

    /** Parse a system-chat component, returning a bank event if it is one. */
    public static Optional<BankEvent> parse(Component message) {
        if (!isCandidate(message)) {
            return Optional.empty();
        }
        String cleaned = ChatText.normalize(message.getString());
        Matcher matcher = BANK_PATTERN.matcher(cleaned);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String displayedPlayer = matcher.group(1).trim();
        String action = matcher.group(2).toLowerCase(java.util.Locale.ROOT);
        String itemBlock = matcher.group(3).trim();
        String accessTier = matcher.group(4).trim();

        Matcher quantity = QUANTITY.matcher(itemBlock);
        if (!quantity.matches()) {
            return Optional.empty();
        }
        Integer count = quantity.group(1) == null ? null : Integer.valueOf(quantity.group(1));
        Matcher charges = CHARGES.matcher(quantity.group(2).trim());
        if (!charges.matches()) {
            return Optional.empty();
        }
        String item = charges.group(1).trim();
        if (item.isEmpty()) {
            return Optional.empty();
        }

        String player = resolvePlayer(message, displayedPlayer);
        if (player == null) {
            return Optional.empty();
        }
        String verb = "deposited".equals(action) ? "deposit" : "withdrawal";
        return Optional.of(new BankEvent(verb, player, count, item, charges.group(2), accessTier));
    }

    private static String resolvePlayer(Component message, String displayed) {
        String fromHover = firstHoverRealName(message);
        if (fromHover != null) {
            return fromHover;
        }
        String cleaned = displayed.replaceAll("[^a-zA-Z0-9_]", "");
        return IGN.matcher(cleaned).matches() ? cleaned : null;
    }

    private static String firstHoverRealName(Component message) {
        String[] found = {null};
        message.visit(
                (style, text) -> {
                    if (found[0] == null) {
                        String hover = hoverRealName(style);
                        if (hover != null) {
                            found[0] = hover;
                        }
                    }
                    return Optional.empty();
                },
                Style.EMPTY);
        return found[0];
    }

    private static String hoverRealName(Style style) {
        HoverEvent hover = style.getHoverEvent();
        if (hover instanceof HoverEvent.ShowText showText) {
            String text = showText.value().getString().replace('’', '\'').replace('‘', '\'');
            Matcher matcher = HOVER_REAL_NAME.matcher(text);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }
}
