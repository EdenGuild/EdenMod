package tel.eden.mod.guild;

/** Available guild rewards as shown in the member-management storage summary. */
public record GuildRewardStorageSnapshot(int aspects, int aspectsMax, int tomes, int tomesMax, long emeralds, long emeraldsMax) {
}
