package com.edenguild.bridge.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/**
 * Detects guild raid completions in Wynncraft system chat and extracts the party,
 * raid name, and loot. Wynncraft sends these as multiline aqua announcements with
 * private-use glyph prefixes. The party is 1-4 players and the claimed loot varies
 * (any of aspects, emeralds, guild experience, seasonal rating may be present or
 * absent, in any order), e.g.:
 *
 * <pre>
 *   Player1, Player2, and Player3 finished Nest of the Grootslangs and claimed
 *   2x Aspects, 2048x Emeralds, +633m Guild Experience, and +440 Seasonal Rating
 *   Player1 finished Nest of the Grootslangs and claimed 2x Aspects,
 *   +158m Guild Experience, and +110 Seasonal Rating
 * </pre>
 *
 * <p>The displayed party names are <em>nicknames</em>; the real account usernames are
 * recovered from each name segment's hover text, exactly as for guild chat
 * ({@link GuildChatParser}).
 */
public final class RaidCompletionParser {
    // Detect a raid by "finished" closely followed by a distinctive keyword of one of
    // the five raids. This is robust because the loot — and sometimes the tail of the
    // raid name — is rendered in a custom icon font (private-use glyphs), so the literal
    // "claimed <loot>" text isn't reliably present in the raw chat component (Wynntils
    // only converts the glyphs to readable text after our capture hook runs).
    private static final Pattern RAID_DETECT = Pattern.compile(
            "finished\\s+[^:]{0,30}?(Grootslang|Orphion|Canyon|Nameless|Wartorn)",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> RAID_BY_KEYWORD = Map.of(
            "grootslang", "Nest of the Grootslangs",
            "orphion", "Orphion's Nexus of Light",
            "canyon", "The Canyon Colossus",
            "nameless", "The Nameless Anomaly",
            "wartorn", "The Wartorn Palace");
    private static final Pattern NAMES = Pattern.compile("^(.+?)\\s+finished\\b");
    // Loot figures, pulled best-effort (each absent when rendered as glyphs).
    private static final Pattern ASPECTS = Pattern.compile("(\\d+)x Aspects");
    private static final Pattern EMERALDS = Pattern.compile("(\\d+)x Emeralds");
    private static final Pattern GUILD_EXP = Pattern.compile("\\+([\\d.]+)m Guild Experience");
    private static final Pattern HOVER_REAL_NAME = Pattern.compile(
            "(?:'(?:s)? real name is\\s+|Real Username:\\s*)([a-zA-Z0-9_]{3,16})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IGN = Pattern.compile("[a-zA-Z0-9_]{3,16}");
    private static final Pattern COMMA = Pattern.compile("\\s*,\\s*");
    private static final String FINISHED = " finished ";

    private RaidCompletionParser() {}

    /** Cheap keyword gate so the regex/cleanup runs only for plausible raid lines. */
    public static boolean isRaidCandidate(Component message) {
        return message != null && message.getString().contains("finished");
    }

    /** Parse a system-chat component, returning a raid completion if it is one. */
    public static Optional<RaidCompletion> parse(Component message) {
        if (!isRaidCandidate(message)) {
            return Optional.empty();
        }
        List<MetaChar> chars = flatten(message);
        String cleaned = ChatText.normalize(text(chars)).replace(",and ", ", and ");
        Matcher detect = RAID_DETECT.matcher(cleaned);
        if (!detect.find()) {
            return Optional.empty();
        }
        String raidName = RAID_BY_KEYWORD.get(detect.group(1).toLowerCase(Locale.ROOT));
        Matcher names = NAMES.matcher(cleaned);
        if (raidName == null || !names.find()) {
            return Optional.empty();
        }
        List<String> party = resolveParty(splitNames(names.group(1)), chars);
        if (party.isEmpty()) {
            return Optional.empty();
        }
        // Loot is best-effort: it's frequently a custom-font glyph run, not literal
        // text, so pull whatever figures are present and default the rest. The
        // per-player reward is fixed backend-side and does not depend on these.
        int aspects = firstInt(ASPECTS, cleaned);
        int emeralds = firstInt(EMERALDS, cleaned);
        String guildExp = firstGroup(GUILD_EXP, cleaned);
        return Optional.of(new RaidCompletion(party, raidName, aspects, emeralds, guildExp));
    }

    /** First integer captured by {@code pattern} in {@code text}, or 0 if absent. */
    private static int firstInt(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** First group-1 capture of {@code pattern} in {@code text}, or "" if absent. */
    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<String> splitNames(String namesPart) {
        String canonical = namesPart.replace(", and ", ", ").replace(" and ", ", ").trim();
        List<String> names = new ArrayList<>();
        for (String token : COMMA.split(canonical)) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }

    /**
     * Resolve each displayed party name to a real username using the hover/insertion
     * metadata carried on the characters that make up the name. Falls back to the
     * displayed name when it is already a valid username.
     */
    private static List<String> resolveParty(List<String> displayed, List<MetaChar> chars) {
        String prefix = prefixText(chars);
        Set<String> resolved = new LinkedHashSet<>();
        int searchFrom = 0;
        for (String name : displayed) {
            int start = prefix.indexOf(name, searchFrom);
            String hover = null;
            String insertion = null;
            if (start >= 0) {
                for (int i = start; i < start + name.length() && i < chars.size(); i++) {
                    MetaChar mc = chars.get(i);
                    if (hover == null && mc.hover() != null) {
                        hover = mc.hover();
                    }
                    if (insertion == null && mc.insertion() != null) {
                        insertion = mc.insertion();
                    }
                }
                searchFrom = start + name.length();
            }
            String real = hover != null ? hover : (isIgn(name) ? name : insertion);
            if (real != null && isIgn(real)) {
                resolved.add(real);
            }
        }
        return List.copyOf(new ArrayList<>(resolved));
    }

    /** Whitespace-collapsed character stream up to the " finished " boundary. */
    private static String prefixText(List<MetaChar> chars) {
        String full = text(chars);
        int boundary = full.indexOf(FINISHED);
        return boundary < 0 ? full : full.substring(0, boundary);
    }

    /**
     * Flatten the component into a per-character stream that carries each character's
     * hover-resolved real name and insertion, with private-use/whitespace runs
     * collapsed to single spaces so it aligns with {@link ChatText#normalize}.
     */
    private static List<MetaChar> flatten(Component message) {
        List<MetaChar> out = new ArrayList<>();
        message.visit(
                (style, fragment) -> {
                    appendFragment(out, fragment, hoverRealName(style), insertionName(style));
                    return Optional.empty();
                },
                Style.EMPTY);
        return out;
    }

    private static void appendFragment(List<MetaChar> out, String text, String hover, String insertion) {
        boolean previousWasSpace = !out.isEmpty() && out.get(out.size() - 1).value() == ' ';
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || ChatText.isIgnorable(codePoint)) {
                if (!previousWasSpace) {
                    out.add(new MetaChar(' ', hover, insertion));
                    previousWasSpace = true;
                }
                continue;
            }
            for (char ch : Character.toChars(codePoint)) {
                out.add(new MetaChar(ch, hover, insertion));
            }
            previousWasSpace = false;
        }
    }

    private static String text(List<MetaChar> chars) {
        StringBuilder builder = new StringBuilder(chars.size());
        for (MetaChar mc : chars) {
            builder.append(mc.value());
        }
        return builder.toString().trim();
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

    private static String insertionName(Style style) {
        String insertion = style.getInsertion();
        return insertion != null && isIgn(insertion) ? insertion : null;
    }

    private static boolean isIgn(String value) {
        return value != null && IGN.matcher(value).matches();
    }

    private record MetaChar(char value, String hover, String insertion) {}
}
