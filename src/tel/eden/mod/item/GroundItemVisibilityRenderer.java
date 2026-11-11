package tel.eden.mod.item;

import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.render.EdenItemScaleState;

/**
 * Resolves dropped-item scale rules and applies their render-only state to item entities.
 */
public final class GroundItemVisibilityRenderer {
	private GroundItemVisibilityRenderer() {
	}

	public static void extract(BridgeConfig config, ItemEntity itemEntity, ItemEntityRenderState state) {
		float scale = 1.0f;
		BridgeConfig.GroundItemVisibilityRule rule = matchingRule(config, itemEntity.getItem());
		if (rule != null) {
			scale = Float.isFinite(rule.size) ? rule.size : 1.0f;
			scale = Math.max(BridgeConfig.GROUND_ITEM_MIN_SCALE, Math.min(BridgeConfig.GROUND_ITEM_MAX_SCALE, scale));
		}
		((EdenItemScaleState) state).edenmod$setScale(scale);
	}

	public static float scaleFor(ItemEntityRenderState state) {
		return EdenItemScaleState.scaleOf(state);
	}

	private static BridgeConfig.GroundItemVisibilityRule matchingRule(BridgeConfig config, ItemStack stack) {
		if (!config.groundItemVisibility || config.groundItemVisibilityRules == null || config.groundItemVisibilityRules.isEmpty()) {
			return null;
		}
		String normalizedName = BridgeConfig.normalizeGroundItemName(stack.getHoverName().getString());
		if (normalizedName.isEmpty()) {
			return null;
		}
		for (BridgeConfig.GroundItemVisibilityRule rule : config.groundItemVisibilityRules) {
			if (rule == null) {
				continue;
			}
			String nameFilter = rule.normalizedNameContains();
			if (nameFilter.isEmpty() || normalizedName.contains(nameFilter)) {
				// A rule supplies the complete scale. Returning the first match makes list
				// order the priority and prevents overlapping rules from stacking transforms.
				return rule;
			}
		}
		return null;
	}
}
