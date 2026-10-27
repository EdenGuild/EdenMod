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
import tel.eden.mod.net.TlsPolicy;

public final class PartyManageScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 360;
	private static final int BASE_PANEL_HEIGHT = 320;

	private final Screen parent;
	private final EdenModClient mod;
	private PartyInfo party;

	private int filledSlots;
	private int maxSize;
	private EditBox noteField;
	private EditBox addPlayerField;

	private Button btnPlayersMinus;
	private Button btnPlayersPlus;
	private Button btnMaxMinus;
	private Button btnMaxPlus;
	private Button btnOpenToAllies;
	private Button btnAddPlayer;

	private Identifier iconTarget;
	private static final Map<String, Identifier> HEAD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
	private static final Set<String> HEAD_FETCHING = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final Map<String, Long> HEAD_FAILURES = new java.util.concurrent.ConcurrentHashMap<>();
	private static final long HEAD_RETRY_DELAY_MS = 60_000L;
	private EdenPanelLayout layout;

	public PartyManageScreen(Screen parent, EdenModClient mod, PartyInfo party) {
		super(Component.literal("Manage Guild Party"));
		this.parent = parent;
		this.mod = mod;
		this.party = party;
		this.filledSlots = filledCount(party);
		this.maxSize = party.max();
	}

	private static int filledCount(PartyInfo party) {
		int filled = 0;
		for (String member : party.members()) {
			if (member.equals("*filled*")) {
				filled++;
			}
		}
		return filled;
	}

	@Override
	public void tick() {
		super.tick();
		if (mod != null) {
			boolean found = false;
			for (PartyInfo p : mod.knownParties()) {
				if (p.id() == party.id()) {
					found = true;
					if (!p.equals(party)) {
						// Keep the host's unsent size edits; take the server's values for the rest.
						boolean maxEdited = this.maxSize != party.max();
						boolean filledEdited = this.filledSlots != filledCount(party);
						this.party = p;
						if (!maxEdited) {
							this.maxSize = p.max();
						}
						if (!filledEdited) {
							this.filledSlots = filledCount(p);
						}

						String curNote = noteField != null ? noteField.getValue() : null;
						boolean noteFocused = noteField != null && noteField.isFocused();
						String curAdd = addPlayerField != null ? addPlayerField.getValue() : null;
						boolean addFocused = addPlayerField != null && addPlayerField.isFocused();

						rebuildWidgets();

						if (noteField != null) {
							if (noteFocused && curNote != null) {
								noteField.setValue(curNote);
								noteField.setFocused(true);
							} else {
								noteField.setValue(p.note());
							}
						}
						if (addPlayerField != null && curAdd != null) {
							addPlayerField.setValue(curAdd);
							if (addFocused) {
								addPlayerField.setFocused(true);
							}
						}
					}
					return;
				}
			}
			if (!found && mod.socket() != null && mod.socket().isConnected()) {
				onClose();
			}
		}
	}

	@Override
	protected void init() {
		updateReferenceSpace();
		layout = EdenPanelLayout.centered(virtualWidth, virtualHeight, BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);
		int rightColX = layout.x(190);

		String self = mod != null ? mod.playerName() : null;
		boolean isHost = self != null && party.host().equalsIgnoreCase(self);

		// Max size adjusters
		btnMaxMinus = Button.builder(Component.literal("-"), b -> adjustMax(-1)).bounds(layout.x(245), layout.y(106), layout.w(20), layout.h(18)).build();
		btnMaxPlus = Button.builder(Component.literal("+"), b -> adjustMax(1)).bounds(layout.x(295), layout.y(106), layout.w(20), layout.h(18)).build();
		btnMaxMinus.active = isHost && party.isCustomSize();
		btnMaxPlus.active = isHost && party.isCustomSize();
		this.addRenderableWidget(btnMaxMinus);
		this.addRenderableWidget(btnMaxPlus);

		// Filled slots adjusters
		btnPlayersMinus = Button.builder(Component.literal("-"), b -> adjustFilledSlots(-1)).bounds(layout.x(245), layout.y(128), layout.w(20), layout.h(18)).build();
		btnPlayersPlus = Button.builder(Component.literal("+"), b -> adjustFilledSlots(1)).bounds(layout.x(295), layout.y(128), layout.w(20), layout.h(18)).build();
		btnPlayersMinus.active = isHost;
		btnPlayersPlus.active = isHost;
		this.addRenderableWidget(btnPlayersMinus);
		this.addRenderableWidget(btnPlayersPlus);

		// Open to Allies toggle button
		btnOpenToAllies = Button.builder(Component.literal(party.ally() ? "§a[✔] Open to Allies" : "§7[ ] Open to Allies"), b -> onToggleAlly()).bounds(rightColX, layout.y(152), layout.w(160), layout.h(18)).build();
		btnOpenToAllies.active = isHost;
		this.addRenderableWidget(btnOpenToAllies);

		// Note field
		noteField = new EditBox(this.font, rightColX, layout.y(186), layout.w(160), layout.h(18), Component.literal("Party Note"));
		noteField.setMaxLength(100);
		noteField.setValue(party.note());
		noteField.setEditable(isHost);
		this.addRenderableWidget(noteField);

		if (isHost) {
			Button btnCreateInGame = Button.builder(Component.literal("Create In-game"), b -> {
				mod.createInGameParty(party.members());
				onClose();
			}).bounds(rightColX, layout.y(214), layout.w(160), layout.h(18)).build();
			Button btnUpdate = Button.builder(Component.literal("Update Party"), b -> onUpdate()).bounds(rightColX, layout.y(236), layout.w(160), layout.h(18)).build();
			Button btnDisband = Button.builder(Component.literal("§cDisband Party"), b -> onDisband()).bounds(rightColX, layout.y(258), layout.w(160), layout.h(18)).build();
			Button btnCancel = Button.builder(Component.literal("Close"), b -> onClose()).bounds(rightColX, layout.y(280), layout.w(160), layout.h(18)).build();

			this.addRenderableWidget(btnCreateInGame);
			this.addRenderableWidget(btnUpdate);
			this.addRenderableWidget(btnDisband);
			this.addRenderableWidget(btnCancel);
		} else {
			Button btnLeave = Button.builder(Component.literal("§cLeave Party"), b -> onLeave()).bounds(rightColX, layout.y(258), layout.w(160), layout.h(18)).build();
			Button btnCancel = Button.builder(Component.literal("Close"), b -> onClose()).bounds(rightColX, layout.y(280), layout.w(160), layout.h(18)).build();

			this.addRenderableWidget(btnLeave);
			this.addRenderableWidget(btnCancel);
		}

		// Target icon
		String target = party.raid().toLowerCase();
		if (target.contains("grootslangs"))
			iconTarget = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/notg.png");
		else if (target.contains("nexus"))
			iconTarget = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/nol.png");
		else if (target.contains("colossus"))
			iconTarget = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/tcc.png");
		else if (target.contains("anomaly"))
			iconTarget = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/tna.png");
		else if (target.contains("palace"))
			iconTarget = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/wtp.png");
		else if (target.contains("annihilation"))
			iconTarget = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/annihilation.png");
		else if (target.contains("festival"))
			iconTarget = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/festival.png");
		else
			iconTarget = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/other.png");

		// Left column: Member rows (Kick button)
		int yPos = layout.y(38);
		int memberIdx = 0;
		for (String member : party.members()) {
			if (member.equals("*filled*")) {
				memberIdx++;
				continue;
			}
			final String currentMember = member;

			// Kick button (host only, cannot kick self)
			if (isHost && !member.equalsIgnoreCase(party.host())) {
				Button btnKick = Button.builder(Component.literal("§c\u2716"), b -> onKick(currentMember)).bounds(layout.x(148), yPos - Math.max(1, layout.h(2)), layout.w(18), layout.h(16)).build();
				this.addRenderableWidget(btnKick);
			}

			yPos += layout.h(24);
			memberIdx++;
		}

		// Add Player by IGN (Host only, bottom of left column)
		if (isHost) {
			addPlayerField = new EditBox(this.font, layout.x(10), layout.y(280), layout.w(110), layout.h(18), Component.literal("IGN"));
			addPlayerField.setMaxLength(16);
			this.addRenderableWidget(addPlayerField);

			btnAddPlayer = Button.builder(Component.literal("Add"), b -> onAddPlayer()).bounds(layout.x(124), layout.y(280), layout.w(44), layout.h(18)).build();
			this.addRenderableWidget(btnAddPlayer);
		}

		updateWidgetStates();
	}

	private void onToggleAlly() {
		var ws = mod != null ? mod.socket() : null;
		if (ws != null) {
			ws.sendPartyAllyToggle(!party.ally());
		}
	}

	private void onAddPlayer() {
		if (addPlayerField == null)
			return;
		String ign = addPlayerField.getValue().trim();
		if (ign.matches("[A-Za-z0-9_]{1,16}") && (realMembers() + filledSlots < maxSize) && party.members().stream().noneMatch(m -> m.equalsIgnoreCase(ign))) {
			var ws = mod != null ? mod.socket() : null;
			if (ws != null) {
				ws.sendPartyManage("add", null, 0, ign);
				addPlayerField.setValue("");
			}
		}
	}

	private void onLeave() {
		var ws = mod != null ? mod.socket() : null;
		if (ws != null) {
			ws.sendPartyLeave(party.id());
		}
		onClose();
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
		String self = mod != null ? mod.playerName() : null;
		boolean isHost = self != null && party.host().equalsIgnoreCase(self);

		int real = realMembers();
		int occupancy = real + filledSlots;
		btnPlayersMinus.active = isHost && filledSlots > 0;
		btnPlayersPlus.active = isHost && occupancy < maxSize;
		btnMaxMinus.active = isHost && party.isCustomSize() && maxSize > Math.max(2, occupancy);
		btnMaxPlus.active = isHost && party.isCustomSize() && maxSize < 10;
		if (btnOpenToAllies != null) {
			btnOpenToAllies.active = isHost;
			btnOpenToAllies.setMessage(Component.literal(party.ally() ? "§a[✔] Open to Allies" : "§7[ ] Open to Allies"));
		}
		if (btnAddPlayer != null) {
			btnAddPlayer.active = isHost && occupancy < maxSize;
		}
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
		String scopeTag = party.ally() ? "§b[Ally] " : "§6[Guild] ";
		g.drawCenteredString(this.font, scopeTag + "Party (" + (realMembers() + filledSlots) + "/" + maxSize + ")", layout.x(87), layout.y(20), 0xFF55FF55);

		int yPos = layout.y(38);
		int headSize = Math.max(8, layout.h(12));
		int memberIdx = 0;
		for (String member : party.members()) {
			if (member.equals("*filled*")) {
				memberIdx++;
				continue;
			}

			Identifier headId = HEAD_CACHE.get(member);
			if (headId == null) {
				g.fill(layout.x(10), yPos - 1, layout.x(10) + headSize, yPos - 1 + headSize, 0xFF444444);
				Long lastFail = HEAD_FAILURES.get(member);
				boolean canRetry = lastFail == null || (System.currentTimeMillis() - lastFail > HEAD_RETRY_DELAY_MS);
				if (canRetry && HEAD_FETCHING.add(member)) {
					java.util.concurrent.CompletableFuture.runAsync(() -> fetchHead(member));
				}
			} else {
				g.pose().pushMatrix();
				g.pose().translate(layout.x(10), yPos - 1);
				g.pose().scale(headSize / 12.0f, headSize / 12.0f);
				g.blit(RenderPipelines.GUI_TEXTURED, headId, 0, 0, 0.0f, 0.0f, 12, 12, 12, 12);
				g.pose().popMatrix();
			}

			String displayName = party.displayMember(memberIdx);
			g.drawString(this.font, displayName, layout.x(26), yPos + 2, member.equalsIgnoreCase(party.host()) ? 0xFFFFD700 : 0xFFFFFFFF);
			yPos += layout.h(24);
			memberIdx++;
		}

		int extraRows = Math.max(0, maxSize - realMembers());
		for (int i = 0; i < extraRows; i++) {
			if (yPos > layout.y(270))
				break;
			g.fill(layout.x(10), yPos - 1, layout.x(10) + headSize, yPos - 1 + headSize, 0xFF222222);
			g.drawString(this.font, i < filledSlots ? "§7*Filled Slot*" : "§8*Empty*", layout.x(26), yPos + 2, 0xFFAAAAAA);
			yPos += layout.h(24);
		}

		if (iconTarget != null) {
			int iconSize = Math.max(16, layout.h(24));
			g.pose().pushMatrix();
			g.pose().translate(layout.x(258), layout.y(34));
			g.pose().scale(iconSize / 64.0f, iconSize / 64.0f);
			g.blit(RenderPipelines.GUI_TEXTURED, iconTarget, 0, 0, 0.0f, 0.0f, 64, 64, 64, 64);
			g.pose().popMatrix();
		}
		g.drawCenteredString(this.font, party.raid(), layout.x(270), layout.y(62), 0xFF55FF55);
		if (party.world() != null && !party.world().isBlank()) {
			g.drawCenteredString(this.font, "§eServer: " + party.world(), layout.x(270), layout.y(74), 0xFFFFFF55);
		}

		g.drawString(this.font, "Max Size:", layout.x(190), layout.y(110), 0xFFAAAAAA);
		g.drawCenteredString(this.font, String.valueOf(this.maxSize), layout.x(275), layout.y(110), 0xFFFFFFFF);

		g.drawString(this.font, "Filled Slots:", layout.x(190), layout.y(132), 0xFFAAAAAA);
		g.drawCenteredString(this.font, String.valueOf(this.filledSlots), layout.x(275), layout.y(132), 0xFFFFFFFF);

		g.drawString(this.font, "Party Note:", layout.x(190), layout.y(174), 0xFFAAAAAA);
		popReferencePose(g);
	}

	private void onUpdate() {
		var ws = mod != null ? mod.socket() : null;
		if (ws != null) {
			// Size edits go out once here, not per click, so +/- doesn't re-render the Discord embed each time.
			// A shrinking party drops its filled slots before its max, or the bot rejects the max as below occupancy.
			boolean maxChanged = this.maxSize != party.max();
			boolean shrinking = this.maxSize < party.max();
			if (maxChanged && !shrinking) {
				ws.sendPartyManage("max", null, this.maxSize, null);
			}
			if (this.filledSlots != filledCount(party)) {
				ws.sendPartyManage("filled", null, this.filledSlots, null);
			}
			if (maxChanged && shrinking) {
				ws.sendPartyManage("max", null, this.maxSize, null);
			}
			if (noteField != null && !noteField.getValue().equals(party.note())) {
				ws.sendPartyManage("note", noteField.getValue(), 0, null);
			}
		}
		onClose();
	}

	private void onDisband() {
		var ws = mod != null ? mod.socket() : null;
		if (ws != null) {
			ws.sendPartyManage("close", null, 0, null);
		}
		onClose();
	}

	private void onKick(String ign) {
		var ws = mod != null ? mod.socket() : null;
		if (ws != null) {
			ws.sendPartyManage("remove", null, 0, ign);
		}
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

	private void fetchHead(String member) {
		if (!member.matches("[A-Za-z0-9_]{1,16}"))
			return;
		NativeImage img = null;
		try {
			java.net.URLConnection conn = TlsPolicy.open(java.net.URI.create("https://minotar.net/helm/" + member + "/12.png").toURL());
			conn.setConnectTimeout(5_000);
			conn.setReadTimeout(5_000);
			try (java.io.InputStream stream = conn.getInputStream()) {
				img = NativeImage.read(stream);
			}
			final NativeImage finalImg = img;
			Minecraft.getInstance().execute(() -> {
				try {
					Identifier loc = Identifier.parse("edenmod:head/" + member.toLowerCase());
					var texture = new DynamicTexture(() -> member, finalImg);
					Minecraft.getInstance().getTextureManager().register(loc, texture);
					HEAD_CACHE.put(member, loc);
					HEAD_FAILURES.remove(member);
				} catch (Exception e) {
					finalImg.close();
				} finally {
					HEAD_FETCHING.remove(member);
				}
			});
		} catch (Exception e) {
			if (img != null) {
				img.close();
			}
			HEAD_FAILURES.put(member, System.currentTimeMillis());
			HEAD_FETCHING.remove(member);
		}
	}
}
