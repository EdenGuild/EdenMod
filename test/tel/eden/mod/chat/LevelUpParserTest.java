package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.jupiter.api.Test;

class LevelUpParserTest {
	@Test
	void resolvesANickedCombatLevelRecipientToTheirUsername() {
		Component nick = Component.literal("catboy").withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal("catboy's real username is FadeDave"))));
		Component message = Component.empty().append("[!] Congratulations to ").append(nick).append(" for reaching combat level 120!");

		LevelUp levelUp = LevelUpParser.parse(message).orElseThrow();

		assertEquals("FadeDave", levelUp.name());
		assertEquals("combat level 120", levelUp.detail());
	}
}
