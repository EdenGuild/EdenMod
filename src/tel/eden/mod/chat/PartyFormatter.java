package tel.eden.mod.chat;

import tel.eden.mod.net.PartyInfo;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Renders client-side raid party-finder lines so they sit with native guild chat:
 * the green guild shield, then the party detail, then a clickable
 * {@code [JOIN #id]} that runs the {@code /eden party join <id>} client command.
 * Built on the client thread (the shield prefix touches a custom font).
 */
public final class PartyFormatter {
    private PartyFormatter() {}

    /** "<host> wants to raid <raid> (n/max)  [JOIN #id]" with the open note, if any. */
    public static Component partyOpen(PartyInfo party) {
        MutableComponent body = Component.empty()
                .append(Component.literal(party.host()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" wants to raid ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(party.raid()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" (" + party.size() + "/" + party.max() + ") ")
                        .withStyle(ChatFormatting.GRAY))
                .append(joinButton(party.id()));
        if (!party.note().isBlank()) {
            body.append(Component.literal(" — ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("\"" + party.note() + "\"")
                            .withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE).withItalic(true)));
        }
        return line(body);
    }

    /** "<actor> joined party #id (<raid>) (n/max)" */
    public static Component partyJoin(String actor, PartyInfo party) {
        MutableComponent body = Component.empty()
                .append(Component.literal(actor).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" joined party #" + party.id() + " ")
                        .withStyle(ChatFormatting.GREEN))
                .append(Component.literal("(" + party.raid() + ") ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(party.size() + "/" + party.max())
                        .withStyle(ChatFormatting.GRAY));
        return line(body);
    }

    /** "<actor> left party #id (<raid>) (n/max)" */
    public static Component partyLeave(String actor, PartyInfo party) {
        MutableComponent body = Component.empty()
                .append(Component.literal(actor).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" left party #" + party.id() + " ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("(" + party.raid() + ") ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(party.size() + "/" + party.max())
                        .withStyle(ChatFormatting.GRAY));
        return line(body);
    }

    /** "Party #id (<raid>) is full!" */
    public static Component partyFull(PartyInfo party) {
        MutableComponent body = Component.empty()
                .append(Component.literal("Party #" + party.id() + " ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("(" + party.raid() + ") ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("is full! ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(String.join(", ", party.members()))
                        .withStyle(ChatFormatting.GRAY));
        return line(body);
    }

    /** A header plus one clickable line per open party (the {@code /eden party list} reply). */
    public static Component listing(List<PartyInfo> parties) {
        if (parties.isEmpty()) {
            return line(Component.literal("No open raid parties. Start one with /eden party create.")
                    .withStyle(ChatFormatting.GREEN));
        }
        MutableComponent out = line(Component.literal("Open Eden parties (" + parties.size() + "):")
                .withStyle(ChatFormatting.GREEN));
        for (PartyInfo party : parties) {
            out.append("\n");
            out.append(Component.literal("  " + party.raid() + " ").withStyle(ChatFormatting.YELLOW));
            out.append(Component.literal(party.size() + "/" + party.max() + " | ")
                    .withStyle(ChatFormatting.GRAY));
            out.append(Component.literal(party.host() + " ").withStyle(ChatFormatting.AQUA));
            out.append(joinButton(party.id()));
            if (!party.note().isBlank()) {
                out.append(Component.literal(" — ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("\"" + party.note() + "\"")
                                .withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE).withItalic(true)));
            }
        }
        return out;
    }

    /** A short green result line (e.g. "Joined party #105...") for an in-game action. */
    public static Component feedback(String message) {
        return line(Component.literal(message).withStyle(ChatFormatting.GREEN));
    }

    private static MutableComponent line(Component body) {
        return Component.empty().append(DiscordChatFormatter.shieldPrefix()).append(body);
    }

    private static Component joinButton(int id) {
        Style style = Style.EMPTY
                .withColor(ChatFormatting.GREEN)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand("/eden party join " + id))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("Click to join party #" + id)));
        return Component.literal("[JOIN #" + id + "]").setStyle(style);
    }
}
