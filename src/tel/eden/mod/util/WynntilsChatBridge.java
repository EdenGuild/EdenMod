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
	public static boolean sendToTab(Component component) {
		return sendToTab(component, "GUILD");
	}

	/**
	 * Send a component to Wynntils chat tabs if available, routing to a specific tab
	 * type if possible. Tab type should be a RecipientType name: "GUILD", "PARTY", etc.
	 * Returns true if Wynntils accepted the message, false if the caller should fall back.
	 */
	public static boolean sendToTab(Component component, String tabTypeName) {
		return trySendViaWynntils(component, tabTypeName);
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
			LOGGER.debug("[EdenMod] Wynntils not available");
			return false;
		} catch (Exception e) {
			classesLoaded = true; // Mark as loaded so we don't keep trying
			LOGGER.warn("[EdenMod] Failed to load Wynntils classes for chat bridge", e);
			return false;
		}
	}

	/**
	 * Add the component directly to the Guild tab's ChatComponent, bypassing
	 * Wynntils' recipient-type matching so the message keeps its original color.
	 */
	private static boolean sendToChatTab(Component component) throws Exception {
		Object chatTabServiceInstance = chatTabField.get(null);
		if (chatTabServiceInstance == null) {
			LOGGER.debug("ChatTabService instance is null");
			return false;
		}

		// Check if chat tabs are enabled
		java.lang.reflect.Method getFocusedTab = chatTabServiceClass.getMethod("getFocusedTab");
		Object focusedTab = getFocusedTab.invoke(chatTabServiceInstance);

		if (focusedTab == null) {
			return false;
		}

		// Get all chat tabs and find the Guild tab
		java.lang.reflect.Method getChatTabs = chatTabServiceClass.getMethod("getChatTabs");
		java.util.List<?> tabs = (java.util.List<?>) getChatTabs.invoke(chatTabServiceInstance);

		for (Object tab : tabs) {
			java.lang.reflect.Method getName = tab.getClass().getMethod("name");
			String name = (String) getName.invoke(tab);
			if ("Guild".equalsIgnoreCase(name) || name.toLowerCase(java.util.Locale.ROOT).contains("guild")) {
				java.lang.reflect.Method getChatComponent = chatTabServiceClass.getMethod("getChatComponent", tab.getClass());
				Object optional = getChatComponent.invoke(chatTabServiceInstance, tab);
				java.lang.reflect.Method isPresent = optional.getClass().getMethod("isPresent");
				if ((boolean) isPresent.invoke(optional)) {
					java.lang.reflect.Method get = optional.getClass().getMethod("get");
					Object chatComponent = get.invoke(optional);
					// Minecraft's public ChatComponent.addMessage(Component)
					java.lang.reflect.Method addMsg = chatComponent.getClass().getMethod("addMessage", Component.class);
					addMsg.invoke(chatComponent, component);
					LOGGER.debug("Message added directly to '{}' tab", name);
					return true;
				}
			}
		}

		LOGGER.debug("No Guild tab found among {} tabs", tabs.size());
		return false;
	}
}
