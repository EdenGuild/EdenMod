package tel.eden.mod.hud;

import net.minecraft.client.gui.GuiGraphics;

/**
 * One drawable piece of a war-suite HUD overlay (a background rectangle or a line
 * of text). Grouped into a {@link HudPanel} and drawn each frame via the mod's
 * single {@code HudRenderCallback} dispatch.
 */
@FunctionalInterface
public interface HudElement {
	void draw(GuiGraphics graphics);
}
