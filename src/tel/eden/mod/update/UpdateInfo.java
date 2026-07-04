package tel.eden.mod.update;

/** A newer EdenMod release: its version, the jar download URL, and the release page. */
public record UpdateInfo(String version, String jarUrl, String pageUrl) {
}
