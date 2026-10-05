package tel.eden.mod.item;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import tel.eden.mod.EdenLogger;

/** Correlated diagnostics for the shared-item rendering pipeline. */
public final class ItemCardDebug {
	private static final ThreadLocal<String> TRACE = new ThreadLocal<>();

	private ItemCardDebug() {
	}

	public static void begin(String itemString) {
		TRACE.set(Integer.toHexString(itemString.hashCode()));
	}

	public static void end() {
		TRACE.remove();
	}

	public static String traceId() {
		return TRACE.get() == null ? "unscoped" : TRACE.get();
	}

	public static void log(String stage, Object details) {
		EdenLogger.get().info("[item-card-debug] [{}] {}: {}", traceId(), stage, details);
	}

	public static List<String> ids(DecodedItem item) {
		return item.identifications().stream().map(id -> id.name() + " " + id.valueText()).toList();
	}

	public static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	/** Preserve the exact UTF-16 payload without relying on invisible chat glyphs. */
	public static List<String> payloadHex(String itemString) {
		return itemString.chars().mapToObj(value -> String.format(Locale.ROOT, "%04x", value)).toList();
	}

	public static String source(Class<?> type) {
		var codeSource = type.getProtectionDomain().getCodeSource();
		return type.getName() + " from " + (codeSource == null ? "unknown source" : codeSource.getLocation()) + " via " + type.getClassLoader();
	}
}
