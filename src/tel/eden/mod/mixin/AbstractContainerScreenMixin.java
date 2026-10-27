package tel.eden.mod.mixin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
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
import tel.eden.mod.gui.TerritoryMenuPanel;
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
	private boolean edenmod$upgradesDirty = true;
	@Unique
	private long edenmod$lastUpgradeScanTime = 0L;
	@Unique
	private int edenmod$lastMenuSlotCount = 0;
	@Unique
	private long edenmod$lastSlotClickTime = 0;
	@Unique
	private String edenmod$lastScannedTerritory = null;
	@Unique
	private String edenmod$lastScannedTitle = null;

	@Unique
	private List<TerritoryContainerScanner.UpgradeSlotInfo> edenmod$getUpgrades(AbstractContainerMenu menu, String territory) {
		long now = System.currentTimeMillis();
		String currentTitle = edenmod$title();
		boolean sameContext = java.util.Objects.equals(territory, edenmod$lastScannedTerritory) && java.util.Objects.equals(currentTitle, edenmod$lastScannedTitle);
		if (!sameContext) {
			edenmod$cachedUpgrades = null;
		}
		if (edenmod$cachedUpgrades == null || edenmod$cachedUpgrades.isEmpty() || edenmod$upgradesDirty || now - edenmod$lastUpgradeScanTime >= 100L || menu.slots.size() != edenmod$lastMenuSlotCount) {
			List<TerritoryContainerScanner.UpgradeSlotInfo> scanned = TerritoryContainerScanner.scanUpgradeMenu(menu);
			if (!scanned.isEmpty()) {
				if (sameContext && edenmod$cachedUpgrades != null && scanned.size() < edenmod$cachedUpgrades.size()) {
					java.util.Map<String, TerritoryContainerScanner.UpgradeSlotInfo> merged = new java.util.LinkedHashMap<>();
					for (TerritoryContainerScanner.UpgradeSlotInfo oldUp : edenmod$cachedUpgrades) {
						merged.put(oldUp.upgradeName(), oldUp);
					}
					for (TerritoryContainerScanner.UpgradeSlotInfo newUp : scanned) {
						merged.put(newUp.upgradeName(), newUp);
					}
					edenmod$cachedUpgrades = new java.util.ArrayList<>(merged.values());
				} else {
					edenmod$cachedUpgrades = scanned;
				}
			}
			edenmod$upgradesDirty = false;
			edenmod$lastUpgradeScanTime = now;
			edenmod$lastMenuSlotCount = menu.slots.size();
			edenmod$lastScannedTerritory = territory;
			edenmod$lastScannedTitle = currentTitle;
			if (territory != null && edenmod$cachedUpgrades != null && !edenmod$cachedUpgrades.isEmpty()) {
				TerritoryGuideController.recordScannedUpgrades(territory, edenmod$cachedUpgrades);
			}
		}
		return edenmod$cachedUpgrades != null ? edenmod$cachedUpgrades : List.of();
	}

	@Unique
	private TerritoryContainerScanner.ScanResult edenmod$cachedTerritoryScan = null;
	@Unique
	private long edenmod$lastTerritoryScanTime = 0L;
	@Unique
	private int edenmod$lastTerritoryMenuSlotCount = 0;

	@Unique
	private TerritoryContainerScanner.ScanResult edenmod$getTerritoryScan(AbstractContainerMenu menu) {
		long now = System.currentTimeMillis();
		if (edenmod$cachedTerritoryScan == null || edenmod$upgradesDirty || now - edenmod$lastTerritoryScanTime >= 150L || menu.slots.size() != edenmod$lastTerritoryMenuSlotCount) {
			edenmod$cachedTerritoryScan = TerritoryContainerScanner.scanTerritoriesMenu(menu);
			edenmod$lastTerritoryScanTime = now;
			edenmod$lastTerritoryMenuSlotCount = menu.slots.size();
		}
		return edenmod$cachedTerritoryScan;
	}

	@Unique
	private boolean edenmod$isTerritoryOverview(String title, AbstractContainerMenu menu) {
		if (title == null || title.isBlank()) {
			return false;
		}
		if (title.contains("Bonus") || title.contains("Bonuses") || title.contains("Guild Tower") || title.contains("Upgrades")) {
			return false;
		}
		String clean = TerritoryContainerScanner.cleanTerritoryTitle(title);
		if (!TerritoryGuideController.isKnownTerritory(clean)) {
			return false;
		}
		Slot towerSlot = TerritoryContainerScanner.findSlotByName(menu, "guild tower");
		if (towerSlot == null)
			towerSlot = TerritoryContainerScanner.findSlotByName(menu, "tower attack");

		Slot bonusSlot = TerritoryContainerScanner.findSlotByName(menu, "bonus");
		if (bonusSlot == null)
			bonusSlot = TerritoryContainerScanner.findSlotByName(menu, "bonuses");

		return towerSlot != null && bonusSlot != null;
	}

	@Unique
	private AbstractContainerMenu edenmod$cachedCheckedMenu = null;
	@Unique
	private boolean edenmod$cachedIsEcoContainer = false;
	@Unique
	private long edenmod$lastEcoCheckTime = 0L;
	@Unique
	private int edenmod$lastEcoSlotCount = 0;

	@Unique
	private boolean edenmod$checkAllowedEcoContainer(String title, AbstractContainerMenu menu) {
		if (menu == null || title == null) {
			return false;
		}
		if (menu == edenmod$cachedCheckedMenu && edenmod$cachedIsEcoContainer) {
			return true;
		}
		long now = System.currentTimeMillis();
		if (menu == edenmod$cachedCheckedMenu && !edenmod$upgradesDirty && now - edenmod$lastEcoCheckTime < 150L && menu.slots.size() == edenmod$lastEcoSlotCount) {
			return edenmod$cachedIsEcoContainer;
		}
		boolean allowed = edenmod$isAllowedEcoContainer(title, menu);
		edenmod$cachedCheckedMenu = menu;
		edenmod$cachedIsEcoContainer = allowed;
		edenmod$lastEcoCheckTime = now;
		edenmod$lastEcoSlotCount = menu.slots.size();
		return allowed;
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
		// 4. "{Territory Name} Bonus" / "{Territory Name} Guild Tower"
		if (title.contains("Bonus") || title.contains("Bonuses") || title.contains("Guild Tower") || title.contains("Upgrades")) {
			String terr = TerritoryContainerScanner.parseTerritoryFromUpgradeTitle(title);
			return terr != null && TerritoryGuideController.isKnownTerritory(terr) && TerritoryContainerScanner.isUpgradeOrBonusMenu(menu, title);
		}
		// 5. Territory Overview (e.g. "Mesquis Tower", has Guild Tower & Bonus buttons)
		if (edenmod$isTerritoryOverview(title, menu))
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
		edenmod$upgradesDirty = true;
		try {
			if (slot != null && edenmod$title().contains("Select Territories")) {
				if (TerritoryContainerScanner.isContainerSlot(getMenu(), slot)) {
					Slot confirmSlot = (!getMenu().slots.isEmpty()) ? getMenu().slots.get(0) : null;
					if (confirmSlot != null && slot == confirmSlot) {
						TerritoryGuideController.commitPendingBatch();
					} else {
						Slot backSlot = edenmod$findBackSlot(getMenu());
						if (backSlot != null && slot == backSlot) {
							TerritoryGuideController.clearPendingBatch();
						}
					}
				}
			}
			BridgeConfig config = EdenModClient.instance().config();
			List<String> reasons = RaidGambitSafety.getActiveBlockReasons(getMenu(), slot, clickType, config);
			if (!reasons.isEmpty()) {
				ci.cancel();
				RaidGambitSafety.notifyBlocked(Minecraft.getInstance(), config.raidGambitMinimum);
				RaidGambitSafety.showPopupWarning(reasons);
			} else if (RaidGambitSafety.isReadyClickSlot(slot)) {
				RaidGambitSafety.clearPopupWarning();
			}
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void edenmod$renderSafetyPopup(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.font != null && mc.getWindow() != null) {
				RaidGambitSafety.renderPopupWarning(graphics, mc.font, mc.getWindow().getGuiScaledWidth(), ((net.minecraft.client.gui.screens.Screen) (Object) this).getTitle().getString(), getMenu(), EdenModClient.instance().config());
			}

			edenmod$skipBtnVisible = false;
			BridgeConfig config = EdenModClient.instance().config();
			if (config != null && config.territoryMenuSuite && mc != null && mc.font != null) {
				String title = edenmod$title();
				AbstractContainerMenu menu = getMenu();
				if (!edenmod$checkAllowedEcoContainer(title, menu)) {
					return;
				}

				boolean isTerritoryMap = title.contains("Territories") || title.equalsIgnoreCase("Territories");
				boolean isLoadoutsMenu = title.contains("Territory Loadouts") || title.equalsIgnoreCase("Loadouts");
				boolean isSelectScreen = title.contains("Select Territories");

				if (isTerritoryMap || isLoadoutsMenu || isSelectScreen) {
					TerritoryContainerScanner.ScanResult scan = (isTerritoryMap || isSelectScreen) ? edenmod$getTerritoryScan(menu) : TerritoryContainerScanner.ScanResult.EMPTY;
					TerritoryGuideController.GuideStep step = TerritoryGuideController.evaluate(scan.currentLoadouts(), scan.selectedTerritories(), title, isSelectScreen);
					TerritoryMenuPanel.renderHudBar((Screen) (Object) this, graphics, mouseX, mouseY, step);

					if (TerritoryGuideController.hasActivePreset()) {
						if (isLoadoutsMenu) {
							Slot targetSlot = null;
							if (step.action() == TerritoryGuideController.ActionType.CLICK_FAKE_MEDIUM_OPTION) {
								targetSlot = TerritoryContainerScanner.findSlotByName(menu, "Fake Medium");
							} else if (step.action() == TerritoryGuideController.ActionType.CLICK_FAKE_HIGH_OPTION) {
								targetSlot = TerritoryContainerScanner.findSlotByName(menu, "Fake High");
							}
							if (targetSlot != null) {
								TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + targetSlot.x, this.topPos + targetSlot.y);
							} else if (step.action() != TerritoryGuideController.ActionType.CLICK_FAKE_MEDIUM_OPTION && step.action() != TerritoryGuideController.ActionType.CLICK_FAKE_HIGH_OPTION) {
								Slot backSlot = edenmod$findBackSlot(menu);
								if (backSlot != null) {
									TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + backSlot.x, this.topPos + backSlot.y);
								}
							}
						} else if (isSelectScreen) {
							if (step.action() == TerritoryGuideController.ActionType.SELECT_TERRITORIES) {
								for (String target : step.targetTerritories()) {
									for (java.util.Map.Entry<String, Slot> entry : scan.territorySlots().entrySet()) {
										if (TerritoryGuideController.cleanName(entry.getKey()).equalsIgnoreCase(TerritoryGuideController.cleanName(target))) {
											Slot slot = entry.getValue();
											TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + slot.x, this.topPos + slot.y);
										}
									}
								}
							} else if (step.action() == TerritoryGuideController.ActionType.DOUBLE_CLICK_APPLY_BUTTON) {
								Slot confirmSlot = (!menu.slots.isEmpty()) ? menu.slots.get(0) : null;
								if (confirmSlot != null) {
									TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + confirmSlot.x, this.topPos + confirmSlot.y);
								}
							} else {
								Slot backSlot = edenmod$findBackSlot(menu);
								if (backSlot != null) {
									TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + backSlot.x, this.topPos + backSlot.y);
								}
							}
						} else if (isTerritoryMap) {
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
						}
					}
					return;
				}

				if (!TerritoryGuideController.hasActivePreset()) {
					return;
				}
				TerritoryPreset preset = TerritoryGuideController.activePreset();
				if (preset == null || preset.territories().isEmpty()) {
					return;
				}

				// 4. Upgrades or Bonuses screen
				if ((title.contains("Upgrades") || title.contains("Guild Tower") || title.contains("Bonus") || title.contains("Bonuses")) && TerritoryGuideController.isKnownTerritory(TerritoryContainerScanner.parseTerritoryFromUpgradeTitle(title))) {
					String territory = TerritoryContainerScanner.parseTerritoryFromUpgradeTitle(title);
					Slot backSlot = edenmod$findBackSlot(menu);
					boolean isSkipped = (territory != null) && TerritoryGuideController.isSkipped(territory);
					if (isSkipped) {
						if (backSlot != null) {
							TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + backSlot.x, this.topPos + backSlot.y);
							edenmod$renderBanner(graphics, mc.font, this.leftPos, this.topPos, this.imageWidth, "§e" + territory + " skipped! Click §e[Back] §fto return.");
						}
					} else {
						if (territory != null) {
							TerritoryGuideController.setLastSelectedTerritory(territory);
							TerritoryGuideController.setActiveTarget(territory);
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
								int diff = targetLvl - up.currentLevel();
								totalDiff += Math.abs(diff);
								int sx = this.leftPos + up.slot().x;
								int sy = this.topPos + up.slot().y;
								if (diff != 0) {
									TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, sx, sy);
								}
								TerritoryUpgradeGuideOverlay.renderClickBadge(graphics, mc.font, sx, sy, diff, diff == 0);
							}

							long now = System.currentTimeMillis();
							boolean debounced = (now - edenmod$lastSlotClickTime >= 350L);
							boolean isBonusView = false;
							Set<String> seenUpgrades = new HashSet<>();
							for (TerritoryContainerScanner.UpgradeSlotInfo up : upgrades) {
								seenUpgrades.add(up.upgradeName());
								if (!up.upgradeName().equals("Damage") && !up.upgradeName().equals("Attack") && !up.upgradeName().equals("Health") && !up.upgradeName().equals("Defence")) {
									isBonusView = true;
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
							if (totalDiff == 0 && debounced && allRequiredSeen) {
								boolean allSatisfied = true;
								for (TerritoryContainerScanner.UpgradeSlotInfo up : upgrades) {
									int targetLvl = switch (up.upgradeName()) {
										case "Damage" -> target.damage();
										case "Attack" -> target.attack();
										case "Health" -> target.health();
										case "Defence" -> target.defence();
										default -> target.bonus(up.upgradeName());
									};
									if (up.currentLevel() != targetLvl) {
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
				// 5. Territory Overview screen (e.g. "Path to Talor" with [Guild Tower] and [Bonus] buttons)
				else if (edenmod$isTerritoryOverview(title, menu)) {
					Slot towerSlot = TerritoryContainerScanner.findSlotByName(menu, "guild tower");
					if (towerSlot == null)
						towerSlot = TerritoryContainerScanner.findSlotByName(menu, "tower attack");

					Slot bonusSlot = TerritoryContainerScanner.findSlotByName(menu, "bonus");
					if (bonusSlot == null)
						bonusSlot = TerritoryContainerScanner.findSlotByName(menu, "bonuses");

					String territory = TerritoryContainerScanner.cleanTerritoryTitle(title);
					if (territory == null || !TerritoryGuideController.isKnownTerritory(territory)) {
						territory = TerritoryContainerScanner.parseTerritoryFromUpgradeTitle(title);
					}
					if (territory == null || !TerritoryGuideController.isKnownTerritory(territory)) {
						territory = TerritoryGuideController.lastSelectedTerritory();
					}
					if (territory != null) {
						Slot backSlot = edenmod$findBackSlot(menu);
						boolean isSkipped = TerritoryGuideController.isSkipped(territory);
						if (isSkipped) {
							if (backSlot != null) {
								TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, this.leftPos + backSlot.x, this.topPos + backSlot.y);
								edenmod$renderBanner(graphics, mc.font, this.leftPos, this.topPos, this.imageWidth, "§e" + territory + " skipped! Click §e[Back] §fto return to territory list.");
							}
						} else {
							TerritoryGuideController.setLastSelectedTerritory(territory);
							TerritoryGuideController.setActiveTarget(territory);
							TerritoryPreset.TerritoryLoadout target = (preset != null) ? preset.loadout(territory) : TerritoryPreset.TerritoryLoadout.ZERO;
							TerritoryPreset.TerritoryLoadout current = TerritoryGuideController.getLiveOrSessionLoadout(territory);

							boolean needTower = target.damage() != current.damage() || target.attack() != current.attack() || target.health() != current.health() || target.defence() != current.defence();
							boolean needBonuses = false;
							Set<String> allB = new HashSet<>();
							if (target.bonuses() != null)
								allB.addAll(target.bonuses().keySet());
							if (current.bonuses() != null)
								allB.addAll(current.bonuses().keySet());
							for (String bName : allB) {
								if (target.bonus(bName) != current.bonus(bName)) {
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
			}
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = "removed", at = @At("TAIL"))
	private void edenmod$onContainerScreenRemoved(CallbackInfo ci) {
		TerritoryMenuPanel.closeDropdown();
		RaidGambitSafety.clearPopupWarning();
		edenmod$cachedUpgrades = null;
		edenmod$upgradesDirty = true;
		edenmod$lastSlotClickTime = 0;
		edenmod$skipBtnVisible = false;
		edenmod$cachedCheckedMenu = null;
		edenmod$cachedIsEcoContainer = false;
		edenmod$lastEcoCheckTime = 0L;
		edenmod$lastEcoSlotCount = 0;
		edenmod$lastScannedTerritory = null;
		edenmod$lastScannedTitle = null;
		if (edenmod$title().contains("Select Territories")) {
			TerritoryGuideController.clearPendingBatch();
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void edenmod$onMouseClicked(MouseButtonEvent event, boolean bl, CallbackInfoReturnable<Boolean> cir) {
		BridgeConfig config = EdenModClient.instance().config();
		if (config != null && config.territoryMenuSuite) {
			String title = edenmod$title();
			AbstractContainerMenu menu = getMenu();
			if (edenmod$checkAllowedEcoContainer(title, menu)) {
				boolean isTerritoryMap = title.contains("Territories") || title.equalsIgnoreCase("Territories");
				boolean isLoadoutsMenu = title.contains("Territory Loadouts") || title.equalsIgnoreCase("Loadouts");
				boolean isSelectScreen = title.contains("Select Territories");
				if (isTerritoryMap || isLoadoutsMenu || isSelectScreen) {
					java.util.Map<String, TerritoryPreset.TerritoryLoadout> containerLoadouts = null;
					if (isTerritoryMap) {
						containerLoadouts = edenmod$getTerritoryScan(menu).currentLoadouts();
					}
					if (!TerritoryMenuPanel.handleMouseClick((Screen) (Object) this, event.x(), event.y(), event.button(), containerLoadouts)) {
						cir.setReturnValue(true);
						return;
					}
				}
			}
		}

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
