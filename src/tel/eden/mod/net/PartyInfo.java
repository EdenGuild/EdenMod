package tel.eden.mod.net;

import java.util.List;

/**
 * A raid party snapshot received from the bridge backend (in a {@code partyUpdate}
 * event or a {@code partyListReply}). {@code members} is host-first.
 */
public record PartyInfo(int id, String raid, String host, List<String> members, int max, String note, List<String> tags, boolean ally, String world) {
	public PartyInfo {
		members = List.copyOf(members);
		tags = tags != null ? List.copyOf(tags) : List.of();
		world = world != null ? world : "";
	}

	public PartyInfo(int id, String raid, String host, List<String> members, int max, String note) {
		this(id, raid, host, members, max, note, List.of(), false, "");
	}

	/** Current occupancy (e.g. {@code 2} of {@code max}). */
	public int size() {
		return members.size();
	}

	public String tagOf(int index) {
		if (index >= 0 && index < tags.size()) {
			String t = tags.get(index);
			if (t != null)
				return t;
		}
		return "";
	}

	public String displayMember(int index) {
		if (index < 0 || index >= members.size())
			return "";
		String m = members.get(index);
		String tag = tagOf(index);
		if (!tag.isEmpty()) {
			return "[" + tag + "] " + m;
		}
		return m;
	}

	public boolean isCustomSize() {
		return "Annihilation".equalsIgnoreCase(raid) || "Festival Minigames".equalsIgnoreCase(raid) || "Other".equalsIgnoreCase(raid);
	}
}
