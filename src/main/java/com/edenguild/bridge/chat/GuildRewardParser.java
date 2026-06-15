package com.edenguild.bridge.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * Detects guild reward handouts in Wynncraft system chat, e.g.:
 *
 * <pre>
 *   OfficerName rewarded an Aspect to MemberName
 *   OfficerName rewarded a Guild Tome to OfficerName
 *   OfficerName rewarded 1024 Emeralds to MemberName
 * </pre>
 *
 * <p>The giver and receiver are shown by nickname (which may contain spaces), so
 * they are hover-resolved to real account names exactly as for guild chat. Each
 * group excludes {@code ':'} so a guild-chat line saying "rewarded" can't be
 * misread as a reward event. The reward text in the middle is kept verbatim.
 */
public final class GuildRewardParser {
    // Group 1: giver; 2: the reward (e.g. "an Aspect"); 3: receiver.
    private static final Pattern REWARD = Pattern.compile(
            "^([^:]+?) rewarded ([^:]+?) to ([^:]+?)$", Pattern.CASE_INSENSITIVE);

    private GuildRewardParser() {}

    /** Cheap keyword gate before the regex/cleanup runs. */
    public static boolean isCandidate(Component message) {
        return message != null && message.getString().contains("rewarded");
    }

    /** Parse a system-chat component, returning a guild reward if it is one. */
    public static Optional<GuildReward> parse(Component message) {
        if (!isCandidate(message)) {
            return Optional.empty();
        }
        Matcher matcher = REWARD.matcher(ChatText.normalize(message.getString()));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String giver = resolvePlayer(message, matcher.group(1));
        String reward = matcher.group(2).trim();
        String receiver = resolvePlayer(message, matcher.group(3));
        return Optional.of(new GuildReward(giver, reward, receiver));
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
