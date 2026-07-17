package tel.eden.mod.chat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import tel.eden.mod.config.BridgeConfig;

/**
 * Display-only decorations applied to incoming chat before it is shown (in the same
 * re-display path as emote rendering): shouts become click-to-reply, and milestone
 * broadcasts gain a one-shot "[Congratulate]" button. Each decorator returns the
 * original component unchanged when it doesn't apply, so the caller can detect "no
 * change" by reference. Runs on the network thread — no UI access, state is
 * concurrent.
 */
public final class ChatDecorators {
	private ChatDecorators() {
	}

	// Wynncraft milestone broadcast, e.g. "[!] Congratulations to Zasper for reaching level 106!"
	// The name can be nicked ("real/nick", "real(nick)", or a bare nick) and a nickname may
	// contain spaces (e.g. "Mintum/read kagurabachi"), so capture everything up to
	// " for reaching" and resolve it to the real account name for the /msg.
	private static final Pattern CONGRATS = Pattern.compile("\\[!\\] Congratulations to (.+?) for reaching .+!");

	/** Players with an unclaimed congratulate button (one-shot per broadcast). */
	private static final Set<String> pendingCongrats = ConcurrentHashMap.newKeySet();

	public static Component decorate(Component message, BridgeConfig config) {
		Component out = message;
		if (config.shoutsClickable) {
			out = makeShoutClickable(out);
		}
		if (config.clickToCongratulate) {
			out = addCongratulateButton(out, config.congratsMessage);
		}
		return out;
	}

	/** Claim a pending congratulation; false when none exists (already used/expired). */
	public static boolean consumePending(String name) {
		return pendingCongrats.remove(name);
	}

	/** Click a shout to pre-fill {@code /msg <shouter> } (real account name). */
	private static Component makeShoutClickable(Component message) {
		return ShoutParser.shouterRealName(message).<Component>map(name -> message.copy().withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand("/msg " + name + " ")).withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to message " + name))))).orElse(message);
	}

	/** Append a clickable "[Congratulate]" to a milestone broadcast. */
	private static Component addCongratulateButton(Component message, String congratsMessage) {
		Matcher matcher = CONGRATS.matcher(ChatText.normalize(message.getString()));
		if (!matcher.find()) {
			return message;
		}
		// Resolve any nickname to the real account name so /msg reaches the right player.
		String name = ChatText.resolveClickTargetName(message, matcher.group(1));
		pendingCongrats.add(name);
		Style style = Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/eden congratulate " + name)).withHoverEvent(new HoverEvent.ShowText(Component.literal("Send: /msg " + name + " " + congratsMessage)));
		return message.copy().append(Component.literal(" [Congratulate]").setStyle(style));
	}
}
