package tel.eden.mod.hud;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;

/** An ordered group of {@link HudElement}s drawn together as one overlay panel. */
public final class HudPanel {
	private final List<HudElement> elements = new ArrayList<>();

	public HudPanel add(HudElement element) {
		elements.add(element);
		return this;
	}

	public boolean isEmpty() {
		return elements.isEmpty();
	}

	public void draw(GuiGraphics graphics) {
		for (HudElement element : elements) {
			element.draw(graphics);
		}
	}
}
