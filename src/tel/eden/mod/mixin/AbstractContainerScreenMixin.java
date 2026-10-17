package tel.eden.mod.mixin;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.gui.TerritoryUpgradeGuideOverlay;
import tel.eden.mod.raid.RaidGambitSafety;
import tel.eden.mod.war.eco.TerritoryContainerScanner;
import tel.eden.mod.war.eco.TerritoryGuideController;
import tel.eden.mod.war.eco.TerritoryPreset;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
	@Shadow
	@Nullable
	protected Slot hoveredSlot;

	@Shadow
	public abstract AbstractContainerMenu getMenu();

	@Shadow
	protected int leftPos;

	@Shadow
	protected int topPos;

	@Shadow
	protected int imageWidth;

	@Shadow
	protected int imageHeight;

	@Unique
	private String edenmod$title() {
		return net.minecraft.ChatFormatting.stripFormatting(((net.minecraft.client.gui.screens.Screen) (Object) this).getTitle().getString()).trim();
	}

	@Unique
	private int edenmod$skipBtnX = -1;
	@Unique
	private int edenmod$skipBtnY = -1;
	@Unique
	private int edenmod$skipBtnW = 34;
	@Unique
	private int edenmod$skipBtnH = 16;
	@Unique
	private boolean edenmod$skipBtnVisible = false;

	@Unique
	private void edenmod$renderBanner(GuiGraphics graphics, Font font, int left, int top, int width, String text) {
		int skipW = 34;
		int skipH = 16;
		int textW = font.width(text);
		int bw = Math.max(Math.max(width, 240), textW + skipW + 24);
		int bx = Math.max(4, left + (width - bw) / 2);
		int by = top - 24;
		graphics.fill(bx, by, bx + bw, by + 20, 0xF012161E);
		graphics.renderOutline(bx, by, bw, 20, 0xFF3E4A5B);
		graphics.drawString(font, text, bx + 8, by + 6, 0xFFFFFFFF);

		edenmod$skipBtnX = bx + bw - skipW - 4;
		edenmod$skipBtnY = by + 2;
		edenmod$skipBtnW = skipW;
		edenmod$skipBtnH = skipH;
		edenmod$skipBtnVisible = true;

		graphics.fill(edenmod$skipBtnX, edenmod$skipBtnY, edenmod$skipBtnX + edenmod$skipBtnW, edenmod$skipBtnY + edenmod$skipBtnH, 0xFF18202A);
		graphics.renderOutline(edenmod$skipBtnX, edenmod$skipBtnY, edenmod$skipBtnW, edenmod$skipBtnH, 0xFFFFAA00);
		int sx = edenmod$skipBtnX + (edenmod$skipBtnW - font.width("Skip")) / 2;
		graphics.drawString(font, "§6Skip", sx, edenmod$skipBtnY + 4, 0xFFFFAA00);
	}

	@Unique
	private List<TerritoryContainerScanner.UpgradeSlotInfo> edenmod$cachedUpgrades = null;
	@Unique
	private long edenmod$lastUpgradeScanTime = 0;
	@Unique
	private int edenmod$lastMenuSlotCount = 0;
	@Unique
	private long edenmod$lastSlotClickTime = 0;

	@Unique
	private List<TerritoryContainerScanner.UpgradeSlotInfo> edenmod$getUpgrades(AbstractContainerMenu menu, String territory) {
		long now = System.currentTimeMillis();
		if (edenmod$cachedUpgrades == null || now - edenmod$lastUpgradeScanTime > 200 || menu.slots.size() != edenmod$lastMenuSlotCount) {
			edenmod$cachedUpgrades = TerritoryContainerScanner.scanUpgradeMenu(menu);
			edenmod$lastUpgradeScanTime = now;
			edenmod$lastMenuSlotCount = menu.slots.size();
			if (territory != null && !edenmod$cachedUpgrades.isEmpty()) {
				TerritoryGuideController.recordScannedUpgrades(territory, edenmod$cachedUpgrades);
			}
		}
		return edenmod$cachedUpgrades;
	}

	@Unique
	private boolean edenmod$isTerritoryOverview(AbstractContainerMenu menu) {
		Slot towerSlot = TerritoryContainerScanner.findSlotByName(menu, "guild tower");
		if (towerSlot == null)
			towerSlot = TerritoryContainerScanner.findSlotByName(menu, "tower attack");

		Slot bonusSlot = TerritoryContainerScanner.findSlotByName(menu, "bonus");
		if (bonusSlot == null)
			bonusSlot = TerritoryContainerScanner.findSlotByName(menu, "bonuses");

		return towerSlot != null && bonusSlot != null;
	}

	@Unique
	private boolean edenmod$isAllowedEcoContainer(String title, AbstractContainerMenu menu) {
		if (title == null || menu == null)
			return false;
		// 1. "Eden: Territories"
		if (title.contains("Territories") || title.equalsIgnoreCase("Territories"))
			return true;
		// 2. "Eden's Territory Loadouts"
		if (title.contains("Territory Loadouts") || title.equalsIgnoreCase("Loadouts"))
			return true;
		// 3. "Select Territories"
		if (title.contains("Select Territories"))
			return true;
		// 4. "{Territory Name} Bonus"
		if (title.contains("Bonus") || title.contains("Bonuses"))
			return TerritoryContainerScanner.isUpgradeOrBonusMenu(menu, title);
		// 5. "{Territory Name} Guild Tower"
		if (title.contains("Guild Tower") || title.contains("Upgrades") || title.contains("Tower"))
			return TerritoryContainerScanner.isUpgradeOrBonusMenu(menu, title);
		// 6. "{Territory Name}" (Territory Overview)
		if (edenmod$isTerritoryOverview(menu))
			return true;

		return false;
	}

	@Unique
	private Slot edenmod$findBackSlot(AbstractContainerMenu menu) {
		if (menu == null)
			return null;
		Slot slot = TerritoryContainerScanner.findSlotByName(menu, "back");
		if (slot != null)
			return slot;
		slot = TerritoryContainerScanner.findSlotByName(menu, "return");
		if (slot != null)
			return slot;
		slot = TerritoryContainerScanner.findSlotByName(menu, "cancel");
		if (slot != null)
			return slot;
		int containerSlots = menu.slots.size() - 36;
		if (containerSlots == 54 && menu.slots.size() > 45 && menu.slots.get(45).hasItem()) {
			return menu.slots.get(45);
		}
		if (menu.slots.size() > 9 && menu.slots.get(9).hasItem()) {
			return menu.slots.get(9);
		}
		return null;
	}

	@Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ClickType;)V", at = @At("HEAD"), cancellable = true)
	private void edenmod$onSlotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType, CallbackInfo ci) {
		edenmod$lastSlotClickTime = System.currentTimeMillis();
		try {
			if (slot != null && edenmod$title().contains("Select Territories")) {
				if (slot.index == 0) {
					TerritoryGuideController.commitPendingBatch();
				} else {
					Slot backSlot = edenmod$findBackSlot(getMenu());
					if (backSlot != null && slot.index == backSlot.index) {
						TerritoryGuideController.clearPendingBatch();
					}
				}
			}
			BridgeConfig config = EdenModClient.instance().config();
			List<String> reasons = RaidGambitSafety.getActiveBlockReasons(getMenu(), slot, clickType, config);
			if (!reasons.isEmpty()) {
				ci.cancel();
				RaidGambitSafety.notifyBlocked(Minecraft.getInstance(), config.raidGambitMinimum);
				RaidGambitSafety.showPopupWarning(reasons);
			}
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void edenmod$renderSafetyPopup(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.font != null && mc.getWindow() != null) {
				RaidGambitSafety.renderPopupWarning(graphics, mc.font, mc.getWindow().getGuiScaledWidth());
			}

			edenmod$skipBtnVisible = false;
			BridgeConfig config = EdenModClient.instance().config();
			if (config != null && config.territoryMenuSuite && mc != null && mc.font != null) {
				String title = edenmod$title();
				AbstractContainerMenu menu = getMenu();
				if (!edenmod$isAllowedEcoContainer(title, menu)) {
					return;
				}
				TerritoryPreset preset = TerritoryGuideController.activePreset();
				if (preset == null || preset.territories().isEmpty()) {
					return;
				}

				// 1. Territory Loadouts screen
				if (title.contains("Territory Loadouts") || title.equalsIgnoreCase("Loadouts")) {
					TerritoryGuideController.GuideStep step = TerritoryGuideController.evaluate(java.util.Map.of(), java.util.Set.of(), title, false);
					Slot targetSlot = null;
					String label = null;
					if (step.action() == TerritoryGuideController.ActionType.CLICK_FAKE_MEDIUM_OPTION) {
						targetSlot = TerritoryContainerScanner.findSlotByName(menu, "Fake Medium");
						label = "Fake Medium";
					} else if (step.action() == TerritoryGuideController.ActionType.CLICK_FAKE_HIGH_OPTION) {
						targetSlot = TerritoryContainerScanner.findSlotByName(menu, "Fake High");
						label = "Fake High";
					}
					if (targetSlot != null) {
						TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + targetSlot.x, this.topPos + targetSlot.y);
						edenmod$renderBanner(graphics, mc.font, this.leftPos, this.topPos, this.imageWidth, "§b[Eden Guide] §fClick §e[" + label + "] §fto select all " + step.totalCount() + " territories at once.");
					} else if (step.action() != TerritoryGuideController.ActionType.CLICK_FAKE_MEDIUM_OPTION && step.action() != TerritoryGuideController.ActionType.CLICK_FAKE_HIGH_OPTION) {
						Slot backSlot = edenmod$findBackSlot(menu);
						if (backSlot != null) {
							TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + backSlot.x, this.topPos + backSlot.y);
							boolean isSkipped = TerritoryGuideController.isSkippedFakeMedium() || TerritoryGuideController.isSkippedFakeHigh();
							String msg = isSkipped ? "§eLoadout skipped! Click §e[Back] §fto return to territory list." : "§aLoadout applied! Click §e[Back] §fto return to territory list.";
							edenmod$renderBanner(graphics, mc.font, this.leftPos, this.topPos, this.imageWidth, msg);
						}
					}
				}
				// 2. Select Territories screen (bulk Fake Medium / Fake High selection in chest container)
				else if (title.contains("Select Territories")) {
					TerritoryContainerScanner.ScanResult scan = TerritoryContainerScanner.scanTerritoriesMenu(menu);
					TerritoryGuideController.GuideStep step = TerritoryGuideController.evaluate(scan.currentLoadouts(), scan.selectedTerritories(), title, true);
					if (step.action() == TerritoryGuideController.ActionType.SELECT_TERRITORIES) {
						for (String target : step.targetTerritories()) {
							for (java.util.Map.Entry<String, Slot> entry : scan.territorySlots().entrySet()) {
								if (TerritoryGuideController.cleanName(entry.getKey()).equalsIgnoreCase(TerritoryGuideController.cleanName(target))) {
									Slot slot = entry.getValue();
									TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + slot.x, this.topPos + slot.y);
								}
							}
						}
						edenmod$renderBanner(graphics, mc.font, this.leftPos, this.topPos, this.imageWidth, "§b[Eden Guide] §f" + step.instruction());
					} else if (step.action() == TerritoryGuideController.ActionType.DOUBLE_CLICK_APPLY_BUTTON) {
						Slot confirmSlot = (menu.slots.size() > 0) ? menu.slots.get(0) : null;
						if (confirmSlot != null) {
							TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + confirmSlot.x, this.topPos + confirmSlot.y);
							edenmod$renderBanner(graphics, mc.font, this.leftPos, this.topPos, this.imageWidth, "§a✔ All territories selected! Click §e[Confirm Selection] §ato apply.");
						}
					} else {
						Slot backSlot = edenmod$findBackSlot(menu);
						if (backSlot != null) {
							TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + backSlot.x, this.topPos + backSlot.y);
							edenmod$renderBanner(graphics, mc.font, this.leftPos, this.topPos, this.imageWidth, "§eSelection skipped! Click §e[Back] §fto return.");
						}
					}
				}
				// 3. Guild Territories list screen (e.g. "Eden: Territories")
				else if (title.contains("Territories") || title.equalsIgnoreCase("Territories")) {
					TerritoryContainerScanner.ScanResult scan = TerritoryContainerScanner.scanTerritoriesMenu(menu);
					TerritoryGuideController.GuideStep step = TerritoryGuideController.evaluate(scan.currentLoadouts(), scan.selectedTerritories(), title, false);
					if (step.action() == TerritoryGuideController.ActionType.CLICK_LOADOUTS_BUTTON) {
						Slot loadoutSlot = TerritoryContainerScanner.findSlotByName(menu, "Territory Loadouts");
						if (loadoutSlot == null) {
							loadoutSlot = TerritoryContainerScanner.findSlotByName(menu, "Loadouts");
						}
						if (loadoutSlot != null) {
							TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + loadoutSlot.x, this.topPos + loadoutSlot.y);
						}
					} else if (step.action() == TerritoryGuideController.ActionType.OPEN_TERRITORY) {
						for (String target : step.targetTerritories()) {
							for (java.util.Map.Entry<String, Slot> entry : scan.territorySlots().entrySet()) {
								if (TerritoryGuideController.cleanName(entry.getKey()).equalsIgnoreCase(TerritoryGuideController.cleanName(target))) {
									Slot slot = entry.getValue();
									TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + slot.x, this.topPos + slot.y);
								}
							}
						}
						if (!step.targetTerritories().isEmpty()) {
							TerritoryGuideController.setLastSelectedTerritory(step.targetTerritories().get(0));
						}
					}
					if (step.instruction() != null && !step.instruction().isBlank()) {
						edenmod$renderBanner(graphics, mc.font, this.leftPos, this.topPos, this.imageWidth, "§b[Eden Guide] §f" + step.instruction());
					}
				}
				// 4. Territory Overview screen (e.g. "Path to Talor" with [Guild Tower] and [Bonus] buttons)
				else if (edenmod$isTerritoryOverview(menu)) {
					Slot towerSlot = TerritoryContainerScanner.findSlotByName(menu, "guild tower");
					if (towerSlot == null)
						towerSlot = TerritoryContainerScanner.findSlotByName(menu, "tower attack");

					Slot bonusSlot = TerritoryContainerScanner.findSlotByName(menu, "bonus");
					if (bonusSlot == null)
						bonusSlot = TerritoryContainerScanner.findSlotByName(menu, "bonuses");

					String territory = TerritoryContainerScanner.parseTerritoryFromUpgradeTitle(title);
					if (territory == null || territory.isBlank()) {
						territory = TerritoryGuideController.lastSelectedTerritory();
					}
					if (territory != null) {
						Slot backSlot = edenmod$findBackSlot(menu);
						boolean isSkipped = TerritoryGuideController.isSkipped(territory) || (TerritoryGuideController.activeTarget() != null && !TerritoryGuideController.activeTarget().equalsIgnoreCase(territory));
						if (isSkipped) {
							if (backSlot != null) {
								TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + backSlot.x, this.topPos + backSlot.y);
								edenmod$renderBanner(graphics, mc.font, this.leftPos, this.topPos, this.imageWidth, "§e" + territory + " skipped! Click §e[Back] §fto return to territory list.");
							}
						} else {
							TerritoryGuideController.setLastSelectedTerritory(territory);
							TerritoryPreset.TerritoryLoadout target = (preset != null) ? preset.loadout(territory) : TerritoryPreset.TerritoryLoadout.ZERO;
							TerritoryPreset.TerritoryLoadout current = TerritoryGuideController.getLiveOrSessionLoadout(territory);

							boolean needTower = target.damage() > current.damage() || target.attack() > current.attack() || target.health() > current.health() || target.defence() > current.defence();
							boolean needBonuses = false;
							for (java.util.Map.Entry<String, Integer> b : target.bonuses().entrySet()) {
								if (b.getValue() > current.bonus(b.getKey())) {
									needBonuses = true;
									break;
								}
							}

							if (needTower && towerSlot != null) {
								TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + towerSlot.x, this.topPos + towerSlot.y);
								String slotName = net.minecraft.ChatFormatting.stripFormatting(towerSlot.getItem().getHoverName().getString());
								edenmod$renderBanner(graphics, mc.font, this.leftPos, this.topPos, this.imageWidth, "§b[Eden Guide] §fClick §e[" + slotName + "] §fto configure tower defense.");
							} else if (needBonuses && bonusSlot != null) {
								TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + bonusSlot.x, this.topPos + bonusSlot.y);
								String slotName = net.minecraft.ChatFormatting.stripFormatting(bonusSlot.getItem().getHoverName().getString());
								edenmod$renderBanner(graphics, mc.font, this.leftPos, this.topPos, this.imageWidth, "§b[Eden Guide] §fTower defense complete! Click §e[" + slotName + "] §fto configure territory bonuses.");
							} else {
								if (backSlot != null) {
									TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + backSlot.x, this.topPos + backSlot.y);
									edenmod$renderBanner(graphics, mc.font, this.leftPos, this.topPos, this.imageWidth, "§a✔ All upgrades complete for " + territory + "! Click §e[Back] §fto return to territory list.");
								}
							}
						}
					}
				}
				// 5. Upgrades or Bonuses screen
				else if (title.endsWith(": Upgrades") || title.contains("Upgrades") || title.endsWith(": Bonuses") || title.contains("Bonuses") || title.endsWith(": Bonus") || title.contains(": Bonus") || title.contains("Bonus") || TerritoryContainerScanner.isUpgradeOrBonusMenu(menu, title)) {
					String territory = TerritoryContainerScanner.parseTerritoryFromUpgradeTitle(title);
					if (territory == null || territory.equalsIgnoreCase("Upgrades") || territory.equalsIgnoreCase("Bonuses") || territory.equalsIgnoreCase("Bonus") || territory.equalsIgnoreCase("Guild Tower") || territory.contains("Tower")) {
						String last = TerritoryGuideController.lastSelectedTerritory();
						if (last != null) {
							territory = last;
						}
					}
					Slot backSlot = edenmod$findBackSlot(menu);
					boolean isSkipped = (territory != null) && (TerritoryGuideController.isSkipped(territory) || (TerritoryGuideController.activeTarget() != null && !TerritoryGuideController.activeTarget().equalsIgnoreCase(territory)));
					if (isSkipped) {
						if (backSlot != null) {
							TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + backSlot.x, this.topPos + backSlot.y);
							edenmod$renderBanner(graphics, mc.font, this.leftPos, this.topPos, this.imageWidth, "§e" + territory + " skipped! Click §e[Back] §fto return.");
						}
					} else {
						if (territory != null) {
							TerritoryGuideController.setLastSelectedTerritory(territory);
						}
						TerritoryPreset.TerritoryLoadout target = (preset != null && territory != null) ? preset.loadout(territory) : TerritoryPreset.TerritoryLoadout.ZERO;
						List<TerritoryContainerScanner.UpgradeSlotInfo> upgrades = edenmod$getUpgrades(menu, territory);

						if (!upgrades.isEmpty()) {
							int[] skipBox = TerritoryUpgradeGuideOverlay.renderUpgradeGuideBanner(graphics, mc.font, this.leftPos, this.topPos, this.imageWidth, territory, preset, target, upgrades);
							if (skipBox != null) {
								edenmod$skipBtnX = skipBox[0];
								edenmod$skipBtnY = skipBox[1];
								edenmod$skipBtnW = skipBox[2];
								edenmod$skipBtnH = skipBox[3];
								edenmod$skipBtnVisible = true;
							}

							int totalDiff = 0;
							for (TerritoryContainerScanner.UpgradeSlotInfo up : upgrades) {
								int targetLvl = switch (up.upgradeName()) {
									case "Damage" -> target.damage();
									case "Attack" -> target.attack();
									case "Health" -> target.health();
									case "Defence" -> target.defence();
									default -> target.bonus(up.upgradeName());
								};
								int remaining = Math.max(0, targetLvl - up.currentLevel());
								totalDiff += remaining;
								int sx = this.leftPos + up.slot().x;
								int sy = this.topPos + up.slot().y;
								if (remaining > 0) {
									TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, sx, sy);
								}
								TerritoryUpgradeGuideOverlay.renderClickBadge(graphics, mc.font, sx, sy, remaining, up.currentLevel() >= targetLvl);
							}

							long now = System.currentTimeMillis();
							boolean debounced = (now - edenmod$lastSlotClickTime >= 350L);
							if (totalDiff == 0 && debounced) {
								boolean allSatisfied = true;
								for (TerritoryContainerScanner.UpgradeSlotInfo up : upgrades) {
									int targetLvl = switch (up.upgradeName()) {
										case "Damage" -> target.damage();
										case "Attack" -> target.attack();
										case "Health" -> target.health();
										case "Defence" -> target.defence();
										default -> target.bonus(up.upgradeName());
									};
									if (up.currentLevel() < targetLvl) {
										allSatisfied = false;
										break;
									}
								}
								if (allSatisfied && backSlot != null) {
									TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + backSlot.x, this.topPos + backSlot.y);
								}
							}
						}
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = "removed", at = @At("TAIL"))
	private void edenmod$onContainerScreenRemoved(CallbackInfo ci) {
		RaidGambitSafety.clearPopupWarning();
		edenmod$cachedUpgrades = null;
		edenmod$lastSlotClickTime = 0;
		edenmod$skipBtnVisible = false;
		if (edenmod$title().contains("Select Territories")) {
			TerritoryGuideController.clearPendingBatch();
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void edenmod$onMouseClicked(MouseButtonEvent event, boolean bl, CallbackInfoReturnable<Boolean> cir) {
		if ((event.button() == 0 || event.button() == 1) && edenmod$skipBtnVisible) {
			if (event.x() >= edenmod$skipBtnX && event.x() < edenmod$skipBtnX + edenmod$skipBtnW && event.y() >= edenmod$skipBtnY && event.y() < edenmod$skipBtnY + edenmod$skipBtnH) {
				Minecraft mc = Minecraft.getInstance();
				if (event.button() == 0) {
					TerritoryGuideController.skipForward();
					if (mc != null && mc.gui != null) {
						mc.gui.getChat().addMessage(Component.literal("§e[Eden] Skipped current instruction."));
					}
				} else {
					TerritoryGuideController.skipBackwards();
					if (mc != null && mc.gui != null) {
						mc.gui.getChat().addMessage(Component.literal("§e[Eden] Returned to previous instruction."));
					}
				}
				cir.setReturnValue(true);
			}
		}
	}

	@Inject(method = "getTooltipFromContainerItem(Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;", at = @At("RETURN"), cancellable = true)
	private void edenmod$appendRaidReadyTooltip(ItemStack stack, CallbackInfoReturnable<List<Component>> cir) {
		try {
			BridgeConfig config = EdenModClient.instance().config();
			if (this.hoveredSlot != null && RaidGambitSafety.isReadyClickSlot(this.hoveredSlot)) {
				if (config != null) {
					AbstractContainerMenu menu = getMenu();
					if (RaidGambitSafety.isRaidReadyMenu(menu) && !RaidGambitSafety.isPlayerAlreadyReadied(menu)) {
						List<Component> warnings = new ArrayList<>();
						if (config.raidGambitSafety && config.raidGambitMinimum > 0) {
							int enabled = RaidGambitSafety.countEnabledGambits(menu);
							if (enabled < config.raidGambitMinimum) {
								warnings.add(RaidGambitSafety.getTooltipWarning(config.raidGambitMinimum));
							}
						}
						if (config.requireGuildRaid && !RaidGambitSafety.isGuildRaidAvailable(menu)) {
							warnings.add(RaidGambitSafety.getGuildRaidTooltipWarning());
						}
						if (!warnings.isEmpty()) {
							List<Component> base = cir.getReturnValue();
							List<Component> tooltip = new ArrayList<>(base != null ? base : List.of());
							int insertIdx = -1;
							for (int i = 0; i < tooltip.size(); i++) {
								String text = net.minecraft.ChatFormatting.stripFormatting(tooltip.get(i).getString()).trim();
								if (text.contains("Click to Ready Up") || text.equalsIgnoreCase("Click to Ready Up")) {
									insertIdx = i;
									break;
								}
							}
							if (insertIdx != -1) {
								for (Component w : warnings) {
									tooltip.add(insertIdx++, w);
								}
								tooltip.add(insertIdx, Component.empty());
							} else {
								tooltip.add(Component.empty());
								tooltip.addAll(warnings);
							}
							cir.setReturnValue(tooltip);
						}
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}
}
