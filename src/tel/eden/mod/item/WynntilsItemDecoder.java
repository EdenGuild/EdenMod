package tel.eden.mod.item;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import tel.eden.mod.EdenLogger;

/**
 * Decodes a Wynntils chat-share payload into the smaller {@link DecodedItem} model
 * used by EdenMod's renderer.
 *
 * <p>Wynntils is optional, so every interaction is reflective and best-effort. If
 * Wynntils is absent or its API changes, shared-item rendering simply no-ops rather
 * than taking down chat handling.
 */
public final class WynntilsItemDecoder {
	private static final EdenLogger LOGGER = EdenLogger.get();
	private static final String WYNNTILS_BUFFER = "com.wynntils.utils.EncodedByteBuffer";
	private static final String WYNNTILS_MODELS = "com.wynntils.core.components.Models";
	private static final String WYNNTILS_ITEM_WEIGHT_SERVICES = "com.wynntils.core.components.Services";
	private static final String WYNNTILS_WEIGHT_SOURCE = "com.wynntils.models.gear.type.ItemWeightSource";
	private static final String WYNNTILS_STAT_ORDERING = "com.wynntils.models.stats.type.StatListOrdering";
	private static final String WYNNTILS_STAT_CALCULATOR = "com.wynntils.models.stats.StatCalculator";

	private WynntilsItemDecoder() {
	}

	/** Whether Wynntils is installed. */
	public static boolean isAvailable() {
		return FabricLoader.getInstance().isModLoaded("wynntils");
	}

	/**
	 * Decode {@code itemString} (optionally with a crafted {@code craftedName}) into a
	 * card model, or empty if Wynntils is unavailable, the item isn't supported, or
	 * decoding failed.
	 */
	public static Optional<DecodedItem> decode(String itemString, String craftedName) {
		if (!isAvailable()) {
			return Optional.empty();
		}
		try {
			Class<?> bufferClass = Class.forName(WYNNTILS_BUFFER);
			Object buffer = call(bufferClass, null, "fromUtf16String", new Class<?>[]{String.class}, itemString);
			Object encodingModel = staticField(WYNNTILS_MODELS, "ItemEncoding");
			Object errorOr = invokeDecode(encodingModel, buffer, craftedName);
			if (errorOr == null || (boolean) call(errorOr, "hasError")) {
				LOGGER.warn("Wynntils could not decode shared item: {}", errorOr == null ? "no matching decode method" : call(errorOr, "getError"));
				return Optional.empty();
			}
			return buildCard(call(errorOr, "getValue"));
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOGGER.warn("Shared-item decode failed via Wynntils reflection: {}", e.toString());
			return Optional.empty();
		}
	}

	/** Build the renderer model from a decoded Wynntils item (duck-typed). */
	private static Optional<DecodedItem> buildCard(Object wynnItem) throws ReflectiveOperationException {
		if (wynnItem == null || !hasMethod(wynnItem, "getIdentifications")) {
			return Optional.empty();
		}

		String name = normalizeRenderableText(String.valueOf(call(wynnItem, "getName")));
		Object tier = callOrNull(wynnItem, "getGearTier");
		Object type = callOrNull(wynnItem, "getGearType");

		String tierName = tier == null ? "" : prettyEnum(String.valueOf(call(tier, "getName")));
		String typeName = type == null ? "" : prettyEnum(enumName(type));

		return Optional.of(new DecodedItem(name, tierName, resolveRarityColor(tierName, tierColor(tier)), typeName, resolveOverallPercent(wynnItem), buildIdentifications(wynnItem, WynntilsItemDecoder::warnStatOrdering), buildMajorIds(wynnItem), buildWeightings(wynnItem), buildShinyTracker(wynnItem), buildPowders(wynnItem), numberOr(callOrNull(wynnItem, "getPowderSlots"), 0), resolveRerollCount(wynnItem)));
	}

	private static List<DecodedItem.Identification> buildIdentifications(Object wynnItem, Consumer<String> warning) throws ReflectiveOperationException {
		List<DecodedItem.Identification> identifications = new ArrayList<>();
		Object actualValues = call(wynnItem, "getIdentifications");
		Object possibleValues = callOrNull(wynnItem, "getPossibleValues");
		Map<Object, Object> possibleByStat = indexByStatType(possibleValues);

		if (!(actualValues instanceof List<?> actualList)) {
			return identifications;
		}

		record OrderedIdentification(DecodedItem.Identification id, int position) {
		}

		Map<Object, Integer> defaultOrder = defaultStatOrder(warning);
		List<String> unknownStats = new ArrayList<>();
		List<OrderedIdentification> ordered = new ArrayList<>();
		for (Object actual : actualList) {
			Object statType = call(actual, "statType");
			int value = numberOr(call(actual, "value"), 0);
			String statName = normalizeRenderableText(String.valueOf(call(statType, "getDisplayName")));
			if (!defaultOrder.isEmpty() && !defaultOrder.containsKey(statType)) {
				unknownStats.add(statName + statUnit(statType));
			}
			// Wynntils stores inverted stats in benefit order; flip the displayed sign,
			// while retaining the stored value's sign for the renderer's color.
			boolean calculateAsInverted = Boolean.TRUE.equals(call(statType, "calculateAsInverted"));
			long displayed = calculateAsInverted ? -(long) value : value;
			String valueText = formatStatValue(displayed, statUnit(statType));
			float roll = rollPercent(actual, possibleByStat.get(statType));
			ordered.add(new OrderedIdentification(new DecodedItem.Identification(statName, valueText, roll, value >= 0), defaultOrder.getOrDefault(statType, Integer.MAX_VALUE)));
		}

		if (!unknownStats.isEmpty()) {
			warning.accept("Wynntils default ID ordering did not recognise: " + String.join(", ", unknownStats) + ". These IDs appear last in payload order.");
		}
		ordered.sort(Comparator.comparingInt(OrderedIdentification::position));
		for (OrderedIdentification orderedIdentification : ordered) {
			identifications.add(orderedIdentification.id());
		}
		return identifications;
	}

	private static List<DecodedItem.MajorIdentification> buildMajorIds(Object wynnItem) {
		List<DecodedItem.MajorIdentification> majorIds = new ArrayList<>();
		try {
			Object info = callOrNull(wynnItem, "getItemInfo");
			Object fixedStats = info == null ? null : callOrNull(info, "fixedStats");
			Object major = unwrapOptional(fixedStats == null ? null : callOrNull(fixedStats, "majorIds"));
			if (major == null) {
				return majorIds;
			}
			String name = normalizeRenderableText(stringOrBlank(callOrNull(major, "name")));
			String description = readableStyledText(callOrNull(major, "lore"));
			if (!name.isBlank() || !description.isBlank()) {
				majorIds.add(new DecodedItem.MajorIdentification(name, description));
			}
		} catch (RuntimeException ignored) {
		}
		return majorIds;
	}

	private static List<DecodedItem.Weighting> buildWeightings(Object wynnItem) {
		List<DecodedItem.Weighting> weightings = new ArrayList<>();
		try {
			// Weightings are exposed via a service rather than the decoded item itself, so
			// look them up by item name after the item has been reconstructed.
			Object services = staticField(WYNNTILS_ITEM_WEIGHT_SERVICES, "ItemWeight");
			Class<?> sourceClass = Class.forName(WYNNTILS_WEIGHT_SOURCE);
			Object[] sources = sourceClass.getEnumConstants();
			if (services == null || sources == null) {
				return weightings;
			}

			Method getItemWeighting = services.getClass().getMethod("getItemWeighting", String.class, sourceClass);
			Method calculateWeighting = findMethod(services.getClass(), "calculateWeighting", 2);
			if (calculateWeighting == null) {
				return weightings;
			}

			String itemName = String.valueOf(call(wynnItem, "getName"));
			for (Object source : sources) {
				String sourceName = enumName(source);
				if (!sourceName.equals("NORI") && !sourceName.equals("WYNNPOOL")) {
					continue;
				}
				Object weightingValues = getItemWeighting.invoke(services, itemName, source);
				if (!(weightingValues instanceof List<?> weightingList) || weightingList.isEmpty()) {
					continue;
				}
				for (Object weighting : weightingList) {
					Object percentage = calculateWeighting.invoke(services, weighting, wynnItem);
					if (percentage instanceof Number n) {
						Object scaleName = callOrNull(weighting, "weightName");
						weightings.add(new DecodedItem.Weighting(prettyEnum(sourceName), normalizeRenderableText(String.valueOf(scaleName)), n.floatValue()));
					}
				}
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
		}
		weightings.sort(Comparator.comparing(DecodedItem.Weighting::source).thenComparing(DecodedItem.Weighting::scaleName));
		return weightings;
	}

	private static DecodedItem.ShinyTracker buildShinyTracker(Object wynnItem) {
		try {
			// Non-crafted gear items expose one shiny-specific tracker such as
			// "Major World Events Won". If the item type does not support it, this
			// simply returns null and the renderer omits the row.
			Object shinyStat = unwrapOptional(callOrNull(wynnItem, "getShinyStat"));
			if (shinyStat == null) {
				return null;
			}

			Object statType = callOrNull(shinyStat, "statType");
			String name = normalizeRenderableText(stringOrBlank(callOrNull(statType, "displayName")));
			long value = longOr(callOrNull(shinyStat, "value"), 0L);
			String valueText = formatStatValue(value, statUnitDisplay(statType));
			return name.isBlank() ? null : new DecodedItem.ShinyTracker(name, valueText);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private static List<DecodedItem.PowderSlot> buildPowders(Object wynnItem) {
		List<DecodedItem.PowderSlot> powders = new ArrayList<>();
		try {
			// Keep this extraction path in place even though chat-shared items currently
			// decode with no powder contents in the cases we tested. The renderer now omits
			// powder visuals entirely so the generated image does not overstate certainty,
			// but we still retain the decoded metadata here for future support and debugging
			// if Wynntils starts exposing real socket contents in chat shares later.
			Object powderValues = callOrNull(wynnItem, "getPowders");
			if (!(powderValues instanceof List<?> powderList) || powderList.isEmpty()) {
				// Some Wynntils item types expose powders only on the nested instance record.
				Object instance = unwrapOptional(callOrNull(wynnItem, "getItemInstance"));
				if (instance != null) {
					powderValues = callOrNull(instance, "powders");
				}
			}
			if (!(powderValues instanceof List<?> powderList)) {
				return powders;
			}

			for (Object powder : powderList) {
				String element = powderElementName(powder);
				if (!element.isBlank()) {
					powders.add(new DecodedItem.PowderSlot(element));
				}
			}
		} catch (RuntimeException ignored) {
		}
		return powders;
	}

	private static int resolveRerollCount(Object wynnItem) {
		// Some items simply are not rerollable (or Wynntils does not expose a count for
		// that item type), so use -1 as the renderer's "not applicable" sentinel.
		Object rerolls = callOrNull(wynnItem, "getRerollCount");
		return rerolls instanceof Number n ? n.intValue() : -1;
	}

	private static float resolveOverallPercent(Object wynnItem) {
		try {
			if (Boolean.TRUE.equals(callOrNull(wynnItem, "hasOverallValue"))) {
				Object overall = callOrNull(wynnItem, "getOverallPercentage");
				if (overall instanceof Number n) {
					return n.floatValue();
				}
			}
		} catch (RuntimeException ignored) {
		}

		try {
			// Crafted gear uses vanilla roll meters rather than the normal overall-quality
			// field, so mirror Wynntils' own fallback when the direct value is absent.
			Object identifications = callOrNull(wynnItem, "getIdentifications");
			if (!(identifications instanceof List<?> list) || list.isEmpty()) {
				return -1f;
			}
			Class<?> calculator = Class.forName(WYNNTILS_STAT_CALCULATOR);
			Object overall = unwrapOptional(calculator.getMethod("calculateOverallQualityFromVanillaMeters", List.class).invoke(null, list));
			return overall instanceof Number n ? n.floatValue() : -1f;
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return -1f;
		}
	}

	/** Roll quality 0-100 for one identification. */
	private static float rollPercent(Object actual, Object possible) {
		if (possible != null) {
			try {
				Class<?> calculator = Class.forName(WYNNTILS_STAT_CALCULATOR);
				Method getPercentage = findMethod(calculator, "getPercentage", 2);
				if (getPercentage != null) {
					Object result = getPercentage.invoke(null, actual, possible);
					if (result instanceof Number n) {
						return n.floatValue();
					}
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
			}
		}

		float vanillaMeterPercent = vanillaMeterPercent(actual);
		if (vanillaMeterPercent >= 0f) {
			return vanillaMeterPercent;
		}

		if (possible != null) {
			try {
				// Last-resort approximation if Wynntils' calculator path is unavailable.
				Object range = call(possible, "range");
				int low = numberOr(call(range, "low"), 0);
				int high = numberOr(call(range, "high"), 0);
				int value = numberOr(call(actual, "value"), 0);
				if (high == low) {
					return -1f;
				}
				float percent = (value - low) / (float) (high - low) * 100f;
				return Math.max(0f, Math.min(100f, percent));
			} catch (ReflectiveOperationException | RuntimeException ignored) {
			}
		}

		return -1f;
	}

	private static float vanillaMeterPercent(Object actual) {
		try {
			Object vanillaMeter = unwrapOptional(callOrNull(actual, "vanillaMeter"));
			if (vanillaMeter instanceof Character c) {
				Class<?> calculator = Class.forName(WYNNTILS_STAT_CALCULATOR);
				Object result = calculator.getMethod("getPercentageFromVanillaMeter", char.class).invoke(null, c.charValue());
				return result instanceof Number n ? n.floatValue() : -1f;
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
		}
		return -1f;
	}

	private static String powderElementName(Object powder) {
		String element = enumName(powder);
		if (element.isBlank() || element.equals(String.valueOf(powder))) {
			Object elementObj = callOrNull(powder, "getElement");
			if (elementObj != null) {
				element = enumName(elementObj);
			}
		}
		if (element.isBlank()) {
			Object powderName = callOrNull(powder, "getName");
			if (powderName != null) {
				element = String.valueOf(powderName);
			}
		}
		return prettyEnum(element);
	}

	private static Map<Object, Integer> defaultStatOrder(Consumer<String> warning) {
		try {
			// Use the same list as Wynntils' tooltip, explicitly selecting DEFAULT rather
			// than the player's configured ordering. Delimiters only occupy unused ranks.
			Object model = staticField(WYNNTILS_MODELS, "Stat");
			if (model == null) {
				throw new IllegalStateException("Wynntils Models.Stat is null");
			}
			Class<?> orderingClass = Class.forName(WYNNTILS_STAT_ORDERING);
			Object ordering = orderingClass.getField("DEFAULT").get(null);
			Object result = model.getClass().getMethod("getOrderingList", orderingClass).invoke(model, ordering);
			if (result instanceof List<?> stats && !stats.isEmpty()) {
				Map<Object, Integer> positions = new HashMap<>();
				for (int index = 0; index < stats.size(); index++) {
					positions.putIfAbsent(stats.get(index), index);
				}
				return positions;
			}
			throw new IllegalStateException("Wynntils getOrderingList(DEFAULT) returned " + (result == null ? "null" : result instanceof List<?> ? "an empty list" : result.getClass().getName()));
		} catch (ReflectiveOperationException | RuntimeException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			warning.accept("Could not apply Wynntils default ID ordering. Using payload order. Reason: " + cause);
		}
		// Preserve payload order if the optional API is unavailable. Unrecognised
		// stats likewise remain in payload order after the known stats.
		return Map.of();
	}

	private static void warnStatOrdering(String message) {
		LOGGER.warn("{}", message);
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			if (client.player != null) {
				client.player.displayClientMessage(Component.literal("[EdenMod] " + message).withStyle(ChatFormatting.YELLOW), false);
			}
		});
	}

	private static String statUnit(Object statType) {
		Object unit = callOrNull(statType, "getUnit");
		if (unit == null) {
			unit = callOrNull(statType, "statUnit");
		}
		if (unit == null) {
			return "";
		}
		Object display = callOrNull(unit, "getDisplayName");
		return display == null ? "" : String.valueOf(display);
	}

	private static String statUnitDisplay(Object statType) {
		if (statType == null) {
			return "";
		}
		Object unit = callOrNull(statType, "statUnit");
		if (unit == null) {
			unit = callOrNull(statType, "getUnit");
		}
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

	/** Prefer Wynntils' chat-share decode path, then fall back to the generic one. */
	private static Object invokeDecode(Object model, Object buffer, String craftedName) throws ReflectiveOperationException {
		if (model == null) {
			return null;
		}

		Method trusted = findSpecificMethod(model.getClass(), "decodeItemWithTrustedName", buffer.getClass(), String.class);
		if (trusted != null) {
			return trusted.invoke(model, buffer, craftedName);
		}

		Method plain = findSpecificMethod(model.getClass(), "decodeItem", buffer.getClass(), String.class);
		if (plain != null) {
			return plain.invoke(model, buffer, craftedName);
		}

		for (Method method : model.getClass().getMethods()) {
			Class<?>[] params = method.getParameterTypes();
			if (params.length == 2 && params[0].isInstance(buffer) && params[1] == String.class) {
				return method.invoke(model, buffer, craftedName);
			}
		}
		return null;
	}

	private static Method findMethod(Class<?> type, String name, int parameterCount) {
		for (Method method : type.getMethods()) {
			if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
				return method;
			}
		}
		return null;
	}

	private static Method findSpecificMethod(Class<?> type, String name, Class<?>... parameterTypes) {
		try {
			return type.getMethod(name, parameterTypes);
		} catch (NoSuchMethodException ignored) {
			return null;
		}
	}

	private static Object staticField(String className, String fieldName) throws ReflectiveOperationException {
		Field field = Class.forName(className).getField(fieldName);
		return field.get(null);
	}

	private static boolean hasMethod(Object target, String name) {
		for (Method method : target.getClass().getMethods()) {
			if (method.getName().equals(name) && method.getParameterCount() == 0) {
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

	private static Object unwrapOptional(Object value) {
		return value instanceof Optional<?> optional ? optional.orElse(null) : value;
	}

	private static String readableStyledText(Object styledText) {
		if (styledText == null) {
			return "";
		}
		Object text = callOrNull(styledText, "getString");
		if (text == null) {
			text = callOrNull(styledText, "toString");
		}
		return normalizeRenderableText(stripMinecraftFormatting(stringOrBlank(text)));
	}

	private static String stripMinecraftFormatting(String text) {
		return text == null ? "" : text.replaceAll("[\\u00A7§].", "");
	}

	private static String normalizeRenderableText(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}

		// Wynncraft/Wynntils strings can include Minecraft-specific glyphs that rely on the
		// in-game font atlas. Java2D does not have those glyphs, so strip them here before
		// they can turn into replacement boxes or check marks in the generated PNG.
		StringBuilder cleaned = new StringBuilder(text.length());
		boolean previousWasSpace = false;
		for (int index = 0; index < text.length();) {
			int codePoint = text.codePointAt(index);
			index += Character.charCount(codePoint);
			if (Character.isWhitespace(codePoint)) {
				if (!previousWasSpace) {
					cleaned.append(' ');
					previousWasSpace = true;
				}
				continue;
			}
			if (isUnsupportedRenderableCodePoint(codePoint)) {
				continue;
			}
			cleaned.appendCodePoint(codePoint);
			previousWasSpace = false;
		}
		return cleaned.toString().trim();
	}

	private static boolean isUnsupportedRenderableCodePoint(int codePoint) {
		return switch (Character.getType(codePoint)) {
			case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED -> true;
			default -> codePoint == 0xFFFD;
		};
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
		String lower = normalizeRenderableText(raw).replace('_', ' ').toLowerCase(Locale.ROOT);
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	private static int numberOr(Object value, int fallback) {
		return value instanceof Number n ? n.intValue() : fallback;
	}

	private static long longOr(Object value, long fallback) {
		return value instanceof Number n ? n.longValue() : fallback;
	}

	private static String formatStatValue(long value, String unit) {
		return normalizeRenderableText((value >= 0 ? "+" : "") + value + (unit == null ? "" : unit));
	}

	private static String stringOrBlank(Object value) {
		return value == null ? "" : String.valueOf(value);
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
