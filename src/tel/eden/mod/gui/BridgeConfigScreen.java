package tel.eden.mod.gui;

import java.util.List;
import java.util.Optional;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;

/** Factory for the bridge config screen, built on Cloth Config. */
public final class BridgeConfigScreen {

	private BridgeConfigScreen() {
	}

	public static Screen create(Screen parent, EdenModClient mod) {
		BridgeConfig config = mod.config();
		ConfigBuilder builder = ConfigBuilder.create().setParentScreen(parent).setTitle(Component.literal("EdenMod")).setSavingRunnable(config::save);

		ConfigEntryBuilder eb = builder.entryBuilder();
		ConfigCategory cat = builder.getOrCreateCategory(Component.literal("Bridge Settings"));

		cat.addEntry(eb.startTextDescription(linkStatusText(config)).build());
		cat.addEntry(new LinkButtonEntry(parent, mod));

		cat.addEntry(eb.startBooleanToggle(Component.literal("Bridge"), config.enabled).setDefaultValue(true).setSaveConsumer(v -> config.enabled = v).setYesNoTextSupplier(v -> Component.literal(v ? "Enabled" : "Disabled")).build());

		cat.addEntry(eb.startBooleanToggle(Component.literal("My login/logout messages"), config.announceSelfPresence).setDefaultValue(true).setSaveConsumer(v -> config.announceSelfPresence = v).setYesNoTextSupplier(v -> Component.literal(v ? "On" : "Off")).build());

		cat.addEntry(eb.startBooleanToggle(Component.literal("Party feed"), config.partyAnnounce).setDefaultValue(true).setSaveConsumer(v -> config.partyAnnounce = v).setYesNoTextSupplier(v -> Component.literal(v ? "On" : "Off")).build());

		cat.addEntry(eb.startBooleanToggle(Component.literal("Game messages"), config.showGameMessages).setDefaultValue(true).setSaveConsumer(v -> config.showGameMessages = v).setYesNoTextSupplier(v -> Component.literal(v ? "Shown" : "Hidden")).build());

		return builder.build();
	}

	private static Component linkStatusText(BridgeConfig config) {
		if (config.jwt.isEmpty()) {
			return Component.literal("Not linked").withStyle(s -> s.withColor(0xAAAAAA));
		}
		if (!config.hasValidJwt()) {
			return Component.literal("Token expired — re-link").withStyle(s -> s.withColor(0xFF5555));
		}
		String name = config.linkedUsername;
		return Component.literal(name.isEmpty() ? "Linked" : "Linked as " + name).withStyle(s -> s.withColor(0x55FF55));
	}

	/** A single row that renders a full-width "Link account" button. */
	private static final class LinkButtonEntry extends AbstractConfigListEntry<Void> {

		private final Screen parent;
		private final EdenModClient mod;
		private Button linkBtn;

		LinkButtonEntry(Screen parent, EdenModClient mod) {
			super(Component.literal("Link account"), false);
			this.parent = parent;
			this.mod = mod;
		}

		@Override
		public Void getValue() {
			return null;
		}

		@Override
		public Optional<Void> getDefaultValue() {
			return Optional.empty();
		}

		@Override
		public void save() {
		}

		@Override
		public int getItemHeight() {
			return 30;
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return linkBtn == null ? List.of() : List.of(linkBtn);
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return linkBtn == null ? List.of() : List.of(linkBtn);
		}

		@Override
		public void render(GuiGraphics g, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
			if (linkBtn == null) {
				linkBtn = Button.builder(Component.literal("Link account"), b -> onLink()).bounds(x, y, entryWidth, 20).build();
			} else {
				linkBtn.setX(x);
				linkBtn.setY(y);
				linkBtn.setWidth(entryWidth);
			}
			linkBtn.render(g, mouseX, mouseY, delta);
		}

		private void onLink() {
			Minecraft mc = Minecraft.getInstance();
			if (linkBtn != null) {
				linkBtn.setMessage(Component.literal("Opening browser…"));
				linkBtn.active = false;
			}
			mod.startLinkFlow(() -> mc.setScreen(BridgeConfigScreen.create(parent, mod)));
		}
	}
}
