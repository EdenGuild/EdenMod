package tel.eden.mod.war.radiance;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RadianceCooldownParser {
	private RadianceCooldownParser() {
	}

	private static final Pattern FORMAT_CODES = Pattern.compile("(?:\\u00A7|\\u00C2\\u00A7|&)[0-9a-fk-or]", Pattern.CASE_INSENSITIVE);
	private static final Pattern BRACE_TAGS = Pattern.compile("&\\{[^}]*}");
	private static final Pattern BRACKET_TAGS = Pattern.compile("&\\[[^]]*]");
	private static final Pattern ANGLE_TAGS = Pattern.compile("&<[^>]*>");
	private static final Pattern COOLDOWN_PATTERN = Pattern.compile("(?i)\\bradiance\\b[\\s:(\\[-]*(?:(?:(\\d{1,2})\\s*:\\s*(\\d{2}))|(\\d{1,3})\\s*s)[)\\]]?");
	private static final Pattern NEWLINE_SPLIT = Pattern.compile("\\r?\\n");

	public static Double remainingSeconds(String text) {
		String cleaned = sanitize(text);
		if (cleaned.isBlank()) {
			return null;
		}

		for (String line : NEWLINE_SPLIT.split(cleaned)) {
			Double seconds = remainingSecondsFromLine(line);
			if (seconds != null) {
				return seconds;
			}
		}

		return null;
	}

	private static Double remainingSecondsFromLine(String line) {
		if (line.isBlank()) {
			return null;
		}

		Matcher matcher = COOLDOWN_PATTERN.matcher(line);
		if (!matcher.find()) {
			return null;
		}

		if (matcher.group(1) != null && matcher.group(2) != null) {
			int minutes = parseIntSafe(matcher.group(1));
			int seconds = parseIntSafe(matcher.group(2));
			return minutes >= 0 && seconds >= 0 ? minutes * 60.0 + seconds : null;
		} else if (matcher.group(3) != null) {
			int seconds = parseIntSafe(matcher.group(3));
			return seconds >= 0 ? (double) seconds : null;
		}
		return null;
	}

	private static String sanitize(String value) {
		if (value == null) {
			return "";
		}

		String cleaned = value.replace("\u0000", "");
		cleaned = FORMAT_CODES.matcher(cleaned).replaceAll("");
		cleaned = BRACE_TAGS.matcher(cleaned).replaceAll("");
		cleaned = BRACKET_TAGS.matcher(cleaned).replaceAll("");
		cleaned = ANGLE_TAGS.matcher(cleaned).replaceAll("");
		return cleaned.trim();
	}

	private static int parseIntSafe(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
