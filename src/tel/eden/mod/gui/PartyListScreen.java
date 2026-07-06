package tel.eden.mod.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.net.PartyInfo;

public final class PartyListScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 420;
	private static final int BASE_PANEL_HEIGHT = 300;
	private static final int ROW_HEIGHT = 28;
	private static final int VISIBLE_ROWS = 8;

	private final Screen parent;
	private final EdenModClient mod;

	private final List<PartyInfo> lastKnownParties = new ArrayList<>();

	private EdenPanelLayout layout;
	private int scrollOffset;
	private boolean draggingScrollbar;

	public PartyListScreen(Screen parent, EdenModClient mod) {
		super(Component.literal("Party List"));
		this.parent = parent;
		this.mod = mod;
	}

	@Override
	protected void init() {
		super.init();
		updateReferenceSpace();
		layout = EdenPanelLayout.centered(virtualWidth, virtualHeight, BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);

		this.addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> requestPartyList()).bounds(layout.x(15), layout.y(266), layout.w(190), layout.h(20)).build());
		this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> this.minecraft.setScreen(parent)).bounds(layout.x(215), layout.y(266), layout.w(190), layout.h(20)).build());

		requestPartyList();
		refreshPartySnapshot();
	}

	private void requestPartyList() {
		if (mod.socket() != null) {
			mod.socket().sendPartyList();
		}
	}

	private void refreshPartySnapshot() {
		lastKnownParties.clear();
		lastKnownParties.addAll(mod.knownParties());
		scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, lastKnownParties.size() - VISIBLE_ROWS)));
	}

	@Override
	public void tick() {
		super.tick();
		if (!lastKnownParties.equals(mod.knownParties())) {
			refreshPartySnapshot();
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);

		this.renderMenuBackground(g);
		pushReferencePose(g);
		layout.drawBackground(g);
		layout.drawPanel(g);
		super.render(g, scaledMouseX, scaledMouseY, delta);

		g.drawCenteredString(this.font, "Active Parties", layout.centerX(), layout.y(12), 0xFFFFFFFF);

		int listLeft = layout.x(15);
		int listTop = layout.y(36);
		int listWidth = layout.w(390);
		int listHeight = layout.h(ROW_HEIGHT * VISIBLE_ROWS);
		g.fill(listLeft, listTop, listLeft + listWidth, listTop + listHeight, 0x22000000);

		if (lastKnownParties.isEmpty()) {
			g.drawCenteredString(this.font, "No active parties", layout.centerX(), layout.y(122), 0xFFAAAAAA);
		} else {
			for (int visible = 0; visible < VISIBLE_ROWS; visible++) {
				int index = scrollOffset + visible;
				if (index >= lastKnownParties.size()) {
					break;
				}

				int rowTop = layout.y(38 + visible * ROW_HEIGHT);
				int rowBottom = rowTop + layout.h(24);
				int joinLeft = layout.x(333);
				int joinRight = layout.x(389);

				g.fill(layout.x(17), rowTop, layout.x(391), rowBottom, 0x44282828);
				drawJoinButton(g, joinLeft, rowTop, joinRight - joinLeft, layout.h(24), isJoinHovered(scaledMouseX, scaledMouseY, index));

				PartyInfo party = lastKnownParties.get(index);
				String text = "#" + party.id() + " | " + party.raid() + " | Host: " + party.host() + " | [" + party.members().size() + "/" + party.max() + "]";
				g.drawString(this.font, trimToWidth(text, layout.w(306)), layout.x(24), rowTop + layout.h(8), 0xFFFFFFFF);
				g.drawCenteredString(this.font, "Join", joinLeft + (layout.w(56) / 2), rowTop + layout.h(8), 0xFFFFFFFF);
			}
		}

		layout.drawScrollbar(g, layout.x(393), listTop, layout.w(8), listHeight, VISIBLE_ROWS, lastKnownParties.size(), scrollOffset);
		popReferencePose(g);
	}

	private void drawJoinButton(GuiGraphics g, int x, int y, int width, int height, boolean hovered) {
		int fill = hovered ? 0xFF777777 : 0xFF5A5A5A;
		g.fill(x, y, x + width, y + height, fill);
		g.fill(x, y, x + width, y + 1, 0xFFD8D8D8);
		g.fill(x, y, x + 1, y + height, 0xFFD8D8D8);
		g.fill(x + width - 1, y, x + width, y + height, 0xFF222222);
		g.fill(x, y + height - 1, x + width, y + height, 0xFF222222);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
		MouseButtonEvent scaled = rescale(event);
		double mouseX = scaled.x();
		double mouseY = scaled.y();

		if (scaled.button() == 0) {
			if (isOverScrollbar(mouseX, mouseY)) {
				draggingScrollbar = true;
				updateScrollFromMouse(mouseY);
				return true;
			}

			int row = rowAt(mouseX, mouseY);
			if (row >= 0 && isJoinHovered(mouseX, mouseY, row)) {
				joinParty(row);
				return true;
			}
		}

		return super.mouseClicked(scaled, bl);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingScrollbar = false;
		return super.mouseReleased(rescale(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double d, double e) {
		MouseButtonEvent scaled = rescale(event);
		if (draggingScrollbar) {
			updateScrollFromMouse(scaled.y());
			return true;
		}
		return super.mouseDragged(scaled, d / uiScale, e / uiScale);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double d, double e) {
		double scaledMouseX = mouseX / uiScale;
		double scaledMouseY = mouseY / uiScale;
		if (!isOverList(scaledMouseX, scaledMouseY) && !isOverScrollbar(scaledMouseX, scaledMouseY)) {
			return super.mouseScrolled(scaledMouseX, scaledMouseY, d, e);
		}

		int maxOffset = Math.max(0, lastKnownParties.size() - VISIBLE_ROWS);
		if (maxOffset == 0) {
			return true;
		}

		scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset - (int) Math.signum(e)));
		return true;
	}

	private void joinParty(int index) {
		if (index < 0 || index >= lastKnownParties.size() || mod.socket() == null) {
			return;
		}
		mod.socket().sendPartyJoin(lastKnownParties.get(index).id());
		this.minecraft.setScreen(null);
	}

	private boolean isOverList(double mouseX, double mouseY) {
		return mouseX >= layout.x(15) && mouseX <= layout.x(401) && mouseY >= layout.y(36) && mouseY <= layout.y(36 + ROW_HEIGHT * VISIBLE_ROWS);
	}

	private boolean isOverScrollbar(double mouseX, double mouseY) {
		return mouseX >= layout.x(393) && mouseX <= layout.x(401) && mouseY >= layout.y(36) && mouseY <= layout.y(36 + ROW_HEIGHT * VISIBLE_ROWS);
	}

	private int rowAt(double mouseX, double mouseY) {
		if (!isOverList(mouseX, mouseY)) {
			return -1;
		}
		int row = ((int) mouseY - layout.y(36)) / layout.h(ROW_HEIGHT);
		int index = scrollOffset + row;
		return index >= 0 && index < lastKnownParties.size() ? index : -1;
	}

	private boolean isJoinHovered(double mouseX, double mouseY, int index) {
		int visibleRow = index - scrollOffset;
		if (visibleRow < 0 || visibleRow >= VISIBLE_ROWS) {
			return false;
		}
		int rowTop = layout.y(38 + visibleRow * ROW_HEIGHT);
		return mouseX >= layout.x(333) && mouseX <= layout.x(389) && mouseY >= rowTop && mouseY <= rowTop + layout.h(24);
	}

	private void updateScrollFromMouse(double mouseY) {
		int maxOffset = Math.max(0, lastKnownParties.size() - VISIBLE_ROWS);
		if (maxOffset == 0) {
			scrollOffset = 0;
			return;
		}

		int trackTop = layout.y(36);
		int trackHeight = layout.h(ROW_HEIGHT * VISIBLE_ROWS);
		int thumbHeight = Math.max(layout.h(18), Math.round(trackHeight * (VISIBLE_ROWS / (float) lastKnownParties.size())));
		double relative = mouseY - trackTop - (thumbHeight / 2.0);
		double range = Math.max(1, trackHeight - thumbHeight);
		double percent = Math.max(0.0, Math.min(1.0, relative / range));
		scrollOffset = (int) Math.round(percent * maxOffset);
	}

	private String trimToWidth(String text, int width) {
		if (this.font.width(text) <= width) {
			return text;
		}
		return this.font.plainSubstrByWidth(text, Math.max(0, width - this.font.width("..."))) + "...";
	}
}
