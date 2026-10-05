package tel.eden.mod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.wynntils.core.components.Models;
import com.wynntils.models.stats.type.StatListOrdering;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WynntilsItemDecoderTest {
	@AfterEach
	void clearStatModel() {
		Models.Stat = null;
	}

	@Test
	void identificationsFollowWynntilsDefaultOrder() throws ReflectiveOperationException {
		// Representative order across all DEFAULT groups, including percent/raw pairs
		// whose identical display names must not determine their order.
		List<Stat> stats = List.of(stat("Strength", ""), stat("Dexterity", ""), stat("Health Regen", "%"), stat("Health Regen", ""), stat("Mana Regen", "/5s"), stat("Walk Speed", "%"), stat("Earth Defence", "%"), stat("Air Defence", "%"), stat("Damage", "%"), stat("Damage", ""), stat("Spell Damage", "%"), stat("Spell Damage", ""), new Stat("1st Spell Cost", new Unit("%"), true), new Stat("1st Spell Cost", new Unit(""), true));
		List<Object> ordering = new ArrayList<>(stats);
		ordering.add(2, new Object()); // Wynntils' list also contains group delimiters.
		Models.Stat = new StatModel(ordering);

		List<Actual> actuals = new ArrayList<>();
		List<DecodedItem.Identification> expected = new ArrayList<>();
		for (int index = 0; index < stats.size(); index++) {
			Stat stat = stats.get(index);
			int value = index + 1;
			actuals.add(new Actual(stat, value));
			expected.add(new DecodedItem.Identification(stat.getDisplayName(), (stat.calculateAsInverted() ? "-" : "+") + value + stat.getUnit().getDisplayName(), -1f, true));
		}
		Collections.reverse(actuals);
		List<Actual> payload = List.copyOf(actuals);

		assertEquals(expected, decodeIdentifications(new Item(actuals)));
		assertEquals(payload, actuals);
	}

	@Test
	void unknownStatsRemainAfterKnownStatsInPayloadOrder() throws ReflectiveOperationException {
		Stat known = stat("Mana Regen", "/5s");
		Stat firstUnknown = stat("Future stat", "%");
		Stat secondUnknown = stat("Another future stat", "");
		Models.Stat = new StatModel(List.of(known));
		Item item = new Item(List.of(new Actual(firstUnknown, 2), new Actual(known, 3), new Actual(secondUnknown, 4)));

		assertEquals(List.of("Mana Regen", "Future stat", "Another future stat"), decodeIdentifications(item).stream().map(DecodedItem.Identification::name).toList());
	}

	@Test
	void unavailableOrderingPreservesPayloadOrder() throws ReflectiveOperationException {
		Item item = new Item(List.of(new Actual(stat("Walk Speed", "%"), 6), new Actual(stat("Strength", ""), 2)));
		for (Object unavailableModel : new Object[]{null, new Object(), new StatModel(null)}) {
			Models.Stat = unavailableModel;
			assertEquals(List.of("Walk Speed", "Strength"), decodeIdentifications(item).stream().map(DecodedItem.Identification::name).toList());
		}
	}

	@Test
	void spellCostsDisplayTheGameSignAndRetainBenefitColor() throws ReflectiveOperationException {
		for (String unit : List.of("", "%")) {
			assertIdentification("2nd Spell Cost", unit, true, 6, "-6" + unit, true);
			assertIdentification("2nd Spell Cost", unit, true, -6, "+6" + unit, false);
		}
	}

	@Test
	void ordinaryStatsRetainTheirSignAndColor() throws ReflectiveOperationException {
		assertIdentification("Walk Speed", "%", false, 6, "+6%", true);
		assertIdentification("Walk Speed", "%", false, -6, "-6%", false);
	}

	private static void assertIdentification(String name, String unit, boolean inverted, int value, String expectedText, boolean expectedPositive) throws ReflectiveOperationException {
		Item item = new Item(List.of(new Actual(new Stat(name, new Unit(unit), inverted), value)));
		List<DecodedItem.Identification> decoded = decodeIdentifications(item);
		assertEquals(1, decoded.size());
		DecodedItem.Identification identification = decoded.getFirst();
		assertEquals(name, identification.name());
		assertEquals(expectedText, identification.valueText());
		assertEquals(expectedPositive, identification.positive());
	}

	@SuppressWarnings("unchecked")
	private static List<DecodedItem.Identification> decodeIdentifications(Item item) throws ReflectiveOperationException {
		Method method = WynntilsItemDecoder.class.getDeclaredMethod("buildIdentifications", Object.class);
		method.setAccessible(true);
		return (List<DecodedItem.Identification>) method.invoke(null, item);
	}

	private static Stat stat(String name, String unit) {
		return new Stat(name, new Unit(unit), false);
	}

	public record StatModel(List<?> ordering) {
		public List<?> getOrderingList(StatListOrdering requested) {
			assertEquals(StatListOrdering.DEFAULT, requested);
			return ordering;
		}
	}

	// Public fixtures expose the methods used by the optional Wynntils reflection API.
	public record Item(List<Actual> getIdentifications) {
	}

	public record Actual(Stat statType, int value) {
	}

	public record Stat(String getDisplayName, Unit getUnit, boolean calculateAsInverted) {
	}

	public record Unit(String getDisplayName) {
	}
}
