package tel.eden.mod.party;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import tel.eden.mod.EdenLogger;

public final class WynntilsPartyBridge {
	private static final EdenLogger LOGGER = EdenLogger.get();
	private static final String WYNNTILS_MODELS = "com.wynntils.core.components.Models";

	private static boolean initialized = false;
	private static boolean available = false;
	private static Object partyModelInstance = null;
	private static Method isInPartyMethod = null;
	private static Method getPartyMembersMethod = null;

	private WynntilsPartyBridge() {
	}

	public static boolean isAvailable() {
		ensureLoaded();
		return available;
	}

	public static boolean isInParty() {
		if (!isAvailable() || partyModelInstance == null || isInPartyMethod == null) {
			return false;
		}
		try {
			Object res = isInPartyMethod.invoke(partyModelInstance);
			return res instanceof Boolean b && b;
		} catch (Exception e) {
			return false;
		}
	}

	@SuppressWarnings("unchecked")
	public static List<String> getPartyMembers() {
		if (!isAvailable() || partyModelInstance == null || getPartyMembersMethod == null) {
			return Collections.emptyList();
		}
		try {
			Object res = getPartyMembersMethod.invoke(partyModelInstance);
			if (res instanceof List<?> list) {
				return (List<String>) list;
			}
		} catch (Exception ignored) {
		}
		return Collections.emptyList();
	}

	private static synchronized void ensureLoaded() {
		if (initialized) {
			return;
		}
		initialized = true;
		try {
			if (!FabricLoader.getInstance().isModLoaded("wynntils")) {
				available = false;
				return;
			}
			Class<?> modelsClass = Class.forName(WYNNTILS_MODELS);
			Field partyField = modelsClass.getField("Party");
			partyModelInstance = partyField.get(null);
			if (partyModelInstance != null) {
				isInPartyMethod = partyModelInstance.getClass().getMethod("isInParty");
				getPartyMembersMethod = partyModelInstance.getClass().getMethod("getPartyMembers");
				available = true;
				LOGGER.info("Wynntils PartyModel integration loaded successfully");
			}
		} catch (Throwable t) {
			available = false;
			LOGGER.warn("Wynntils PartyModel integration unavailable: {}", t.getMessage());
		}
	}

	/** For testing purposes only: inject mock instance or reset state. */
	static synchronized void setTestInstance(Object instance, Method inParty, Method partyMembers) {
		initialized = true;
		available = (instance != null);
		partyModelInstance = instance;
		isInPartyMethod = inParty;
		getPartyMembersMethod = partyMembers;
	}

	static synchronized void reset() {
		initialized = false;
		available = false;
		partyModelInstance = null;
		isInPartyMethod = null;
		getPartyMembersMethod = null;
	}
}
