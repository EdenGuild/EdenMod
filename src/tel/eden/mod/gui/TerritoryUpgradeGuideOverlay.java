package tel.eden.mod.gui;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;

/**
 * Renders visual guide overlays: pulsating circular target rings and clicks-remaining badges.
 */
public final class TerritoryUpgradeGuideOverlay {
	private TerritoryUpgradeGuideOverlay() {
	}

	private static final Identifier TARGET_CIRCLE = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/target_circle.png");

	public static void renderTargetPulse(GuiGraphics graphics, int slotX, int slotY) {
		renderTargetPulse(graphics, slotX, slotY, 16, 16);
	}

	public static void renderTargetPulse(GuiGraphics graphics, int x, int y, int width, int height) {
		int cx = x + width / 2;
		int cy = y + height / 2;

		// Gentle breathing pulsation
		float sin = (float) Math.sin(System.currentTimeMillis() / 180.0);
		int baseSize = Math.max(width, height) + 14;
		int size = Math.round(baseSize + 3 * sin);

		int drawX = cx - size / 2;
		int drawY = cy - size / 2;

		try {
			graphics.blit(RenderPipelines.GUI_TEXTURED, TARGET_CIRCLE, drawX, drawY, 0.0f, 0.0f, size, size, 100, 100, 100, 100);
		} catch (Throwable t) {
			int alpha = (int) (160 + 95 * sin);
			int color = (alpha << 24) | 0x00FF44;
			graphics.renderOutline(x - 1, y - 1, width + 2, height + 2, color);
		}
	}

	public static void renderClickBadge(GuiGraphics graphics, Font font, int slotX, int slotY, int diff, boolean matches) {
		if (matches || diff == 0) {
			// Checkmark badge in slot corner
			graphics.drawString(font, "§a✓", slotX + 10, slotY - 2, 0xFF55FF55);
		} else {
			String badge = (diff > 0) ? ("+" + diff) : String.valueOf(diff);
			int color = (diff > 0) ? 0xFFFFAA00 : 0xFFFF5555;
			String prefix = (diff > 0) ? "§6" : "§c";
			int bw = font.width(badge) + 4;
			int bx = slotX + 16 - bw;
			int by = slotY;

			graphics.fill(bx, by, bx + bw, by + 9, 0xEE111111);
			graphics.renderOutline(bx, by, bw, 9, color);
			graphics.drawString(font, prefix + badge, bx + 2, by + 1, color);
		}
	}

	public static int[] renderUpgradeGuideBanner(GuiGraphics graphics, Font font, int guiLeft, int guiTop, int xSize, String territory, tel.eden.mod.war.eco.TerritoryPreset preset, tel.eden.mod.war.eco.TerritoryPreset.TerritoryLoadout target, java.util.List<tel.eden.mod.war.eco.TerritoryContainerScanner.UpgradeSlotInfo> upgrades) {
		if (territory == null || preset == null) {
			return null;
		}

		boolean isBonusView = false;
		if (upgrades != null) {
			for (tel.eden.mod.war.eco.TerritoryContainerScanner.UpgradeSlotInfo up : upgrades) {
				if (!up.upgradeName().equals("Damage") && !up.upgradeName().equals("Attack") && !up.upgradeName().equals("Health") && !up.upgradeName().equals("Defence")) {
					isBonusView = true;
					break;
				}
			}
		}

		int totalClicksNeeded = 0;
		StringBuilder sb = new StringBuilder();
		if (upgrades != null && target != null) {
			for (tel.eden.mod.war.eco.TerritoryContainerScanner.UpgradeSlotInfo up : upgrades) {
				int targetLvl = switch (up.upgradeName()) {
					case "Damage" -> target.damage();
					case "Attack" -> target.attack();
					case "Health" -> target.health();
					case "Defence" -> target.defence();
					default -> target.bonus(up.upgradeName());
				};
				int diff = targetLvl - up.currentLevel();
				if (diff != 0) {
					totalClicksNeeded += Math.abs(diff);
					if (sb.length() > 0) {
						sb.append(", ");
					}
					String clickType = (diff > 0) ? "L-Click" : "R-Click";
					String sign = (diff > 0) ? ("+" + diff) : String.valueOf(diff);
					sb.append(up.upgradeName()).append(" (").append(sign).append(" ").append(clickType).append(")");
				}
			}
		}

		String typeLabel = isBonusView ? "Bonuses" : "Upgrades";
		String header = "§b[Eden Guide] §f" + territory + " §8(" + typeLabel + " • Preset: §6" + preset.name() + "§8)";

		Set<String> seenUpgrades = new HashSet<>();
		if (upgrades != null) {
			for (tel.eden.mod.war.eco.TerritoryContainerScanner.UpgradeSlotInfo up : upgrades) {
				seenUpgrades.add(up.upgradeName());
			}
		}
		boolean allRequiredSeen;
		if (isBonusView) {
			boolean allPresetBonusesSeen = true;
			if (target != null && target.bonuses() != null) {
				for (java.util.Map.Entry<String, Integer> b : target.bonuses().entrySet()) {
					if (b.getValue() > 0 && !seenUpgrades.contains(b.getKey())) {
						allPresetBonusesSeen = false;
						break;
					}
				}
			}
			allRequiredSeen = allPresetBonusesSeen && !seenUpgrades.isEmpty();
		} else {
			allRequiredSeen = seenUpgrades.contains("Damage") && seenUpgrades.contains("Attack") && seenUpgrades.contains("Health") && seenUpgrades.contains("Defence");
		}

		String status;
		if (upgrades == null || upgrades.isEmpty() || !allRequiredSeen) {
			status = "§eScanning " + typeLabel + "...";
		} else if (totalClicksNeeded > 0) {
			status = "§eAdjust highlighted: " + sb.toString() + " §7(§6" + totalClicksNeeded + " clicks left§7)";
		} else {
			status = "§a✔ " + typeLabel + " match preset! No further clicks needed.";
		}

		int skipW = 34;
		int skipH = 18;
		int maxContentW = Math.max(font.width(header), font.width(status));
		int bannerWidth = Math.max(Math.max(xSize, 240), maxContentW + skipW + 24);
		int bannerHeight = 28;
		int bannerX = Math.max(4, guiLeft + (xSize - bannerWidth) / 2);
		int bannerY = guiTop - bannerHeight - 4;

		graphics.fill(bannerX, bannerY, bannerX + bannerWidth, bannerY + bannerHeight, 0xF012161E);
		graphics.renderOutline(bannerX, bannerY, bannerWidth, bannerHeight, 0xFF3E4A5B);

		graphics.drawString(font, header, bannerX + 8, bannerY + 4, 0xFFFFFFFF);
		graphics.drawString(font, status, bannerX + 8, bannerY + 16, 0xFFFFFFFF);

		int skipX = bannerX + bannerWidth - skipW - 4;
		int skipY = bannerY + (bannerHeight - skipH) / 2;
		graphics.fill(skipX, skipY, skipX + skipW, skipY + skipH, 0xFF18202A);
		graphics.renderOutline(skipX, skipY, skipW, skipH, 0xFFFFAA00);
		int stx = skipX + (skipW - font.width("Skip")) / 2;
		graphics.drawString(font, "§6Skip", stx, skipY + 5, 0xFFFFAA00);

		return new int[]{skipX, skipY, skipW, skipH};
	}
}
