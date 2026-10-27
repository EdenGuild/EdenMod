package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class CharacterInfoNicknameTest {
	@Test
	void takesTheItalicPartOfTheRankAndNicknameLine() {
		Component line = Component.empty().append(Component.literal(" Champion ").withStyle(ChatFormatting.GOLD)).append(Component.literal("rolling girl").withStyle(ChatFormatting.ITALIC));

		assertEquals(Optional.of("rolling girl"), CharacterInfoNickname.italicText(line));
	}

	@Test
	void findsNothingWhenThePlayerHasNoNickname() {
		Component line = Component.literal("Champion FadeDave").withStyle(ChatFormatting.GOLD);

		assertEquals(Optional.empty(), CharacterInfoNickname.italicText(line));
	}

	@Test
	void ignoresStrayGlyphsAndColourCodesInsideTheItalicText() {
		Component line = Component.literal(" §fcatboy").withStyle(ChatFormatting.ITALIC);

		assertEquals(Optional.of("catboy"), CharacterInfoNickname.italicText(line));
	}
}
