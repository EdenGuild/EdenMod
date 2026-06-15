package com.edenguild.bridge.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * Detects Wynncraft guild-management and alliance announcements and extracts a
 * {@link GuildEvent}. Visible forms (from in-game):
 *
 * <pre>
 *   NewMember has joined the guild, say hello!
 *   Officer/Nick has invited Recruit to the guild
 *   Officer/Nick has uninvited Recruit from the guild
 *   AllyLeader from Another Guild is requesting to be allied
 *   Officer/Nick revoked the alliance with Some Other Guild
 *   Eden formed an alliance with Some Other Guild
 * </pre>
 *
 * <p>Player names are recovered to their real account name from hover text (like
 * guild chat); guild names are kept as shown. Leading groups exclude {@code ':'}
 * so a guild-chat line mentioning these phrases can't be misread as an event.
 */
public final class GuildEventParser {
    // Player tokens (single word, may carry a "/nick"); guild names may have spaces.
    private static final String NAME = "[A-Za-z0-9_/]+";
    private static final Pattern JOIN =
            Pattern.compile("^(" + NAME + ") has joined the guild.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEAVE =
            Pattern.compile("^(" + NAME + ") has left the guild.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INVITE = Pattern.compile(
            "^(" + NAME + ") has invited (" + NAME + ") to the guild$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNINVITE = Pattern.compile(
            "^(" + NAME + ") has uninvited (" + NAME + ") from the guild$",
            Pattern.CASE_INSENSITIVE);
    // The kicker is shown by nickname (which can contain spaces), so allow any
    // non-colon run there; ':' is excluded so a guild-chat line can't be misread.
    private static final Pattern KICK = Pattern.compile(
            "^([^:]+?) has kicked ([^:]+?) from the guild$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALLIANCE_REQUEST = Pattern.compile(
            "^(" + NAME + ") from ([^:]+?) is requesting to be allied$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALLIANCE_FORMED = Pattern.compile(
            "^([^:]+?) formed an alliance with (.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALLIANCE_REVOKED = Pattern.compile(
            "^([^:]+?) revoked the alliance with (.+)$", Pattern.CASE_INSENSITIVE);
    // Outgoing-side alliance actions (we initiated/responded), e.g.:
    //   Officer/Nick sent Another Guild a request to be allied
    //   Officer/Nick rejected Another Guild alliance request
    //   Officer/Nick withdrew Another Guild alliance request
    private static final Pattern ALLIANCE_SENT = Pattern.compile(
            "^(" + NAME + ") sent (.+?) a request to be allied$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALLIANCE_REJECTED = Pattern.compile(
            "^(" + NAME + ") rejected (.+?) alliance request$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALLIANCE_WITHDREW = Pattern.compile(
            "^(" + NAME + ") withdrew (.+?) alliance request$", Pattern.CASE_INSENSITIVE);

    private GuildEventParser() {}

    /** Cheap keyword gate before the regexes/cleanup run. */
    public static boolean isCandidate(Component message) {
        if (message == null) {
            return false;
        }
        String plain = message.getString();
        // "alli" covers both "alliance" and "allied" (request to be allied).
        return plain.contains("the guild") || plain.contains("alli");
    }

    /** Parse a system-chat component, returning a guild event if it is one. */
    public static Optional<GuildEvent> parse(Component message) {
        if (!isCandidate(message)) {
            return Optional.empty();
        }
        String text = ChatText.normalize(message.getString());

        Matcher m = JOIN.matcher(text);
        if (m.matches()) {
            return Optional.of(new GuildEvent("join", "", resolvePlayer(message, m.group(1))));
        }
        m = LEAVE.matcher(text);
        if (m.matches()) {
            return Optional.of(new GuildEvent("leave", "", resolvePlayer(message, m.group(1))));
        }
        m = INVITE.matcher(text);
        if (m.matches()) {
            return Optional.of(new GuildEvent(
                    "invite", resolvePlayer(message, m.group(1)), resolvePlayer(message, m.group(2))));
        }
        m = UNINVITE.matcher(text);
        if (m.matches()) {
            return Optional.of(new GuildEvent(
                    "uninvite",
                    resolvePlayer(message, m.group(1)),
                    resolvePlayer(message, m.group(2))));
        }
        m = KICK.matcher(text);
        if (m.matches()) {
            return Optional.of(new GuildEvent(
                    "kick", resolvePlayer(message, m.group(1)), resolvePlayer(message, m.group(2))));
        }
        m = ALLIANCE_REQUEST.matcher(text);
        if (m.matches()) {
            return Optional.of(new GuildEvent(
                    "alliance_request", resolvePlayer(message, m.group(1)), m.group(2).trim()));
        }
        m = ALLIANCE_SENT.matcher(text);
        if (m.matches()) {
            return Optional.of(new GuildEvent(
                    "alliance_sent", resolvePlayer(message, m.group(1)), m.group(2).trim()));
        }
        m = ALLIANCE_REJECTED.matcher(text);
        if (m.matches()) {
            return Optional.of(new GuildEvent(
                    "alliance_rejected", resolvePlayer(message, m.group(1)), m.group(2).trim()));
        }
        m = ALLIANCE_WITHDREW.matcher(text);
        if (m.matches()) {
            return Optional.of(new GuildEvent(
                    "alliance_withdrew", resolvePlayer(message, m.group(1)), m.group(2).trim()));
        }
        m = ALLIANCE_REVOKED.matcher(text);
        if (m.matches()) {
            return Optional.of(new GuildEvent(
                    "alliance_revoked", resolvePlayer(message, m.group(1)), m.group(2).trim()));
        }
        m = ALLIANCE_FORMED.matcher(text);
        if (m.matches()) {
            return Optional.of(new GuildEvent("alliance_formed", "", m.group(2).trim()));
        }
        return Optional.empty();
    }

    /** Real account name from hover, else the displayed name minus any "/nick". */
    private static String resolvePlayer(Component message, String displayed) {
        String resolved = ChatText.resolveRealName(message, displayed);
        if (resolved != null) {
            return resolved;
        }
        String name = displayed.trim();
        int slash = name.indexOf('/');
        return slash > 0 ? name.substring(0, slash).trim() : name;
    }
}
