package tel.eden.mod.gui;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tel.eden.mod.EdenModClient;

/** Screen GUI for creating a raid/Annihilation/Other party with auto-detected party size. */
public final class PartyCreateScreen extends Screen {

	private static final Identifier ICON_NOTG = Identifier.parse("edenmod:textures/gui/icons/notg.png");
	private static final Identifier ICON_NOL = Identifier.parse("edenmod:textures/gui/icons/nol.png");
	private static final Identifier ICON_TCC = Identifier.parse("edenmod:textures/gui/icons/tcc.png");
	private static final Identifier ICON_TNA = Identifier.parse("edenmod:textures/gui/icons/tna.png");
	private static final Identifier ICON_WTP = Identifier.parse("edenmod:textures/gui/icons/wtp.png");
	private static final Identifier ICON_ANNIHILATION = Identifier.parse("edenmod:textures/gui/icons/annihilation.png");
	private static final Identifier ICON_OTHER = Identifier.parse("edenmod:textures/gui/icons/other.png");

	private final Screen parent;
	private final EdenModClient mod;

	private final Set<String> selectedTargets = new LinkedHashSet<>();
	private int maxPartySize = 4;
	private int playersInParty = 1;

	private Button btnNotg;
	private Button btnNol;
	private Button btnTcc;
	private Button btnTna;
	private Button btnWtp;
	private Button btnAnnihilation;
	private Button btnOther;

	private Button btnMaxMinus;
	private Button btnMaxPlus;
	private Button btnPlayersMinus;
	private Button btnPlayersPlus;
	private Button btnCreate;

	private EditBox noteField;

	public PartyCreateScreen(Screen parent, EdenModClient mod) {
		super(Component.literal("Create Guild Party"));
		this.parent = parent;
		this.mod = mod;
		this.playersInParty = getScoreboardPartySize();
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;

		// Targets Grid
		// Row 1
		btnNotg = Button.builder(Component.literal("Grootslangs"), b -> onTargetClick("Nest of the Grootslangs")).bounds(centerX - 155, 30, 150, 20).build();
		btnNol = Button.builder(Component.literal("Nexus of Light"), b -> onTargetClick("Orphion's Nexus of Light")).bounds(centerX + 5, 30, 150, 20).build();

		// Row 2
		btnTcc = Button.builder(Component.literal("Canyon Colossus"), b -> onTargetClick("The Canyon Colossus")).bounds(centerX - 155, 52, 150, 20).build();
		btnTna = Button.builder(Component.literal("Nameless Anomaly"), b -> onTargetClick("The Nameless Anomaly")).bounds(centerX + 5, 52, 150, 20).build();

		// Row 3
		btnWtp = Button.builder(Component.literal("Wartorn Palace"), b -> onTargetClick("The Wartorn Palace")).bounds(centerX - 155, 74, 150, 20).build();
		btnAnnihilation = Button.builder(Component.literal("Annihilation"), b -> onTargetClick("Annihilation")).bounds(centerX + 5, 74, 150, 20).build();

		// Row 4
		btnOther = Button.builder(Component.literal("Other"), b -> onTargetClick("Other")).bounds(centerX - 155, 96, 150, 20).build();

		this.addRenderableWidget(btnNotg);
		this.addRenderableWidget(btnNol);
		this.addRenderableWidget(btnTcc);
		this.addRenderableWidget(btnTna);
		this.addRenderableWidget(btnWtp);
		this.addRenderableWidget(btnAnnihilation);
		this.addRenderableWidget(btnOther);

		// Max Party Size Adjusters
		btnMaxMinus = Button.builder(Component.literal("-"), b -> adjustMaxPartySize(-1)).bounds(centerX + 35, 122, 20, 20).build();
		btnMaxPlus = Button.builder(Component.literal("+"), b -> adjustMaxPartySize(1)).bounds(centerX + 85, 122, 20, 20).build();

		this.addRenderableWidget(btnMaxMinus);
		this.addRenderableWidget(btnMaxPlus);

		// Players in Party Adjusters
		btnPlayersMinus = Button.builder(Component.literal("-"), b -> adjustPlayersInParty(-1)).bounds(centerX + 35, 144, 20, 20).build();
		btnPlayersPlus = Button.builder(Component.literal("+"), b -> adjustPlayersInParty(1)).bounds(centerX + 85, 144, 20, 20).build();

		this.addRenderableWidget(btnPlayersMinus);
		this.addRenderableWidget(btnPlayersPlus);

		// Note Text Field
		noteField = new EditBox(this.font, centerX - 155, 180, 310, 20, Component.literal("Party Note"));
		noteField.setMaxLength(100);
		this.addRenderableWidget(noteField);

		// Action Buttons
		btnCreate = Button.builder(Component.literal("Create Party"), b -> onCreate()).bounds(centerX - 155, 210, 150, 20).build();
		Button btnCancel = Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(centerX + 5, 210, 150, 20).build();

		this.addRenderableWidget(btnCreate);
		this.addRenderableWidget(btnCancel);

		updateWidgetStates();
	}

	private void onTargetClick(String target) {
		if (target.equals("Annihilation")) {
			selectedTargets.clear();
			selectedTargets.add("Annihilation");
			maxPartySize = 10;
		} else if (target.equals("Other")) {
			selectedTargets.clear();
			selectedTargets.add("Other");
			maxPartySize = 4;
		} else {
			if (selectedTargets.contains("Annihilation") || selectedTargets.contains("Other")) {
				selectedTargets.clear();
			}
			if (selectedTargets.contains(target)) {
				selectedTargets.remove(target);
			} else {
				selectedTargets.add(target);
			}
			maxPartySize = 4;
		}

		if (playersInParty > maxPartySize) {
			playersInParty = maxPartySize;
		}

		updateWidgetStates();
	}

	private void adjustMaxPartySize(int amount) {
		if (selectedTargets.contains("Other")) {
			maxPartySize = Math.max(2, Math.min(10, maxPartySize + amount));
			if (playersInParty > maxPartySize) {
				playersInParty = maxPartySize;
			}
			updateWidgetStates();
		}
	}

	private void adjustPlayersInParty(int amount) {
		playersInParty = Math.max(1, Math.min(maxPartySize, playersInParty + amount));
		updateWidgetStates();
	}

	private void updateWidgetStates() {
		btnNotg.setMessage(Component.literal(getButtonText("Nest of the Grootslangs", "Grootslangs")));
		btnNol.setMessage(Component.literal(getButtonText("Orphion's Nexus of Light", "Nexus of Light")));
		btnTcc.setMessage(Component.literal(getButtonText("The Canyon Colossus", "Canyon Colossus")));
		btnTna.setMessage(Component.literal(getButtonText("The Nameless Anomaly", "Nameless Anomaly")));
		btnWtp.setMessage(Component.literal(getButtonText("The Wartorn Palace", "Wartorn Palace")));
		btnAnnihilation.setMessage(Component.literal(getButtonText("Annihilation", "Annihilation")));
		btnOther.setMessage(Component.literal(getButtonText("Other", "Other")));

		boolean isOther = selectedTargets.contains("Other");
		btnMaxMinus.active = isOther && maxPartySize > 2;
		btnMaxPlus.active = isOther && maxPartySize < 10;

		btnPlayersMinus.active = playersInParty > 1;
		btnPlayersPlus.active = playersInParty < maxPartySize;

		btnCreate.active = !selectedTargets.isEmpty();
	}

	private String getButtonText(String target, String displayName) {
		if (selectedTargets.contains(target)) {
			return "§a✔ " + displayName;
		} else {
			return "§r" + displayName;
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, this.width, this.height, 0xC0000000);
		super.render(g, mouseX, mouseY, delta);

		drawButtonIcon(g, btnNotg, ICON_NOTG);
		drawButtonIcon(g, btnNol, ICON_NOL);
		drawButtonIcon(g, btnTcc, ICON_TCC);
		drawButtonIcon(g, btnTna, ICON_TNA);
		drawButtonIcon(g, btnWtp, ICON_WTP);
		drawButtonIcon(g, btnAnnihilation, ICON_ANNIHILATION);
		drawButtonIcon(g, btnOther, ICON_OTHER);

		g.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

		int centerX = this.width / 2;
		g.drawString(this.font, "Max Party Size:", centerX - 155, 128, 0xA0A0A0);
		g.drawCenteredString(this.font, String.valueOf(this.maxPartySize), centerX + 70, 128, 0xFFFFFF);

		g.drawString(this.font, "Players in Party:", centerX - 155, 150, 0xA0A0A0);
		g.drawCenteredString(this.font, String.valueOf(this.playersInParty), centerX + 70, 150, 0xFFFFFF);

		g.drawString(this.font, "Party Note:", centerX - 155, 168, 0xA0A0A0);

		if (selectedTargets.isEmpty()) {
			g.drawCenteredString(this.font, "Please select at least one target!", centerX, 200, 0xFF5555);
		} else {
			String preview;
			if (selectedTargets.contains("Annihilation")) {
				preview = "Annihilation (" + playersInParty + "/10)";
			} else if (selectedTargets.contains("Other")) {
				preview = "Other (" + playersInParty + "/" + maxPartySize + ")";
			} else {
				preview = String.join(" / ", selectedTargets) + " (" + playersInParty + "/4)";
			}
			g.drawCenteredString(this.font, "Ready: " + preview, centerX, 200, 0x55FF55);
		}
	}

	private void onCreate() {
		if (selectedTargets.isEmpty()) {
			return;
		}

		String targetName;
		if (selectedTargets.contains("Annihilation")) {
			targetName = "Annihilation";
		} else if (selectedTargets.contains("Other")) {
			targetName = "Other";
		} else {
			targetName = String.join(" / ", selectedTargets);
		}

		int filledSlots = playersInParty - 1;
		var ws = mod.socket();
		if (ws != null) {
			ws.sendPartyOpen(targetName, maxPartySize, noteField.getValue(), filledSlots);
		}

		onClose();
	}

	private int getScoreboardPartySize() {
		try {
			var player = Minecraft.getInstance().player;
			if (player != null) {
				var scoreboard = player.level().getScoreboard();
				var team = scoreboard.getPlayersTeam(player.getScoreboardName());
				if (team != null && team.getName().startsWith("p_")) {
					return Math.max(1, team.getPlayers().size());
				}
			}
		} catch (Exception ignored) {
		}
		return 1;
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}

	private void drawButtonIcon(GuiGraphics g, Button btn, Identifier icon) {
		if (btn != null) {
			int iconX = btn.getX() + 6;
			int iconY = btn.getY() + 2;
			g.blit(icon, iconX, iconY, 16, 16, 0.0f, 0.0f, 256.0f, 256.0f);
		}
	}
}
