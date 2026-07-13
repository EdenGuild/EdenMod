package tel.eden.mod.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Formats raw chat-editor text so complete {@code :shortcode:} tokens render as
 * inline bitmap emotes while the underlying value stays plain text.
 */
public final class ChatEmoteFormatter {
	private static final Pattern EMOTE_PATTERN = Pattern.compile(":(?<emote>[a-zA-Z0-9_+\\-]{2,32}):");

	private ChatEmoteFormatter() {
	}

	/**
	 * Format the visible editor text for an {@link net.minecraft.client.gui.components.EditBox}.
	 * Returns {@code null} when the line has no complete emote token so vanilla rendering
	 * can handle it unchanged.
	 */
	public static FormattedCharSequence format(String text) {
		if (!EMOTE_PATTERN.matcher(text).find()) {
			return null;
		}
		return toComponent(text).getVisualOrderText();
	}

	private static MutableComponent toComponent(String text) {
		MutableComponent out = Component.empty();
		Matcher matcher = EMOTE_PATTERN.matcher(text);
		int last = 0;
		while (matcher.find()) {
			if (matcher.start() > last) {
				out.append(Component.literal(text.substring(last, matcher.start())));
			}
			String shortcode = matcher.group("emote");
			Integer codepoint = EmoteRegistry.codepointFor(shortcode);
			if (codepoint != null) {
				String glyph = new String(Character.toChars(codepoint));
				Style emoteStyle = Style.EMPTY.withFont(EmoteRegistry.font()).withColor(ChatFormatting.WHITE).withHoverEvent(new HoverEvent.ShowText(Component.literal(":" + shortcode + ":").withStyle(ChatFormatting.GRAY)));
				out.append(Component.literal(glyph).withStyle(emoteStyle));
			} else {
				out.append(Component.literal(matcher.group()));
			}
			last = matcher.end();
		}
		if (last < text.length()) {
			out.append(Component.literal(text.substring(last)));
		}
		return out;
	}

	/** Convert a formatted sequence back into a component, mainly for picker/suggestion previews. */
	public static Component previewComponent(String text) {
		return Optional.ofNullable(format(text)).map(seq -> toComponent(text)).orElseGet(() -> Component.literal(text));
	}
}
