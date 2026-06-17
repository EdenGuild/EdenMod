package tel.eden.mod.net;

import java.util.List;

/**
 * A raid party snapshot received from the bridge backend (in a {@code partyUpdate}
 * event or a {@code partyListReply}). {@code members} is host-first.
 */
public record PartyInfo(int id, String raid, String host, List<String> members, int max, String note) {
    /** Current occupancy (e.g. {@code 2} of {@code max}). */
    public int size() {
        return members.size();
    }
}
