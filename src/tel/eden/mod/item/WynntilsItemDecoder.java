package tel.eden.mod.item;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes a Wynntils item-sharing string into a {@link DecodedItem} by calling
 * Wynntils' own decoder at runtime via reflection.
 *
 * <p>Wynntils is an optional dependency; the bot only needs <em>one</em> online
 * member running it to render shared items. Reflection (rather than compiling
 * against Wynntils) keeps this mod self-contained and dodges mapping issues, at the
 * cost of being sensitive to Wynntils API changes — so every step fails gracefully
 * and logs, and the feature simply no-ops when Wynntils is absent or its API moved.
 */
public final class WynntilsItemDecoder {
	private static final Logger LOGGER = LoggerFactory.getLogger("edenmod");

	private WynntilsItemDecoder() {
	}

	/** Whether Wynntils is installed (a precondition for decoding). */
	public static boolean isAvailable() {
		return FabricLoader.getInstance().isModLoaded("wynntils");
	}

	/**
	 * Decode {@code itemString} (optionally with a crafted {@code craftedName}) into a
	 * card model, or empty if Wynntils is unavailable, the item isn't gear, or
	 * decoding failed (all logged at debug).
	 */
	public static Optional<DecodedItem> decode(String itemString, String craftedName) {
		if (!isAvailable()) {
			return Optional.empty();
		}
		try {
			Object buffer = call(Class.forName("com.wynntils.utils.EncodedByteBuffer"), null, "fromUtf16String", new Class<?>[]{String.class}, itemString);
			Object encodingModel = staticField("com.wynntils.core.components.Models", "ItemEncoding");
			Object errorOr = invokeDecode(encodingModel, buffer, craftedName);
			if (errorOr == null || (boolean) call(errorOr, "hasError")) {
				LOGGER.warn("Wynntils could not decode shared item: {}", errorOr == null ? "no matching decode method" : call(errorOr, "getError"));
				return Optional.empty();
			}
			Object wynnItem = call(errorOr, "getValue");
			return buildCard(wynnItem);
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOGGER.warn("Shared-item decode failed via Wynntils reflection: {}", e.toString());
			return Optional.empty();
		}
	}

	/** Build the card model from a decoded Wynntils gear item (duck-typed). */
	private static Optional<DecodedItem> buildCard(Object gearItem) throws ReflectiveOperationException {
		if (gearItem == null || !hasMethod(gearItem, "getIdentifications")) {
			// Not a gear item (tome/charm/crafted) — unsupported for now.
			return Optional.empty();
		}
		String name = String.valueOf(call(gearItem, "getName"));

		Object tier = callOrNull(gearItem, "getGearTier");
		String tierName = tier == null ? "" : prettyEnum(String.valueOf(call(tier, "getName")));
		int tierColor = resolveRarityColor(tierName, tierColor(tier));

		Object type = callOrNull(gearItem, "getGearType");
		String typeName = type == null ? "" : prettyEnum(enumName(type));

		boolean hasOverall = Boolean.TRUE.equals(callOrNull(gearItem, "hasOverallValue"));
		float overall = hasOverall ? ((Number) call(gearItem, "getOverallPercentage")).floatValue() : -1f;

		int powderSlots = numberOr(callOrNull(gearItem, "getPowderSlots"), 0);

		List<DecodedItem.Identification> ids = buildIdentifications(gearItem);
		return Optional.of(new DecodedItem(name, tierName, tierColor, typeName, overall, ids, powderSlots));
	}

	private static List<DecodedItem.Identification> buildIdentifications(Object gearItem) throws ReflectiveOperationException {
		List<DecodedItem.Identification> out = new ArrayList<>();
		Object identifications = call(gearItem, "getIdentifications");
		Object possibleValues = callOrNull(gearItem, "getPossibleValues");
		Map<Object, Object> possibleByStat = indexByStatType(possibleValues);

		if (!(identifications instanceof List<?> list)) {
			return out;
		}

		class IdWithOrder {
			final DecodedItem.Identification id;
			final int ordinal;
			IdWithOrder(DecodedItem.Identification id, int ordinal) {
				this.id = id;
				this.ordinal = ordinal;
			}
		}
		List<IdWithOrder> orderedList = new ArrayList<>();

		for (Object actual : list) {
			Object statType = call(actual, "statType");
			int value = numberOr(call(actual, "value"), 0);
			String statName = String.valueOf(call(statType, "getDisplayName"));
			String unit = statUnit(statType);
			String valueText = (value >= 0 ? "+" : "") + value + unit;

			Object possible = possibleByStat.get(statType);
			float roll = possible == null ? -1f : rollPercent(actual, possible);
			DecodedItem.Identification id = new DecodedItem.Identification(statName, valueText, roll, value >= 0);

			int ordinal = 9999;
			if (statType instanceof Enum<?> e) {
				ordinal = e.ordinal();
			} else {
				try {
					Object ord = call(statType, "ordinal");
					if (ord instanceof Number n) {
						ordinal = n.intValue();
					}
				} catch (Exception ignored) {
				}
			}
			orderedList.add(new IdWithOrder(id, ordinal));
		}

		orderedList.sort(java.util.Comparator.comparingInt(x -> x.ordinal));

		for (IdWithOrder item : orderedList) {
			out.add(item.id);
		}
		return out;
	}

	// -- value/roll computation -------------------------------------------------

	/** The roll quality 0-100 for an identification, via Wynntils' calculator if present. */
	private static float rollPercent(Object actual, Object possible) {
		// Prefer Wynntils' own StatCalculator.getPercentage so inverted stats match.
		try {
			Class<?> calc = Class.forName("com.wynntils.models.stats.StatCalculator");
			for (Method m : calc.getMethods()) {
				if (m.getName().equals("getPercentage") && m.getParameterCount() == 2) {
					Object result = m.invoke(null, actual, possible);
					if (result instanceof Number n) {
						return n.floatValue();
					}
				}
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// Fall back to a range-based estimate below.
		}
		try {
			Object range = call(possible, "range");
			int low = numberOr(call(range, "low"), 0);
			int high = numberOr(call(range, "high"), 0);
			int value = numberOr(call(actual, "value"), 0);
			if (high == low) {
				return -1f;
			}
			float pct = (value - low) / (float) (high - low) * 100f;
			return Math.max(0f, Math.min(100f, pct));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return -1f;
		}
	}

	private static String statUnit(Object statType) {
		Object unit = callOrNull(statType, "getUnit");
		if (unit == null) {
			return "";
		}
		Object display = callOrNull(unit, "getDisplayName");
		return display == null ? "" : String.valueOf(display);
	}

	private static int tierColor(Object tier) {
		if (tier == null) {
			return 0xFFFFFF;
		}
		Object formatting = callOrNull(tier, "getChatFormatting");
		Object color = formatting == null ? null : callOrNull(formatting, "getColor");
		return color instanceof Number n ? n.intValue() : 0xFFFFFF;
	}

	private static Map<Object, Object> indexByStatType(Object possibleValues) throws ReflectiveOperationException {
		Map<Object, Object> map = new HashMap<>();
		if (possibleValues instanceof List<?> list) {
			for (Object possible : list) {
				map.put(call(possible, "statType"), possible);
			}
		}
		return map;
	}

	// -- reflection helpers -----------------------------------------------------

	/** Find a (EncodedByteBuffer, String)->ErrorOr decode method by shape, name-agnostic. */
	private static Object invokeDecode(Object model, Object buffer, String craftedName) throws ReflectiveOperationException {
		if (model == null) {
			return null;
		}
		for (Method m : model.getClass().getMethods()) {
			Class<?>[] params = m.getParameterTypes();
			if (params.length == 2 && params[0].isInstance(buffer) && params[1] == String.class) {
				return m.invoke(model, buffer, craftedName);
			}
		}
		return null;
	}

	private static Object staticField(String className, String fieldName) throws ReflectiveOperationException {
		Field field = Class.forName(className).getField(fieldName);
		return field.get(null);
	}

	private static boolean hasMethod(Object target, String name) {
		for (Method m : target.getClass().getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == 0) {
				return true;
			}
		}
		return false;
	}

	private static Object call(Object target, String method) throws ReflectiveOperationException {
		return target.getClass().getMethod(method).invoke(target);
	}

	private static Object callOrNull(Object target, String method) {
		try {
			return call(target, method);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static Object call(Class<?> cls, Object target, String method, Class<?>[] types, Object... args) throws ReflectiveOperationException {
		return cls.getMethod(method, types).invoke(target, args);
	}

	private static String enumName(Object enumValue) {
		return enumValue instanceof Enum<?> e ? e.name() : String.valueOf(enumValue);
	}

	private static String prettyEnum(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		String lower = raw.replace('_', ' ').toLowerCase(Locale.ROOT);
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	private static int numberOr(Object value, int fallback) {
		return value instanceof Number n ? n.intValue() : fallback;
	}

	private static int resolveRarityColor(String tierName, int defaultColor) {
		if (tierName == null) {
			return defaultColor;
		}
		return switch (tierName.toLowerCase(Locale.ROOT).trim()) {
			case "mythic" -> 0xC80DB1;
			case "fabled" -> 0xFF5C5C;
			case "legendary" -> 0x0DFCFC;
			case "rare" -> 0xFF5CFF;
			case "unique" -> 0xFCFC48;
			case "crafted" -> 0x00AAAA;
			default -> defaultColor;
		};
	}
}
