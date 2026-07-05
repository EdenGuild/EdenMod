package tel.eden.mod.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.net.PartyInfo;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.entity.player.PlayerSkin;

public final class PartyManageScreen extends Screen {

	private final Screen parent;
	private final EdenModClient mod;
	private final PartyInfo party;

	private int filledSlots;
	private int maxSize;
	private EditBox noteField;

	private Button btnPlayersMinus;
	private Button btnPlayersPlus;
	private Button btnMaxMinus;
	private Button btnMaxPlus;
	private Button btnUpdate;

	private Identifier iconTarget;
	private final java.util.Map<String, Identifier> headCache = new java.util.HashMap<>();
	private final java.util.Set<String> headFetching = new java.util.HashSet<>();

	public PartyManageScreen(Screen parent, EdenModClient mod, PartyInfo party) {
		super(Component.literal("Manage Guild Party"));
		this.parent = parent;
		this.mod = mod;
		this.party = party;

		int fSlots = 0;
		for (String member : party.members()) {
			if (member.equals("*filled*")) {
				fSlots++;
			}
		}
		this.filledSlots = fSlots;
		this.maxSize = party.max();
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int startY = (this.height - 310) / 2;
		int rightColX = centerX + 10;

		// Max Size Adjusters
		btnMaxMinus = Button.builder(Component.literal("-"), b -> adjustMax(-1)).bounds(rightColX + 55, startY + 100, 20, 20).build();
		btnMaxPlus = Button.builder(Component.literal("+"), b -> adjustMax(1)).bounds(rightColX + 105, startY + 100, 20, 20).build();
		this.addRenderableWidget(btnMaxMinus);
		this.addRenderableWidget(btnMaxPlus);

		// Filled Slots Adjusters
		btnPlayersMinus = Button.builder(Component.literal("-"), b -> adjustFilledSlots(-1)).bounds(rightColX + 55, startY + 124, 20, 20).build();
		btnPlayersPlus = Button.builder(Component.literal("+"), b -> adjustFilledSlots(1)).bounds(rightColX + 105, startY + 124, 20, 20).build();
		this.addRenderableWidget(btnPlayersMinus);
		this.addRenderableWidget(btnPlayersPlus);

		// Note Text Field
		noteField = new EditBox(this.font, rightColX, startY + 175, 160, 20, Component.literal("Party Note"));
		noteField.setMaxLength(100);
		noteField.setValue(party.note());
		this.addRenderableWidget(noteField);

		// Action Buttons
		Button btnCreateInGame = Button.builder(Component.literal("Create In-game"), b -> {
			mod.createInGameParty(party.members());
			onClose();
		}).bounds(rightColX, startY + 220, 160, 20).build();
		btnUpdate = Button.builder(Component.literal("Update Party"), b -> onUpdate()).bounds(rightColX, startY + 245, 160, 20).build();
		Button btnDisband = Button.builder(Component.literal("§cDisband Party"), b -> onDisband()).bounds(rightColX, startY + 270, 160, 20).build();
		Button btnCancel = Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(rightColX, startY + 295, 160, 20).build();

		this.addRenderableWidget(btnCreateInGame);
		this.addRenderableWidget(btnUpdate);
		this.addRenderableWidget(btnDisband);
		this.addRenderableWidget(btnCancel);

		// Register target icon
		String target = party.raid().toLowerCase();
		if (target.contains("grootslangs"))
			iconTarget = registerDynamicIcon("notg");
		else if (target.contains("nexus"))
			iconTarget = registerDynamicIcon("nol");
		else if (target.contains("colossus"))
			iconTarget = registerDynamicIcon("tcc");
		else if (target.contains("anomaly"))
			iconTarget = registerDynamicIcon("tna");
		else if (target.contains("palace"))
			iconTarget = registerDynamicIcon("wtp");
		else if (target.contains("annihilation"))
			iconTarget = registerDynamicIcon("annihilation");
		else
			iconTarget = registerDynamicIcon("other");

		// Add kick buttons for players
		int yPos = startY + 40;
		for (String member : party.members()) {
			if (member.equals("*filled*"))
				continue;
			if (!member.equalsIgnoreCase(party.host())) {
				String finalMember = member;
				Button btnKick = Button.builder(Component.literal("§c\u2716"), b -> onKick(finalMember)).bounds(centerX - 35, yPos - 3, 20, 20).build();
				this.addRenderableWidget(btnKick);
			}
			yPos += 24;
		}

		updateWidgetStates();
	}

	/** Members occupying a real slot (i.e. excluding the ``*filled*`` placeholders). */
	private int realMembers() {
		int filled = 0;
		for (String member : party.members()) {
			if (member.equals("*filled*"))
				filled++;
		}
		return party.members().size() - filled;
	}

	private void adjustFilledSlots(int amount) {
		int limit = maxSize - realMembers();
		filledSlots = Math.max(0, Math.min(limit, filledSlots + amount));
		updateWidgetStates();
	}

	private void adjustMax(int amount) {
		int floor = Math.max(2, realMembers() + filledSlots);
		maxSize = Math.max(floor, Math.min(10, maxSize + amount));
		// Keep filled slots within the new capacity.
		filledSlots = Math.min(filledSlots, Math.max(0, maxSize - realMembers()));
		updateWidgetStates();
	}

	private void updateWidgetStates() {
		int real = realMembers();
		int occupancy = real + filledSlots;
		btnPlayersMinus.active = filledSlots > 0;
		btnPlayersPlus.active = occupancy < maxSize;
		btnMaxMinus.active = maxSize > Math.max(2, occupancy);
		btnMaxPlus.active = maxSize < 10;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		// Draw standard translucent background overlay
		g.fill(0, 0, this.width, this.height, 0xC0000000);

		int centerX = this.width / 2;
		int startY = (this.height - 310) / 2;
		int startX = centerX - 180;
		int panelWidth = 360;
		int panelHeight = 320;
		int rightColX = centerX + 10;

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

		// Vertical divider
		g.fill(centerX - 5, startY + 30, centerX - 4, startY + panelHeight - 20, 0xFF555555);

		super.render(g, mouseX, mouseY, delta);

		// Title
		g.drawCenteredString(this.font, this.title, centerX, startY + 12, 0xFFFFFFFF);

		// LEFT SIDE: Roster
		g.drawCenteredString(this.font, "Party Roster (" + (realMembers() + filledSlots) + "/" + maxSize + ")", startX + (centerX - startX) / 2, startY + 20, 0xFF55FF55);
		int yPos = startY + 40;
		for (String member : party.members()) {
			if (member.equals("*filled*"))
				continue;

			// Draw player head
			Identifier headId = headCache.get(member);
			if (headId == null) {
				g.fill(startX + 10, yPos - 1, startX + 22, yPos + 11, 0xFF444444); // placeholder box
				if (!headFetching.contains(member)) {
					headFetching.add(member);
					java.util.concurrent.CompletableFuture.runAsync(() -> fetchHead(member));
				}
			} else {
				g.pose().pushMatrix();
				g.pose().translate(startX + 10, yPos - 1);
				g.pose().scale(12.0f / 12.0f, 12.0f / 12.0f);
				g.blit(RenderPipelines.GUI_TEXTURED, headId, 0, 0, 0.0f, 0.0f, 12, 12, 12, 12);
				g.pose().popMatrix();
			}

			g.drawString(this.font, member, startX + 28, yPos + 1, member.equalsIgnoreCase(party.host()) ? 0xFFFFD700 : 0xFFFFFFFF);
			yPos += 24;
		}

		int extraRows = Math.max(0, maxSize - realMembers());
		for (int i = 0; i < extraRows; i++) {
			g.fill(startX + 10, yPos - 1, startX + 22, yPos + 11, 0xFF222222); // placeholder box for empty/filled
			g.drawString(this.font, i < filledSlots ? "§7*Filled Slot*" : "§8*Empty*", startX + 28, yPos + 1, 0xFFAAAAAA);
			yPos += 24;
		}

		// RIGHT SIDE: Manage Controls
		if (iconTarget != null) {
			g.pose().pushMatrix();
			g.pose().translate(rightColX + 66, startY + 40);
			g.pose().scale(28.0f / 64.0f, 28.0f / 64.0f);
			g.blit(RenderPipelines.GUI_TEXTURED, iconTarget, 0, 0, 0.0f, 0.0f, 64, 64, 64, 64);
			g.pose().popMatrix();
		}
		g.drawCenteredString(this.font, party.raid(), rightColX + 80, startY + 74, 0xFF55FF55);

		g.drawString(this.font, "Max Size:", rightColX, startY + 106, 0xFFAAAAAA);
		g.drawCenteredString(this.font, String.valueOf(this.maxSize), rightColX + 90, startY + 106, 0xFFFFFFFF);

		g.drawString(this.font, "Filled Slots:", rightColX, startY + 130, 0xFFAAAAAA);
		g.drawCenteredString(this.font, String.valueOf(this.filledSlots), rightColX + 90, startY + 130, 0xFFFFFFFF);

		g.drawString(this.font, "Party Note:", rightColX, startY + 162, 0xFFAAAAAA);
	}

	private void onUpdate() {
		var ws = mod.socket();
		if (ws != null) {
			if (this.maxSize != party.max()) {
				ws.sendPartyManage("max", null, this.maxSize, null);
			}

			if (!noteField.getValue().equals(party.note())) {
				ws.sendPartyManage("note", noteField.getValue(), 0, null);
			}

			int fSlots = 0;
			for (String member : party.members()) {
				if (member.equals("*filled*"))
					fSlots++;
			}
			if (this.filledSlots != fSlots) {
				ws.sendPartyManage("filled", null, this.filledSlots, null);
			}
		}
		onClose();
	}

	private void onDisband() {
		var ws = mod.socket();
		if (ws != null) {
			ws.sendPartyManage("close", null, 0, null);
		}
		onClose();
	}

	private void onKick(String ign) {
		var ws = mod.socket();
		if (ws != null) {
			ws.sendPartyManage("remove", null, 0, ign);
		}
		onClose();
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}

	private Identifier registerDynamicIcon(String name) {
		Identifier loc = Identifier.parse("edenmod:dynamic_icon/" + name);
		var tm = Minecraft.getInstance().getTextureManager();
		try {
			var stream = PartyManageScreen.class.getClassLoader().getResourceAsStream("assets/edenmod/textures/gui/icons/" + name + ".png");
			if (stream != null) {
				var img = NativeImage.read(stream);
				var texture = new DynamicTexture(() -> name, img);
				tm.register(loc, texture);
				stream.close();
				return loc;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	private void fetchHead(String member) {
		try (java.io.InputStream stream = new java.net.URL("https://minotar.net/helm/" + member + "/12.png").openStream()) {
			var img = NativeImage.read(stream);
			Minecraft.getInstance().execute(() -> {
				Identifier loc = Identifier.parse("edenmod:head/" + member.toLowerCase());
				var texture = new DynamicTexture(() -> member, img);
				Minecraft.getInstance().getTextureManager().register(loc, texture);
				headCache.put(member, loc);
			});
		} catch (Exception e) {
			// Silently fail if head cannot be fetched
		}
	}
}
