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

	public record LiveHealth(int current, int max, float percent) {
	}

	private static boolean initialized = false;
	private static boolean available = false;
	private static Object partyModelInstance = null;
	private static Method isInPartyMethod = null;
	private static Method getPartyMembersMethod = null;

	private static Object characterStatsModelInstance = null;
	private static Method characterStatsGetHealthMethod = null;
	private static Object hadesServiceInstance = null;
	private static Method hadesGetHadesUserMethod = null;
	private static Method hadesUserGetHealthMethod = null;
	private static Method cappedValueCurrentMethod = null;
	private static Method cappedValueMaxMethod = null;
	private static Method cappedValueGetProgressMethod = null;

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

	public static List<String> getPartyMembers() {
		if (!isAvailable() || partyModelInstance == null || getPartyMembersMethod == null) {
			return Collections.emptyList();
		}
		try {
			Object res = getPartyMembersMethod.invoke(partyModelInstance);
			if (res instanceof List<?> list) {
				if (list.isEmpty()) {
					return Collections.emptyList();
				}
				List<String> names = new java.util.ArrayList<>(list.size());
				java.util.Set<String> seen = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
				for (Object obj : list) {
					if (obj != null) {
						String s = obj.toString().trim();
						if (!s.isEmpty() && seen.add(s)) {
							names.add(s);
						}
					}
				}
				return names;
			}
		} catch (Exception ignored) {
		}
		return Collections.emptyList();
	}

	public static LiveHealth getLocalPlayerHealth() {
		if (!isAvailable() || characterStatsModelInstance == null || characterStatsGetHealthMethod == null) {
			return null;
		}
		try {
			Object opt = characterStatsGetHealthMethod.invoke(characterStatsModelInstance);
			if (opt instanceof java.util.Optional<?> optional && optional.isPresent()) {
				return extractFromCappedValue(optional.get());
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	public static LiveHealth getHadesHealth(java.util.UUID uuid) {
		if (!isAvailable() || hadesServiceInstance == null || hadesGetHadesUserMethod == null || uuid == null) {
			return null;
		}
		try {
			Object opt = hadesGetHadesUserMethod.invoke(hadesServiceInstance, uuid);
			if (opt instanceof java.util.Optional<?> optional && optional.isPresent()) {
				Object hadesUser = optional.get();
				if (hadesUserGetHealthMethod == null) {
					hadesUserGetHealthMethod = hadesUser.getClass().getMethod("getHealth");
				}
				Object cv = hadesUserGetHealthMethod.invoke(hadesUser);
				return extractFromCappedValue(cv);
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	private static LiveHealth extractFromCappedValue(Object cappedValue) {
		if (cappedValue == null) {
			return null;
		}
		try {
			if (cappedValueCurrentMethod == null) {
				Class<?> cvClass = cappedValue.getClass();
				cappedValueCurrentMethod = cvClass.getMethod("current");
				cappedValueMaxMethod = cvClass.getMethod("max");
				cappedValueGetProgressMethod = cvClass.getMethod("getProgress");
			}
			Object curObj = cappedValueCurrentMethod.invoke(cappedValue);
			Object maxObj = cappedValueMaxMethod.invoke(cappedValue);
			Object progObj = cappedValueGetProgressMethod.invoke(cappedValue);
			if (!(curObj instanceof Number curNum) || !(maxObj instanceof Number maxNum)) {
				return null;
			}
			int current = curNum.intValue();
			int max = maxNum.intValue();
			double progress = (progObj instanceof Number progNum) ? progNum.doubleValue() : 0.0;
			float percent = max > 0 ? (float) Math.max(0.0, Math.min(1.0, (progress > 0.001) ? progress : ((double) current / max))) : 1.0f;
			return new LiveHealth(current, max, percent);
		} catch (Throwable ignored) {
			return null;
		}
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

			try {
				Field charStatsField = modelsClass.getField("CharacterStats");
				characterStatsModelInstance = charStatsField.get(null);
				if (characterStatsModelInstance != null) {
					characterStatsGetHealthMethod = characterStatsModelInstance.getClass().getMethod("getHealth");
				}
			} catch (Throwable ignored) {
			}

			try {
				Class<?> servicesClass = Class.forName("com.wynntils.core.components.Services");
				Field hadesField = servicesClass.getField("Hades");
				hadesServiceInstance = hadesField.get(null);
				if (hadesServiceInstance != null) {
					hadesGetHadesUserMethod = hadesServiceInstance.getClass().getMethod("getHadesUser", java.util.UUID.class);
				}
			} catch (Throwable ignored) {
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
		characterStatsModelInstance = null;
		characterStatsGetHealthMethod = null;
		hadesServiceInstance = null;
		hadesGetHadesUserMethod = null;
		hadesUserGetHealthMethod = null;
		cappedValueCurrentMethod = null;
		cappedValueMaxMethod = null;
		cappedValueGetProgressMethod = null;
	}
}
