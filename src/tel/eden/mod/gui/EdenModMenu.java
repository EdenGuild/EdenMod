package tel.eden.mod.gui;

import tel.eden.mod.EdenModClient;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu integration: routes the mod's config button to {@link BridgeConfigScreen}. */
public final class EdenModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new BridgeConfigScreen(parent, EdenModClient.instance());
    }
}
