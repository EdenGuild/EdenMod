package tel.eden.mod.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GuildAllyManagerTest {

	@Test
	void normalizeTargetHandlesQuotesAndWhitespace() {
		assertEquals("", GuildAllyManager.normalizeTarget(null));
		assertEquals("", GuildAllyManager.normalizeTarget("   "));
		assertEquals("The Simpleton", GuildAllyManager.normalizeTarget("The Simpleton"));
		assertEquals("The Simpleton", GuildAllyManager.normalizeTarget("  The   Simpleton  "));
		assertEquals("The Simpleton", GuildAllyManager.normalizeTarget("\"The Simpleton\""));
		assertEquals("The Simpleton", GuildAllyManager.normalizeTarget("'The Simpleton'"));
		assertEquals("EDN", GuildAllyManager.normalizeTarget("EDN"));
		assertEquals("EDN", GuildAllyManager.normalizeTarget("\"EDN\""));
		assertEquals("Kingdom of Greater Lucerne", GuildAllyManager.normalizeTarget("  \"Kingdom   of   Greater Lucerne\"  "));
	}

	@Test
	void cleanFailureReasonStripsPrivateUseGlyphsColorCodesAndExtraSpaces() {
		assertEquals("", GuildAllyManager.cleanFailureReason(null));
		assertEquals("", GuildAllyManager.cleanFailureReason("   "));
		assertEquals("That guild was not found or invalid, try again!", GuildAllyManager.cleanFailureReason("That guild was not found or invalid, try again!"));
		assertEquals("That guild was not found or invalid, try again!", GuildAllyManager.cleanFailureReason("§cThat guild was not found or invalid, try again!"));
		assertEquals("That guild was not found or invalid, try again!", GuildAllyManager.cleanFailureReason("§c That guild was not found or invalid, try again!"));
		assertEquals("That guild was not found or invalid, try again!", GuildAllyManager.cleanFailureReason(" \uE001 That guild was not found or invalid, try again!"));
		assertEquals("That guild was not found or invalid, try again!", GuildAllyManager.cleanFailureReason("§c\uDAFF\uDFFC\uE001  That guild was not found or invalid, try again!§r"));
		assertEquals("You do not have permission to manage guild allies.", GuildAllyManager.cleanFailureReason("§c You do not have permission to manage guild allies."));
	}

	@Test
	void recognizesPromptMessages() {
		org.junit.jupiter.api.Assertions.assertTrue(GuildAllyManager.isPromptMessage("type the guild name or tag in chat"));
		org.junit.jupiter.api.Assertions.assertTrue(GuildAllyManager.isPromptMessage("enter the guild name or tag:"));
		org.junit.jupiter.api.Assertions.assertTrue(GuildAllyManager.isPromptMessage("type the name of the guild you want to ally with"));
		org.junit.jupiter.api.Assertions.assertFalse(GuildAllyManager.isPromptMessage("that guild was not found or invalid"));
		org.junit.jupiter.api.Assertions.assertFalse(GuildAllyManager.isPromptMessage(null));
	}

	@Test
	void permissionErrorStringIsAccurate() {
		assertEquals("Only guild Strategist+ members can manage allies.", GuildAllyManager.PERMISSION_ERROR);
	}
}
