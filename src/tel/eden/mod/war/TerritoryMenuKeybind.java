package tel.eden.mod.war;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Optional keybind (unbound by default) that opens the guild territory menu in one
 * press: it runs {@code gu manage}, then when the management chest appears, clicks the
 * Territories item so the player lands directly on the territory screen.
 */
public final class TerritoryMenuKeybind {
	private TerritoryMenuKeybind() {
	}

	private static final int TERRITORIES_SLOT = 14;
	private static final long OPEN_TIMEOUT_MS = 2000L;

	private static KeyMapping key;
	private static boolean opening;
	private static long lastPress;

	public static void register(KeyMapping.Category category) {
		key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.edenmod.open_territory_menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
	}

	public static void onTick(Minecraft mc) {
		if (key == null || mc.player == null) {
			return;
		}
		if (opening && System.currentTimeMillis() - lastPress > OPEN_TIMEOUT_MS) {
			opening = false;
		}
		while (key.consumeClick()) {
			if (mc.getConnection() != null) {
				mc.getConnection().sendCommand("gu manage");
				opening = true;
				lastPress = System.currentTimeMillis();
			}
		}
		if (opening && mc.screen instanceof AbstractContainerScreen<?> screen && screen.getTitle().getString().contains("Manage")) {
			AbstractContainerMenu menu = screen.getMenu();
			if (menu.slots.size() > TERRITORIES_SLOT && mc.gameMode != null) {
				ItemStack item = menu.slots.get(TERRITORIES_SLOT).getItem();
				if (item.getHoverName().getString().contains("Territories")) {
					mc.gameMode.handleInventoryMouseClick(menu.containerId, TERRITORIES_SLOT, 0, ClickType.PICKUP, mc.player);
					opening = false;
				}
			}
		}
	}
}
