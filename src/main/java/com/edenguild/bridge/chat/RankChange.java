package com.edenguild.bridge.chat;

/**
 * One parsed guild rank change.
 *
 * @param target  the affected member's real account username
 * @param oldRank the previous guild rank (e.g. {@code "Recruit"})
 * @param newRank the new guild rank (e.g. {@code "Strategist"})
 * @param setter  the member who changed the rank (display name)
 */
public record RankChange(String target, String oldRank, String newRank, String setter) {}
