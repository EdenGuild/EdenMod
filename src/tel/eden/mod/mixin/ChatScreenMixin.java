package tel.eden.mod.mixin;

import com.mojang.blaze3d.platform.Window;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.chat.ChatEmoteFormatter;
import tel.eden.mod.chat.EmoteRegistry;
import tel.eden.mod.config.BridgeConfig;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
	@Shadow
	protected EditBox input;

	@Unique
	private static final int EDENMOD_MAX_EMOTE_SUGGESTIONS = 10;

	@Unique
	private static final int EDENMOD_PICKER_PADDING = 4;

	@Unique
	private static final int EDENMOD_PICKER_HEADER_HEIGHT = 32;

	@Unique
	private static final int EDENMOD_PICKER_CELL_WIDTH = 52;

	@Unique
	private static final int EDENMOD_PICKER_CELL_HEIGHT = 28;

	@Unique
	private static final int EDENMOD_PICKER_MIN_COLUMNS = 1;

	@Unique
	private static final int EDENMOD_PICKER_MAX_COLUMNS = 10;

	@Unique
	private static final int EDENMOD_PICKER_MIN_ROWS = 1;

	@Unique
	private static final int EDENMOD_PICKER_MAX_ROWS = 10;

	@Unique
	private static final int EDENMOD_PICKER_SLIDER_WIDTH = 52;

	@Unique
	private static final int EDENMOD_HEADER_ICON_SPACING = 8;

	@Unique
	private static final String EDENMOD_FAVORITE_ICON = "\u2606";

	@Unique
	private static final String EDENMOD_SETTINGS_ICON = "\u2630";

	@Unique
	private static final int EDENMOD_SETTINGS_PANEL_WIDTH = 96;

	@Unique
	private static final int EDENMOD_SETTINGS_PANEL_HEIGHT = 124;

	@Unique
	private final List<String> edenmod$emoteSuggestions = new ArrayList<>();

	@Unique
	private final List<String> edenmod$autocompletePool = new ArrayList<>();

	@Unique
	private int edenmod$selectedSuggestion;

	@Unique
	private int edenmod$suggestionScroll;

	@Unique
	private int edenmod$emoteTokenStart = -1;

	@Unique
	private int edenmod$emoteTokenEnd = -1;

	@Unique
	private String edenmod$suggestionSeed = "";

	@Unique
	private boolean edenmod$showSuggestionOverlay;

	@Unique
	private boolean edenmod$suggestionApplied;

	@Unique
	private boolean edenmod$preserveSuggestionSeed;

	@Unique
	private boolean edenmod$pickerOpen;

	@Unique
	private boolean edenmod$pickerFavoritesOnly;

	@Unique
	private int edenmod$pickerX;

	@Unique
	private int edenmod$pickerY;

	@Unique
	private int edenmod$pickerScrollRow;

	@Unique
	private boolean edenmod$pickerSettingsOpen;

	@Unique
	private SliderTarget edenmod$draggingSlider;

	@Unique
	private boolean edenmod$draggingPicker;

	@Unique
	private int edenmod$pickerDragOffsetX;

	@Unique
	private int edenmod$pickerDragOffsetY;

	@Unique
	private int edenmod$sliderPreviewColumns = -1;

	@Unique
	private int edenmod$sliderPreviewRows = -1;

	@Inject(method = "init", at = @At("TAIL"))
	private void edenmod$addEmoteFormatter(CallbackInfo ci) {
		input.addFormatter((text, cursor) -> {
			if (!edenmod$isChatEmoteUiVisible()) {
				return null;
			}
			FormattedCharSequence formatted = ChatEmoteFormatter.format(text);
			return formatted;
		});
		if (EdenModClient.instance().shouldOpenEmotePickerOnChatOpen()) {
			EdenModClient.instance().requestCenteredEmotePicker();
		}
		edenmod$openCenteredPickerIfRequested();
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void edenmod$handleChatOverlayKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (!edenmod$isChatEmoteUiVisible() && !edenmod$isChatEmoteAutocompleteEnabled()) {
			edenmod$resetOverlayState();
			return;
		}
		if (edenmod$isChatEmoteUiVisible() && edenmod$pickerOpen && event.key() == GLFW.GLFW_KEY_ESCAPE) {
			if (edenmod$pickerSettingsOpen) {
				edenmod$pickerSettingsOpen = false;
			} else {
				edenmod$pickerOpen = false;
			}
			edenmod$draggingSlider = null;
			edenmod$draggingPicker = false;
			cir.setReturnValue(true);
			return;
		}
		if (!edenmod$isChatEmoteAutocompleteEnabled()) {
			edenmod$emoteSuggestions.clear();
			edenmod$autocompletePool.clear();
			return;
		}
		edenmod$refreshEmoteSuggestions();
		if (edenmod$emoteSuggestions.isEmpty()) {
			return;
		}
		int key = event.key();
		if (key == GLFW.GLFW_KEY_TAB) {
			if (edenmod$suggestionApplied) {
				edenmod$selectedSuggestion = Math.floorMod(edenmod$selectedSuggestion + 1, edenmod$emoteSuggestions.size());
			}
			edenmod$applySuggestion(edenmod$selectedSuggestion);
			cir.setReturnValue(true);
			return;
		}
		if (key == GLFW.GLFW_KEY_UP) {
			edenmod$selectedSuggestion = Math.floorMod(edenmod$selectedSuggestion - 1, edenmod$emoteSuggestions.size());
			edenmod$ensureSuggestionVisible();
			if (edenmod$suggestionApplied) {
				edenmod$applySuggestion(edenmod$selectedSuggestion);
			}
			cir.setReturnValue(true);
			return;
		}
		if (key == GLFW.GLFW_KEY_DOWN) {
			edenmod$selectedSuggestion = Math.floorMod(edenmod$selectedSuggestion + 1, edenmod$emoteSuggestions.size());
			edenmod$ensureSuggestionVisible();
			if (edenmod$suggestionApplied) {
				edenmod$applySuggestion(edenmod$selectedSuggestion);
			}
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void edenmod$handleChatOverlayClick(MouseButtonEvent event, boolean bl, CallbackInfoReturnable<Boolean> cir) {
		if (!edenmod$isChatEmoteUiVisible() && !edenmod$isChatEmoteAutocompleteEnabled()) {
			edenmod$resetOverlayState();
			return;
		}
		if (edenmod$isChatEmoteUiVisible() && edenmod$handlePickerClick(event)) {
			cir.setReturnValue(true);
			return;
		}
		if (!edenmod$isChatEmoteAutocompleteEnabled()) {
			return;
		}
		edenmod$refreshEmoteSuggestions();
		if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || edenmod$emoteSuggestions.isEmpty()) {
			return;
		}
		int hit = edenmod$suggestionIndexAt(event.x(), event.y());
		if (hit >= 0) {
			edenmod$applySuggestion(hit);
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
	private void edenmod$handlePickerScroll(double mouseX, double mouseY, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
		if (!edenmod$isChatEmoteUiVisible()) {
			edenmod$resetOverlayState();
			return;
		}
		if (!edenmod$pickerOpen || !edenmod$isInsidePicker(mouseX, mouseY)) {
			return;
		}
		edenmod$scrollPicker(dy);
		cir.setReturnValue(true);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void edenmod$renderChatOverlays(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (!edenmod$isChatEmoteUiVisible() && !edenmod$isChatEmoteAutocompleteEnabled()) {
			edenmod$resetOverlayState();
			return;
		}
		if (edenmod$isChatEmoteUiVisible()) {
			edenmod$openCenteredPickerIfRequested();
			Window window = Minecraft.getInstance().getWindow();
			if (edenmod$draggingSlider != null && GLFW.glfwGetMouseButton(window.handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
				edenmod$commitSliderPreview();
				edenmod$draggingSlider = null;
			}
			if (edenmod$draggingPicker && GLFW.glfwGetMouseButton(window.handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
				edenmod$commitCustomPickerPosition();
				edenmod$draggingPicker = false;
			}
			if (edenmod$draggingSlider != null) {
				edenmod$updateSliderFromMouse(edenmod$draggingSlider, mouseX);
			}
			if (edenmod$draggingPicker) {
				edenmod$updatePickerDrag(mouseX, mouseY);
			}
		}
		if (edenmod$isChatEmoteAutocompleteEnabled()) {
			edenmod$refreshEmoteSuggestions();
		} else {
			edenmod$emoteSuggestions.clear();
			edenmod$autocompletePool.clear();
		}
		if (edenmod$showSuggestionOverlay && !edenmod$emoteSuggestions.isEmpty()) {
			edenmod$renderSuggestions(graphics);
		}
		if (edenmod$isChatEmoteUiVisible() && edenmod$pickerOpen) {
			edenmod$renderPicker(graphics, mouseX, mouseY);
		}
	}

	@Unique
	private void edenmod$refreshEmoteSuggestions() {
		edenmod$emoteSuggestions.clear();
		edenmod$autocompletePool.clear();
		edenmod$emoteTokenStart = -1;
		edenmod$emoteTokenEnd = -1;
		edenmod$showSuggestionOverlay = false;
		String value = input.getValue();
		if (value.startsWith("/")) {
			edenmod$resetSuggestionSession();
			return;
		}
		EmoteToken token = edenmod$findActiveEmoteToken(value, input.getCursorPosition());
		if (token == null && edenmod$preserveSuggestionSeed) {
			token = edenmod$findCompletedEmoteToken(value, input.getCursorPosition());
		}
		if (token == null) {
			edenmod$resetSuggestionSession();
			return;
		}
		String previousSeed = edenmod$suggestionSeed;
		boolean continuingAppliedCycle = edenmod$preserveSuggestionSeed && token.complete();
		String query = continuingAppliedCycle ? previousSeed : token.query();
		if (!continuingAppliedCycle && !query.equals(previousSeed)) {
			edenmod$selectedSuggestion = 0;
			edenmod$suggestionScroll = 0;
		}
		edenmod$suggestionSeed = query;
		String lowered = query.toLowerCase(java.util.Locale.ROOT);
		edenmod$autocompletePool.addAll(edenmod$autocompleteShortcodes());
		List<String> prefixMatches = new ArrayList<>();
		List<String> containsMatches = new ArrayList<>();
		for (String shortcode : edenmod$autocompletePool) {
			String candidate = shortcode.toLowerCase(java.util.Locale.ROOT);
			if (candidate.startsWith(lowered)) {
				prefixMatches.add(shortcode);
			} else if (lowered.isEmpty() || candidate.contains(lowered)) {
				containsMatches.add(shortcode);
			}
		}
		edenmod$emoteSuggestions.addAll(prefixMatches);
		edenmod$emoteSuggestions.addAll(containsMatches);
		if (edenmod$emoteSuggestions.isEmpty()) {
			edenmod$resetSuggestionSession();
			return;
		}
		edenmod$emoteTokenStart = token.start();
		edenmod$emoteTokenEnd = token.end();
		edenmod$suggestionApplied = token.complete();
		edenmod$showSuggestionOverlay = !token.complete() || continuingAppliedCycle;
		edenmod$preserveSuggestionSeed = continuingAppliedCycle;
		edenmod$selectedSuggestion = Math.max(0, Math.min(edenmod$selectedSuggestion, edenmod$emoteSuggestions.size() - 1));
		edenmod$ensureSuggestionVisible();
	}

	@Unique
	private EmoteToken edenmod$findActiveEmoteToken(String value, int cursor) {
		if (cursor < 0 || cursor > value.length()) {
			return null;
		}
		if (edenmod$isCursorAfterCompleteToken(value, cursor)) {
			return null;
		}
		int tokenStart = cursor;
		while (tokenStart > 0 && edenmod$isEmoteNameChar(value.charAt(tokenStart - 1))) {
			tokenStart--;
		}
		if (tokenStart <= 0 || value.charAt(tokenStart - 1) != ':') {
			return null;
		}
		int startColon = tokenStart - 1;
		if (!edenmod$isTokenBoundary(value, startColon - 1)) {
			return null;
		}
		int tokenEnd = cursor;
		while (tokenEnd < value.length() && edenmod$isEmoteNameChar(value.charAt(tokenEnd))) {
			tokenEnd++;
		}
		if (tokenEnd < value.length() && value.charAt(tokenEnd) == ':') {
			return null;
		}
		String query = value.substring(startColon + 1, cursor);
		return query.chars().allMatch(c -> edenmod$isEmoteNameChar((char) c)) ? new EmoteToken(startColon, tokenEnd, query, false) : null;
	}

	@Unique
	private EmoteToken edenmod$findCompletedEmoteToken(String value, int cursor) {
		if (!edenmod$isCursorAfterCompleteToken(value, cursor)) {
			return null;
		}
		int closingColon = cursor - 1;
		int nameStart = closingColon;
		while (nameStart > 0 && edenmod$isEmoteNameChar(value.charAt(nameStart - 1))) {
			nameStart--;
		}
		int startColon = nameStart - 1;
		if (startColon < 0 || value.charAt(startColon) != ':') {
			return null;
		}
		String query = value.substring(nameStart, closingColon);
		return query.isEmpty() ? null : new EmoteToken(startColon, closingColon + 1, query, true);
	}

	@Unique
	private boolean edenmod$isCursorAfterCompleteToken(String value, int cursor) {
		if (cursor <= 0 || cursor > value.length() || value.charAt(cursor - 1) != ':') {
			return false;
		}
		int closingColon = cursor - 1;
		int nameStart = closingColon;
		while (nameStart > 0 && edenmod$isEmoteNameChar(value.charAt(nameStart - 1))) {
			nameStart--;
		}
		if (nameStart == closingColon) {
			return false;
		}
		if (nameStart <= 0 || value.charAt(nameStart - 1) != ':') {
			return false;
		}
		return edenmod$isTokenBoundary(value, nameStart - 2);
	}

	@Unique
	private static boolean edenmod$isTokenBoundary(String value, int index) {
		if (index < 0) {
			return true;
		}
		char c = value.charAt(index);
		return c != ':' && !edenmod$isEmoteNameChar(c);
	}

	@Unique
	private void edenmod$applySuggestion(int index) {
		if (index < 0 || index >= edenmod$emoteSuggestions.size() || edenmod$emoteTokenStart < 0) {
			return;
		}
		String suggestion = ":" + edenmod$emoteSuggestions.get(index) + ":";
		String value = input.getValue();
		String updated = value.substring(0, edenmod$emoteTokenStart) + suggestion + value.substring(edenmod$emoteTokenEnd);
		input.setValue(updated);
		input.setCursorPosition(edenmod$emoteTokenStart + suggestion.length());
		edenmod$selectedSuggestion = index;
		edenmod$suggestionApplied = true;
		edenmod$preserveSuggestionSeed = true;
		edenmod$refreshEmoteSuggestions();
	}

	@Unique
	private void edenmod$renderSuggestions(GuiGraphics graphics) {
		Font font = Minecraft.getInstance().font;
		int rowHeight = Math.max(font.lineHeight + 4, 14);
		int width = edenmod$suggestionWidth(font);
		int x = input.getX();
		int visibleCount = Math.min(EDENMOD_MAX_EMOTE_SUGGESTIONS, edenmod$emoteSuggestions.size());
		int y = input.getY() - (visibleCount * rowHeight) - 4;
		graphics.fill(x, y, x + width, y + visibleCount * rowHeight, 0xE0101010);
		for (int row = 0; row < visibleCount; row++) {
			int index = edenmod$suggestionScroll + row;
			int rowY = y + row * rowHeight;
			if (index == edenmod$selectedSuggestion) {
				graphics.fill(x + 1, rowY + 1, x + width - 1, rowY + rowHeight - 1, 0xCC2A5A78);
			}
			String suggestion = edenmod$emoteSuggestions.get(index);
			FormattedCharSequence preview = ChatEmoteFormatter.previewComponent(":" + suggestion + ":").getVisualOrderText();
			graphics.drawString(font, preview, x + 4, rowY + 2, 0xFFFFFFFF);
			graphics.drawString(font, ":" + suggestion + ":", x + 20, rowY + 2, 0xFFFFFFFF);
		}
	}

	@Unique
	private int edenmod$suggestionWidth(Font font) {
		int width = 64;
		for (String suggestion : edenmod$emoteSuggestions) {
			width = Math.max(width, font.width(":" + suggestion + ":") + 28);
		}
		return width;
	}

	@Unique
	private int edenmod$suggestionIndexAt(double mouseX, double mouseY) {
		Font font = Minecraft.getInstance().font;
		int rowHeight = Math.max(font.lineHeight + 4, 14);
		int width = edenmod$suggestionWidth(font);
		int x = input.getX();
		int visibleCount = Math.min(EDENMOD_MAX_EMOTE_SUGGESTIONS, edenmod$emoteSuggestions.size());
		int y = input.getY() - (visibleCount * rowHeight) - 4;
		if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + visibleCount * rowHeight) {
			return -1;
		}
		int row = (int) ((mouseY - y) / rowHeight);
		int index = edenmod$suggestionScroll + row;
		return row >= 0 && row < visibleCount && index < edenmod$emoteSuggestions.size() ? index : -1;
	}

	@Unique
	private boolean edenmod$handlePickerClick(MouseButtonEvent event) {
		if (EdenModClient.instance().matchesOpenEmotePickerMouse(event)) {
			if (edenmod$pickerOpen) {
				edenmod$pickerOpen = false;
				edenmod$pickerSettingsOpen = false;
				edenmod$draggingSlider = null;
				edenmod$draggingPicker = false;
				edenmod$clearSliderPreview();
			} else {
				edenmod$openPickerForMode(event.x(), event.y());
			}
			return true;
		}
		if (!edenmod$pickerOpen) {
			return false;
		}
		if (!edenmod$isInsidePicker(event.x(), event.y())) {
			edenmod$pickerOpen = false;
			edenmod$pickerSettingsOpen = false;
			edenmod$draggingSlider = null;
			edenmod$draggingPicker = false;
			edenmod$clearSliderPreview();
			return true;
		}
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && edenmod$canDragCustomPicker(event.x(), event.y())) {
			edenmod$draggingPicker = true;
			edenmod$pickerDragOffsetX = (int) event.x() - edenmod$pickerX;
			edenmod$pickerDragOffsetY = (int) event.y() - edenmod$pickerY;
			return true;
		}
		PickerHeaderAction headerAction = edenmod$pickerHeaderActionAt(event.x(), event.y());
		if (headerAction != null && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			edenmod$handleHeaderAction(headerAction, event.x());
			return true;
		}
		if (edenmod$pickerSettingsOpen && edenmod$isInsideSettingsPanel(event.x(), event.y())) {
			return true;
		}
		int visibleIndex = edenmod$pickerVisibleIndexAt(event.x(), event.y());
		if (visibleIndex < 0) {
			return true;
		}
		List<String> visibleEntries = edenmod$visiblePickerEntries();
		if (visibleIndex >= visibleEntries.size()) {
			return true;
		}
		String shortcode = visibleEntries.get(visibleIndex);
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			edenmod$toggleFavorite(shortcode);
			return true;
		}
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			input.setFocused(true);
			input.insertText(":" + shortcode + ":");
			edenmod$refreshEmoteSuggestions();
			return true;
		}
		return true;
	}

	@Unique
	private void edenmod$handleHeaderAction(PickerHeaderAction action, double mouseX) {
		switch (action) {
			case FAVORITES -> edenmod$pickerFavoritesOnly = !edenmod$pickerFavoritesOnly;
			case TOGGLE_SETTINGS -> {
				edenmod$pickerSettingsOpen = !edenmod$pickerSettingsOpen;
				edenmod$draggingSlider = null;
				edenmod$clearSliderPreview();
			}
			case TOGGLE_AUTOCOMPLETE_FAVORITES -> edenmod$config().autocompleteFavoriteEmotes = !edenmod$config().autocompleteFavoriteEmotes;
			case COLUMNS_SLIDER -> {
				edenmod$draggingSlider = SliderTarget.COLUMNS;
				edenmod$updateSliderFromMouse(SliderTarget.COLUMNS, mouseX);
			}
			case ROWS_SLIDER -> {
				edenmod$draggingSlider = SliderTarget.ROWS;
				edenmod$updateSliderFromMouse(SliderTarget.ROWS, mouseX);
			}
			case CYCLE_OPEN_MODE -> edenmod$config().emotePickerOpenMode = edenmod$nextOpenMode(edenmod$config().emotePickerOpenMode);
		}
		if (action == PickerHeaderAction.FAVORITES || action == PickerHeaderAction.TOGGLE_SETTINGS || action == PickerHeaderAction.TOGGLE_AUTOCOMPLETE_FAVORITES || action == PickerHeaderAction.CYCLE_OPEN_MODE) {
			edenmod$config().save();
			edenmod$pickerScrollRow = Math.min(edenmod$pickerScrollRow, edenmod$maxPickerScrollRows());
			edenmod$clampPickerToWindow();
		}
	}

	@Unique
	private void edenmod$renderPicker(GuiGraphics graphics, int mouseX, int mouseY) {
		edenmod$clampPickerToWindow();
		Font font = Minecraft.getInstance().font;
		List<String> visibleEntries = edenmod$visiblePickerEntries();
		int panelWidth = edenmod$pickerPanelWidth();
		int panelHeight = edenmod$pickerPanelHeight();
		graphics.fill(edenmod$pickerX, edenmod$pickerY, edenmod$pickerX + panelWidth, edenmod$pickerY + panelHeight, 0xEE111111);
		graphics.fill(edenmod$pickerX, edenmod$pickerY, edenmod$pickerX + panelWidth, edenmod$pickerY + 1, 0xFF787878);
		graphics.drawString(font, edenmod$pickerFavoritesOnly ? "Favorites" : "Emotes", edenmod$pickerX + EDENMOD_PICKER_PADDING, edenmod$pickerY + 5, 0xFFE0E0E0);
		graphics.drawString(font, EDENMOD_SETTINGS_ICON, edenmod$wrenchButtonX(), edenmod$pickerY + 4, edenmod$pickerSettingsOpen ? 0xFFE0E0E0 : 0xFFB0B0B0);
		graphics.drawString(font, EDENMOD_FAVORITE_ICON, edenmod$favoriteButtonX(), edenmod$pickerY + 4, edenmod$pickerFavoritesOnly ? 0xFFFFD75E : 0xFFB0B0B0);
		for (int i = 0; i < visibleEntries.size(); i++) {
			int col = i % edenmod$pickerColumns();
			int row = i / edenmod$pickerColumns();
			int cellX = edenmod$pickerGridX() + col * EDENMOD_PICKER_CELL_WIDTH;
			int cellY = edenmod$pickerGridY() + row * EDENMOD_PICKER_CELL_HEIGHT;
			boolean hovered = mouseX >= cellX && mouseX < cellX + EDENMOD_PICKER_CELL_WIDTH - 2 && mouseY >= cellY && mouseY < cellY + EDENMOD_PICKER_CELL_HEIGHT - 2;
			graphics.fill(cellX, cellY, cellX + EDENMOD_PICKER_CELL_WIDTH - 2, cellY + EDENMOD_PICKER_CELL_HEIGHT - 2, hovered ? 0xCC2A5A78 : 0x66222222);
			String shortcode = visibleEntries.get(i);
			FormattedCharSequence preview = ChatEmoteFormatter.previewComponent(":" + shortcode + ":").getVisualOrderText();
			int previewWidth = font.width(preview);
			int previewX = cellX + Math.max(2, (EDENMOD_PICKER_CELL_WIDTH - 2 - previewWidth) / 2);
			graphics.drawString(font, preview, previewX, cellY + 2, 0xFFFFFFFF);
			String label = edenmod$clip(font, shortcode, EDENMOD_PICKER_CELL_WIDTH - 6);
			int labelX = cellX + Math.max(2, (EDENMOD_PICKER_CELL_WIDTH - 2 - font.width(label)) / 2);
			graphics.drawString(font, label, labelX, cellY + 17, edenmod$isFavorite(shortcode) ? 0xFFFFD75E : 0xFFCCCCCC);
		}
		if (visibleEntries.isEmpty()) {
			String empty = edenmod$pickerFavoritesOnly ? "No favorite emotes yet" : "No emotes loaded";
			graphics.drawString(font, empty, edenmod$pickerGridX(), edenmod$pickerGridY() + 4, 0xFFAAAAAA);
			if (edenmod$pickerFavoritesOnly) {
				graphics.drawString(font, "Right-click an emote", edenmod$pickerGridX(), edenmod$pickerGridY() + 32, 0xFF888888);
				graphics.drawString(font, "to add it here.", edenmod$pickerGridX(), edenmod$pickerGridY() + 44, 0xFF888888);
			}
		}
		if (edenmod$maxPickerScrollRows() > 0) {
			String scrollText = (edenmod$pickerScrollRow + 1) + "/" + (edenmod$maxPickerScrollRows() + 1);
			graphics.drawString(font, scrollText, edenmod$pickerX + panelWidth - EDENMOD_PICKER_PADDING - font.width(scrollText), edenmod$pickerY + panelHeight - font.lineHeight - 2, 0xFF888888);
		}
		if (edenmod$pickerSettingsOpen) {
			edenmod$renderSettingsPanel(graphics, font);
		}
	}

	@Unique
	private void edenmod$renderSettingsPanel(GuiGraphics graphics, Font font) {
		int panelX = edenmod$settingsPanelX();
		int panelY = edenmod$settingsPanelY();
		int panelW = EDENMOD_SETTINGS_PANEL_WIDTH;
		int panelH = EDENMOD_SETTINGS_PANEL_HEIGHT;
		graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xEE111111);
		graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, 0xFF787878);
		graphics.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, 0xFF5C5C5C);
		graphics.fill(panelX, panelY, panelX + 1, panelY + panelH, 0xFF8E8E8E);
		graphics.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, 0xFF5C5C5C);
		graphics.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 2, 0xFFC6C6C6);
		graphics.fill(panelX + 1, panelY + panelH - 2, panelX + panelW - 1, panelY + panelH - 1, 0xFF3E3E3E);
		graphics.drawString(font, "Grid", panelX + 6, panelY + 5, 0xFFE0E0E0);
		graphics.drawString(font, "Columns", panelX + 8, panelY + 18, 0xFFE0E0E0);
		graphics.drawString(font, Integer.toString(edenmod$displayedColumns()), panelX + panelW - 14, panelY + 18, 0xFFE0E0E0);
		edenmod$drawSlider(graphics, panelX + 8, panelY + 30, edenmod$displayedColumns(), EDENMOD_PICKER_MIN_COLUMNS, EDENMOD_PICKER_MAX_COLUMNS);
		graphics.drawString(font, "Rows", panelX + 8, panelY + 44, 0xFFE0E0E0);
		graphics.drawString(font, Integer.toString(edenmod$displayedRows()), panelX + panelW - 14, panelY + 44, 0xFFE0E0E0);
		edenmod$drawSlider(graphics, panelX + 8, panelY + 56, edenmod$displayedRows(), EDENMOD_PICKER_MIN_ROWS, EDENMOD_PICKER_MAX_ROWS);
		graphics.drawString(font, "Autocomplete", panelX + 8, panelY + 72, 0xFFE0E0E0);
		String autoText = edenmod$config().autocompleteFavoriteEmotes ? "Favorites Only" : "All Emotes";
		graphics.drawString(font, autoText, panelX + 8, panelY + 84, edenmod$config().autocompleteFavoriteEmotes ? 0xFFFFD75E : 0xFFB0B0B0);
		graphics.drawString(font, "Open At", panelX + 8, panelY + 98, 0xFFE0E0E0);
		graphics.drawString(font, edenmod$config().emotePickerOpenMode.label(), panelX + 8, panelY + 110, 0xFFFFD75E);
	}

	@Unique
	private void edenmod$drawSlider(GuiGraphics graphics, int x, int y, int value, int min, int max) {
		int height = 8;
		graphics.fill(x, y, x + EDENMOD_PICKER_SLIDER_WIDTH, y + height, 0x2A000000);
		graphics.fill(x, y, x + EDENMOD_PICKER_SLIDER_WIDTH, y + 1, 0xFF4A4A4A);
		graphics.fill(x, y, x + 1, y + height, 0xFF4A4A4A);
		graphics.fill(x + EDENMOD_PICKER_SLIDER_WIDTH - 1, y, x + EDENMOD_PICKER_SLIDER_WIDTH, y + height, 0xFF1E1E1E);
		graphics.fill(x, y + height - 1, x + EDENMOD_PICKER_SLIDER_WIDTH, y + height, 0xFF1E1E1E);
		int knobWidth = 6;
		int knobX = x + Math.round((value - min) * (EDENMOD_PICKER_SLIDER_WIDTH - knobWidth - 2) / (float) Math.max(1, max - min)) + 1;
		graphics.fill(knobX, y + 1, knobX + knobWidth, y + height - 1, 0xFF8A8A8A);
		graphics.fill(knobX, y + 1, knobX + knobWidth, y + 2, 0xFFD8D8D8);
		graphics.fill(knobX, y + height - 2, knobX + knobWidth, y + height - 1, 0xFF444444);
	}

	@Unique
	private void edenmod$openPicker(double mouseX, double mouseY) {
		edenmod$pickerOpen = true;
		edenmod$pickerSettingsOpen = false;
		edenmod$pickerScrollRow = 0;
		edenmod$pickerX = (int) mouseX;
		edenmod$pickerY = (int) mouseY;
		edenmod$clampPickerToWindow();
	}

	@Unique
	private void edenmod$openPickerForMode(double mouseX, double mouseY) {
		BridgeConfig config = edenmod$config();
		switch (config.emotePickerOpenMode) {
			case CENTER -> {
				Minecraft mc = Minecraft.getInstance();
				double centerX = mc.getWindow().getGuiScaledWidth() / 2.0 - edenmod$pickerPanelWidth() / 2.0;
				double centerY = mc.getWindow().getGuiScaledHeight() / 2.0 - edenmod$pickerPanelHeight() / 2.0;
				edenmod$openPicker(centerX, centerY);
			}
			case CUSTOM -> {
				if (config.emotePickerCustomX >= 0 && config.emotePickerCustomY >= 0) {
					edenmod$openPicker(config.emotePickerCustomX, config.emotePickerCustomY);
				} else {
					Minecraft mc = Minecraft.getInstance();
					double centerX = mc.getWindow().getGuiScaledWidth() / 2.0 - edenmod$pickerPanelWidth() / 2.0;
					double centerY = mc.getWindow().getGuiScaledHeight() / 2.0 - edenmod$pickerPanelHeight() / 2.0;
					edenmod$openPicker(centerX, centerY);
				}
			}
			case CURSOR -> edenmod$openPicker(mouseX, mouseY);
		}
	}

	@Unique
	private void edenmod$openCenteredPickerIfRequested() {
		if (!edenmod$isChatEmoteUiVisible() || !EdenModClient.instance().consumeCenteredEmotePickerRequest()) {
			return;
		}
		double[] mouse = edenmod$currentMousePosition();
		edenmod$openPickerForMode(mouse[0], mouse[1]);
	}

	@Unique
	private double[] edenmod$currentMousePosition() {
		Minecraft mc = Minecraft.getInstance();
		Window window = mc.getWindow();
		double[] rawX = new double[1];
		double[] rawY = new double[1];
		GLFW.glfwGetCursorPos(window.handle(), rawX, rawY);
		double scaledX = rawX[0] * window.getGuiScaledWidth() / window.getWidth();
		double scaledY = rawY[0] * window.getGuiScaledHeight() / window.getHeight();
		return new double[] { scaledX, scaledY };
	}

	@Unique
	private void edenmod$scrollPicker(double dy) {
		int maxScroll = edenmod$maxPickerScrollRows();
		if (maxScroll <= 0) {
			edenmod$pickerScrollRow = 0;
			return;
		}
		int delta = dy > 0 ? -1 : 1;
		edenmod$pickerScrollRow = Math.max(0, Math.min(maxScroll, edenmod$pickerScrollRow + delta));
	}

	@Unique
	private List<String> edenmod$pickerEntries() {
		List<String> all = EmoteRegistry.shortcodes();
		if (!edenmod$pickerFavoritesOnly) {
			return all;
		}
		Set<String> favorites = new LinkedHashSet<>(edenmod$config().favoriteEmotes);
		return all.stream().filter(favorites::contains).toList();
	}

	@Unique
	private List<String> edenmod$visiblePickerEntries() {
		List<String> entries = edenmod$pickerEntries();
		int pageSize = edenmod$pickerColumns() * edenmod$pickerRows();
		int from = Math.min(entries.size(), edenmod$pickerScrollRow * pageSize);
		int to = Math.min(entries.size(), from + pageSize);
		return entries.subList(from, to);
	}

	@Unique
	private int edenmod$maxPickerScrollRows() {
		int pageSize = edenmod$pickerColumns() * edenmod$pickerRows();
		return Math.max(0, (int) Math.ceil(edenmod$pickerEntries().size() / (double) pageSize) - 1);
	}

	@Unique
	private int edenmod$pickerColumns() {
		return Math.max(EDENMOD_PICKER_MIN_COLUMNS, Math.min(EDENMOD_PICKER_MAX_COLUMNS, edenmod$config().emotePickerColumns));
	}

	@Unique
	private int edenmod$pickerRows() {
		return Math.max(EDENMOD_PICKER_MIN_ROWS, Math.min(EDENMOD_PICKER_MAX_ROWS, edenmod$config().emotePickerRows));
	}

	@Unique
	private int edenmod$pickerPanelWidth() {
		return edenmod$pickerColumns() * EDENMOD_PICKER_CELL_WIDTH + EDENMOD_PICKER_PADDING * 2;
	}

	@Unique
	private int edenmod$pickerPanelHeight() {
		return EDENMOD_PICKER_HEADER_HEIGHT + edenmod$pickerRows() * EDENMOD_PICKER_CELL_HEIGHT + EDENMOD_PICKER_PADDING * 2;
	}

	@Unique
	private int edenmod$pickerGridX() {
		return edenmod$pickerX + EDENMOD_PICKER_PADDING;
	}

	@Unique
	private int edenmod$pickerGridY() {
		return edenmod$pickerY + EDENMOD_PICKER_HEADER_HEIGHT;
	}

	@Unique
	private int edenmod$favoriteButtonX() {
		Font font = Minecraft.getInstance().font;
		return edenmod$wrenchButtonX() + font.width(EDENMOD_SETTINGS_ICON) + EDENMOD_HEADER_ICON_SPACING;
	}

	@Unique
	private int edenmod$wrenchButtonX() {
		Font font = Minecraft.getInstance().font;
		return edenmod$pickerX + edenmod$pickerPanelWidth() - EDENMOD_PICKER_PADDING - font.width(EDENMOD_FAVORITE_ICON) - EDENMOD_HEADER_ICON_SPACING - font.width(EDENMOD_SETTINGS_ICON);
	}

	@Unique
	private int edenmod$settingsPanelX() {
		return edenmod$pickerX + edenmod$pickerPanelWidth() + 6;
	}

	@Unique
	private int edenmod$settingsPanelY() {
		return edenmod$pickerY;
	}

	@Unique
	private boolean edenmod$canDragCustomPicker(double mouseX, double mouseY) {
		if (edenmod$config().emotePickerOpenMode != BridgeConfig.EmotePickerOpenMode.CUSTOM) {
			return false;
		}
		if (mouseY < edenmod$pickerY + 3 || mouseY > edenmod$pickerY + EDENMOD_PICKER_HEADER_HEIGHT - 2) {
			return false;
		}
		return mouseX >= edenmod$pickerX + EDENMOD_PICKER_PADDING && mouseX < edenmod$wrenchButtonX() - 4;
	}

	@Unique
	private int edenmod$displayedColumns() {
		return edenmod$sliderPreviewColumns >= 0 ? edenmod$sliderPreviewColumns : edenmod$config().emotePickerColumns;
	}

	@Unique
	private int edenmod$displayedRows() {
		return edenmod$sliderPreviewRows >= 0 ? edenmod$sliderPreviewRows : edenmod$config().emotePickerRows;
	}

	@Unique
	private void edenmod$clampPickerToWindow() {
		Minecraft mc = Minecraft.getInstance();
		int maxWidth = edenmod$pickerPanelWidth() + (edenmod$pickerSettingsOpen ? EDENMOD_SETTINGS_PANEL_WIDTH + 6 : 0);
		edenmod$pickerX = Math.max(2, Math.min(edenmod$pickerX, mc.getWindow().getGuiScaledWidth() - maxWidth - 2));
		edenmod$pickerY = Math.max(2, Math.min(edenmod$pickerY, mc.getWindow().getGuiScaledHeight() - edenmod$pickerPanelHeight() - 2));
	}

	@Unique
	private boolean edenmod$isInsidePicker(double mouseX, double mouseY) {
		boolean insideMain = mouseX >= edenmod$pickerX && mouseX <= edenmod$pickerX + edenmod$pickerPanelWidth() && mouseY >= edenmod$pickerY && mouseY <= edenmod$pickerY + edenmod$pickerPanelHeight();
		if (insideMain) {
			return true;
		}
		return edenmod$isInsideSettingsPanel(mouseX, mouseY);
	}

	@Unique
	private boolean edenmod$isInsideSettingsPanel(double mouseX, double mouseY) {
		return edenmod$pickerSettingsOpen && mouseX >= edenmod$settingsPanelX() && mouseX <= edenmod$settingsPanelX() + EDENMOD_SETTINGS_PANEL_WIDTH && mouseY >= edenmod$settingsPanelY() && mouseY <= edenmod$settingsPanelY() + EDENMOD_SETTINGS_PANEL_HEIGHT;
	}

	@Unique
	private int edenmod$pickerVisibleIndexAt(double mouseX, double mouseY) {
		if (mouseX < edenmod$pickerGridX() || mouseY < edenmod$pickerGridY()) {
			return -1;
		}
		int col = (int) ((mouseX - edenmod$pickerGridX()) / EDENMOD_PICKER_CELL_WIDTH);
		int row = (int) ((mouseY - edenmod$pickerGridY()) / EDENMOD_PICKER_CELL_HEIGHT);
		if (col < 0 || col >= edenmod$pickerColumns() || row < 0 || row >= edenmod$pickerRows()) {
			return -1;
		}
		return row * edenmod$pickerColumns() + col;
	}

	@Unique
	private PickerHeaderAction edenmod$pickerHeaderActionAt(double mouseX, double mouseY) {
		Font font = Minecraft.getInstance().font;
		if (mouseY >= edenmod$pickerY + 3 && mouseY <= edenmod$pickerY + EDENMOD_PICKER_HEADER_HEIGHT - 2) {
			int favoritesX = edenmod$favoriteButtonX();
			if (mouseX >= favoritesX - 1 && mouseX <= favoritesX + font.width(EDENMOD_FAVORITE_ICON) + 1) {
				return PickerHeaderAction.FAVORITES;
			}
			int wrenchX = edenmod$wrenchButtonX();
			if (mouseX >= wrenchX - 1 && mouseX <= wrenchX + font.width(EDENMOD_SETTINGS_ICON) + 1) {
				return PickerHeaderAction.TOGGLE_SETTINGS;
			}
		}
		if (!edenmod$pickerSettingsOpen) {
			return null;
		}
		int sliderX = edenmod$settingsPanelX() + 8;
		if (mouseY >= edenmod$settingsPanelY() + 28 && mouseY <= edenmod$settingsPanelY() + 40 && mouseX >= sliderX - 3 && mouseX <= sliderX + EDENMOD_PICKER_SLIDER_WIDTH + 3) {
			return PickerHeaderAction.COLUMNS_SLIDER;
		}
		if (mouseY >= edenmod$settingsPanelY() + 54 && mouseY <= edenmod$settingsPanelY() + 66 && mouseX >= sliderX - 3 && mouseX <= sliderX + EDENMOD_PICKER_SLIDER_WIDTH + 3) {
			return PickerHeaderAction.ROWS_SLIDER;
		}
		if (mouseY >= edenmod$settingsPanelY() + 80 && mouseY <= edenmod$settingsPanelY() + 92 && mouseX >= sliderX - 3 && mouseX <= sliderX + EDENMOD_SETTINGS_PANEL_WIDTH - 16) {
			return PickerHeaderAction.TOGGLE_AUTOCOMPLETE_FAVORITES;
		}
		if (mouseY >= edenmod$settingsPanelY() + 106 && mouseY <= edenmod$settingsPanelY() + 118 && mouseX >= sliderX - 3 && mouseX <= sliderX + EDENMOD_SETTINGS_PANEL_WIDTH - 16) {
			return PickerHeaderAction.CYCLE_OPEN_MODE;
		}
		return null;
	}

	@Unique
	private BridgeConfig.EmotePickerOpenMode edenmod$nextOpenMode(BridgeConfig.EmotePickerOpenMode current) {
		BridgeConfig.EmotePickerOpenMode[] values = BridgeConfig.EmotePickerOpenMode.values();
		return values[(current.ordinal() + 1) % values.length];
	}

	@Unique
	private void edenmod$updateSliderFromMouse(SliderTarget target, double mouseX) {
		int sliderX = edenmod$settingsPanelX() + 8;
		double percent = Math.max(0.0, Math.min(1.0, (mouseX - (sliderX + 1)) / Math.max(1.0, EDENMOD_PICKER_SLIDER_WIDTH - 6)));
		int min = target == SliderTarget.COLUMNS ? EDENMOD_PICKER_MIN_COLUMNS : EDENMOD_PICKER_MIN_ROWS;
		int max = target == SliderTarget.COLUMNS ? EDENMOD_PICKER_MAX_COLUMNS : EDENMOD_PICKER_MAX_ROWS;
		int value = min + (int) Math.round(percent * (max - min));
		if (target == SliderTarget.COLUMNS) {
			edenmod$sliderPreviewColumns = value;
		} else {
			edenmod$sliderPreviewRows = value;
		}
	}

	@Unique
	private void edenmod$updatePickerDrag(double mouseX, double mouseY) {
		edenmod$pickerX = (int) mouseX - edenmod$pickerDragOffsetX;
		edenmod$pickerY = (int) mouseY - edenmod$pickerDragOffsetY;
		edenmod$clampPickerToWindow();
	}

	@Unique
	private void edenmod$commitSliderPreview() {
		BridgeConfig config = edenmod$config();
		if (edenmod$sliderPreviewColumns >= 0) {
			config.emotePickerColumns = edenmod$sliderPreviewColumns;
		}
		if (edenmod$sliderPreviewRows >= 0) {
			config.emotePickerRows = edenmod$sliderPreviewRows;
		}
		config.save();
		edenmod$pickerScrollRow = Math.min(edenmod$pickerScrollRow, edenmod$maxPickerScrollRows());
		edenmod$clampPickerToWindow();
		edenmod$clearSliderPreview();
	}

	@Unique
	private void edenmod$clearSliderPreview() {
		edenmod$sliderPreviewColumns = -1;
		edenmod$sliderPreviewRows = -1;
	}

	@Unique
	private void edenmod$commitCustomPickerPosition() {
		if (edenmod$config().emotePickerOpenMode != BridgeConfig.EmotePickerOpenMode.CUSTOM) {
			return;
		}
		BridgeConfig config = edenmod$config();
		config.emotePickerCustomX = edenmod$pickerX;
		config.emotePickerCustomY = edenmod$pickerY;
		config.save();
	}

	@Unique
	private void edenmod$ensureSuggestionVisible() {
		int maxScroll = Math.max(0, edenmod$emoteSuggestions.size() - EDENMOD_MAX_EMOTE_SUGGESTIONS);
		if (edenmod$selectedSuggestion < edenmod$suggestionScroll) {
			edenmod$suggestionScroll = edenmod$selectedSuggestion;
		} else if (edenmod$selectedSuggestion >= edenmod$suggestionScroll + EDENMOD_MAX_EMOTE_SUGGESTIONS) {
			edenmod$suggestionScroll = edenmod$selectedSuggestion - EDENMOD_MAX_EMOTE_SUGGESTIONS + 1;
		}
		edenmod$suggestionScroll = Math.max(0, Math.min(maxScroll, edenmod$suggestionScroll));
	}

	@Unique
	private void edenmod$resetSuggestionSession() {
		edenmod$selectedSuggestion = 0;
		edenmod$suggestionScroll = 0;
		edenmod$suggestionSeed = "";
		edenmod$showSuggestionOverlay = false;
		edenmod$suggestionApplied = false;
		edenmod$preserveSuggestionSeed = false;
	}

	@Unique
	private void edenmod$resetOverlayState() {
		edenmod$pickerOpen = false;
		edenmod$pickerSettingsOpen = false;
		edenmod$draggingSlider = null;
		edenmod$draggingPicker = false;
		edenmod$clearSliderPreview();
		edenmod$emoteSuggestions.clear();
		edenmod$autocompletePool.clear();
		edenmod$emoteTokenStart = -1;
		edenmod$emoteTokenEnd = -1;
		edenmod$resetSuggestionSession();
	}

	@Unique
	private List<String> edenmod$autocompleteShortcodes() {
		if (!edenmod$config().autocompleteFavoriteEmotes) {
			return EmoteRegistry.shortcodes();
		}
		Set<String> favorites = new LinkedHashSet<>(edenmod$config().favoriteEmotes);
		return EmoteRegistry.shortcodes().stream().filter(code -> favorites.stream().anyMatch(fav -> fav.equalsIgnoreCase(code))).toList();
	}

	@Unique
	private void edenmod$toggleFavorite(String shortcode) {
		BridgeConfig config = edenmod$config();
		if (config.favoriteEmotes.removeIf(code -> code.equalsIgnoreCase(shortcode))) {
			config.save();
			edenmod$pickerScrollRow = Math.min(edenmod$pickerScrollRow, edenmod$maxPickerScrollRows());
			return;
		}
		config.favoriteEmotes.add(shortcode);
		config.favoriteEmotes.sort(String.CASE_INSENSITIVE_ORDER);
		config.save();
	}

	@Unique
	private boolean edenmod$isFavorite(String shortcode) {
		for (String favorite : edenmod$config().favoriteEmotes) {
			if (favorite.equalsIgnoreCase(shortcode)) {
				return true;
			}
		}
		return false;
	}

	@Unique
	private BridgeConfig edenmod$config() {
		return EdenModClient.instance().config();
	}

	@Unique
	private boolean edenmod$isChatEmoteUiVisible() {
		return switch (edenmod$config().chatEmoteToolsMode) {
			case UI, UI_AND_AUTO -> true;
			case AUTO, NONE -> false;
		};
	}

	@Unique
	private boolean edenmod$isChatEmoteAutocompleteEnabled() {
		return switch (edenmod$config().chatEmoteToolsMode) {
			case AUTO, UI_AND_AUTO -> true;
			case UI, NONE -> false;
		};
	}

	@Unique
	private static String edenmod$clip(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String out = text;
		while (out.length() > 1 && font.width(out + "...") > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}
		return out + "...";
	}

	@Unique
	private static boolean edenmod$isEmoteNameChar(char c) {
		return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '+' || c == '-';
	}

	@Unique
	private record EmoteToken(int start, int end, String query, boolean complete) {
	}

	@Unique
	private enum PickerHeaderAction {
		FAVORITES, TOGGLE_SETTINGS, TOGGLE_AUTOCOMPLETE_FAVORITES, COLUMNS_SLIDER, ROWS_SLIDER, CYCLE_OPEN_MODE
	}

	@Unique
	private enum SliderTarget {
		COLUMNS, ROWS
	}
}
