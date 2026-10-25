package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerNameResolverMemberListTest {
	@BeforeEach
	void useAMemberList() {
		PlayerNameResolver.reset();
		PlayerNameResolver.setMemberNames(() -> List.of("snail111860", "Asthae"));
	}

	@AfterEach
	void clearIt() {
		PlayerNameResolver.setMemberNames(null);
		PlayerNameResolver.reset();
	}

	@Test
	void recognisesAGuildMembersExactUsernameInAnyCase() {
		assertEquals(Optional.of("snail111860"), PlayerNameResolver.resolveKnown("snail111860"));
		assertEquals(Optional.of("Asthae"), PlayerNameResolver.resolveKnown("asthae"));
	}

	@Test
	void doesNotTreatANicknameOrAPartialNameAsAMember() {
		assertEquals(Optional.empty(), PlayerNameResolver.resolveKnown("catboy"));
		assertEquals(Optional.empty(), PlayerNameResolver.resolveKnown("snail"));
		assertEquals(Optional.empty(), PlayerNameResolver.resolveKnown("rolling girl"));
	}

	@Test
	void forgettingAnAccountsAliasesDropsItsNicknamesButNotItsUsername() {
		PlayerNameResolver.recordAlias("catboy", "FadeDave");
		PlayerNameResolver.recordAlias("rolling girl", "snail111860");
		assertEquals(Optional.of("FadeDave"), PlayerNameResolver.resolveKnown("catboy"));

		PlayerNameResolver.forgetAliasesFor("FadeDave");

		assertEquals(Optional.empty(), PlayerNameResolver.resolveKnown("catboy"));
		assertEquals(Optional.of("FadeDave"), PlayerNameResolver.resolveKnown("FadeDave"));
		assertEquals(Optional.of("snail111860"), PlayerNameResolver.resolveKnown("rolling girl"));
	}

	@Test
	void learnsTheLocalNicknameFromAPartyFinderGreeting() {
		Component greeting = Component.empty().append(Component.literal("Party Finder:").withStyle(net.minecraft.ChatFormatting.DARK_PURPLE)).append(Component.literal(" Hey ")).append(Component.literal("catboy").withStyle(net.minecraft.ChatFormatting.ITALIC)).append(Component.literal(", over here! Join the Nest of the Grootslangs queue and match up with 3 other players!"));

		PlayerNameResolver.learnFromPartyFinder(greeting, "FadeDave");

		assertEquals(Optional.of("FadeDave"), PlayerNameResolver.resolveKnown("catboy"));
	}

	@Test
	void ignoresItalicTextInOtherMessages() {
		Component other = Component.empty().append(Component.literal("Hey ")).append(Component.literal("catboy").withStyle(net.minecraft.ChatFormatting.ITALIC)).append(Component.literal(", over here!"));

		PlayerNameResolver.learnFromPartyFinder(other, "FadeDave");

		assertEquals(Optional.empty(), PlayerNameResolver.resolveKnown("catboy"));
	}
}
