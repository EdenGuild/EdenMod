package tel.eden.mod.gui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tel.eden.mod.EdenModClient;

/** Screen GUI for creating a raid/Annihilation/Festival/Other party. */
public final class PartyCreateScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 350;
	private static final int BASE_PANEL_HEIGHT = 330;

	private final Screen parent;
	private final EdenModClient mod;
	private final String initialTarget;

	private final Set<String> selectedTargets = new LinkedHashSet<>();
	private final List<String> addedMembers = new ArrayList<>();
	private int maxPartySize = 4;
	private int filledSlots = 0;
	private boolean sendToAlly = false;

	private Button btnNotg;
	private Button btnNol;
	private Button btnTcc;
	private Button btnTna;
	private Button btnWtp;
	private Button btnAnnihilation;
	private Button btnOther;
	private Button btnFestival;

	private Button btnMaxMinus;
	private Button btnMaxPlus;
	private Button btnFilledMinus;
	private Button btnFilledPlus;
	private Button btnAddPlayer;
	private Button btnClearAdded;
	private Button btnSendToAlly;
	private Button btnCreate;

	private EditBox addPlayerField;
	private EditBox noteField;

	private static final Identifier ICON_NOTG = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/notg.png");
	private static final Identifier ICON_NOL = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/nol.png");
	private static final Identifier ICON_TCC = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/tcc.png");
	private static final Identifier ICON_TNA = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/tna.png");
	private static final Identifier ICON_WTP = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/wtp.png");
	private static final Identifier ICON_ANNIHILATION = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/annihilation.png");
	private static final Identifier ICON_OTHER = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/other.png");
	private static final Identifier ICON_FESTIVAL = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/festival.png");
	private EdenPanelLayout layout;

	public PartyCreateScreen(Screen parent, EdenModClient mod) {
		this(parent, mod, null);
	}

	public PartyCreateScreen(Screen parent, EdenModClient mod, String initialTarget) {
		super(Component.literal("Create Guild Party"));
		this.parent = parent;
		this.mod = mod;
		this.initialTarget = initialTarget;
		this.filledSlots = 0;
	}

	@Override
	protected void init() {
		updateReferenceSpace();
		layout = EdenPanelLayout.centered(virtualWidth, virtualHeight, BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);

		// Targets Grid (24px tall buttons with icons)
		// Row 1
		btnNotg = Button.builder(Component.literal("    NOTG"), b -> onTargetClick("Nest of the Grootslangs")).bounds(layout.x(15), layout.y(30), layout.w(155), layout.h(24)).build();
		btnNol = Button.builder(Component.literal("    NOL"), b -> onTargetClick("Orphion's Nexus of Light")).bounds(layout.x(180), layout.y(30), layout.w(155), layout.h(24)).build();

		// Row 2
		btnTcc = Button.builder(Component.literal("    TCC"), b -> onTargetClick("The Canyon Colossus")).bounds(layout.x(15), layout.y(56), layout.w(155), layout.h(24)).build();
		btnTna = Button.builder(Component.literal("    TNA"), b -> onTargetClick("The Nameless Anomaly")).bounds(layout.x(180), layout.y(56), layout.w(155), layout.h(24)).build();

		// Row 3
		btnWtp = Button.builder(Component.literal("    WTP"), b -> onTargetClick("The Wartorn Palace")).bounds(layout.x(15), layout.y(82), layout.w(155), layout.h(24)).build();
		btnAnnihilation = Button.builder(Component.literal("    Annihilation"), b -> onTargetClick("Annihilation")).bounds(layout.x(180), layout.y(82), layout.w(155), layout.h(24)).build();

		// Row 4
		btnOther = Button.builder(Component.literal("    Other"), b -> onTargetClick("Other")).bounds(layout.x(15), layout.y(108), layout.w(155), layout.h(24)).build();
		btnFestival = Button.builder(Component.literal("    Festival"), b -> onTargetClick("Festival Minigames")).bounds(layout.x(180), layout.y(108), layout.w(155), layout.h(24)).build();

		this.addRenderableWidget(btnNotg);
		this.addRenderableWidget(btnNol);
		this.addRenderableWidget(btnTcc);
		this.addRenderableWidget(btnTna);
		this.addRenderableWidget(btnWtp);
		this.addRenderableWidget(btnAnnihilation);
		this.addRenderableWidget(btnOther);
		this.addRenderableWidget(btnFestival);

		// Max Party Size Adjusters
		btnMaxMinus = Button.builder(Component.literal("-"), b -> adjustMaxPartySize(-1)).bounds(layout.x(220), layout.y(136), layout.w(20), layout.h(18)).build();
		btnMaxPlus = Button.builder(Component.literal("+"), b -> adjustMaxPartySize(1)).bounds(layout.x(270), layout.y(136), layout.w(20), layout.h(18)).build();
		this.addRenderableWidget(btnMaxMinus);
		this.addRenderableWidget(btnMaxPlus);

		// Filled Slots Adjusters
		btnFilledMinus = Button.builder(Component.literal("-"), b -> adjustFilledSlots(-1)).bounds(layout.x(220), layout.y(158), layout.w(20), layout.h(18)).build();
		btnFilledPlus = Button.builder(Component.literal("+"), b -> adjustFilledSlots(1)).bounds(layout.x(270), layout.y(158), layout.w(20), layout.h(18)).build();
		this.addRenderableWidget(btnFilledMinus);
		this.addRenderableWidget(btnFilledPlus);

		// Add Player Field + Button
		addPlayerField = new EditBox(this.font, layout.x(15), layout.y(182), layout.w(125), layout.h(18), Component.literal("Player IGN"));
		addPlayerField.setMaxLength(16);
		this.addRenderableWidget(addPlayerField);

		btnAddPlayer = Button.builder(Component.literal("Add"), b -> onAddPlayer()).bounds(layout.x(145), layout.y(182), layout.w(45), layout.h(18)).build();
		this.addRenderableWidget(btnAddPlayer);

		// Send to Ally toggle
		btnSendToAlly = Button.builder(Component.literal(sendToAlly ? "§a[✔] Ally" : "§7[ ] Ally"), b -> toggleSendToAlly()).bounds(layout.x(200), layout.y(182), layout.w(135), layout.h(18)).build();
		this.addRenderableWidget(btnSendToAlly);

		// Clear Added Members Button (only visible when addedMembers is non-empty)
		btnClearAdded = Button.builder(Component.literal("§cClear"), b -> onClearAdded()).bounds(layout.x(280), layout.y(204), layout.w(55), layout.h(16)).build();
		btnClearAdded.visible = !addedMembers.isEmpty();
		this.addRenderableWidget(btnClearAdded);

		// Note Text Field
		noteField = new EditBox(this.font, layout.x(15), layout.y(236), layout.w(320), layout.h(18), Component.literal("Party Note"));
		noteField.setMaxLength(100);
		this.addRenderableWidget(noteField);

		// Action Buttons
		btnCreate = Button.builder(Component.literal("Create Party"), b -> onCreate()).bounds(layout.x(15), layout.y(294), layout.w(155), layout.h(20)).build();
		Button btnCancel = Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(layout.x(180), layout.y(294), layout.w(155), layout.h(20)).build();
		this.addRenderableWidget(btnCreate);
		this.addRenderableWidget(btnCancel);

		applyInitialTarget();
		trimToMax();

		updateWidgetStates();
	}

	private int occupancy() {
		return 1 + addedMembers.size() + filledSlots;
	}

	private void trimToMax() {
		while (occupancy() > maxPartySize) {
			if (filledSlots > 0) {
				filledSlots--;
			} else if (!addedMembers.isEmpty()) {
				addedMembers.remove(addedMembers.size() - 1);
			} else {
				break;
			}
		}
	}

	private void toggleSendToAlly() {
		sendToAlly = !sendToAlly;
		btnSendToAlly.setMessage(Component.literal(sendToAlly ? "§a[✔] Ally" : "§7[ ] Ally"));
	}

	private void applyInitialTarget() {
		if (initialTarget == null || initialTarget.isBlank()) {
			return;
		}
		if (initialTarget.equals("Festival Minigames") && (mod == null || !mod.isFestivalEnabled())) {
			return;
		}
		selectedTargets.clear();
		if (initialTarget.equals("Annihilation")) {
			selectedTargets.add("Annihilation");
			maxPartySize = 10;
		} else if (initialTarget.equals("Festival Minigames")) {
			selectedTargets.add("Festival Minigames");
			maxPartySize = 5;
		} else if (initialTarget.equals("Other")) {
			selectedTargets.add("Other");
			maxPartySize = 4;
		} else {
			selectedTargets.add(initialTarget);
			maxPartySize = 4;
		}
	}

	private void onTargetClick(String target) {
		if (target.equals("Annihilation") || target.equals("Festival Minigames") || target.equals("Other")) {
			selectedTargets.clear();
			selectedTargets.add(target);
			if (target.equals("Annihilation")) {
				maxPartySize = 10;
			} else if (target.equals("Festival Minigames")) {
				maxPartySize = 5;
			} else {
				maxPartySize = 4;
			}
		} else {
			if (selectedTargets.contains("Annihilation") || selectedTargets.contains("Festival Minigames") || selectedTargets.contains("Other")) {
				selectedTargets.clear();
			}
			if (selectedTargets.contains(target)) {
				selectedTargets.remove(target);
			} else {
				selectedTargets.add(target);
			}
			maxPartySize = 4;
		}

		trimToMax();
		updateWidgetStates();
	}

	private void adjustMaxPartySize(int amount) {
		if (selectedTargets.contains("Other") || selectedTargets.contains("Annihilation") || selectedTargets.contains("Festival Minigames")) {
			maxPartySize = Math.max(Math.max(2, occupancy()), Math.min(10, maxPartySize + amount));
			updateWidgetStates();
		}
	}

	private void adjustFilledSlots(int amount) {
		int maxAllowed = Math.max(0, maxPartySize - 1 - addedMembers.size());
		filledSlots = Math.max(0, Math.min(maxAllowed, filledSlots + amount));
		updateWidgetStates();
	}

	private void onAddPlayer() {
		if (addPlayerField == null)
			return;
		String ign = addPlayerField.getValue().trim();
		if (ign.matches("[A-Za-z0-9_]{1,16}") && (mod == null || !ign.equalsIgnoreCase(mod.playerName())) && addedMembers.stream().noneMatch(m -> m.equalsIgnoreCase(ign))) {
			if (occupancy() < maxPartySize) {
				addedMembers.add(ign);
				addPlayerField.setValue("");
				updateWidgetStates();
			}
		}
	}

	private void onClearAdded() {
		addedMembers.clear();
		updateWidgetStates();
	}

	private String getCreateDisabledReason() {
		if (selectedTargets.isEmpty()) {
			return "Please select at least one target!";
		}
		if (selectedTargets.contains("Annihilation") && (mod != null && !mod.isAnnihilationActive())) {
			return "Annihilation parties can only be opened while the Annihilation timer is active. Check /party annihilation when.";
		}
		if (selectedTargets.contains("Festival Minigames") && (mod != null && !mod.isFestivalEnabled())) {
			return "Festival Minigames party finder is currently disabled by Senate.";
		}
		int limit = Math.max(0, maxPartySize - 2 - addedMembers.size());
		if (filledSlots > limit) {
			return "Filled slots must be between 0 and " + limit + ".";
		}
		return null;
	}

	private void updateWidgetStates() {
		btnNotg.setMessage(Component.literal(getButtonText("Nest of the Grootslangs", "NOTG")));
		btnNol.setMessage(Component.literal(getButtonText("Orphion's Nexus of Light", "NOL")));
		btnTcc.setMessage(Component.literal(getButtonText("The Canyon Colossus", "TCC")));
		btnTna.setMessage(Component.literal(getButtonText("The Nameless Anomaly", "TNA")));
		btnWtp.setMessage(Component.literal(getButtonText("The Wartorn Palace", "WTP")));
		btnAnnihilation.setMessage(Component.literal(getButtonText("Annihilation", "Annihilation")));
		btnOther.setMessage(Component.literal(getButtonText("Other", "Other")));
		btnFestival.setMessage(Component.literal(getButtonText("Festival Minigames", "Festival")));
		btnFestival.active = mod != null && mod.isFestivalEnabled();

		boolean canEditMax = selectedTargets.contains("Other") || selectedTargets.contains("Annihilation") || selectedTargets.contains("Festival Minigames");
		btnMaxMinus.active = canEditMax && maxPartySize > Math.max(2, occupancy());
		btnMaxPlus.active = canEditMax && maxPartySize < 10;

		btnFilledMinus.active = filledSlots > 0;
		btnFilledPlus.active = occupancy() < maxPartySize;

		if (btnAddPlayer != null) {
			btnAddPlayer.active = occupancy() < maxPartySize;
		}
		if (btnClearAdded != null) {
			btnClearAdded.visible = !addedMembers.isEmpty();
		}

		btnCreate.active = (getCreateDisabledReason() == null);
	}

	private String getButtonText(String target, String displayName) {
		if (selectedTargets.contains(target)) {
			return "    §a✔ " + displayName;
		} else {
			return "    §r" + displayName;
		}
	}

	private String getShortTargetName(String target) {
		if (target.equals("Nest of the Grootslangs"))
			return "NOTG";
		if (target.equals("Orphion's Nexus of Light"))
			return "NOL";
		if (target.equals("The Canyon Colossus"))
			return "TCC";
		if (target.equals("The Nameless Anomaly"))
			return "TNA";
		if (target.equals("The Wartorn Palace"))
			return "WTP";
		if (target.equals("Festival Minigames"))
			return "Festival";
		return target;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);

		pushReferencePose(g);
		layout.drawBackground(g);
		layout.drawPanel(g);

		super.render(g, scaledMouseX, scaledMouseY, delta);

		// Draw custom icons on buttons
		drawButtonIcon(g, btnNotg, ICON_NOTG);
		drawButtonIcon(g, btnNol, ICON_NOL);
		drawButtonIcon(g, btnTcc, ICON_TCC);
		drawButtonIcon(g, btnTna, ICON_TNA);
		drawButtonIcon(g, btnWtp, ICON_WTP);
		drawButtonIcon(g, btnAnnihilation, ICON_ANNIHILATION);
		drawButtonIcon(g, btnOther, ICON_OTHER);
		drawButtonIcon(g, btnFestival, ICON_FESTIVAL);

		// Draw Screen Title (Opaque White)
		g.drawCenteredString(this.font, this.title, layout.centerX(), layout.y(12), 0xFFFFFFFF);

		// Draw Labels & Values (Opaque Colors)
		g.drawString(this.font, "Max Party Size:", layout.x(15), layout.y(141), 0xFFA0A0A0);
		g.drawCenteredString(this.font, String.valueOf(this.maxPartySize), layout.x(255), layout.y(141), 0xFFFFFFFF);

		g.drawString(this.font, "Filled Slots:", layout.x(15), layout.y(163), 0xFFA0A0A0);
		g.drawCenteredString(this.font, String.valueOf(this.filledSlots), layout.x(255), layout.y(163), 0xFFFFFFFF);

		// Draw Added Members if present
		if (!addedMembers.isEmpty()) {
			String membersText = "§aMembers: §f" + String.join(", ", addedMembers);
			if (this.font.width(membersText) > layout.w(255)) {
				membersText = "§aMembers: §f" + addedMembers.size() + " added";
			}
			g.drawString(this.font, membersText, layout.x(15), layout.y(208), 0xFFFFFFFF);
		}

		g.drawString(this.font, "Party Note:", layout.x(15), layout.y(224), 0xFFA0A0A0);

		// Draw Preview Message (Opaque Colors)
		if (selectedTargets.isEmpty()) {
			g.drawCenteredString(this.font, "Please select at least one target!", layout.centerX(), layout.y(266), 0xFFFF5555);
		} else {
			String preview;
			String scopeTag = sendToAlly ? "[Ally] " : "[Guild] ";
			if (selectedTargets.contains("Annihilation")) {
				preview = scopeTag + "Annihilation (" + occupancy() + "/" + maxPartySize + ")";
			} else if (selectedTargets.contains("Festival Minigames")) {
				preview = scopeTag + "Festival (" + occupancy() + "/" + maxPartySize + ")";
			} else if (selectedTargets.contains("Other")) {
				preview = scopeTag + "Other (" + occupancy() + "/" + maxPartySize + ")";
			} else {
				java.util.List<String> shortNames = new java.util.ArrayList<>();
				for (String target : selectedTargets) {
					shortNames.add(getShortTargetName(target));
				}
				preview = scopeTag + String.join(" / ", shortNames) + " (" + occupancy() + "/4)";
			}
			boolean ready = getCreateDisabledReason() == null;
			g.drawCenteredString(this.font, (ready ? "Ready: " : "Preview: ") + preview, layout.centerX(), layout.y(266), ready ? 0xFF55FF55 : 0xFFA0A0A0);
		}
		popReferencePose(g);

		if (!btnCreate.active && isHovered(scaledMouseX, scaledMouseY, btnCreate)) {
			String reason = getCreateDisabledReason();
			if (reason != null) {
				g.setComponentTooltipForNextFrame(this.font, List.of(Component.literal(reason).withStyle(ChatFormatting.RED)), mouseX, mouseY);
			}
		} else if (mod != null && !mod.isAnnihilationActive() && isHovered(scaledMouseX, scaledMouseY, btnAnnihilation)) {
			g.setComponentTooltipForNextFrame(this.font, List.of(Component.literal("Annihilation parties can only be opened while the Annihilation timer is active. Check /party annihilation when.").withStyle(ChatFormatting.RED)), mouseX, mouseY);
		} else if (mod != null && !mod.isFestivalEnabled() && isHovered(scaledMouseX, scaledMouseY, btnFestival)) {
			g.setComponentTooltipForNextFrame(this.font, List.of(Component.literal("Festival Minigames party finder is currently disabled by Senate.").withStyle(ChatFormatting.RED)), mouseX, mouseY);
		}
	}

	private static boolean isHovered(int mouseX, int mouseY, Button btn) {
		return btn != null && mouseX >= btn.getX() && mouseX <= btn.getX() + btn.getWidth() && mouseY >= btn.getY() && mouseY <= btn.getY() + btn.getHeight();
	}

	private void drawButtonIcon(GuiGraphics g, Button btn, Identifier icon) {
		if (btn != null && icon != null) {
			int size = Math.max(8, btn.getHeight() - 4);
			g.pose().pushMatrix();
			g.pose().translate(btn.getX() + 2, btn.getY() + 2);
			g.pose().scale(size / 64.0f, size / 64.0f);
			g.blit(RenderPipelines.GUI_TEXTURED, icon, 0, 0, 0.0f, 0.0f, 64, 64, 64, 64);
			g.pose().popMatrix();
		}
	}

	private void onCreate() {
		if (selectedTargets.isEmpty()) {
			return;
		}

		String targetName;
		if (selectedTargets.contains("Annihilation")) {
			targetName = "Annihilation";
		} else if (selectedTargets.contains("Festival Minigames")) {
			targetName = "Festival Minigames";
		} else if (selectedTargets.contains("Other")) {
			targetName = "Other";
		} else {
			targetName = String.join(" / ", selectedTargets);
		}

		var ws = mod.socket();
		if (ws != null) {
			ws.sendPartyOpen(targetName, maxPartySize, noteField.getValue(), filledSlots, sendToAlly, mod.currentWynnWorld(), addedMembers);
		}

		onClose();
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
		return super.mouseClicked(rescale(event), bl);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return super.mouseReleased(rescale(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double d, double e) {
		return super.mouseDragged(rescale(event), d / uiScale, e / uiScale);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double d, double e) {
		return super.mouseScrolled(mouseX / uiScale, mouseY / uiScale, d, e);
	}
}
