package tel.eden.mod.render;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards against a class of render-thread regression that is invisible in unit-level
 * behaviour and shows up in game only as occasional frame spikes.
 *
 * <p>Minecraft builds a fresh render state object for every entity every frame. A map keyed
 * by those states (weak or not) therefore takes hundreds of new keys per frame. A
 * {@code WeakHashMap} additionally queues every dead key after each GC and drains them all
 * in one {@code put}, which produced ~100 ms stalls on Wynncraft's display-heavy scenes.
 * Store per-state data on the state through a mixin field instead (see
 * {@link EdenDisplayState}).
 */
class RenderHotPathTest {
	// Any map-like field whose key type is a per-frame render state.
	private static final Pattern STATE_KEYED_MAP = Pattern.compile("\\b\\w*(?:Map|Cache)<\\s*\\w*RenderState\\b");

	@Test
	void noMapIsKeyedByPerFrameRenderState() throws IOException {
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Path.of(System.getProperty("edenmod.srcDir")))) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				List<String> lines = Files.readAllLines(file);
				for (int i = 0; i < lines.size(); i++) {
					String line = lines.get(i);
					if (!line.stripLeading().startsWith("//") && STATE_KEYED_MAP.matcher(line).find()) {
						offenders.add(file.getFileName() + ":" + (i + 1) + "  " + line.strip());
					}
				}
			}
		}
		assertTrue(offenders.isEmpty(), "Render states are recreated every frame; do not key maps by them. " + "Add a field to the state with a mixin instead (see EdenDisplayState):\n" + String.join("\n", offenders));
	}

	@Test
	void sourceOfIsNullForStatesWithoutTheMixinField() {
		assertNull(EdenDisplayState.sourceOf(new Object()));
		assertNull(EdenDisplayState.sourceOf(null));
	}
}
