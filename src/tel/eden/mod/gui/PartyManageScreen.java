package tel.eden.mod.gui;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.net.PartyInfo;

public final class PartyManageScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 360;
	private static final int BASE_PANEL_HEIGHT = 320;

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

	private Identifier iconTarget;
	private final Map<String, Identifier> headCache = new HashMap<>();
	private final Set<String> headFetching = new HashSet<>();
	private EdenPanelLayout layout;

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
		updateReferenceSpace();
		layout = EdenPanelLayout.centered(virtualWidth, virtualHeight, BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);
		int rightColX = layout.x(190);

		btnMaxMinus = Button.builder(Component.literal("-"), b -> adjustMax(-1)).bounds(layout.x(245), layout.y(100), layout.w(20), layout.h(20)).build();
		btnMaxPlus = Button.builder(Component.literal("+"), b -> adjustMax(1)).bounds(layout.x(295), layout.y(100), layout.w(20), layout.h(20)).build();
		this.addRenderableWidget(btnMaxMinus);
		this.addRenderableWidget(btnMaxPlus);

		btnPlayersMinus = Button.builder(Component.literal("-"), b -> adjustFilledSlots(-1)).bounds(layout.x(245), layout.y(124), layout.w(20), layout.h(20)).build();
		btnPlayersPlus = Button.builder(Component.literal("+"), b -> adjustFilledSlots(1)).bounds(layout.x(295), layout.y(124), layout.w(20), layout.h(20)).build();
		this.addRenderableWidget(btnPlayersMinus);
		this.addRenderableWidget(btnPlayersPlus);

		noteField = new EditBox(this.font, rightColX, layout.y(175), layout.w(160), layout.h(20), Component.literal("Party Note"));
		noteField.setMaxLength(100);
		noteField.setValue(party.note());
		this.addRenderableWidget(noteField);

		Button btnCreateInGame = Button.builder(Component.literal("Create In-game"), b -> {
			mod.createInGameParty(party.members());
			onClose();
		}).bounds(rightColX, layout.y(220), layout.w(160), layout.h(20)).build();
		Button btnUpdate = Button.builder(Component.literal("Update Party"), b -> onUpdate()).bounds(rightColX, layout.y(245), layout.w(160), layout.h(20)).build();
		Button btnDisband = Button.builder(Component.literal("Â§cDisband Party"), b -> onDisband()).bounds(rightColX, layout.y(270), layout.w(160), layout.h(20)).build();
		Button btnCancel = Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(rightColX, layout.y(295), layout.w(160), layout.h(20)).build();

		this.addRenderableWidget(btnCreateInGame);
		this.addRenderableWidget(btnUpdate);
		this.addRenderableWidget(btnDisband);
		this.addRenderableWidget(btnCancel);

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

		int yPos = layout.y(40);
		for (String member : party.members()) {
			if (member.equals("*filled*"))
				continue;
			if (!member.equalsIgnoreCase(party.host())) {
				String finalMember = member;
				Button btnKick = Button.builder(Component.literal("Â§c\u2716"), b -> onKick(finalMember)).bounds(layout.x(145), yPos - Math.max(1, layout.h(3)), layout.w(20), layout.h(20)).build();
				this.addRenderableWidget(btnKick);
			}
			yPos += layout.h(24);
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
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);

		pushReferencePose(g);
		layout.drawBackground(g);
		layout.drawPanel(g);
		g.fill(layout.x(175), layout.y(30), layout.x(176), layout.y(300), 0xFF555555);

		super.render(g, scaledMouseX, scaledMouseY, delta);

		g.drawCenteredString(this.font, this.title, layout.centerX(), layout.y(12), 0xFFFFFFFF);
		g.drawCenteredString(this.font, "Party Roster (" + (realMembers() + filledSlots) + "/" + maxSize + ")", layout.x(87), layout.y(20), 0xFF55FF55);

		int yPos = layout.y(40);
		int headSize = Math.max(8, layout.h(12));
		for (String member : party.members()) {
			if (member.equals("*filled*"))
				continue;

			Identifier headId = headCache.get(member);
			if (headId == null) {
				g.fill(layout.x(10), yPos - 1, layout.x(10) + headSize, yPos - 1 + headSize, 0xFF444444);
				if (!headFetching.contains(member)) {
					headFetching.add(member);
					java.util.concurrent.CompletableFuture.runAsync(() -> fetchHead(member));
				}
			} else {
				g.pose().pushMatrix();
				g.pose().translate(layout.x(10), yPos - 1);
				g.pose().scale(headSize / 12.0f, headSize / 12.0f);
				g.blit(RenderPipelines.GUI_TEXTURED, headId, 0, 0, 0.0f, 0.0f, 12, 12, 12, 12);
				g.pose().popMatrix();
			}

			g.drawString(this.font, member, layout.x(28), yPos + 1, member.equalsIgnoreCase(party.host()) ? 0xFFFFD700 : 0xFFFFFFFF);
			yPos += layout.h(24);
		}

		int extraRows = Math.max(0, maxSize - realMembers());
		for (int i = 0; i < extraRows; i++) {
			g.fill(layout.x(10), yPos - 1, layout.x(10) + headSize, yPos - 1 + headSize, 0xFF222222);
			g.drawString(this.font, i < filledSlots ? "Â§7*Filled Slot*" : "Â§8*Empty*", layout.x(28), yPos + 1, 0xFFAAAAAA);
			yPos += layout.h(24);
		}

		if (iconTarget != null) {
			int iconSize = Math.max(16, layout.h(28));
			g.pose().pushMatrix();
			g.pose().translate(layout.x(256), layout.y(40));
			g.pose().scale(iconSize / 64.0f, iconSize / 64.0f);
			g.blit(RenderPipelines.GUI_TEXTURED, iconTarget, 0, 0, 0.0f, 0.0f, 64, 64, 64, 64);
			g.pose().popMatrix();
		}
		g.drawCenteredString(this.font, party.raid(), layout.x(270), layout.y(74), 0xFF55FF55);

		g.drawString(this.font, "Max Size:", layout.x(190), layout.y(106), 0xFFAAAAAA);
		g.drawCenteredString(this.font, String.valueOf(this.maxSize), layout.x(280), layout.y(106), 0xFFFFFFFF);

		g.drawString(this.font, "Filled Slots:", layout.x(190), layout.y(130), 0xFFAAAAAA);
		g.drawCenteredString(this.font, String.valueOf(this.filledSlots), layout.x(280), layout.y(130), 0xFFFFFFFF);

		g.drawString(this.font, "Party Note:", layout.x(190), layout.y(162), 0xFFAAAAAA);
		popReferencePose(g);
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
