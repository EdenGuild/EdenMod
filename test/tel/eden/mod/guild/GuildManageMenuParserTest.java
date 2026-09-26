package tel.eden.mod.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GuildManageMenuParserTest {
	@Test
	void extractsSeasonWeeklyAndTerritoryStatusFromObservedRootMenu() {
		var snapshot = GuildManageMenuParser.parseText(Map.of(11, List.of("§7Season will end in §d4 days", "§d- §7Season Rating (SR): §f3,483,802", "§d- §7Position: §f11"), 13, List.of("§6Current Guild Goal: §f24§7/30"), 14, List.of("§e§lTerritories [0]"), 26, List.of(" ", "§aGuild Alliance:", "§a- §7Blood Orchid [Wilt]", "§a- §7Paladins United [PUN]", " ", "§7Click to view allies"))).orElseThrow();

		assertEquals(3_483_802L, snapshot.seasonRating());
		assertEquals(11, snapshot.seasonPosition());
		assertEquals(24, snapshot.weeklyCompleted());
		assertEquals(30, snapshot.weeklyGoal());
		assertEquals(0, snapshot.territoryCount());
		assertEquals(List.of(new GuildManageSnapshot.Alliance("Blood Orchid", "Wilt"), new GuildManageSnapshot.Alliance("Paladins United", "PUN")), snapshot.alliances());
	}

	@Test
	void pendingInvitesAreNotCountedAsAllies() {
		// Regression: production showed 6 real allies plus 2 sent-but-not-accepted
		// invites, both listed as "- Name [TAG]" lines — the parser must stop
		// collecting once it leaves the "Guild Alliance:" section, not keep matching
		// every dash-bulleted line for the rest of the tooltip.
		var snapshot = GuildManageMenuParser.parseText(Map.of(26, List.of("§dView Diplomacy", " ", "§aGuild Alliance:", "§a- §7Imperial [Imp]", "§a- §7Avicia [AVO]", " ", "§aPending Invites:", "§a- §7ShadowedSerenity [SDY]", "§a- §7Aequitas [Aeq]", " ", "§7No tributes sent or received", " ", "§7Click to view allies"))).orElseThrow();

		assertEquals(List.of(new GuildManageSnapshot.Alliance("Imperial", "Imp"), new GuildManageSnapshot.Alliance("Avicia", "AVO")), snapshot.alliances());
	}

	@Test
	void refusesAnUnrelatedOrUnpopulatedMenu() {
		assertTrue(GuildManageMenuParser.parseText(Map.of(11, List.of("Season has not loaded"))).isEmpty());
	}
}
