package tel.eden.mod.render;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A brightness boost that works under shader packs, toggled with a key (unbound by default).
 *
 * <p>The usual trick, raising the lightmap's ambient light, is lost under a shader pack, which does
 * its own lighting. This instead brightens the finished frame with a gamma curve, after the world has
 * been drawn (shader pack included) and before the HUD, so it works the same with or without shaders.
 * The pass is the post effect {@code edenmod:gammabright}; see {@code GameRendererMixin}.
 */
public final class Gammabright {
	private Gammabright() {
	}

	private static final Identifier EFFECT = Identifier.parse("edenmod:gammabright");

	private static KeyMapping key;
	private static boolean enabled;

	public static void register(KeyMapping.Category category) {
		key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.edenmod.toggle_gammabright", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));
	}

	public static void onTick(Minecraft mc) {
		if (key == null) {
			return;
		}
		while (key.consumeClick()) {
			enabled = !enabled;
			if (mc.player != null) {
				mc.player.displayClientMessage(Component.literal("Gammabright " + (enabled ? "enabled" : "disabled")).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED), false);
			}
		}
	}

	/** Brightens the main render target in place; called once per frame by {@code GameRendererMixin}. */
	public static void apply(Minecraft mc, GraphicsResourceAllocator allocator) {
		if (!enabled || mc.level == null) {
			return;
		}
		PostChain chain = mc.getShaderManager().getPostChain(EFFECT, LevelTargetBundle.MAIN_TARGETS);
		if (chain != null) {
			chain.process(mc.getMainRenderTarget(), allocator);
		}
	}

	/** For the gametest, which presses no key. */
	public static void setEnabled(boolean value) {
		enabled = value;
	}
}
