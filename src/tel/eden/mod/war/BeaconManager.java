package tel.eden.mod.war;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.Vec3;
import tel.eden.mod.config.BridgeConfig;

/**
 * Draws a tall in-world marker beam at the soonest upcoming territory attack, using
 * the debug-gizmo primitives the engine flushes each frame. Called during
 * {@code WorldRenderEvents.AFTER_ENTITIES}, so the gizmos land inside the per-frame
 * collector and are re-added every frame (no persistence, no stacking).
 */
public final class BeaconManager {
	private BeaconManager() {
	}

	private static final int BEAM_BOTTOM = -64;
	private static final int BEAM_TOP = 320;
	private static final int GREEN = 0xFF33CC33;
	private static final float BEAM_WIDTH = 4.0f;

	public static void render(BridgeConfig config) {
		if (!config.warGreenBeacon) {
			return;
		}
		String soonest = AttackTimerMenu.soonestTerritory();
		if (soonest == null) {
			return;
		}
		int[] middle = TerritoryData.middle(soonest);
		Minecraft mc = Minecraft.getInstance();
		if (middle == null || mc.player == null) {
			return;
		}
		double x = middle[0] + 0.5;
		double z = middle[1] + 0.5;
		Gizmos.line(new Vec3(x, BEAM_BOTTOM, z), new Vec3(x, BEAM_TOP, z), GREEN, BEAM_WIDTH).setAlwaysOnTop();
		Vec3 label = new Vec3(x, mc.player.getY() + 2.0, z);
		Gizmos.billboardText(soonest, label, TextGizmo.Style.forColorAndCentered(GREEN)).setAlwaysOnTop();
	}
}
