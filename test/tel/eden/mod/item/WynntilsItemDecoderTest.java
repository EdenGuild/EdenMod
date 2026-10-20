package tel.eden.mod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class WynntilsItemDecoderTest {
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
		Method method = WynntilsItemDecoder.class.getDeclaredMethod("buildIdentifications", Object.class);
		method.setAccessible(true);
		Item item = new Item(List.of(new Actual(new Stat(name, new Unit(unit), inverted), value)));
		List<?> decoded = (List<?>) method.invoke(null, item);
		assertEquals(1, decoded.size());
		DecodedItem.Identification identification = (DecodedItem.Identification) decoded.getFirst();
		assertEquals(name, identification.name());
		assertEquals(expectedText, identification.valueText());
		assertEquals(expectedPositive, identification.positive());
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
