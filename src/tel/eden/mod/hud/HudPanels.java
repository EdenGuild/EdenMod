package tel.eden.mod.hud;

import java.util.List;

/**
 * Registry of the movable/scalable war-suite HUD panels, so the layout editor can list
 * and draw a preview of each without the panels themselves being on screen. The
 * {@code name} keys match those used by {@link HudLayout}; the preview size is a rough
 * footprint used only to draw the editor handle.
 */
public record HudPanels(String name, String label, float defaultX, float defaultY, int previewWidth, int previewHeight) {

	public static final List<HudPanels> ALL = List.of(new HudPanels("attackTimers", "Attack timers", 0.40f, 0.14f, 130, 44), new HudPanels("warInfo", "War info overlay", 0.015f, 0.30f, 150, 74), new HudPanels("weeklyWars", "Weekly war count", 0.83f, 0.04f, 60, 12), new HudPanels("radianceTracker", "Radiance tracker", 0.50f, 0.35f, 110, 24), new HudPanels("tccLabyrinthMap", "TCC Labyrinth minimap", 0.0f, 0.0f, 130, 180), new HudPanels("tccReportTitle", "TCC report title", 0.30f, 0.18f, 220, 20));
}
