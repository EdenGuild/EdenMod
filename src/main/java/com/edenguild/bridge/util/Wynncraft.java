package com.edenguild.bridge.util;

import java.util.Locale;

/** Server-gating helper: only act while connected to a Wynncraft server. */
public final class Wynncraft {
    private Wynncraft() {}

    /**
     * Whether {@code address} is a Wynncraft server (case-insensitive, port-tolerant).
     *
     * @param address the server address the client is connected to, or {@code null}
     * @return {@code true} for {@code wynncraft.com} and any {@code *.wynncraft.com}
     */
    public static boolean isWynncraft(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String host = address.toLowerCase(Locale.ROOT).trim();
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        return host.equals("wynncraft.com") || host.endsWith(".wynncraft.com");
    }
}
