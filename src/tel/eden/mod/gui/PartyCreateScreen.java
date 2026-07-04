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
		this.playersInParty = getScoreboardPartySize();

		this.iconNotg = registerDynamicIcon("notg");
		this.iconNol = registerDynamicIcon("nol");
		this.iconTcc = registerDynamicIcon("tcc");
		this.iconTna = registerDynamicIcon("tna");
		this.iconWtp = registerDynamicIcon("wtp");
		this.iconAnnihilation = registerDynamicIcon("annihilation");
		this.iconOther = registerDynamicIcon("other");
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int startY = (this.height - 255) / 2;

		// Targets Grid
		// Row 1
		btnNotg = Button.builder(Component.literal("Grootslangs"), b -> onTargetClick("Nest of the Grootslangs")).bounds(centerX - 150, startY + 35, 145, 20).build();
		btnNol = Button.builder(Component.literal("Nexus of Light"), b -> onTargetClick("Orphion's Nexus of Light")).bounds(centerX + 5, startY + 35, 145, 20).build();

		// Row 2
		btnTcc = Button.builder(Component.literal("Canyon Colossus"), b -> onTargetClick("The Canyon Colossus")).bounds(centerX - 150, startY + 57, 145, 20).build();
		btnTna = Button.builder(Component.literal("Nameless Anomaly"), b -> onTargetClick("The Nameless Anomaly")).bounds(centerX + 5, startY + 57, 145, 20).build();

		// Row 3
		btnWtp = Button.builder(Component.literal("Wartorn Palace"), b -> onTargetClick("The Wartorn Palace")).bounds(centerX - 150, startY + 79, 145, 20).build();
		btnAnnihilation = Button.builder(Component.literal("Annihilation"), b -> onTargetClick("Annihilation")).bounds(centerX + 5, startY + 79, 145, 20).build();

		// Row 4
		btnOther = Button.builder(Component.literal("Other"), b -> onTargetClick("Other")).bounds(centerX - 150, startY + 101, 145, 20).build();

		this.addRenderableWidget(btnNotg);
		this.addRenderableWidget(btnNol);
		this.addRenderableWidget(btnTcc);
		this.addRenderableWidget(btnTna);
		this.addRenderableWidget(btnWtp);
		this.addRenderableWidget(btnAnnihilation);
		this.addRenderableWidget(btnOther);

		// Max Party Size Adjusters
		btnMaxMinus = Button.builder(Component.literal("-"), b -> adjustMaxPartySize(-1)).bounds(centerX + 35, startY + 128, 20, 20).build();
		btnMaxPlus = Button.builder(Component.literal("+"), b -> adjustMaxPartySize(1)).bounds(centerX + 85, startY + 128, 20, 20).build();

		this.addRenderableWidget(btnMaxMinus);
		this.addRenderableWidget(btnMaxPlus);

		// Players in Party Adjusters
		btnPlayersMinus = Button.builder(Component.literal("-"), b -> adjustPlayersInParty(-1)).bounds(centerX + 35, startY + 150, 20, 20).build();
		btnPlayersPlus = Button.builder(Component.literal("+"), b -> adjustPlayersInParty(1)).bounds(centerX + 85, startY + 150, 20, 20).build();

		this.addRenderableWidget(btnPlayersMinus);
		this.addRenderableWidget(btnPlayersPlus);

		// Note Text Field
		noteField = new EditBox(this.font, centerX - 150, startY + 180, 300, 20, Component.literal("Party Note"));
		noteField.setMaxLength(100);
		this.addRenderableWidget(noteField);

		// Action Buttons
		btnCreate = Button.builder(Component.literal("Create Party"), b -> onCreate()).bounds(centerX - 150, startY + 220, 145, 20).build();
		Button btnCancel = Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(centerX + 5, startY + 220, 145, 20).build();

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
		if (selectedTargets.contains("Other") || selectedTargets.contains("Annihilation")) {
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

		boolean canEditMax = selectedTargets.contains("Other") || selectedTargets.contains("Annihilation");
		btnMaxMinus.active = canEditMax && maxPartySize > 2;
		btnMaxPlus.active = canEditMax && maxPartySize < 10;

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

	private String getShortTargetName(String target) {
		if (target.equals("Nest of the Grootslangs"))
			return "Groots";
		if (target.equals("Orphion's Nexus of Light"))
			return "Nexus";
		if (target.equals("The Canyon Colossus"))
			return "Colossus";
		if (target.equals("The Nameless Anomaly"))
			return "Anomaly";
		if (target.equals("The Wartorn Palace"))
			return "Wartorn";
		return target;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		// Draw standard translucent background overlay
		g.fill(0, 0, this.width, this.height, 0xC0000000);

		int centerX = this.width / 2;
		int startY = (this.height - 255) / 2;
		int startX = centerX - 165;
		int panelWidth = 330;
		int panelHeight = 255;

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

		// Draw icons next to checkmarks inside buttons
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
		g.drawString(this.font, "Max Party Size:", centerX - 150, startY + 134, 0xFFA0A0A0);
		g.drawCenteredString(this.font, String.valueOf(this.maxPartySize), centerX + 70, startY + 134, 0xFFFFFFFF);

		g.drawString(this.font, "Players in Party:", centerX - 150, startY + 156, 0xFFA0A0A0);
		g.drawCenteredString(this.font, String.valueOf(this.playersInParty), centerX + 70, startY + 156, 0xFFFFFFFF);

		g.drawString(this.font, "Party Note:", centerX - 150, startY + 172, 0xFFA0A0A0);

		// Draw Preview Message (Opaque Colors)
		if (selectedTargets.isEmpty()) {
			g.drawCenteredString(this.font, "Please select at least one target!", centerX, startY + 201, 0xFFFF5555);
		} else {
			String preview;
			if (selectedTargets.contains("Annihilation")) {
				preview = "Annihilation (" + playersInParty + "/10)";
			} else if (selectedTargets.contains("Other")) {
				preview = "Other (" + playersInParty + "/" + maxPartySize + ")";
			} else {
				java.util.List<String> shortNames = new java.util.ArrayList<>();
				for (String target : selectedTargets) {
					shortNames.add(getShortTargetName(target));
				}
				preview = String.join(" / ", shortNames) + " (" + playersInParty + "/4)";
			}
			g.drawCenteredString(this.font, "Ready: " + preview, centerX, startY + 201, 0xFF55FF55);
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
		if (btn != null && icon != null) {
			int iconX = btn.getX() + 6;
			int iconY = btn.getY() + 2;
			g.blit(icon, iconX, iconY, 16, 16, 0.0f, 0.0f, 16.0f, 16.0f);
		}
	}

	private Identifier registerDynamicIcon(String name) {
		Identifier loc = Identifier.parse("edenmod:dynamic_icon/" + name);
		var tm = Minecraft.getInstance().getTextureManager();
		if (tm.getTexture(loc) != null) {
			return loc;
		}

		java.io.InputStream stream = null;

		// 1. Try classloader (immune to resource pack reload crashes)
		try {
			stream = PartyCreateScreen.class.getClassLoader().getResourceAsStream("assets/edenmod/textures/gui/icons/" + name + ".png");
		} catch (Exception ignored) {
		}

		// 2. Try Minecraft resource manager fallback
		if (stream == null) {
			try {
				var rm = Minecraft.getInstance().getResourceManager();
				var res = rm.getResource(Identifier.parse("edenmod:textures/gui/icons/" + name + ".png"));
				if (res.isPresent()) {
					stream = res.get().open();
				}
			} catch (Exception ignored) {
			}
		}

		if (stream != null) {
			try (var s = stream) {
				var srcImage = com.mojang.blaze3d.platform.NativeImage.read(s);
				// Create a 256x256 transparent canvas to align size with division logic
				var canvas = new com.mojang.blaze3d.platform.NativeImage(256, 256, true);
				canvas.fillRect(0, 0, 256, 256, 0); // clear alpha
				for (int x = 0; x < 16; x++) {
					for (int y = 0; y < 16; y++) {
						canvas.setPixel(x, y, srcImage.getPixel(x, y));
					}
				}
				var texture = new net.minecraft.client.renderer.texture.DynamicTexture(() -> name, canvas);
				tm.register(loc, texture);
				srcImage.close();
				return loc;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		return null; // Return null so we draw nothing rather than a missing texture box!
	}
}
