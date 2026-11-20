package tel.eden.mod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ItemCardRendererTest {
	private static final Path OUT = Path.of("build", "item-cards");

	/**
	 * Cards use Wynncraft's tooltip art when the pack is available. Point
	 * EDENMOD_WYNN_PACK at an extracted pack's assets/minecraft folder to preview with it;
	 * otherwise the drawn fallback is rendered.
	 */
	@BeforeEach
	void useLocalPackIfSet() {
		String pack = System.getenv("EDENMOD_WYNN_PACK");
		TooltipArt.useOpenerForTesting(path -> {
			Path file = pack == null ? null : Path.of(pack, path);
			return file != null && Files.isRegularFile(file) ? Files.newInputStream(file) : null;
		});
	}

	@Test
	void pixelFontLoadsFromTheGameJar() {
		assertTrue(ItemCardRenderer.PixelFont.available());
	}

	@Test
	void rendersArmourCard() throws Exception {
		DecodedItem.GearDetails gear = new DecodedItem.GearDetails(3375, List.of(new DecodedItem.ElementValue("Earth", 150), new DecodedItem.ElementValue("Air", 150)), 0, "", 0, List.of(), List.of("Earth", "Air"), List.of(50, 0, 0, 0, 50), 100, "", "The sheer bond between Adamastor and Urdar has manifested itself into a physical form due to their duress. Such power is not to be abused...", 3, 4);
		DecodedItem item = new DecodedItem("Dondasch", "Legendary", 0x55FFFF, "Chestplate", 87.4f, List.of(new DecodedItem.Identification("Strength", "+20", -1, true), new DecodedItem.Identification("Main Attack Damage", "+512", 81.2f, true), new DecodedItem.Identification("Walk Speed", "+31%", 90.6f, true), new DecodedItem.Identification("Earth Damage", "+29%", 84.0f, true), new DecodedItem.Identification("Air Damage", "+30%", 88.0f, true), new DecodedItem.Identification("Fire Damage", "-40%", 50.0f, false)), List.of(), List.of(new DecodedItem.Weighting("Nori", "Main Attack", 83.1f)), null, List.of(), 3, 2, gear);
		assertCard(item, "armour.png");
	}

	@Test
	void rendersWeaponCard() throws Exception {
		DecodedItem.GearDetails gear = new DecodedItem.GearDetails(0, List.of(), 742, "Very Fast", 3.1, List.of(new DecodedItem.Damage("Neutral", 40, 60), new DecodedItem.Damage("Thunder", 120, 250)), List.of("Thunder"), List.of(0, 110, 0, 0, 0), 101, "Assassin/Ninja", "", 0, 5);
		DecodedItem item = new DecodedItem("Inferno", "Mythic", 0xC80DB1, "Dagger", 12.5f, List.of(new DecodedItem.Identification("Spell Damage", "+18%", 12.0f, true), new DecodedItem.Identification("2nd Spell Cost", "-12%", 3.0f, true), new DecodedItem.Identification("Health Regen", "-60%", 64.0f, false)), List.of(new DecodedItem.MajorIdentification("Lightweight", "Your Walk Speed bonuses are doubled while your health is above 50%.")), List.of(), new DecodedItem.ShinyTracker("Mobs Killed", "+1234"), List.of(), 2, 0, gear);
		assertCard(item, "weapon.png");
	}

	@Test
	void rendersCardWithoutGearDetails() throws Exception {
		DecodedItem item = new DecodedItem("Crafted Helmet of Very Long Naming Purposes", "Crafted", 0x00AAAA, "Helmet", -1f, List.of(new DecodedItem.Identification("Health Regen", "+12", 50f, true)), List.of(), List.of(), null, List.of(), 0, -1, null);
		assertCard(item, "no-gear.png");
	}

	private static void assertCard(DecodedItem item, String file) throws Exception {
		byte[] png = ItemCardRenderer.render(item);
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
		assertEquals(480, image.getWidth());
		assertTrue(image.getHeight() > 100);
		Files.createDirectories(OUT);
		Files.write(OUT.resolve(file), png);
	}
}
