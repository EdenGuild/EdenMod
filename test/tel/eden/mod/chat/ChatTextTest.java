package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.jupiter.api.Test;

class ChatTextTest {
	// Built from the same three codepoints as ChatText.WYNN_SOFT_WRAP (a surrogate
	// pair, a private-use char, another surrogate pair) via explicit code points
	// rather than a typed literal, so this can't silently mismatch what the
	// production string actually is.
	private static final String SOFT_WRAP_MARKER = new String(new int[]{0xCFFFC, 0xE001, 0xD0006}, 0, 3);

	@Test
	void normalizeCollapsesWhitespaceAndFixesSpacingBeforePunctuation() {
		assertEquals("FadeDave: hi there", ChatText.normalize("FadeDave  :   hi   there"));
	}

	@Test
	void normalizeStripsPrivateUseGlyphsAndControlCharacters() {
		assertEquals("clean text", ChatText.normalize("clean text"));
	}

	@Test
	void normalizeWhitespaceReturnsEmptyForNullOrBlank() {
		assertEquals("", ChatText.normalizeWhitespace(null));
		assertEquals("", ChatText.normalizeWhitespace("   "));
	}

	@Test
	void normalizeWhitespacePreservesIntentionalSpacingBeforePunctuation() {
		// Unlike normalize(), user-authored text spacing before punctuation is kept.
		assertEquals("hi :shortcode:", ChatText.normalizeWhitespace("hi   :shortcode:"));
	}

	@Test
	void resolveRealNameReturnsTheHoverAttachedToTheNamesOwnSpan() {
		Component name = Component.literal("catboy").withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal("catboy's real username is FadeDave"))));
		Component message = Component.empty().append(name).append(" said hi");

		assertEquals("FadeDave", ChatText.resolveRealName(message, "catboy"));
	}

	@Test
	void resolveRealNameDoesNotLeakAnotherSpansHoverOntoAPlainTextName() {
		// Regression: a whole-message scan would incorrectly attribute the giver's hover
		// to the receiver's plain-text name just because both share one component.
		Component giver = Component.literal("catboy").withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal("catboy's real username is FadeDave"))));
		Component message = Component.empty().append(giver).append(" rewarded an Aspect to Asthae");

		assertEquals("Asthae", ChatText.resolveRealName(message, "Asthae"));
	}

	@Test
	void resolveRealNameFallsBackToTheDisplayedNameWhenAlreadyIgnShaped() {
		Component message = Component.literal("Asthae said hi");

		assertEquals("Asthae", ChatText.resolveRealName(message, "Asthae"));
	}

	@Test
	void resolveRealNameReturnsNullForANonIgnShapedNameWithNoHover() {
		Component message = Component.literal("some weird nickname said hi");

		assertNull(ChatText.resolveRealName(message, "some weird nickname"));
	}

	@Test
	void resolveRealNameAnywherePrefersACandidateTheNicknameIsAPrefixOf() {
		Component message = Component.empty().append(Component.literal("nick").withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal("Real Username: nickname_real")))));

		assertEquals("nickname_real", ChatText.resolveRealNameAnywhere(message, "nick"));
	}

	@Test
	void resolveRealNameAnywhereReturnsNullWithNoHoverOrInsertionMetadata() {
		Component message = Component.literal("plain text with no metadata");

		assertNull(ChatText.resolveRealNameAnywhere(message, "plain"));
	}

	@Test
	void resolveClickTargetNamePeelsANickSlashRealSuffix() {
		Component message = Component.literal("catboy/FadeDave");

		assertEquals("catboy", ChatText.resolveClickTargetName(message, "catboy/FadeDave"));
	}

	@Test
	void resolveClickTargetNamePeelsAParenthesizedNickWrapper() {
		Component message = Component.literal("FadeDave (catboy)");

		assertEquals("FadeDave", ChatText.resolveClickTargetName(message, "FadeDave (catboy)"));
	}

	@Test
	void unwrapWynncraftSoftWrapsReturnsTheInputUnchangedWhenNoMarkerIsPresent() {
		assertEquals("no marker here", ChatText.unwrapWynncraftSoftWraps("no marker here"));
		assertNull(ChatText.unwrapWynncraftSoftWraps(null));
	}

	@Test
	void unwrapWynncraftSoftWrapsJoinsAWrappedUrlWithoutInsertingASpace() {
		String wrapped = "https://wynnbuilder.github.io/\n" + SOFT_WRAP_MARKER + " builder/#abc";

		String result = ChatText.unwrapWynncraftSoftWraps(wrapped);

		assertEquals("https://wynnbuilder.github.io/builder/#abc", result);
	}

	@Test
	void unwrapWynncraftSoftWrapsInsertsASpaceForOrdinaryProse() {
		String wrapped = "some long sentence that wraps\n" + SOFT_WRAP_MARKER + " onto a second line";

		String result = ChatText.unwrapWynncraftSoftWraps(wrapped);

		assertEquals("some long sentence that wraps onto a second line", result);
	}
}
