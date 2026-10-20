package tel.eden.mod.gui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import tel.eden.mod.EdenLogger;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.war.eco.TerritoryContainerScanner;
import tel.eden.mod.war.eco.TerritoryGuideController;
import tel.eden.mod.war.eco.TerritoryPreset;
import tel.eden.mod.war.eco.TerritoryPresetCodec;

/**
 * Top Preset HUD bar, Preset Storage Dropdown, and in-menu guide highlights on Wynntils' TerritoryManagementScreen.
 */
public final class TerritoryMenuPanel {
	private static final EdenLogger LOGGER = EdenLogger.get();

	private TerritoryMenuPanel() {
	}

	private static boolean dropdownOpen = false;
	private static boolean overlayFailureLogged = false;

	public static boolean isDropdownOpen() {
		return dropdownOpen;
	}

	public static void closeDropdown() {
		dropdownOpen = false;
	}

	public static void init() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen == null) {
				return;
			}
			String name = screen.getClass().getName();
			if (name.endsWith("TerritoryManagementScreen")) {
				dropdownOpen = false;
				ScreenEvents.afterRender(screen).register((scr, graphics, mouseX, mouseY, tickDelta) -> {
					renderWynntilsOverlay(scr, graphics, mouseX, mouseY);
				});
				ScreenMouseEvents.allowMouseClick(screen).register((scr, event) -> {
					return handleWynntilsMouseClick(scr, event.x(), event.y(), event.button());
				});
			}
		});
	}

	private static Field holderField = null;
	private static boolean holderFieldInit = false;
	private static Field holderSelectedField = null;
	private static boolean holderSelectedFieldInit = false;

	private static Field renderAreaWidgetsField = null;
	private static boolean renderAreaWidgetsFieldInit = false;

	private static Field mapModeField = null;
	private static Field territoryPoisField = null;
	private static Field mapButtonsField = null;
	private static Field mapCenterXField = null;
	private static Field mapCenterZField = null;
	private static Field centerXField = null;
	private static Field centerZField = null;
	private static Field zoomRenderScaleField = null;
	private static Field mapRenderXField = null;
	private static Field mapRenderYField = null;
	private static Field mapRenderWidthField = null;
	private static Field mapRenderHeightField = null;
	private static boolean mapFieldsInit = false;

	private static Method poiGetNameMethod = null;
	private static Method poiGetLocationMethod = null;
	private static Method locGetXMethod = null;
	private static Method locGetZMethod = null;
	private static boolean poiMethodsInit = false;

	private static Field widgetTerritoryItemField = null;
	private static Method tiGetNameMethod = null;
	private static boolean widgetFieldInit = false;

	private static Method holderTerritoryItemsMethod = null;
	private static Method pairAMethod = null;
	private static Method pairBMethod = null;
	private static Method tiIsSelectedMethod = null;
	private static Method tiIsPendingMethod = null;
	private static Method holderIsSelectionModeMethod = null;
	private static boolean holderMethodsInit = false;

	private static Object getHolder(Screen screen) {
		if (!holderFieldInit) {
			holderFieldInit = true;
			try {
				holderField = screen.getClass().getDeclaredField("holder");
				holderField.setAccessible(true);
			} catch (Throwable ignored) {
			}
		}
		if (holderField != null) {
			try {
				return holderField.get(screen);
			} catch (Throwable ignored) {
			}
		}
		return null;
	}

	private static List<?> getRenderAreaWidgets(Screen screen) {
		if (!renderAreaWidgetsFieldInit) {
			renderAreaWidgetsFieldInit = true;
			try {
				renderAreaWidgetsField = screen.getClass().getDeclaredField("renderAreaWidgets");
				renderAreaWidgetsField.setAccessible(true);
			} catch (Throwable ignored) {
			}
		}
		if (renderAreaWidgetsField != null) {
			try {
				return (List<?>) renderAreaWidgetsField.get(screen);
			} catch (Throwable ignored) {
			}
		}
		return null;
	}

	private static void ensureMapFields(Screen screen) {
		if (mapFieldsInit || screen == null) {
			return;
		}
		mapFieldsInit = true;
		try {
			Class<?> cls = screen.getClass();
			Class<?> mapScreenClass = cls.getSuperclass();
			mapModeField = cls.getDeclaredField("mapMode");
			mapModeField.setAccessible(true);
			territoryPoisField = cls.getDeclaredField("territoryPois");
			territoryPoisField.setAccessible(true);

			if (mapScreenClass != null) {
				mapButtonsField = mapScreenClass.getDeclaredField("mapButtons");
				mapButtonsField.setAccessible(true);
				centerXField = mapScreenClass.getDeclaredField("centerX");
				centerXField.setAccessible(true);
				centerZField = mapScreenClass.getDeclaredField("centerZ");
				centerZField.setAccessible(true);
				mapCenterXField = mapScreenClass.getDeclaredField("mapCenterX");
				mapCenterXField.setAccessible(true);
				mapCenterZField = mapScreenClass.getDeclaredField("mapCenterZ");
				mapCenterZField.setAccessible(true);
				zoomRenderScaleField = mapScreenClass.getDeclaredField("zoomRenderScale");
				zoomRenderScaleField.setAccessible(true);
				mapRenderXField = mapScreenClass.getDeclaredField("renderX");
				mapRenderXField.setAccessible(true);
				mapRenderYField = mapScreenClass.getDeclaredField("renderY");
				mapRenderYField.setAccessible(true);
				mapRenderWidthField = mapScreenClass.getDeclaredField("renderWidth");
				mapRenderWidthField.setAccessible(true);
				mapRenderHeightField = mapScreenClass.getDeclaredField("renderHeight");
				mapRenderHeightField.setAccessible(true);
			}
		} catch (Throwable ignored) {
		}
	}

	private static boolean isMapMode(Screen screen) {
		ensureMapFields(screen);
		if (mapModeField != null) {
			try {
				return (Boolean) mapModeField.get(screen);
			} catch (Throwable ignored) {
			}
		}
		return false;
	}

	private static boolean matchesTarget(List<String> targets, String label) {
		if (label == null || label.isBlank() || targets == null) {
			return false;
		}
		String cleanLabel = TerritoryGuideController.cleanName(label);
		for (String target : targets) {
			if (cleanLabel.equalsIgnoreCase(TerritoryGuideController.cleanName(target))) {
				return true;
			}
		}
		return false;
	}

	private static void highlightMapMode(Screen screen, GuiGraphics graphics, List<String> targets) {
		try {
			ensureMapFields(screen);
			if (territoryPoisField == null || centerXField == null)
				return;
			List<?> pois = (List<?>) territoryPoisField.get(screen);
			if (pois == null || pois.isEmpty())
				return;

			float cX = centerXField.getFloat(screen);
			float cZ = centerZField.getFloat(screen);
			float mX = mapCenterXField.getFloat(screen);
			float mZ = mapCenterZField.getFloat(screen);
			float zoom = zoomRenderScaleField.getFloat(screen);
			float rX = mapRenderXField.getFloat(screen);
			float rY = mapRenderYField.getFloat(screen);
			float rW = mapRenderWidthField.getFloat(screen);
			float rH = mapRenderHeightField.getFloat(screen);

			if (!poiMethodsInit && !pois.isEmpty()) {
				poiMethodsInit = true;
				try {
					Object samplePoi = pois.iterator().next();
					poiGetNameMethod = samplePoi.getClass().getMethod("getName");
					poiGetNameMethod.setAccessible(true);
					poiGetLocationMethod = samplePoi.getClass().getMethod("getLocation");
					poiGetLocationMethod.setAccessible(true);
					Object sampleLoc = poiGetLocationMethod.invoke(samplePoi);
					if (sampleLoc != null) {
						locGetXMethod = sampleLoc.getClass().getMethod("getX");
						locGetXMethod.setAccessible(true);
						locGetZMethod = sampleLoc.getClass().getMethod("getZ");
						locGetZMethod.setAccessible(true);
					}
				} catch (Throwable ignored) {
				}
			}

			if (poiGetNameMethod != null && poiGetLocationMethod != null && locGetXMethod != null && locGetZMethod != null) {
				for (Object poiObj : pois) {
					String name = (String) poiGetNameMethod.invoke(poiObj);
					if (matchesTarget(targets, name)) {
						Object loc = poiGetLocationMethod.invoke(poiObj);
						if (loc != null) {
							int worldX = (Integer) locGetXMethod.invoke(loc);
							int worldZ = (Integer) locGetZMethod.invoke(loc);
							float px = cX + (worldX - mX) * zoom;
							float pz = cZ + (worldZ - mZ) * zoom;

							if (px >= rX && px <= rX + rW && pz >= rY && pz <= rY + rH) {
								TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, Math.round(px) - 8, Math.round(pz) - 8, 16, 16);
							}
						}
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	private static void highlightWidgets(Screen screen, GuiGraphics graphics, TerritoryGuideController.GuideStep step) {
		if (step == null || step.action() == TerritoryGuideController.ActionType.NONE || step.action() == TerritoryGuideController.ActionType.ALL_COMPLETE) {
			return;
		}

		boolean mapMode = isMapMode(screen);
		int renderX = (screen.width - 256) / 2;
		int renderY = (screen.height - 136) / 2;

		// Highlight Territory Loadouts button or Apply button
		if (step.action() == TerritoryGuideController.ActionType.CLICK_LOADOUTS_BUTTON || step.action() == TerritoryGuideController.ActionType.DOUBLE_CLICK_APPLY_BUTTON) {
			if (mapMode) {
				try {
					List<?> mapButtons = (mapButtonsField != null) ? (List<?>) mapButtonsField.get(screen) : null;
					AbstractWidget btn = null;
					if (mapButtons != null) {
						for (Object mb : mapButtons) {
							if (mb instanceof AbstractWidget w) {
								String wName = w.getClass().getSimpleName();
								if (wName.contains("Apply") || wName.contains("Checkmark")) {
									btn = w;
									break;
								}
							}
						}
						if (btn == null && mapButtons.size() > 3 && mapButtons.get(3) instanceof AbstractWidget w3) {
							btn = w3;
						}
					}
					if (btn != null) {
						TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, btn.getX(), btn.getY(), btn.getWidth(), btn.getHeight());
						if (step.action() == TerritoryGuideController.ActionType.DOUBLE_CLICK_APPLY_BUTTON) {
							Minecraft mc = Minecraft.getInstance();
							if (mc.font != null) {
								String msg = "§aConfirm Selection ✔";
								int tx = btn.getX() + (btn.getWidth() - mc.font.width(msg)) / 2;
								graphics.drawString(mc.font, msg, tx, btn.getY() - 12, 0xFF55FF55);
							}
						}
					}
				} catch (Throwable ignored) {
				}
			} else {
				AbstractWidget btn = null;
				for (GuiEventListener child : screen.children()) {
					if (child instanceof AbstractWidget w) {
						String wName = w.getClass().getSimpleName();
						if (wName.contains("Apply") || wName.contains("Loadout")) {
							btn = w;
							break;
						}
					}
				}
				int btnX = (btn != null) ? btn.getX() : (renderX - 20);
				int btnY = (btn != null) ? btn.getY() : (renderY + 115);
				int btnW = (btn != null) ? btn.getWidth() : 16;
				int btnH = (btn != null) ? btn.getHeight() : 16;
				TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, btnX, btnY, btnW, btnH);
				if (step.action() == TerritoryGuideController.ActionType.DOUBLE_CLICK_APPLY_BUTTON) {
					Minecraft mc = Minecraft.getInstance();
					if (mc.font != null) {
						graphics.drawString(mc.font, "§aConfirm Selection ✔", btnX - 110, btnY + 4, 0xFF55FF55);
					}
				}
			}
		}

		// Highlight matching territory widgets or map POIs on the screen
		if (step.action() == TerritoryGuideController.ActionType.SELECT_TERRITORIES || step.action() == TerritoryGuideController.ActionType.OPEN_TERRITORY) {
			List<String> targets = step.targetTerritories();
			if (targets != null && !targets.isEmpty()) {
				if (mapMode) {
					highlightMapMode(screen, graphics, targets);
				} else {
					List<?> areaWidgets = getRenderAreaWidgets(screen);
					if (areaWidgets != null && !areaWidgets.isEmpty()) {
						int areaY = renderY + 16;
						int areaH = 110;
						for (Object obj : areaWidgets) {
							if (obj instanceof AbstractWidget widget) {
								String label = ChatFormatting.stripFormatting(widget.getMessage().getString()).trim();
								if (label.isEmpty()) {
									try {
										if (!widgetFieldInit) {
											widgetFieldInit = true;
											widgetTerritoryItemField = widget.getClass().getDeclaredField("territoryItem");
											widgetTerritoryItemField.setAccessible(true);
										}
										if (widgetTerritoryItemField != null) {
											Object ti = widgetTerritoryItemField.get(widget);
											if (ti != null) {
												if (tiGetNameMethod == null) {
													tiGetNameMethod = ti.getClass().getMethod("getName");
													tiGetNameMethod.setAccessible(true);
												}
												label = (String) tiGetNameMethod.invoke(ti);
											}
										}
									} catch (Throwable ignored) {
									}
								}
								if (matchesTarget(targets, label)) {
									int wx = widget.getX();
									int wy = widget.getY();
									if (wy + widget.getHeight() >= areaY && wy <= areaY + areaH) {
										TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, wx, wy, widget.getWidth(), widget.getHeight());
									}
								}
							}
						}
					}

					for (GuiEventListener child : screen.children()) {
						if (child instanceof AbstractWidget widget) {
							String label = ChatFormatting.stripFormatting(widget.getMessage().getString()).trim();
							if (matchesTarget(targets, label)) {
								TerritoryUpgradeGuideOverlay.renderTargetPulse(graphics, widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
							}
						}
					}
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, TerritoryPreset.TerritoryLoadout> readLiveLoadouts(Object holder, Set<String> liveSelectedOut) {
		Map<String, TerritoryPreset.TerritoryLoadout> loadouts = new HashMap<>();
		if (holder == null) {
			return loadouts;
		}
		try {
			Set<String> holderSelected = null;
			if (!holderSelectedFieldInit) {
				holderSelectedFieldInit = true;
				try {
					holderSelectedField = holder.getClass().getDeclaredField("selectedTerritories");
					holderSelectedField.setAccessible(true);
				} catch (Throwable ignored) {
				}
			}
			if (holderSelectedField != null) {
				try {
					holderSelected = (Set<String>) holderSelectedField.get(holder);
				} catch (Throwable ignored) {
				}
			}
			if (holderSelected != null && liveSelectedOut != null) {
				liveSelectedOut.addAll(holderSelected);
			}

			if (!holderMethodsInit) {
				holderMethodsInit = true;
				try {
					holderTerritoryItemsMethod = holder.getClass().getMethod("territoryItems");
					holderTerritoryItemsMethod.setAccessible(true);
					holderIsSelectionModeMethod = holder.getClass().getMethod("isSelectionMode");
					holderIsSelectionModeMethod.setAccessible(true);
				} catch (Throwable ignored) {
				}
			}

			if (holderTerritoryItemsMethod != null) {
				Collection<?> items = (Collection<?>) holderTerritoryItemsMethod.invoke(holder);
				if (items != null) {
					for (Object pair : items) {
						if (pairAMethod == null) {
							pairAMethod = pair.getClass().getMethod("a");
							pairAMethod.setAccessible(true);
							pairBMethod = pair.getClass().getMethod("b");
							pairBMethod.setAccessible(true);
						}
						ItemStack stack = (ItemStack) pairAMethod.invoke(pair);
						Object territoryItem = pairBMethod.invoke(pair);
						if (territoryItem != null) {
							if (tiGetNameMethod == null) {
								tiGetNameMethod = territoryItem.getClass().getMethod("getName");
								tiGetNameMethod.setAccessible(true);
							}
							if (tiIsSelectedMethod == null) {
								tiIsSelectedMethod = territoryItem.getClass().getMethod("isSelected");
								tiIsSelectedMethod.setAccessible(true);
							}
							if (tiIsPendingMethod == null) {
								try {
									tiIsPendingMethod = territoryItem.getClass().getMethod("isPending");
									tiIsPendingMethod.setAccessible(true);
								} catch (Throwable ignored) {
								}
							}
							String name = (String) tiGetNameMethod.invoke(territoryItem);
							boolean selected = (Boolean) tiIsSelectedMethod.invoke(territoryItem);
							boolean pending = false;
							if (tiIsPendingMethod != null) {
								try {
									pending = (Boolean) tiIsPendingMethod.invoke(territoryItem);
								} catch (Throwable ignored) {
								}
							}
							if (name != null) {
								String clean = TerritoryGuideController.canonicalTerritoryName(name);
								if (!clean.isEmpty()) {
									loadouts.put(clean, TerritoryContainerScanner.parseLoadout(stack));
									if (liveSelectedOut != null && ((selected && !pending) || (holderSelected != null && (holderSelected.contains(name) || holderSelected.contains(clean))))) {
										liveSelectedOut.add(clean);
									}
								}
							}
						}
					}
				}
			}
		} catch (Throwable ignored) {
		}
		return loadouts;
	}

	private static boolean isSelectionMode(Object holder) {
		if (holder == null) {
			return false;
		}
		try {
			if (holderIsSelectionModeMethod == null) {
				holderIsSelectionModeMethod = holder.getClass().getMethod("isSelectionMode");
				holderIsSelectionModeMethod.setAccessible(true);
			}
			return (Boolean) holderIsSelectionModeMethod.invoke(holder);
		} catch (Throwable ignored) {
			return false;
		}
	}

	public static void renderHudBar(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, TerritoryGuideController.GuideStep step) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		if (font == null || screen == null) {
			return;
		}

		// Top HUD Bar Dimensions
		int barWidth = Math.min(screen.width - 20, 640);
		int barHeight = 24;
		int barX = (screen.width - barWidth) / 2;
		int barY = 4;

		// Background & Border
		graphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xF012161E);
		graphics.renderOutline(barX, barY, barWidth, barHeight, 0xFF3E4A5B);

		// 1. [◀] Prev Preset
		int btnPrevX = barX + 4;
		int btnPrevY = barY + 3;
		int btnPrevW = 14;
		int btnPrevH = 18;
		renderButton(graphics, font, btnPrevX, btnPrevY, btnPrevW, btnPrevH, "<", mouseX, mouseY);

		// 2. [Preset: Name ▾] Preset Selector
		int btnSelX = btnPrevX + btnPrevW + 2;
		int btnSelY = barY + 3;
		int btnSelW = 114;
		int btnSelH = 18;
		String activeName = TerritoryGuideController.hasActivePreset() ? TerritoryGuideController.activePreset().name() : "None";
		String arrow = dropdownOpen ? "\u25B2" : "\u25BC";
		String displayPresetName = font.plainSubstrByWidth(activeName, btnSelW - 18) + " " + arrow;
		renderButton(graphics, font, btnSelX, btnSelY, btnSelW, btnSelH, displayPresetName, mouseX, mouseY);

		// 3. [▶] Next Preset
		int btnNextX = btnSelX + btnSelW + 2;
		int btnNextY = barY + 3;
		int btnNextW = 14;
		int btnNextH = 18;
		renderButton(graphics, font, btnNextX, btnNextY, btnNextW, btnNextH, ">", mouseX, mouseY);

		// 4. [Copy]
		int btnCopyX = btnNextX + btnNextW + 4;
		int btnCopyY = barY + 3;
		int btnCopyW = 40;
		int btnCopyH = 18;
		renderButton(graphics, font, btnCopyX, btnCopyY, btnCopyW, btnCopyH, "Copy", mouseX, mouseY);

		// 5. [Paste]
		int btnPasteX = btnCopyX + btnCopyW + 3;
		int btnPasteY = barY + 3;
		int btnPasteW = 40;
		int btnPasteH = 18;
		renderButton(graphics, font, btnPasteX, btnPasteY, btnPasteW, btnPasteH, "Paste", mouseX, mouseY);

		// 6. [Rename]
		int btnRenameX = btnPasteX + btnPasteW + 3;
		int btnRenameY = barY + 3;
		int btnRenameW = 46;
		int btnRenameH = 18;
		renderButton(graphics, font, btnRenameX, btnRenameY, btnRenameW, btnRenameH, "Rename", mouseX, mouseY);

		// 7. [Unselect]
		int btnUnselX = btnRenameX + btnRenameW + 3;
		int btnUnselY = barY + 3;
		int btnUnselW = 48;
		int btnUnselH = 18;
		renderButton(graphics, font, btnUnselX, btnUnselY, btnUnselW, btnUnselH, "Unselect", mouseX, mouseY);

		// 8. [Skip] (visible during all active instructions)
		boolean showSkip = TerritoryGuideController.hasActivePreset() && (step != null && step.action() != TerritoryGuideController.ActionType.NONE && step.action() != TerritoryGuideController.ActionType.ALL_COMPLETE);
		int btnSkipX = btnUnselX + btnUnselW + 3;
		int btnSkipY = barY + 3;
		int btnSkipW = 34;
		int btnSkipH = 18;
		if (showSkip) {
			renderButton(graphics, font, btnSkipX, btnSkipY, btnSkipW, btnSkipH, "Skip", mouseX, mouseY);
		}

		// Instruction label
		int textX = showSkip ? (btnSkipX + btnSkipW + 6) : (btnUnselX + btnUnselW + 6);
		int textMaxW = (barX + barWidth - 4) - textX;
		String instruction = (TerritoryGuideController.hasActivePreset() && step != null) ? step.instruction() : "§7No preset active. Select or paste a preset.";
		if (font.width(instruction) > textMaxW) {
			instruction = font.plainSubstrByWidth(instruction, textMaxW - 10) + "...";
		}
		graphics.drawString(font, instruction, textX, barY + 8, 0xFFFFFFFF);

		// Render Dropdown if open (on top of everything)
		if (dropdownOpen) {
			renderPresetDropdown(graphics, font, btnSelX, btnSelY + btnSelH + 2, mouseX, mouseY);
		}
	}

	private static void renderWynntilsOverlay(Screen screen, GuiGraphics graphics, int mouseX, int mouseY) {
		// Drawn from inside Wynntils' screen render, where an exception crashes the game every
		// time the menu opens. Skip the overlay instead, and log the first failure.
		try {
			drawWynntilsOverlay(screen, graphics, mouseX, mouseY);
		} catch (RuntimeException e) {
			if (!overlayFailureLogged) {
				overlayFailureLogged = true;
				LOGGER.warn("Territory preset overlay failed, skipping it: {}", e.toString());
			}
		}
	}

	private static void drawWynntilsOverlay(Screen screen, GuiGraphics graphics, int mouseX, int mouseY) {
		BridgeConfig config = EdenModClient.instance().config();
		if (config == null || !config.territoryMenuSuite) {
			return;
		}

		Object holder = getHolder(screen);
		Set<String> liveSelected = new HashSet<>();
		Map<String, TerritoryPreset.TerritoryLoadout> currentLoadouts = readLiveLoadouts(holder, liveSelected);
		boolean selectionMode = isSelectionMode(holder);
		String title = screen.getTitle().getString();

		TerritoryGuideController.GuideStep step = TerritoryGuideController.evaluate(currentLoadouts, liveSelected, title, selectionMode);

		renderHudBar(screen, graphics, mouseX, mouseY, step);
		highlightWidgets(screen, graphics, step);
	}

	private static void renderPresetDropdown(GuiGraphics graphics, Font font, int x, int y, int mouseX, int mouseY) {
		Map<String, String> presets = TerritoryGuideController.savedPresets();
		int count = presets.size();
		int dropW = 210;
		int headerH = 18;
		int rowH = 16;
		int dropH = headerH + (1 + Math.max(1, count)) * rowH + 4;

		graphics.fill(x, y, x + dropW, y + dropH, 0xF510141D);
		graphics.renderOutline(x, y, dropW, dropH, 0xFF3E4A5B);

		// Header
		graphics.drawString(font, "§6Saved Presets (" + count + "):", x + 6, y + 5, 0xFFFFAA00);

		int curY = y + headerH;
		String active = TerritoryGuideController.hasActivePreset() ? TerritoryGuideController.activePreset().name() : "";

		// Row 0: None (Unselect)
		boolean isNoneActive = !TerritoryGuideController.hasActivePreset();
		boolean noneHover = mouseX >= x + 4 && mouseX < x + dropW - 4 && mouseY >= curY && mouseY < curY + rowH;
		if (noneHover) {
			graphics.fill(x + 2, curY, x + dropW - 4, curY + rowH, 0xFF2A394D);
		}
		String noneBullet = isNoneActive ? "§a● " : "§7○ ";
		graphics.drawString(font, noneBullet + "§7None §8(Unselect)", x + 6, curY + 4, isNoneActive ? 0xFF55FF55 : 0xFFCCCCCC);
		curY += rowH;

		if (presets.isEmpty()) {
			graphics.drawString(font, "§7No presets saved yet.", x + 8, curY + 4, 0xFFAAAAAA);
			curY += rowH;
		} else {
			for (String pName : presets.keySet()) {
				boolean isActive = pName.equalsIgnoreCase(active);
				boolean rowHover = mouseX >= x + 4 && mouseX < x + dropW - 40 && mouseY >= curY && mouseY < curY + rowH;
				boolean renHover = mouseX >= x + dropW - 38 && mouseX < x + dropW - 22 && mouseY >= curY && mouseY < curY + rowH;
				boolean delHover = mouseX >= x + dropW - 20 && mouseX < x + dropW - 4 && mouseY >= curY && mouseY < curY + rowH;

				if (rowHover) {
					graphics.fill(x + 2, curY, x + dropW - 40, curY + rowH, 0xFF2A394D);
				}

				String bullet = isActive ? "§a● " : "§7○ ";
				String label = bullet + font.plainSubstrByWidth(pName, dropW - 54);
				graphics.drawString(font, label, x + 6, curY + 4, isActive ? 0xFF55FF55 : 0xFFDDDDDD);

				// Rename button [R]
				graphics.drawString(font, renHover ? "§e[R]" : "§8[r]", x + dropW - 38, curY + 4, renHover ? 0xFFFFAA00 : 0xFF888888);

				// Delete button [x]
				graphics.drawString(font, delHover ? "§c[X]" : "§8[x]", x + dropW - 20, curY + 4, delHover ? 0xFFFF5555 : 0xFF888888);

				curY += rowH;
			}
		}
	}

	private static void renderButton(GuiGraphics graphics, Font font, int x, int y, int w, int h, String text, int mouseX, int mouseY) {
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		int bg = hovered ? 0xFF2A394D : 0xFF18202A;
		int border = hovered ? 0xFF55FFFF : 0xFF3E4A5B;
		graphics.fill(x, y, x + w, y + h, bg);
		graphics.renderOutline(x, y, w, h, border);
		int textX = x + (w - font.width(text)) / 2;
		int textY = y + (h - 8) / 2;
		graphics.drawString(font, text, textX, textY, hovered ? 0xFF55FFFF : 0xFFDDDDDD);
	}

	public static boolean handleMouseClick(Screen screen, double mouseX, double mouseY, int button, Map<String, TerritoryPreset.TerritoryLoadout> fallbackLoadouts) {
		BridgeConfig config = EdenModClient.instance().config();
		if (config == null || !config.territoryMenuSuite || (button != 0 && button != 1)) {
			return true;
		}

		int barWidth = Math.min(screen.width - 20, 640);
		int barX = (screen.width - barWidth) / 2;
		int barY = 4;

		int btnPrevX = barX + 4;
		int btnPrevY = barY + 3;
		int btnPrevW = 14;
		int btnPrevH = 18;

		int btnSelX = btnPrevX + btnPrevW + 2;
		int btnSelY = barY + 3;
		int btnSelW = 114;
		int btnSelH = 18;

		int btnNextX = btnSelX + btnSelW + 2;
		int btnNextY = barY + 3;
		int btnNextW = 14;
		int btnNextH = 18;

		int btnCopyX = btnNextX + btnNextW + 4;
		int btnCopyY = barY + 3;
		int btnCopyW = 40;
		int btnCopyH = 18;

		int btnPasteX = btnCopyX + btnCopyW + 3;
		int btnPasteY = barY + 3;
		int btnPasteW = 40;
		int btnPasteH = 18;

		int btnRenameX = btnPasteX + btnPasteW + 3;
		int btnRenameY = barY + 3;
		int btnRenameW = 46;
		int btnRenameH = 18;

		int btnUnselX = btnRenameX + btnRenameW + 3;
		int btnUnselY = barY + 3;
		int btnUnselW = 48;
		int btnUnselH = 18;

		int btnSkipX = btnUnselX + btnUnselW + 3;
		int btnSkipY = barY + 3;
		int btnSkipW = 34;
		int btnSkipH = 18;

		Minecraft mc = Minecraft.getInstance();

		// Right click on preset selector -> Rename active preset
		if (button == 1 && mouseX >= btnSelX && mouseX < btnSelX + btnSelW && mouseY >= btnSelY && mouseY < btnSelY + btnSelH) {
			if (TerritoryGuideController.hasActivePreset()) {
				dropdownOpen = false;
				mc.setScreen(new RenamePresetScreen(screen, TerritoryGuideController.activePreset().name()));
				return false;
			}
		}

		// Handle Dropdown Clicks if open
		if (dropdownOpen) {
			Map<String, String> presets = TerritoryGuideController.savedPresets();
			int count = presets.size();
			int dropW = 210;
			int headerH = 18;
			int rowH = 16;
			int dropH = headerH + (1 + Math.max(1, count)) * rowH + 4;
			int dropX = btnSelX;
			int dropY = btnSelY + btnSelH + 2;

			if (mouseX >= dropX && mouseX < dropX + dropW && mouseY >= dropY && mouseY < dropY + dropH) {
				int curY = dropY + headerH;
				// Row 0: None (Unselect)
				if (mouseX >= dropX + 4 && mouseX < dropX + dropW - 4 && mouseY >= curY && mouseY < curY + rowH) {
					TerritoryGuideController.unselectPreset();
					dropdownOpen = false;
					if (mc.gui != null) {
						mc.gui.getChat().addMessage(Component.literal("§e[Eden] Unselected territory preset."));
					}
					return false;
				}
				curY += rowH;

				for (String pName : new ArrayList<>(presets.keySet())) {
					// Rename button [R]
					if (mouseX >= dropX + dropW - 38 && mouseX < dropX + dropW - 22 && mouseY >= curY && mouseY < curY + rowH) {
						dropdownOpen = false;
						mc.setScreen(new RenamePresetScreen(screen, pName));
						return false;
					}
					// Delete button [X]
					if (mouseX >= dropX + dropW - 20 && mouseX < dropX + dropW && mouseY >= curY && mouseY < curY + rowH) {
						dropdownOpen = false;
						mc.setScreen(new ConfirmScreen(confirmed -> {
							if (confirmed) {
								TerritoryGuideController.deleteSavedPreset(pName);
								if (mc.gui != null) {
									mc.gui.getChat().addMessage(Component.literal("§e[Eden] Deleted preset: " + pName));
								}
							}
							mc.setScreen(screen);
						}, Component.literal("Delete Territory Preset"), Component.literal("Are you sure you want to delete preset '§e" + pName + "§r'?")));
						return false;
					}
					// Row select
					if (mouseX >= dropX + 4 && mouseX < dropX + dropW - 40 && mouseY >= curY && mouseY < curY + rowH) {
						TerritoryGuideController.selectSavedPreset(pName);
						dropdownOpen = false;
						if (mc.gui != null) {
							mc.gui.getChat().addMessage(Component.literal("§a[Eden] Selected preset: " + pName));
						}
						return false;
					}
					curY += rowH;
				}
				return false;
			} else {
				// Clicked outside dropdown
				dropdownOpen = false;
				return false;
			}
		}

		// Handle [◀] Prev Preset
		if (mouseX >= btnPrevX && mouseX < btnPrevX + btnPrevW && mouseY >= btnPrevY && mouseY < btnPrevY + btnPrevH) {
			String next = TerritoryGuideController.cycleSavedPreset(-1);
			if (next != null && mc.gui != null) {
				mc.gui.getChat().addMessage(Component.literal("§a[Eden] Preset: " + next));
			}
			return false;
		}

		// Handle [Preset: Name ▾] Toggle Dropdown
		if (mouseX >= btnSelX && mouseX < btnSelX + btnSelW && mouseY >= btnSelY && mouseY < btnSelY + btnSelH) {
			dropdownOpen = !dropdownOpen;
			return false;
		}

		// Handle [▶] Next Preset
		if (mouseX >= btnNextX && mouseX < btnNextX + btnNextW && mouseY >= btnNextY && mouseY < btnNextY + btnNextH) {
			String next = TerritoryGuideController.cycleSavedPreset(1);
			if (next != null && mc.gui != null) {
				mc.gui.getChat().addMessage(Component.literal("§a[Eden] Preset: " + next));
			}
			return false;
		}

		// Handle [Copy Preset]
		if (mouseX >= btnCopyX && mouseX < btnCopyX + btnCopyW && mouseY >= btnCopyY && mouseY < btnCopyY + btnCopyH) {
			Object holder = getHolder(screen);
			Map<String, TerritoryPreset.TerritoryLoadout> currentLoadouts = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			currentLoadouts.putAll(TerritoryGuideController.sessionLoadouts());
			if (fallbackLoadouts != null) {
				currentLoadouts.putAll(fallbackLoadouts);
			}
			if (holder != null) {
				currentLoadouts.putAll(readLiveLoadouts(holder, null));
			}
			if (currentLoadouts.isEmpty()) {
				if (mc.gui != null) {
					mc.gui.getChat().addMessage(Component.literal("§c[Eden] No territories loaded yet. Wait for menu to load."));
				}
			} else {
				Map<String, TerritoryPreset.TerritoryLoadout> cleanMap = new LinkedHashMap<>();
				for (Map.Entry<String, TerritoryPreset.TerritoryLoadout> entry : currentLoadouts.entrySet()) {
					String clean = TerritoryGuideController.canonicalTerritoryName(entry.getKey());
					if (!clean.isEmpty()) {
						cleanMap.put(clean, entry.getValue());
					}
				}
				String stamp = new SimpleDateFormat("MMM d HH:mm", Locale.US).format(new Date());
				String pName = TerritoryGuideController.getUniquePresetName(TerritoryGuideController.savedPresets(), "Claim " + stamp);
				TerritoryPreset preset = new TerritoryPreset(pName, "", Set.of(), cleanMap);
				String code = TerritoryPresetCodec.encodeBase64(preset);
				mc.keyboardHandler.setClipboard(code);
				TerritoryGuideController.savePresetToStorage(pName, code);
				if (mc.gui != null) {
					mc.gui.getChat().addMessage(Component.literal("§a[Eden] Copied preset to clipboard & saved as '" + pName + "' (" + cleanMap.size() + " territories)!"));
				}
			}
			return false;
		}

		// Handle [Paste Preset]
		if (mouseX >= btnPasteX && mouseX < btnPasteX + btnPasteW && mouseY >= btnPasteY && mouseY < btnPasteY + btnPasteH) {
			String clipboard = mc.keyboardHandler.getClipboard();
			if (clipboard != null && clipboard.length() > TerritoryPresetCodec.MAX_INPUT_CHARS) {
				if (mc.gui != null) {
					mc.gui.getChat().addMessage(Component.literal("§c[Eden] Preset code in clipboard is too large (max 64 KB)."));
				}
				return false;
			}
			try {
				TerritoryPreset preset = TerritoryPresetCodec.parse(clipboard);
				if (preset != null && !preset.territories().isEmpty()) {
					TerritoryGuideController.savePresetToStorage(preset.name(), clipboard);
					if (mc.gui != null) {
						mc.gui.getChat().addMessage(Component.literal("§a[Eden] Imported preset: " + preset.name() + " (" + preset.territories().size() + " territories)!"));
					}
				} else {
					if (mc.gui != null) {
						mc.gui.getChat().addMessage(Component.literal("§c[Eden] Preset code contains no territories."));
					}
				}
			} catch (Exception e) {
				if (mc.gui != null) {
					mc.gui.getChat().addMessage(Component.literal("§c[Eden] Invalid preset in clipboard: " + e.getMessage()));
				}
			}
			return false;
		}

		// Handle [Rename]
		if (mouseX >= btnRenameX && mouseX < btnRenameX + btnRenameW && mouseY >= btnRenameY && mouseY < btnRenameY + btnRenameH) {
			if (TerritoryGuideController.hasActivePreset()) {
				dropdownOpen = false;
				mc.setScreen(new RenamePresetScreen(screen, TerritoryGuideController.activePreset().name()));
			} else {
				if (mc.gui != null) {
					mc.gui.getChat().addMessage(Component.literal("§c[Eden] No active preset to rename."));
				}
			}
			return false;
		}

		// Handle [Unselect]
		if (mouseX >= btnUnselX && mouseX < btnUnselX + btnUnselW && mouseY >= btnUnselY && mouseY < btnUnselY + btnUnselH) {
			TerritoryGuideController.unselectPreset();
			if (mc.gui != null) {
				mc.gui.getChat().addMessage(Component.literal("§e[Eden] Unselected territory preset."));
			}
			return false;
		}

		// Handle [Skip]
		if (mouseX >= btnSkipX && mouseX < btnSkipX + btnSkipW && mouseY >= btnSkipY && mouseY < btnSkipY + btnSkipH) {
			if (button == 0) {
				TerritoryGuideController.skipForward();
				if (mc.gui != null) {
					mc.gui.getChat().addMessage(Component.literal("§e[Eden] Skipped current instruction."));
				}
			} else if (button == 1) {
				TerritoryGuideController.skipBackwards();
				if (mc.gui != null) {
					mc.gui.getChat().addMessage(Component.literal("§e[Eden] Returned to previous instruction."));
				}
			}
			return false;
		}

		return true;
	}

	private static boolean handleWynntilsMouseClick(Screen screen, double mouseX, double mouseY, int button) {
		return handleMouseClick(screen, mouseX, mouseY, button, null);
	}
}
