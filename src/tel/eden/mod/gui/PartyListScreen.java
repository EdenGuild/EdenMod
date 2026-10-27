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
	private static final int ROW_HEIGHT = 44;
	private static final int VISIBLE_ROWS = 5;

	private final Screen parent;
	private final EdenModClient mod;

	private final List<PartyInfo> lastKnownParties = new ArrayList<>();
	private List<PartyInfo> rawKnownParties = List.of();

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
		rawKnownParties = mod != null ? mod.knownParties() : List.of();
		lastKnownParties.clear();
		lastKnownParties.addAll(rawKnownParties);
		lastKnownParties.sort(java.util.Comparator.comparing(PartyListScreen::isFull));
		scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, lastKnownParties.size() - VISIBLE_ROWS)));
	}

	private static boolean isFull(PartyInfo party) {
		return party.size() >= party.max();
	}

	@Override
	public void tick() {
		super.tick();
		if (mod != null && !rawKnownParties.equals(mod.knownParties())) {
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

		PartyInfo hoveredParty = null;

		if (lastKnownParties.isEmpty()) {
			g.drawCenteredString(this.font, "No active parties", layout.centerX(), layout.y(122), 0xFFAAAAAA);
		} else {
			for (int visible = 0; visible < VISIBLE_ROWS; visible++) {
				int index = scrollOffset + visible;
				if (index >= lastKnownParties.size()) {
					break;
				}

				PartyInfo party = lastKnownParties.get(index);
				int cardTop = layout.y(38 + visible * ROW_HEIGHT);
				int cardBottom = cardTop + layout.h(40);
				int cardLeft = layout.x(17);
				int cardRight = layout.x(391);

				boolean cardHovered = scaledMouseX >= cardLeft && scaledMouseX <= cardRight && scaledMouseY >= cardTop && scaledMouseY <= cardBottom;
				if (cardHovered) {
					hoveredParty = party;
				}

				// Card background and subtle borders
				g.fill(cardLeft, cardTop, cardRight, cardBottom, cardHovered ? 0x55353535 : 0x44252525);
				g.fill(cardLeft, cardTop, cardRight, cardTop + 1, cardHovered ? 0x44FFFFFF : 0x22FFFFFF);
				g.fill(cardLeft, cardBottom - 1, cardRight, cardBottom, 0x22000000);

				// Right side: Join / Full / In Party button
				int btnLeft = layout.x(335);
				int btnRight = layout.x(387);
				int btnWidth = btnRight - btnLeft;
				int btnTop = cardTop + layout.h(9);
				int btnHeight = layout.h(22);

				boolean isSelf = mod != null && party.members().stream().anyMatch(m -> m.equalsIgnoreCase(mod.playerName()));
				boolean isFull = isFull(party);
				boolean joinHovered = cardHovered && scaledMouseX >= btnLeft && scaledMouseX <= btnRight && scaledMouseY >= btnTop && scaledMouseY <= btnTop + btnHeight;

				String btnLabel;
				int btnTextColor;
				boolean btnActive;
				if (isSelf) {
					btnLabel = "In Party";
					btnTextColor = 0xFFAAAAAA;
					btnActive = false;
				} else if (isFull) {
					btnLabel = "Full";
					btnTextColor = 0xFFFF7777;
					btnActive = false;
				} else {
					btnLabel = "Join";
					btnTextColor = 0xFFFFFFFF;
					btnActive = true;
				}
				drawJoinButton(g, btnLeft, btnTop, btnWidth, btnHeight, btnLabel, btnTextColor, btnActive, joinHovered && btnActive);

				// Occupancy count badge before the button
				int badgeRight = layout.x(330);
				String countStr = party.size() + "/" + party.max();
				int countWidth = this.font.width(countStr);
				g.drawString(this.font, countStr, badgeRight - countWidth, cardTop + layout.h(16), isFull ? 0xFFFF6666 : 0xFF55FF55);

				// Text lines on left
				int textLeft = layout.x(22);
				int textWidth = badgeRight - countWidth - textLeft - layout.w(8);

				// Line 1: Scope, ID, World, Raid name
				String scope = party.ally() ? "§b[Ally] " : "§6[Guild] ";
				String worldStr = party.world() != null && !party.world().isBlank() ? "§a[" + party.world() + "] " : "";
				String line1 = scope + "§f#" + party.id() + " " + worldStr + "§f§l" + party.raid();
				g.drawString(this.font, trimToWidth(line1, textWidth), textLeft, cardTop + layout.h(5), 0xFFFFFFFF);

				// Line 2: Host & Members
				String hostStr = "§eHost: §f" + party.displayMember(0);
				String line2;
				if (party.size() > 1) {
					List<String> others = new ArrayList<>();
					for (int i = 1; i < party.size(); i++) {
						others.add(party.displayMember(i));
					}
					line2 = hostStr + " §7| §bMembers: §f" + String.join(", ", others);
				} else {
					line2 = hostStr + " §7| §8(Waiting for members)";
				}
				g.drawString(this.font, trimToWidth(line2, textWidth), textLeft, cardTop + layout.h(17), 0xFFFFFFFF);

				// Line 3: Note (if present)
				if (party.note() != null && !party.note().isBlank()) {
					String line3 = "§8Note: §7" + party.note();
					g.drawString(this.font, trimToWidth(line3, textWidth), textLeft, cardTop + layout.h(28), 0xFFFFFFFF);
				}
			}
		}

		layout.drawScrollbar(g, layout.x(393), listTop, layout.w(8), listHeight, VISIBLE_ROWS, lastKnownParties.size(), scrollOffset);
		popReferencePose(g);

		if (hoveredParty != null) {
			g.setComponentTooltipForNextFrame(this.font, buildPartyTooltip(hoveredParty), mouseX, mouseY);
		}
	}

	private List<Component> buildPartyTooltip(PartyInfo party) {
		List<Component> lines = new ArrayList<>();
		String scope = party.ally() ? "§b[Ally Party]" : "§6[Guild Party]";
		lines.add(Component.literal(scope + " §f#" + party.id() + " - §a§l" + party.raid()));
		if (party.world() != null && !party.world().isBlank()) {
			lines.add(Component.literal("§7World: §e" + party.world()));
		}
		lines.add(Component.literal("§eHost: §f" + party.displayMember(0)));
		if (party.size() > 1) {
			lines.add(Component.literal("§bMembers (" + (party.size() - 1) + "):"));
			for (int i = 1; i < party.size(); i++) {
				lines.add(Component.literal("  §7- §f" + party.displayMember(i)));
			}
		}
		int openSlots = party.max() - party.size();
		if (openSlots > 0) {
			lines.add(Component.literal("§8  + " + openSlots + " open slot" + (openSlots == 1 ? "" : "s")));
		}
		if (party.note() != null && !party.note().isBlank()) {
			lines.add(Component.literal("§8Note: §f" + party.note()));
		}
		boolean isSelf = mod != null && party.members().stream().anyMatch(m -> m.equalsIgnoreCase(mod.playerName()));
		if (isSelf) {
			lines.add(Component.literal("§7(You are already in this party)"));
		} else if (isFull(party)) {
			lines.add(Component.literal("§c(Party is full)"));
		} else {
			lines.add(Component.literal("§aClick \"Join\" to join this party"));
		}
		return lines;
	}

	private void drawJoinButton(GuiGraphics g, int x, int y, int width, int height, String label, int textColor, boolean active, boolean hovered) {
		int fill = !active ? 0xFF353535 : (hovered ? 0xFF2E7D32 : 0xFF1E5224);
		int borderTop = !active ? 0xFF4A4A4A : (hovered ? 0xFF4CAF50 : 0xFF2E7D32);
		int borderBottom = !active ? 0xFF222222 : (hovered ? 0xFF1B5E20 : 0xFF143B17);

		g.fill(x, y, x + width, y + height, fill);
		g.fill(x, y, x + width, y + 1, borderTop);
		g.fill(x, y, x + 1, y + height, borderTop);
		g.fill(x + width - 1, y, x + width, y + height, borderBottom);
		g.fill(x, y + height - 1, x + width, y + height, borderBottom);

		g.drawCenteredString(this.font, label, x + (width / 2), y + ((height - 8) / 2), textColor);
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
		return index >= 0 && index < lastKnownParties.size() && row < VISIBLE_ROWS ? index : -1;
	}

	private boolean isJoinHovered(double mouseX, double mouseY, int index) {
		int visibleRow = index - scrollOffset;
		if (visibleRow < 0 || visibleRow >= VISIBLE_ROWS) {
			return false;
		}
		PartyInfo party = lastKnownParties.get(index);
		boolean isSelf = mod != null && party.members().stream().anyMatch(m -> m.equalsIgnoreCase(mod.playerName()));
		if (isSelf || isFull(party)) {
			return false;
		}
		int cardTop = layout.y(38 + visibleRow * ROW_HEIGHT);
		int btnTop = cardTop + layout.h(9);
		return mouseX >= layout.x(335) && mouseX <= layout.x(387) && mouseY >= btnTop && mouseY <= btnTop + layout.h(22);
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
