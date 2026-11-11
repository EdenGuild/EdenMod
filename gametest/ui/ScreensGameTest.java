package ui;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import support.Failures;
import support.Output;
import support.Worlds;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.emote.EmoteConfigScreen;
import tel.eden.mod.gui.AllyProgressScreen;
import tel.eden.mod.gui.BridgeConfigScreen;
import tel.eden.mod.gui.EdenMenuScreen;
import tel.eden.mod.gui.GiftProgressScreen;
import tel.eden.mod.gui.GroundItemVisibilityRuleScreen;
import tel.eden.mod.gui.GroundItemVisibilityScreen;
import tel.eden.mod.gui.HudLayoutScreen;
import tel.eden.mod.gui.PartyManageScreen;
import tel.eden.mod.gui.RenamePresetScreen;
import tel.eden.mod.gui.TerritoryLossBlacklistScreen;
import tel.eden.mod.net.PartyInfo;

/**
 * Opens every screen EdenMod defines in a real client, at several window sizes and GUI scales, and
 * checks that it comes up and lays out sanely. A screenshot of each is saved for a human to look at.
 * Behaviour that needs no game (what Escape does, say) is tested in plain JUnit instead.
 *
 * <p>The screens are discovered, not listed: every non-abstract {@code Screen} under
 * {@code tel.eden.mod}. One with a {@code (Screen, EdenModClient)} or no-argument constructor is
 * opened automatically, so a new screen is covered the day it is written. Anything else must be in
 * {@link #MANUAL} (how to build it) or {@link #EXCLUDED} (why it cannot be tested), or the test
 * fails and says so; entries for screens that no longer exist fail too.
 *
 * <p>The checks are structural on purpose. Pixel comparisons would break whenever Mesa or the
 * fonts change; widget rectangles do not.
 */
public final class ScreensGameTest implements FabricClientGameTest {
	/**
	 * How to open a screen. {@code checkOverlap} is off for screens with a scrolling list: rows scrolled
	 * out of view are still widgets and sit under the buttons below the list.
	 */
	private record Spec(String name, boolean checkOverlap, BiFunction<Screen, EdenModClient, Screen> factory) {
	}

	private record Manual(boolean checkOverlap, BiFunction<Screen, EdenModClient, Screen> factory) {
		static Manual of(BiFunction<Screen, EdenModClient, Screen> factory) {
			return new Manual(true, factory);
		}
	}

	/** Screens that cannot be built from a parent and the mod alone, and how to build them. */
	private static final Map<String, Manual> MANUAL = Map.ofEntries(Map.entry("BridgeConfigScreen", new Manual(false, BridgeConfigScreen::create)), Map.entry("GroundItemVisibilityScreen", Manual.of((parent, mod) -> new GroundItemVisibilityScreen(parent, mod.config()))), Map.entry("GroundItemVisibilityRuleScreen", Manual.of((parent, mod) -> new GroundItemVisibilityRuleScreen(parent, mod.config(), new BridgeConfig.GroundItemVisibilityRule("potion", 1.5f)))), Map.entry("TerritoryLossBlacklistScreen", Manual.of((parent, mod) -> new TerritoryLossBlacklistScreen(parent, mod.config()))), Map.entry("HudLayoutScreen", Manual.of((parent, mod) -> new HudLayoutScreen(parent, mod.config()))), Map.entry("EmoteConfigScreen", Manual.of((parent, mod) -> new EmoteConfigScreen(parent, mod.config()))), Map.entry("RenamePresetScreen", Manual.of((parent, mod) -> new RenamePresetScreen(parent, "Preset"))), Map.entry("PartyManageScreen", Manual.of((parent, mod) -> new PartyManageScreen(parent, mod, new PartyInfo(1, "NOTG", "Host", List.of("Host"), 4, "", List.of(), false, "WC1")))),
				// Modal progress screens: Escape must leave them open, see checkEscape.
				Map.entry("GiftProgressScreen", Manual.of((parent, mod) -> new GiftProgressScreen("Gifting", () -> {
				}))), Map.entry("AllyProgressScreen", Manual.of((parent, mod) -> new AllyProgressScreen("Ally", new AllyProgressScreen.ProgressListener() {
					@Override
					public void onProgress(String status) {
					}

					@Override
					public void onDone() {
					}

					@Override
					public void requestCancel() {
					}
				}))));

	/** Screens that are deliberately not tested here, and why. */
	private static final Map<String, String> EXCLUDED = Map.of("EmoteWheelScreen", "a radial wheel drawn by hand with no widgets, opened by holding a key; there is nothing for the layout checks to look at");

	/**
	 * Only two settings: the smallest window, where everything is tightest, and full HD at GUI scale 2,
	 * the case where screens used to come out far too big. The layout rules themselves are swept across
	 * every window size and GUI scale in EdenPanelLayoutTest, which needs no game; this only confirms that
	 * the real screens, with real fonts and widgets, follow them.
	 */
	private static final List<Setup> SETUPS = List.of(new Setup(854, 480, 0), new Setup(1920, 1080, 2));

	/** A window size and GUI scale (0 = the automatic one Minecraft picks). */
	private record Setup(int width, int height, int guiScale) {
		String label() {
			return width + "x" + height + (guiScale == 0 ? "" : "-gui" + guiScale);
		}
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Output.enabled("ui")) {
			Failures.run("ui", () -> runSuite(context));
		}
	}

	private void runSuite(ClientGameTestContext context) {
		List<String> problems = new ArrayList<>();
		List<Spec> specs = buildSpecs(problems);
		try (TestSingleplayerContext world = Worlds.open(context)) {
			for (Setup setup : SETUPS) {
				context.getInput().resizeWindow(setup.width(), setup.height());
				context.runOnClient(client -> {
					client.options.guiScale().set(setup.guiScale());
					client.resizeDisplay();
				});
				context.waitTicks(5);
				int[] actual = context.computeOnClient(client -> new int[]{client.getWindow().getWidth(), client.getWindow().getHeight()});
				if (actual[0] != setup.width() || actual[1] != setup.height()) {
					// A desktop window manager can refuse a resize; every later check would then be about the wrong size.
					throw new AssertionError("The window is " + actual[0] + "x" + actual[1] + ", not the requested " + setup.label() + ". Run under a virtual display (xvfb-run), which has no window manager to refuse.");
				}
				for (Spec spec : specs) {
					String label = spec.name() + " at " + setup.label();
					context.runOnClient(client -> client.setScreen(spec.factory().apply(null, EdenModClient.instance())));
					context.waitTicks(5);
					problems.addAll(context.computeOnClient(client -> inspect(client, spec, label)));
					Output.keepScreenshot(context.takeScreenshot(spec.name() + "-" + setup.label()));
					context.runOnClient(client -> client.setScreen(null));
				}
			}
			problems.addAll(checkEscape(context, specs));
		}
		if (!problems.isEmpty()) {
			throw new AssertionError("EdenMod screens have problems:\n  " + String.join("\n  ", problems) + "\nScreenshots: " + Output.dir().resolve("screens"));
		}
		Output.marker("ui-screens.ok");
	}

	/**
	 * Three Escape behaviours worth a real key press. That the others go back to their parent is enforced
	 * by the compiler: EdenReferenceScreen.onClose is final and every screen must pass a parent. A screen
	 * that is opened from another one returns to it; the modal progress screens, which a background task
	 * is driving the game menus behind, must stay open.
	 */
	private static final String RETURNS_TO_PARENT = "PartyListScreen";
	private static final Set<String> IGNORES_ESCAPE = Set.of("GiftProgressScreen", "AllyProgressScreen");

	private static List<String> checkEscape(ClientGameTestContext context, List<Spec> specs) {
		List<String> problems = new ArrayList<>();
		Set<String> wanted = new HashSet<>(IGNORES_ESCAPE);
		wanted.add(RETURNS_TO_PARENT);
		for (Spec spec : specs) {
			if (!wanted.remove(spec.name())) {
				continue;
			}
			boolean shouldStay = IGNORES_ESCAPE.contains(spec.name());
			Screen[] opened = new Screen[2];
			context.runOnClient(client -> {
				opened[0] = new EdenMenuScreen();
				client.setScreen(opened[0]);
				opened[1] = spec.factory().apply(opened[0], EdenModClient.instance());
				client.setScreen(opened[1]);
			});
			context.waitTicks(3);
			context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			context.waitTicks(3);
			Screen now = context.computeOnClient(client -> client.screen);
			if (shouldStay && now != opened[1]) {
				problems.add(spec.name() + ": Escape closed a screen that must stay open (now showing " + describe(now) + ")");
			} else if (!shouldStay && now != opened[0]) {
				problems.add(spec.name() + ": Escape did not return to the screen it was opened from (now showing " + describe(now) + ")");
			}
			context.runOnClient(client -> client.setScreen(null));
		}
		for (String missing : wanted) {
			problems.add(missing + " is named in the Escape checks of ScreensGameTest but no such screen is tested; update the names");
		}
		return problems;
	}

	private static String describe(Screen screen) {
		return screen == null ? "the game" : screen.getClass().getSimpleName();
	}

	/** Finds every screen the mod defines and decides how to open each; anything it cannot decide becomes a problem. */
	private static List<Spec> buildSpecs(List<String> problems) {
		List<Class<?>> found = discoverScreens();
		Set<String> foundNames = new HashSet<>();
		List<Spec> specs = new ArrayList<>();
		for (Class<?> type : found) {
			String name = type.getSimpleName();
			foundNames.add(name);
			if (EXCLUDED.containsKey(name)) {
				continue;
			}
			Manual manual = MANUAL.get(name);
			if (manual != null) {
				specs.add(new Spec(name, manual.checkOverlap(), manual.factory()));
				continue;
			}
			Constructor<?> withParent = constructor(type, Screen.class, EdenModClient.class);
			Constructor<?> noArgs = constructor(type);
			if (withParent != null) {
				specs.add(new Spec(name, true, (parent, mod) -> instantiate(withParent, parent, mod)));
			} else if (noArgs != null) {
				specs.add(new Spec(name, true, (parent, mod) -> instantiate(noArgs)));
			} else {
				problems.add(name + " cannot be opened automatically: add it to MANUAL in ScreensGameTest (how to build it) or to EXCLUDED (why it cannot be tested)");
			}
		}
		for (String listed : MANUAL.keySet()) {
			if (!foundNames.contains(listed)) {
				problems.add(listed + " is in MANUAL in ScreensGameTest but no such screen exists any more; remove it");
			}
		}
		for (String listed : EXCLUDED.keySet()) {
			if (!foundNames.contains(listed)) {
				problems.add(listed + " is in EXCLUDED in ScreensGameTest but no such screen exists any more; remove it");
			}
		}
		specs.sort(Comparator.comparing(Spec::name));
		return specs;
	}

	/**
	 * Every non-abstract Screen in the mod's own classes, found by walking the mod's files, so nobody has
	 * to remember to list a new one. The locations come from the class path: in a dev environment Fabric
	 * reports only the mod's resources folder as its root, not the folder its classes are in.
	 */
	private static List<Class<?>> discoverScreens() {
		List<Class<?>> screens = new ArrayList<>();
		try {
			for (URL location : Collections.list(ScreensGameTest.class.getClassLoader().getResources("tel/eden/mod"))) {
				URI uri = location.toURI();
				if (uri.getScheme().equals("jar")) {
					try (FileSystem jar = FileSystems.newFileSystem(uri, Map.of())) {
						scanForScreens(jar.getPath("/"), screens);
					}
				} else {
					// The location is .../tel/eden/mod; the class names are relative to the folder three levels up.
					scanForScreens(Path.of(uri).getParent().getParent().getParent(), screens);
				}
			}
		} catch (IOException | URISyntaxException e) {
			throw new IllegalStateException("Could not scan the mod for screens", e);
		}
		if (screens.isEmpty()) {
			throw new IllegalStateException("Found no screens at all; the scan is broken, not the mod");
		}
		System.out.println("[gametest] discovered " + screens.size() + " screens");
		return screens;
	}

	private static void scanForScreens(Path root, List<Class<?>> screens) throws IOException {
		Path base = root.resolve("tel/eden/mod");
		if (!Files.isDirectory(base)) {
			return;
		}
		try (Stream<Path> files = Files.walk(base)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				String relative = root.relativize(file).toString().replace('\\', '/');
				// Mixin classes cannot be loaded directly, and are not screens.
				if (!relative.endsWith(".class") || relative.contains("$") || relative.contains("/mixin/")) {
					continue;
				}
				String className = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
				try {
					Class<?> type = Class.forName(className, false, ScreensGameTest.class.getClassLoader());
					if (Screen.class.isAssignableFrom(type) && !Modifier.isAbstract(type.getModifiers())) {
						screens.add(type);
					}
				} catch (ClassNotFoundException | LinkageError e) {
					// Classes that need an optional mod that is not installed here (Mod Menu's API, say) cannot
					// load, and are not screens. A class named like a screen that fails to load is a real problem.
					if (className.endsWith("Screen")) {
						throw new IllegalStateException("Could not load " + className + " while looking for screens", e);
					}
				}
			}
		}
	}

	private static Constructor<?> constructor(Class<?> type, Class<?>... parameters) {
		try {
			Constructor<?> found = type.getConstructor(parameters);
			return Modifier.isPublic(type.getModifiers()) ? found : null;
		} catch (NoSuchMethodException e) {
			return null;
		}
	}

	private static Screen instantiate(Constructor<?> constructor, Object... arguments) {
		try {
			return (Screen) constructor.newInstance(arguments);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException("Opening " + constructor.getDeclaringClass().getSimpleName() + " threw", e.getCause());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not open " + constructor.getDeclaringClass().getSimpleName(), e);
		}
	}

	/** The space a screen lays its widgets out in, and the scale it is drawn at relative to the GUI scale. */
	private record Area(int width, int height, float uiScale) {
	}

	/**
	 * Most Eden screens draw in a scaled "reference space" (virtualWidth/virtualHeight) rather than in
	 * the screen's own units, so widget coordinates must be compared against that, not against
	 * {@code screen.width}.
	 */
	private static Area layoutArea(Screen screen) {
		for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
			if (type.getSimpleName().equals("EdenReferenceScreen")) {
				try {
					return new Area(readInt(type, screen, "virtualWidth"), readInt(type, screen, "virtualHeight"), readFloat(type, screen, "uiScale"));
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException("EdenReferenceScreen changed; update ScreensGameTest", e);
				}
			}
		}
		return new Area(screen.width, screen.height, 1.0f);
	}

	private static int readInt(Class<?> type, Object target, String name) throws ReflectiveOperationException {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field.getInt(target);
	}

	private static float readFloat(Class<?> type, Object target, String name) throws ReflectiveOperationException {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field.getFloat(target);
	}

	private static List<String> inspect(Minecraft client, Spec spec, String label) {
		List<String> problems = new ArrayList<>();
		Screen screen = client.screen;
		if (screen == null || !screen.getClass().getSimpleName().equals(spec.name())) {
			problems.add(label + ": expected " + spec.name() + " to be open but found " + describe(screen));
			return problems;
		}
		Area area = layoutArea(screen);
		// Never bigger than the GUI scale the player chose: a screen drawn larger than the rest of the UI looks absurd.
		if (area.uiScale() > 1.0001f) {
			problems.add(String.format("%s: drawn at %.2fx the GUI scale; it must never exceed 1.0", label, area.uiScale()));
		}
		List<AbstractWidget> widgets = new ArrayList<>();
		for (GuiEventListener child : screen.children()) {
			if (child instanceof AbstractWidget widget && widget.visible) {
				widgets.add(widget);
			}
		}
		if (widgets.isEmpty()) {
			problems.add(label + ": the screen has no visible widgets");
		}
		for (AbstractWidget widget : widgets) {
			if (widget.getX() < 0 || widget.getY() < 0 || widget.getX() + widget.getWidth() > area.width() || widget.getY() + widget.getHeight() > area.height()) {
				problems.add(String.format("%s: '%s' sticks out of the %dx%d layout area (x %d..%d, y %d..%d)", label, widget.getMessage().getString(), area.width(), area.height(), widget.getX(), widget.getX() + widget.getWidth(), widget.getY(), widget.getY() + widget.getHeight()));
			}
		}
		List<AbstractButton> buttons = !spec.checkOverlap() ? List.of() : widgets.stream().filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast).toList();
		for (int i = 0; i < buttons.size(); i++) {
			for (int j = i + 1; j < buttons.size(); j++) {
				if (overlaps(buttons.get(i), buttons.get(j))) {
					problems.add(label + ": buttons '" + buttons.get(i).getMessage().getString() + "' and '" + buttons.get(j).getMessage().getString() + "' overlap");
				}
			}
		}
		return problems;
	}

	private static boolean overlaps(AbstractWidget a, AbstractWidget b) {
		return a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth() && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight();
	}
}
