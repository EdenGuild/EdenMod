package tel.eden.mod.war.eco;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Dual codec for TerritoryPreset: compressed Base64 ("EDEN1:...") and JSON.
 */
public final class TerritoryPresetCodec {
	private TerritoryPresetCodec() {
	}

	public static final String PREFIX = "EDEN1:";
	private static final byte VERSION = 1;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static String encodeBase64(TerritoryPreset preset) {
		if (preset == null) {
			preset = TerritoryPreset.empty("Default");
		}
		String name = (preset.name() != null) ? preset.name().trim() : "";
		if (name.isEmpty()) {
			name = "Default";
		}
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
			try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater); DataOutputStream out = new DataOutputStream(dos)) {
				out.writeByte(VERSION);
				out.writeUTF(name);
				out.writeUTF(preset.notes());

				// Excluded territories
				Set<String> excluded = preset.excludedTerritories();
				out.writeShort(excluded.size());
				for (String ex : excluded) {
					out.writeUTF(ex);
				}

				// Territories map
				Map<String, TerritoryPreset.TerritoryLoadout> terrs = preset.territories();
				out.writeShort(terrs.size());
				for (Map.Entry<String, TerritoryPreset.TerritoryLoadout> entry : terrs.entrySet()) {
					out.writeUTF(entry.getKey());
					TerritoryPreset.TerritoryLoadout l = entry.getValue();
					out.writeByte(l.damage());
					out.writeByte(l.attack());
					out.writeByte(l.health());
					out.writeByte(l.defence());

					Map<String, Integer> bonuses = l.bonuses();
					out.writeByte(bonuses.size());
					for (Map.Entry<String, Integer> b : bonuses.entrySet()) {
						out.writeUTF(b.getKey());
						out.writeByte(b.getValue());
					}
				}
			} finally {
				deflater.end();
			}
			return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(baos.toByteArray()) + ":" + name;
		} catch (IOException e) {
			throw new RuntimeException("Failed to encode preset to Base64", e);
		}
	}

	public static final int MAX_INPUT_CHARS = 65_536; // 64 KB max input
	public static final int MAX_DECOMPRESSED_BYTES = 1024 * 1024; // 1 MB max decompressed
	public static final int MAX_TERRITORIES = 500;
	public static final int MAX_NAME_LENGTH = 100;
	public static final int MAX_NOTES_LENGTH = 2000;

	private static class LimitedInputStream extends java.io.FilterInputStream {
		private int remaining;

		LimitedInputStream(java.io.InputStream in, int max) {
			super(in);
			this.remaining = max;
		}

		@Override
		public int read() throws IOException {
			if (remaining <= 0) {
				throw new IOException("Decompressed preset exceeded " + (MAX_DECOMPRESSED_BYTES / 1024 / 1024) + " MB limit");
			}
			int b = super.read();
			if (b >= 0) {
				remaining--;
			}
			return b;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (remaining <= 0) {
				throw new IOException("Decompressed preset exceeded " + (MAX_DECOMPRESSED_BYTES / 1024 / 1024) + " MB limit");
			}
			int toRead = Math.min(len, remaining);
			int count = super.read(b, off, toRead);
			if (count > 0) {
				remaining -= count;
			}
			return count;
		}
	}

	public static TerritoryPreset decodeBase64(String input) {
		if (input == null || !input.startsWith(PREFIX)) {
			throw new IllegalArgumentException("Invalid Eden preset code: missing " + PREFIX + " prefix");
		}
		if (input.length() > MAX_INPUT_CHARS) {
			throw new IllegalArgumentException("Preset code too large (max " + (MAX_INPUT_CHARS / 1024) + " KB)");
		}
		String payload = input.substring(PREFIX.length()).trim();
		String overrideName = null;
		int colonIdx = payload.indexOf(':');
		if (colonIdx < 0) {
			colonIdx = payload.indexOf('#');
		}
		String base64;
		if (colonIdx >= 0) {
			base64 = payload.substring(0, colonIdx).trim();
			overrideName = payload.substring(colonIdx + 1).trim();
			if (overrideName.isEmpty()) {
				overrideName = "Default";
			} else if (overrideName.length() > MAX_NAME_LENGTH) {
				overrideName = overrideName.substring(0, MAX_NAME_LENGTH);
			}
		} else {
			base64 = payload;
		}

		try {
			byte[] compressed = Base64.getUrlDecoder().decode(base64);
			try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed); InflaterInputStream iis = new InflaterInputStream(bais); LimitedInputStream lis = new LimitedInputStream(iis, MAX_DECOMPRESSED_BYTES); DataInputStream in = new DataInputStream(lis)) {
				byte ver = in.readByte();
				if (ver != VERSION) {
					throw new IllegalArgumentException("Unsupported preset version: " + ver);
				}
				String name = in.readUTF();
				if (name.length() > MAX_NAME_LENGTH) {
					name = name.substring(0, MAX_NAME_LENGTH);
				}
				String notes = in.readUTF();
				if (notes.length() > MAX_NOTES_LENGTH) {
					notes = notes.substring(0, MAX_NOTES_LENGTH);
				}

				int exCount = in.readUnsignedShort();
				if (exCount > MAX_TERRITORIES) {
					throw new IllegalArgumentException("Too many excluded territories (max " + MAX_TERRITORIES + ")");
				}
				Set<String> excluded = new HashSet<>(exCount);
				for (int i = 0; i < exCount; i++) {
					String ex = in.readUTF();
					if (ex.length() > MAX_NAME_LENGTH) {
						ex = ex.substring(0, MAX_NAME_LENGTH);
					}
					excluded.add(ex);
				}

				int terrCount = in.readUnsignedShort();
				if (terrCount > MAX_TERRITORIES) {
					throw new IllegalArgumentException("Too many territories in preset (max " + MAX_TERRITORIES + ")");
				}
				Map<String, TerritoryPreset.TerritoryLoadout> map = new HashMap<>(terrCount);
				for (int i = 0; i < terrCount; i++) {
					String tName = in.readUTF();
					if (tName.length() > MAX_NAME_LENGTH) {
						tName = tName.substring(0, MAX_NAME_LENGTH);
					}
					int dmg = in.readUnsignedByte();
					int atk = in.readUnsignedByte();
					int hp = in.readUnsignedByte();
					int def = in.readUnsignedByte();

					int bonusCount = in.readUnsignedByte();
					if (bonusCount > 50) {
						throw new IllegalArgumentException("Too many bonuses in territory (max 50)");
					}
					Map<String, Integer> bonuses = new HashMap<>(bonusCount);
					for (int b = 0; b < bonusCount; b++) {
						String bName = in.readUTF();
						if (bName.length() > MAX_NAME_LENGTH) {
							bName = bName.substring(0, MAX_NAME_LENGTH);
						}
						bonuses.put(bName, (int) in.readUnsignedByte());
					}
					map.put(tName, new TerritoryPreset.TerritoryLoadout(dmg, atk, hp, def, bonuses));
				}
				String finalName = (overrideName != null) ? overrideName : name;
				if (finalName == null || finalName.isBlank()) {
					finalName = "Default";
				}
				return new TerritoryPreset(finalName, notes, excluded, map);
			}
		} catch (Exception e) {
			throw new IllegalArgumentException("Corrupt preset code: " + e.getMessage(), e);
		}
	}

	public static String encodeJson(TerritoryPreset preset) {
		return GSON.toJson(preset);
	}

	public static TerritoryPreset decodeJson(String json) {
		if (json == null || json.length() > MAX_INPUT_CHARS) {
			throw new IllegalArgumentException("Preset JSON too large (max " + (MAX_INPUT_CHARS / 1024) + " KB)");
		}
		TerritoryPreset preset = GSON.fromJson(json, TerritoryPreset.class);
		if (preset != null) {
			if (preset.territories() != null && preset.territories().size() > MAX_TERRITORIES) {
				throw new IllegalArgumentException("Too many territories in preset JSON (max " + MAX_TERRITORIES + ")");
			}
			if (preset.name() == null || preset.name().isBlank()) {
				preset = new TerritoryPreset("Default", preset.notes(), preset.excludedTerritories(), preset.territories());
			}
		}
		return preset;
	}

	public static TerritoryPreset parse(String input) {
		if (input == null || input.isBlank()) {
			return TerritoryPreset.empty("Default");
		}
		if (input.length() > MAX_INPUT_CHARS) {
			throw new IllegalArgumentException("Preset code too large (max " + (MAX_INPUT_CHARS / 1024) + " KB)");
		}
		String trimmed = input.trim();
		if (trimmed.startsWith(PREFIX)) {
			return decodeBase64(trimmed);
		}
		if (trimmed.startsWith("{")) {
			return decodeJson(trimmed);
		}
		// Try raw Base64 if prefix was omitted
		try {
			return decodeBase64(PREFIX + trimmed);
		} catch (Exception ignored) {
		}
		throw new IllegalArgumentException("Unrecognized preset format: must start with " + PREFIX + " or be a JSON object");
	}
}
