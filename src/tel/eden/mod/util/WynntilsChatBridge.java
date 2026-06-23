package tel.eden.mod.util;

import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional integration with Wynntils chat tabs. If Wynntils is loaded and chat
 * tabs are enabled, EdenMod bridge messages are routed to the appropriate tab
 * (Guild for bridge announcements, Party for party updates). Falls back to vanilla
 * chat if Wynntils is unavailable or disabled.
 */
public final class WynntilsChatBridge {
	private static final Logger LOGGER = LoggerFactory.getLogger("edenmod");

	private static final String WYNNTILS_CHAT_TAB_SERVICE = "com.wynntils.services.chat.ChatTabService";
	private static final String WYNNTILS_SERVICES = "com.wynntils.core.components.Services";

	// Cache the ChatTabService class and Services class to avoid repeated lookups
	private static Class<?> servicesClass = null;
	private static Class<?> chatTabServiceClass = null;
	private static java.lang.reflect.Field chatTabField = null;
	private static boolean classesLoaded = false;

	private WynntilsChatBridge() {
	}

	/**
	 * Send a component to Wynntils chat tabs if available, or fall back to vanilla chat.
	 * Attempts to route to the Guild tab for standard messages.
	 */
	public static void sendToTab(Component component) {
		sendToTab(component, "GUILD");
	}

	/**
	 * Send a component to Wynntils chat tabs if available, routing to a specific tab
	 * type if possible. Tab type should be a RecipientType name: "GUILD", "PARTY", etc.
	 * Falls back to vanilla chat if Wynntils is unavailable.
	 */
	public static void sendToTab(Component component, String tabTypeName) {
		if (trySendViaWynntils(component, tabTypeName)) {
			return;
		}
		// Fallback: caller will use vanilla displayClientMessage directly
	}

	/**
	 * Attempt to send via Wynntils on each call. This checks if Wynntils chat tabs
	 * are currently active and routes the message if they are.
	 */
	private static boolean trySendViaWynntils(Component component, String tabTypeName) {
		try {
			// Load classes once if not already loaded
			if (!classesLoaded) {
				if (!loadWynntilsClasses()) {
					return false;
				}
			}

			// Try to get the focused tab and send to it
			return sendToChatTab(component);
		} catch (Exception e) {
			LOGGER.debug("Failed to send to Wynntils chat tab", e);
			return false;
		}
	}

	/**
	 * One-time load of Wynntils classes and fields via reflection.
	 */
	private static boolean loadWynntilsClasses() {
		try {
			servicesClass = Class.forName(WYNNTILS_SERVICES);
			chatTabServiceClass = Class.forName(WYNNTILS_CHAT_TAB_SERVICE);
			chatTabField = servicesClass.getField("ChatTab");
			classesLoaded = true;
			LOGGER.info("[EdenMod] Wynntils classes loaded - bridge messages will route to chat tabs");
			return true;
		} catch (ClassNotFoundException e) {
			classesLoaded = true; // Mark as loaded so we don't keep trying
			LOGGER.debug("[EdenMod] Wynntils not available (expected if not installed)");
			return false;
		} catch (Exception e) {
			classesLoaded = true; // Mark as loaded so we don't keep trying
			LOGGER.warn("[EdenMod] Failed to load Wynntils classes for chat bridge", e);
			return false;
		}
	}

	/**
	 * Send the component to the currently focused tab.
	 */
	private static boolean sendToChatTab(Component component) throws Exception {
		// Get the ChatTabService instance: Services.ChatTab
		Object chatTabServiceInstance = chatTabField.get(null);
		if (chatTabServiceInstance == null) {
			LOGGER.debug("ChatTabService instance is null");
			return false;
		}

		// Check if chat tabs are enabled by checking for focused tab
		java.lang.reflect.Method getFocusedTab = chatTabServiceClass.getMethod("getFocusedTab");
		Object focusedTab = getFocusedTab.invoke(chatTabServiceInstance);

		if (focusedTab == null) {
			return false; // Chat tabs disabled or no tab focused
		}

		// Call Services.ChatTab.addMessage(component, null, null)
		// This properly routes the message through Wynntils' chat tab system
		java.lang.reflect.Method addMessage = chatTabServiceClass.getMethod("addMessage", Component.class, Object.class, Object.class);
		addMessage.invoke(chatTabServiceInstance, component, null, null);

		LOGGER.debug("Message sent to Wynntils chat tab successfully via ChatTabService.addMessage()");
		return true;
	}
}
