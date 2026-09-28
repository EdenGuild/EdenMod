package tel.eden.mod.party;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;

public final class PartyHighlightManager {
	public static final List<Integer> DEFAULT_PALETTE = List.of(0xFF2222, // Slot 0 (Party Leader): Crimson Red
				0x1E56FF, // Slot 1: Deep Royal Blue
				0xFFA6D5, // Slot 2: Light Pink
				0x55FF55, // Slot 3: Lime Green
				0xFF7700, // Slot 4: Vivid Electric Orange
				0x9933FF, // Slot 5: Royal Violet / Purple
				0xFF5588, // Slot 6: Coral Rose
				0x5522FF, // Slot 7: Electric Indigo
				0xCC4400, // Slot 8: Burnt Rust / Amber
				0x6C7CE4 // Slot 9: Periwinkle / Slate Blue
	);

	private PartyHighlightManager() {
	}

	public static Integer getPartyOutlineColor(Player player) {
		if (player == null) {
			return null;
		}

		EdenModClient client = EdenModClient.instance();
		BridgeConfig config = client != null ? client.config() : null;
		if (config != null && !config.partyHighlightEnabled) {
			return null;
		}

		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.player != null && player == mc.player) {
			return null;
		}

		if (!WynntilsPartyBridge.isAvailable() || !WynntilsPartyBridge.isInParty()) {
			return null;
		}

		String name = null;
		if (player.getGameProfile() != null) {
			name = player.getGameProfile().name();
		}
		if (name == null && player.getName() != null) {
			name = player.getName().getString();
		}
		if (name == null || name.isBlank()) {
			return null;
		}

		List<String> members = WynntilsPartyBridge.getPartyMembers();
		return getColorForPlayerName(name, members, config);
	}

	public static Integer getColorForPlayerName(String playerName, List<String> members, BridgeConfig config) {
		if (playerName == null || members == null || members.isEmpty()) {
			return null;
		}

		for (int i = 0; i < members.size(); i++) {
			String member = members.get(i);
			if (member != null && member.equalsIgnoreCase(playerName)) {
				List<Integer> palette = (config != null && config.partyColors != null && config.partyColors.size() == 10) ? config.partyColors : DEFAULT_PALETTE;
				return palette.get(i % palette.size());
			}
		}
		return null;
	}
}
