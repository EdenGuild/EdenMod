package tel.eden.mod.gui;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tel.eden.mod.EdenModClient;

/** Screen GUI for creating a raid/Annihilation/Other party with auto-detected party size. */
public final class PartyCreateScreen extends Screen {

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

	private Identifier iconNotg;
	private Identifier iconNol;
	private Identifier iconTcc;
	private Identifier iconTna;
	private Identifier iconWtp;
	private Identifier iconAnnihilation;
	private Identifier iconOther;

	public PartyCreateScreen(Screen parent, EdenModClient mod) {
		super(Component.literal("Create Guild Party"));
		this.parent = parent;
		this.mod = mod;
		this.playersInParty = Math.max(1, Math.min(maxPartySize - 1, getScoreboardPartySize()));
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int startY = (this.height - 310) / 2;

		// Targets Grid (24px tall buttons with icons)
		// Row 1
		btnNotg = Button.builder(Component.literal("    NOTG"), b -> onTargetClick("Nest of the Grootslangs")).bounds(centerX - 160, startY + 30, 155, 24).build();
		btnNol = Button.builder(Component.literal("    NOL"), b -> onTargetClick("Orphion's Nexus of Light")).bounds(centerX + 5, startY + 30, 155, 24).build();

		// Row 2
		btnTcc = Button.builder(Component.literal("    TCC"), b -> onTargetClick("The Canyon Colossus")).bounds(centerX - 160, startY + 56, 155, 24).build();
		btnTna = Button.builder(Component.literal("    TNA"), b -> onTargetClick("The Nameless Anomaly")).bounds(centerX + 5, startY + 56, 155, 24).build();

		// Row 3
		btnWtp = Button.builder(Component.literal("    WTP"), b -> onTargetClick("The Wartorn Palace")).bounds(centerX - 160, startY + 82, 155, 24).build();
		btnAnnihilation = Button.builder(Component.literal("    Annihilation"), b -> onTargetClick("Annihilation")).bounds(centerX + 5, startY + 82, 155, 24).build();

		// Row 4
		btnOther = Button.builder(Component.literal("    Other"), b -> onTargetClick("Other")).bounds(centerX - 160, startY + 108, 155, 24).build();

		this.addRenderableWidget(btnNotg);
		this.addRenderableWidget(btnNol);
		this.addRenderableWidget(btnTcc);
		this.addRenderableWidget(btnTna);
		this.addRenderableWidget(btnWtp);
		this.addRenderableWidget(btnAnnihilation);
		this.addRenderableWidget(btnOther);

		// Max Party Size Adjusters
		btnMaxMinus = Button.builder(Component.literal("-"), b -> adjustMaxPartySize(-1)).bounds(centerX + 45, startY + 142, 20, 20).build();
		btnMaxPlus = Button.builder(Component.literal("+"), b -> adjustMaxPartySize(1)).bounds(centerX + 95, startY + 142, 20, 20).build();

		this.addRenderableWidget(btnMaxMinus);
		this.addRenderableWidget(btnMaxPlus);

		// Players in Party Adjusters
		btnPlayersMinus = Button.builder(Component.literal("-"), b -> adjustPlayersInParty(-1)).bounds(centerX + 45, startY + 164, 20, 20).build();
		btnPlayersPlus = Button.builder(Component.literal("+"), b -> adjustPlayersInParty(1)).bounds(centerX + 95, startY + 164, 20, 20).build();

		this.addRenderableWidget(btnPlayersMinus);
		this.addRenderableWidget(btnPlayersPlus);

		// Note Text Field
		noteField = new EditBox(this.font, centerX - 160, startY + 206, 320, 20, Component.literal("Party Note"));
		noteField.setMaxLength(100);
		this.addRenderableWidget(noteField);

		// Action Buttons
		btnCreate = Button.builder(Component.literal("Create Party"), b -> onCreate()).bounds(centerX - 160, startY + 276, 155, 20).build();
		Button btnCancel = Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(centerX + 5, startY + 276, 155, 20).build();

		this.addRenderableWidget(btnCreate);
		this.addRenderableWidget(btnCancel);

		// Register custom icon textures
		iconNotg = registerDynamicIcon("notg");
		iconNol = registerDynamicIcon("nol");
		iconTcc = registerDynamicIcon("tcc");
		iconTna = registerDynamicIcon("tna");
		iconWtp = registerDynamicIcon("wtp");
		iconAnnihilation = registerDynamicIcon("annihilation");
		iconOther = registerDynamicIcon("other");

		updateWidgetStates();
	}

	private void onTargetClick(String target) {
		if (target.equals("Annihilation")) {
			if (selectedTargets.contains("Annihilation")) {
				selectedTargets.clear();
				maxPartySize = 4;
			} else {
				selectedTargets.clear();
				selectedTargets.add("Annihilation");
				maxPartySize = 10;
			}
		} else if (target.equals("Other")) {
			if (selectedTargets.contains("Other")) {
				selectedTargets.clear();
				maxPartySize = 4;
			} else {
				selectedTargets.clear();
				selectedTargets.add("Other");
				maxPartySize = 4;
			}
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

		if (playersInParty >= maxPartySize) {
			playersInParty = maxPartySize - 1;
		}

		updateWidgetStates();
	}

	private void adjustMaxPartySize(int amount) {
		if (selectedTargets.contains("Other") || selectedTargets.contains("Annihilation")) {
			maxPartySize = Math.max(2, Math.min(10, maxPartySize + amount));
			if (playersInParty >= maxPartySize) {
				playersInParty = maxPartySize - 1;
			}
			updateWidgetStates();
		}
	}

	private void adjustPlayersInParty(int amount) {
		playersInParty = Math.max(1, Math.min(maxPartySize - 1, playersInParty + amount));
		updateWidgetStates();
	}

	private void updateWidgetStates() {
		btnNotg.setMessage(Component.literal(getButtonText("Nest of the Grootslangs", "NOTG")));
		btnNol.setMessage(Component.literal(getButtonText("Orphion's Nexus of Light", "NOL")));
		btnTcc.setMessage(Component.literal(getButtonText("The Canyon Colossus", "TCC")));
		btnTna.setMessage(Component.literal(getButtonText("The Nameless Anomaly", "TNA")));
		btnWtp.setMessage(Component.literal(getButtonText("The Wartorn Palace", "WTP")));
		btnAnnihilation.setMessage(Component.literal(getButtonText("Annihilation", "Annihilation")));
		btnOther.setMessage(Component.literal(getButtonText("Other", "Other")));

		boolean canEditMax = selectedTargets.contains("Other") || selectedTargets.contains("Annihilation");
		btnMaxMinus.active = canEditMax && maxPartySize > 2;
		btnMaxPlus.active = canEditMax && maxPartySize < 10;

		btnPlayersMinus.active = playersInParty > 1;
		btnPlayersPlus.active = playersInParty < (maxPartySize - 1);

		btnCreate.active = !selectedTargets.isEmpty();
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
		return target;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		// Draw standard translucent background overlay
		g.fill(0, 0, this.width, this.height, 0xC0000000);

		int centerX = this.width / 2;
		int startY = (this.height - 310) / 2;
		int startX = centerX - 175;
		int panelWidth = 350;
		int panelHeight = 310;

		// 1. Draw centered dialog panel (solid dark gray, slightly translucent)
		g.fill(startX, startY, startX + panelWidth, startY + panelHeight, 0xE0282828);

		// 2. Draw 3D outer bevel borders
		g.fill(startX, startY, startX + panelWidth, startY + 1, 0xFF8E8E8E); // Top outer
		g.fill(startX, startY + panelHeight - 1, startX + panelWidth, startY + panelHeight, 0xFF5C5C5C); // Bottom outer
		g.fill(startX, startY, startX + 1, startY + panelHeight, 0xFF8E8E8E); // Left outer
		g.fill(startX + panelWidth - 1, startY, startX + panelWidth, startY + panelHeight, 0xFF5C5C5C); // Right outer

		// 3. Draw 3D inner highlight borders
		g.fill(startX + 1, startY + 1, startX + panelWidth - 1, startY + 2, 0xFFC6C6C6); // Top inner
		g.fill(startX + 1, startY + panelHeight - 2, startX + panelWidth - 1, startY + panelHeight - 1, 0xFF3E3E3E); // Bottom inner
		g.fill(startX + 1, startY + 1, startX + 2, startY + panelHeight - 1, 0xFFC6C6C6); // Left inner
		g.fill(startX + panelWidth - 2, startY + 1, startX + panelWidth - 1, startY + panelHeight - 1, 0xFF3E3E3E); // Right inner

		super.render(g, mouseX, mouseY, delta);

		// Draw custom icons on buttons
		drawButtonIcon(g, btnNotg, iconNotg);
		drawButtonIcon(g, btnNol, iconNol);
		drawButtonIcon(g, btnTcc, iconTcc);
		drawButtonIcon(g, btnTna, iconTna);
		drawButtonIcon(g, btnWtp, iconWtp);
		drawButtonIcon(g, btnAnnihilation, iconAnnihilation);
		drawButtonIcon(g, btnOther, iconOther);

		// Draw Screen Title (Opaque White)
		g.drawCenteredString(this.font, this.title, centerX, startY + 12, 0xFFFFFFFF);

		// Draw Labels & Values (Opaque Colors)
		g.drawString(this.font, "Max Party Size:", centerX - 160, startY + 148, 0xFFA0A0A0);
		g.drawCenteredString(this.font, String.valueOf(this.maxPartySize), centerX + 80, startY + 148, 0xFFFFFFFF);

		g.drawString(this.font, "Players in Party:", centerX - 160, startY + 170, 0xFFA0A0A0);
		g.drawCenteredString(this.font, String.valueOf(this.playersInParty), centerX + 80, startY + 170, 0xFFFFFFFF);

		g.drawString(this.font, "Party Note:", centerX - 160, startY + 194, 0xFFA0A0A0);

		// Draw Preview Message (Opaque Colors)
		if (selectedTargets.isEmpty()) {
			g.drawCenteredString(this.font, "Please select at least one target!", centerX, startY + 240, 0xFFFF5555);
		} else {
			String preview;
			if (selectedTargets.contains("Annihilation")) {
				preview = "Annihilation (" + playersInParty + "/" + maxPartySize + ")";
			} else if (selectedTargets.contains("Other")) {
				preview = "Other (" + playersInParty + "/" + maxPartySize + ")";
			} else {
				java.util.List<String> shortNames = new java.util.ArrayList<>();
				for (String target : selectedTargets) {
					shortNames.add(getShortTargetName(target));
				}
				preview = String.join(" / ", shortNames) + " (" + playersInParty + "/4)";
			}
			g.drawCenteredString(this.font, "Ready: " + preview, centerX, startY + 240, 0xFF55FF55);
		}
	}

	private void drawButtonIcon(GuiGraphics g, Button btn, Identifier icon) {
		if (btn != null && icon != null) {
			// Render full 64x64 texture, scaled down to 20x20 using matrix
			g.pose().pushMatrix();
			g.pose().translate(btn.getX() + 2, btn.getY() + 2);
			g.pose().scale(20.0f / 64.0f, 20.0f / 64.0f);
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

	private Identifier registerDynamicIcon(String name) {
		Identifier loc = Identifier.parse("edenmod:dynamic_icon/" + name);
		var tm = Minecraft.getInstance().getTextureManager();
		try {
			var stream = PartyCreateScreen.class.getClassLoader().getResourceAsStream("assets/edenmod/textures/gui/icons/" + name + ".png");
			if (stream != null) {
				var img = com.mojang.blaze3d.platform.NativeImage.read(stream);
				var texture = new net.minecraft.client.renderer.texture.DynamicTexture(() -> name, img);
				tm.register(loc, texture);
				stream.close();
				return loc;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
}
