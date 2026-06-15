package com.edenguild.bridge.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * Detects shout announcements and renders them for the bridge chat, e.g.:
 *
 * <pre>
 *   PlayerName/Nick &lt;rank&gt; shouts: Eden [EDN] is recruiting...
 * </pre>
 *
 * <p>The rank badge between the name and {@code shouts:} is a
 * custom-font glyph stripped by {@link ChatText#normalize}; the name is hover-resolved.
 */
public final class ShoutParser {
    private static final Pattern SHOUT = Pattern.compile("^(.+?)\\s+shouts:\\s+(.+)$");

    private ShoutParser() {}

    /** Cheap keyword gate before the regex/cleanup runs. */
    public static boolean isCandidate(Component message) {
        return message != null && message.getString().contains("shouts:");
    }

    /** Parse a shout into its bridge-chat line ("<name> shouts: <message>"), if it is one. */
    public static Optional<String> parse(Component message) {
        if (!isCandidate(message)) {
            return Optional.empty();
        }
        Matcher matcher = SHOUT.matcher(ChatText.normalize(message.getString()));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(resolve(message, matcher.group(1)) + " shouts: " + matcher.group(2).trim());
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
