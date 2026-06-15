package com.edenguild.bridge.gui;

import com.edenguild.bridge.EdenBridgeClient;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu integration: routes the mod's config button to {@link BridgeConfigScreen}. */
public final class EdenModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new BridgeConfigScreen(parent, EdenBridgeClient.instance());
    }
}
