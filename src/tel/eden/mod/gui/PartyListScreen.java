package tel.eden.mod.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.net.PartyInfo;

public final class PartyListScreen extends Screen {

	private final Screen parent;
	private final EdenModClient mod;
	private List<PartyInfo> lastKnownParties = new ArrayList<>();

	public PartyListScreen(Screen parent, EdenModClient mod) {
		super(Component.literal("Party List"));
		this.parent = parent;
		this.mod = mod;
	}

	@Override
	protected void init() {
		super.init();

		this.addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> {
			if (mod.socket() != null) {
				mod.socket().sendPartyList();
			}
		}).bounds(this.width / 2 - 105, this.height - 30, 100, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> {
			this.minecraft.setScreen(parent);
		}).bounds(this.width / 2 + 5, this.height - 30, 100, 20).build());

		if (mod.socket() != null) {
			mod.socket().sendPartyList();
		}

		buildPartyWidgets();
	}

	private void buildPartyWidgets() {
		lastKnownParties = new ArrayList<>(mod.knownParties());

		int y = 50;
		for (PartyInfo party : lastKnownParties) {
			this.addRenderableWidget(Button.builder(Component.literal("Join"), button -> {
				if (mod.socket() != null) {
					mod.socket().sendPartyJoin(party.id());
					this.minecraft.setScreen(null);
				}
			}).bounds(this.width / 2 + 100, y - 5, 40, 20).build());

			y += 30;
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (!lastKnownParties.equals(mod.knownParties())) {
			this.clearWidgets();
			this.init();
		}
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		this.renderMenuBackground(guiGraphics);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		guiGraphics.drawCenteredString(this.font, "Active Parties", this.width / 2, 20, 0xFFFFFF);

		if (lastKnownParties.isEmpty()) {
			guiGraphics.drawCenteredString(this.font, "No active parties", this.width / 2, 60, 0xAAAAAA);
			return;
		}

		int y = 50;
		for (PartyInfo party : lastKnownParties) {
			String text = String.format("#%d - %s (Host: %s) [%d/%d]", party.id(), party.raid(), party.host(), party.members().size(), party.max());
			guiGraphics.drawString(this.font, text, this.width / 2 - 140, y, 0xFFFFFF);
			y += 30;
		}
	}
}
