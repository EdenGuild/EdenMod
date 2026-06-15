package com.edenguild.bridge.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * Detects Wynncraft level-up announcements (combat and profession), e.g.:
 *
 * <pre>
 *   [!] Congratulations to PlayerName for reaching combat level 121!
 *   [!] Congratulations to PlayerName for reaching level 120 in &lt;icon&gt; Fishing!
 * </pre>
 *
 * <p>The player name is hover-resolved to a real account name (the same way guild
 * chat is), so a "/nick" or styled name still matches the guild roster. The
 * profession icon (a custom-font glyph) is stripped by {@link ChatText#normalize}.
 */
public final class LevelUpParser {
    private static final Pattern COMBAT = Pattern.compile(
            "Congratulations to (.+?) for reaching combat level (\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROFESSION = Pattern.compile(
            "Congratulations to (.+?) for reaching level (\\d+) in .*?([A-Za-z]+)\\s*!",
            Pattern.CASE_INSENSITIVE);

    private LevelUpParser() {}

    /** Cheap keyword gate before the regexes run. */
    public static boolean isCandidate(Component message) {
        return message != null && message.getString().contains("for reaching");
    }

    /** Parse a system-chat component, returning a level-up if it is one. */
    public static Optional<LevelUp> parse(Component message) {
        if (!isCandidate(message)) {
            return Optional.empty();
        }
        String text = ChatText.normalize(message.getString());
        Matcher combat = COMBAT.matcher(text);
        if (combat.find()) {
            return Optional.of(new LevelUp(
                    resolve(message, combat.group(1)), "combat level " + combat.group(2)));
        }
        Matcher profession = PROFESSION.matcher(text);
        if (profession.find()) {
            return Optional.of(new LevelUp(
                    resolve(message, profession.group(1)),
                    "level " + profession.group(2) + " in " + profession.group(3)));
        }
        return Optional.empty();
    }

    /** Real account name from hover, else the displayed name minus any "/nick". */
    private static String resolve(Component message, String displayed) {
        String resolved = ChatText.resolveRealName(message, displayed);
        if (resolved != null) {
            return resolved;
        }
        String name = displayed.trim();
        int slash = name.indexOf('/');
        return slash > 0 ? name.substring(0, slash).trim() : name;
    }
}
