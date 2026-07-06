package tel.eden.mod.gui;

import java.util.LinkedHashSet;
import java.util.Set;
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

/** Screen GUI for creating a raid/Annihilation/Other party with auto-detected party size. */
public final class PartyCreateScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 350;
	private static final int BASE_PANEL_HEIGHT = 310;

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
	private EdenPanelLayout layout;

	public PartyCreateScreen(Screen parent, EdenModClient mod) {
		super(Component.literal("Create Guild Party"));
		this.parent = parent;
		this.mod = mod;
		this.playersInParty = Math.max(1, Math.min(maxPartySize - 1, getScoreboardPartySize()));
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

		this.addRenderableWidget(btnNotg);
		this.addRenderableWidget(btnNol);
		this.addRenderableWidget(btnTcc);
		this.addRenderableWidget(btnTna);
		this.addRenderableWidget(btnWtp);
		this.addRenderableWidget(btnAnnihilation);
		this.addRenderableWidget(btnOther);

		// Max Party Size Adjusters
		btnMaxMinus = Button.builder(Component.literal("-"), b -> adjustMaxPartySize(-1)).bounds(layout.x(220), layout.y(142), layout.w(20), layout.h(20)).build();
		btnMaxPlus = Button.builder(Component.literal("+"), b -> adjustMaxPartySize(1)).bounds(layout.x(270), layout.y(142), layout.w(20), layout.h(20)).build();

		this.addRenderableWidget(btnMaxMinus);
		this.addRenderableWidget(btnMaxPlus);

		// Players in Party Adjusters
		btnPlayersMinus = Button.builder(Component.literal("-"), b -> adjustPlayersInParty(-1)).bounds(layout.x(220), layout.y(164), layout.w(20), layout.h(20)).build();
		btnPlayersPlus = Button.builder(Component.literal("+"), b -> adjustPlayersInParty(1)).bounds(layout.x(270), layout.y(164), layout.w(20), layout.h(20)).build();

		this.addRenderableWidget(btnPlayersMinus);
		this.addRenderableWidget(btnPlayersPlus);

		// Note Text Field
		noteField = new EditBox(this.font, layout.x(15), layout.y(206), layout.w(320), layout.h(20), Component.literal("Party Note"));
		noteField.setMaxLength(100);
		this.addRenderableWidget(noteField);

		// Action Buttons
		btnCreate = Button.builder(Component.literal("Create Party"), b -> onCreate()).bounds(layout.x(15), layout.y(276), layout.w(155), layout.h(20)).build();
		Button btnCancel = Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(layout.x(180), layout.y(276), layout.w(155), layout.h(20)).build();

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
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);

		pushReferencePose(g);
		layout.drawBackground(g);
		layout.drawPanel(g);

		super.render(g, scaledMouseX, scaledMouseY, delta);

		// Draw custom icons on buttons
		drawButtonIcon(g, btnNotg, iconNotg);
		drawButtonIcon(g, btnNol, iconNol);
		drawButtonIcon(g, btnTcc, iconTcc);
		drawButtonIcon(g, btnTna, iconTna);
		drawButtonIcon(g, btnWtp, iconWtp);
		drawButtonIcon(g, btnAnnihilation, iconAnnihilation);
		drawButtonIcon(g, btnOther, iconOther);

		// Draw Screen Title (Opaque White)
		g.drawCenteredString(this.font, this.title, layout.centerX(), layout.y(12), 0xFFFFFFFF);

		// Draw Labels & Values (Opaque Colors)
		g.drawString(this.font, "Max Party Size:", layout.x(15), layout.y(148), 0xFFA0A0A0);
		g.drawCenteredString(this.font, String.valueOf(this.maxPartySize), layout.x(255), layout.y(148), 0xFFFFFFFF);

		g.drawString(this.font, "Players in Party:", layout.x(15), layout.y(170), 0xFFA0A0A0);
		g.drawCenteredString(this.font, String.valueOf(this.playersInParty), layout.x(255), layout.y(170), 0xFFFFFFFF);

		g.drawString(this.font, "Party Note:", layout.x(15), layout.y(194), 0xFFA0A0A0);

		// Draw Preview Message (Opaque Colors)
		if (selectedTargets.isEmpty()) {
			g.drawCenteredString(this.font, "Please select at least one target!", layout.centerX(), layout.y(240), 0xFFFF5555);
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
			g.drawCenteredString(this.font, "Ready: " + preview, layout.centerX(), layout.y(240), 0xFF55FF55);
		}
		popReferencePose(g);
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
