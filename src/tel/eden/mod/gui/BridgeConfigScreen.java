package tel.eden.mod.gui;

import tel.eden.mod.EdenModClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Minimal settings screen: backend URL, link button, enable toggle, status. */
public final class BridgeConfigScreen extends Screen {
    private static final int WIDTH = 220;
    private static final int HEIGHT = 20;

    private final Screen parent;
    private final EdenModClient mod;
    private EditBox urlBox;
    private String status = "";

    public BridgeConfigScreen(Screen parent, EdenModClient mod) {
        super(Component.literal("EdenMod"));
        this.parent = parent;
        this.mod = mod;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2 - WIDTH / 2;
        int y = this.height / 4;

        this.urlBox = new EditBox(this.font, centerX, y + 14, WIDTH, HEIGHT,
                Component.literal("Backend URL"));
        this.urlBox.setMaxLength(256);
        this.urlBox.setHint(Component.literal("https://eden.example.com"));
        this.urlBox.setValue(mod.config().backendBaseUrl);
        addRenderableWidget(this.urlBox);

        addRenderableWidget(Button.builder(Component.literal("Link account"), b -> onLink())
                .bounds(centerX, y + 44, WIDTH, HEIGHT).build());

        addRenderableWidget(Button.builder(Component.literal(enabledLabel()), b -> toggleEnabled(b))
                .bounds(centerX, y + 68, WIDTH, HEIGHT).build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(centerX, y + 100, WIDTH, HEIGHT).build());
    }

    private String enabledLabel() {
        return "Bridge: " + (mod.config().enabled ? "Enabled" : "Disabled");
    }

    private void toggleEnabled(Button button) {
        mod.config().enabled = !mod.config().enabled;
        mod.config().save();
        button.setMessage(Component.literal(enabledLabel()));
    }

    private void onLink() {
        saveUrl();
        if (mod.config().backendBaseUrl.isBlank()) {
            status = "Set the backend URL first.";
            return;
        }
        status = "Opening browser… complete the link there.";
        mod.startLinkFlow(() -> status = mod.config().hasValidJwt() ? "Linked!" : "Not linked.");
    }

    private void saveUrl() {
        mod.config().backendBaseUrl = this.urlBox.getValue().strip();
        mod.config().save();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 4 - 16,
                0xFFFFFF);
        if (!status.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, Component.literal(status), this.width / 2,
                    this.height / 4 + 130, 0xAAAAAA);
        }
    }

    @Override
    public void onClose() {
        saveUrl();
        this.minecraft.setScreen(parent);
    }
}
