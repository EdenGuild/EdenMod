package com.edenguild.bridge.chat;

import java.util.Optional;
import net.minecraft.network.chat.Component;

/**
 * Detects guild "flavour" announcements that should be mirrored verbatim into the
 * Discord bridge chat (not the management feed), e.g.:
 *
 * <pre>
 *   KingVonGaming has finished their weekly objective.
 *   m1ngx210 has started boosting the guild
 * </pre>
 *
 * <p>Returns the cleaned line to relay as-is so Discord shows the same message.
 */
public final class GuildAnnounceParser {
    private static final String WEEKLY = "(?i)^.+ has finished their weekly objective\\.?$";
    private static final String BOOST = "(?i)^.+ has started boosting the guild\\.?$";

    private GuildAnnounceParser() {}

    /** Cheap keyword gate before the cleanup/match runs. */
    public static boolean isCandidate(Component message) {
        if (message == null) {
            return false;
        }
        String plain = message.getString();
        return plain.contains("weekly objective") || plain.contains("boosting the guild");
    }

    /** Parse a system-chat component, returning the line to relay if it is one. */
    public static Optional<String> parse(Component message) {
        if (!isCandidate(message)) {
            return Optional.empty();
        }
        String text = ChatText.normalize(message.getString());
        if (text.matches(WEEKLY) || text.matches(BOOST)) {
            return Optional.of(text);
        }
        return Optional.empty();
    }
}
