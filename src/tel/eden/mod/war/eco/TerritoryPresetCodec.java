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

	public static TerritoryPreset decodeBase64(String input) {
		if (input == null || !input.startsWith(PREFIX)) {
			throw new IllegalArgumentException("Invalid Eden preset code: missing " + PREFIX + " prefix");
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
			}
		} else {
			base64 = payload;
		}

		try {
			byte[] compressed = Base64.getUrlDecoder().decode(base64);
			try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed); InflaterInputStream iis = new InflaterInputStream(bais); DataInputStream in = new DataInputStream(iis)) {
				byte ver = in.readByte();
				if (ver != VERSION) {
					throw new IllegalArgumentException("Unsupported preset version: " + ver);
				}
				String name = in.readUTF();
				String notes = in.readUTF();

				int exCount = in.readUnsignedShort();
				Set<String> excluded = new HashSet<>(exCount);
				for (int i = 0; i < exCount; i++) {
					excluded.add(in.readUTF());
				}

				int terrCount = in.readUnsignedShort();
				Map<String, TerritoryPreset.TerritoryLoadout> map = new HashMap<>(terrCount);
				for (int i = 0; i < terrCount; i++) {
					String tName = in.readUTF();
					int dmg = in.readUnsignedByte();
					int atk = in.readUnsignedByte();
					int hp = in.readUnsignedByte();
					int def = in.readUnsignedByte();

					int bonusCount = in.readUnsignedByte();
					Map<String, Integer> bonuses = new HashMap<>(bonusCount);
					for (int b = 0; b < bonusCount; b++) {
						bonuses.put(in.readUTF(), (int) in.readUnsignedByte());
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
		TerritoryPreset preset = GSON.fromJson(json, TerritoryPreset.class);
		if (preset != null && (preset.name() == null || preset.name().isBlank())) {
			preset = new TerritoryPreset("Default", preset.notes(), preset.excludedTerritories(), preset.territories());
		}
		return preset;
	}

	public static TerritoryPreset parse(String input) {
		if (input == null || input.isBlank()) {
			return TerritoryPreset.empty("Default");
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
