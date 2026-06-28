package tel.eden.mod.util;

import net.minecraft.network.chat.Component;

/**
 * Optional integration with Wynntils chat tabs. Currently unused — messages are
 * routed through the normal Minecraft chat pipeline, where Wynntils classifies
 * them by the chat-component prefix pattern matching the GUILD recipient type.
 */
public final class WynntilsChatBridge {
	private WynntilsChatBridge() {
	}

	public static boolean sendToTab(Component component) {
		return false;
	}

	public static boolean sendToTab(Component component, String tabTypeName) {
		return false;
	}
}
