package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class EconomyMessageFilterTest {
	@Test
	void matchesUserRequestedEconomyMessages() {
		String[] userExamples = {"Player/Nick changed 3 bonuses on Waterfall Cave", "Player/Nick changed 4 upgrades on Waterfall Cave", " Player/Nick set Health upgrade to level 4 on Waterfall Cave", " Player/Nick set Attack upgrade to level 3 on Waterfall Cave", " Player/Nick set Damage upgrade to level 3 on Waterfall Cave", " Player/Nick set Defence upgrade to level 4 on Waterfall Cave", "Player/Nick removed Defence upgrade from Waterfall Cave", " Player/Nick set Stronger Minions bonus to level 1 on  Waterfall Cave", " Player/Nick removed Stronger Minions bonus from Waterfall Cave", " Player/Nick removed Larger Emerald Storage bonus from  Waterfall Cave"};

		for (String example : userExamples) {
			assertTrue(EconomyMessageFilter.isEconomyMessage(example), "Should match: " + example);
			assertTrue(EconomyMessageFilter.isEconomyMessage(Component.literal(example)), "Component should match: " + example);
		}
	}

	@Test
	void matchesExactMessageShapesWithGlyphsAndSpacing() {
		String[] exactLogShapes = {"󏿼󐀆 Player/Nick set Health upgrade to level 4 on Waterfall Cave", "󏿼\uFFFD\u0003\uFFFD Player/Nick set Health upgrade to level 5 on Waterfall Cave", "󏿼󐀆 Player/Nick set Attack upgrade to level 3 on Waterfall Cave", "󏿼󐀆 Player/Nick set Attack upgrade to level 4 on Waterfall Cave", "󏿼󐀆 Player/Nick set Damage upgrade to level 3 on Waterfall Cave", "󏿼󐀆 Player/Nick set Defence upgrade to level 4 on Waterfall Cave", "󏿼󐀆 Player/Nick set Stronger Minions bonus to level 1 on  Waterfall Cave", "󏿼\uFFFD\u0003\uFFFD Player/Nick removed Stronger Minions bonus from Waterfall Cave", "󏿼󐀆 Player/Nick set Larger Emerald Storage bonus to level 1 on  Waterfall Cave", "󏿼󐀆 Player/Nick removed Larger Emerald Storage bonus from  Waterfall Cave"};

		for (String line : exactLogShapes) {
			assertTrue(EconomyMessageFilter.isEconomyMessage(line), "Exact log shape should match: " + line);
			assertTrue(EconomyMessageFilter.isEconomyMessage(Component.literal(line)), "Component from log shape should match: " + line);
		}
	}

	@Test
	void preservesPlayerChatEvenWithSimilarPhrasing() {
		String[] playerChatLines = {"󏿼󐀆 \uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD󏿢󐀂 Player/Nick: thank u", "󏿼󐀆 \uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD󏿢󐀂 Player/Nick: gg all", "󏿼󐀆 \uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD󏿢󐀂 Player/Nick: its stolen name", "󏿼󐀆 \uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD󏿢󐀂 MemberOne/NickOne: i dont have money to craft the new hq builds.", "󏿼󐀆 \uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD󏿊󐀂 MemberTwo/NickTwo: with firefox u can use ad blockers too", "󏿼󐀆 \uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD󏿢󐀂 MemberThree/NickThree: changes your style with mind"};

		for (String chat : playerChatLines) {
			assertFalse(EconomyMessageFilter.isEconomyMessage(chat), "Player chat should NOT match: " + chat);
			assertFalse(EconomyMessageFilter.isEconomyMessage(Component.literal(chat)), "Player chat component should NOT match: " + chat);
		}
	}

	@Test
	void matchesAdditionalBonusesAndTerritories() {
		String[] variations = {"MemberA set Multi Attack bonus to level 2 on Detlas", "MemberB set Resource Rate bonus to level 1 on Almuj", "Player/Nick set Larger Ore Storage bonus to level 3 on Ragni", "Player/Nick set Larger Wood Storage bonus to level 2 on Troms", "Player/Nick set Efficient Emeralds bonus to level 1 on Llevigar", "Player/Nick removed Multi Attack bonus from Detlas", "Player/Nick removed Resource Rate bonus from Almuj", "MemberA set Tower Multi-Attacks bonus to level 1 on Mangled Lake", "MemberA set Damage upgrade to level 8 on Llevigar Farm"};

		for (String variation : variations) {
			assertTrue(EconomyMessageFilter.isEconomyMessage(variation), "Should match: " + variation);
		}
	}

	@Test
	void matchesAllyTaxChanges() {
		String[] taxMessages = {"MemberA changed the ally tax of Path to the Grootslangs to 67%", "MemberA changed the ally tax of Waterfall Cave to 10%", "MemberA changed the ally tax to 15%", "MemberA changed the global ally tax to 20%", "MemberA changed the global tax to 70%", "MemberA changed the tax of Espren to 69%"};

		for (String message : taxMessages) {
			assertTrue(EconomyMessageFilter.isEconomyMessage(message), "Should match tax message: " + message);
			assertTrue(EconomyMessageFilter.isEconomyMessage(Component.literal(message)), "Component should match: " + message);
		}
	}

	@Test
	void matchesWynncraftTerritoryManagementAndResourceAlerts() {
		String[] messages = {"󏿼󐀆 MemberA changed 2 upgrades on Alder Understory", "󏿼󐀆 MemberB changed 3 bonuses on Citadel's Shadow", "󏿼\uFFFD\u0003\uFFFD MemberC changed the global tax to 70%", "󏿼\uFFFD\u0003\uFFFD MemberD changed the tax of Espren to 69%", "󏿼󐀆 MemberA changed the ally tax of Path to the Grootslangs\n󏿼󐀆 to 67%", "󏿼󐀆 MemberA set Efficient Resources bonus to level 3 on Lake\n󏿼󐀆 Rieke", "󏿼󐀆 MemberA set Efficient Resources upgrade to level 3 on Lake\n󏿼󐀆 Rieke", "󏿼󐀆 MemberA changed the style of Wood Sprite Hideaway to \n󏿼󐀆 fastest", "󏿼󐀆 MemberA changed the style of Wood Sprite Hideaway to \n󏿼󐀆 cheapest", "󏿼󐀆 MemberA changed the global style to fastest", "󏿼󐀆 MemberA changed the global style to cheapest", "󏿼\uFFFD\u0003\uFFFD MemberE changed the borders of Harnort Compound to close", "󏿼󐀆 MemberE changed the borders of Harnort Compound to open", "󏿼\uFFFD\u0003\uFFFD MemberF changed the global borders to close", "󏿼󐀆 MemberE set the guild headquarters to Harnort Compound", "󏿼\uFFFD\u0003\uFFFD Territory Citadel's Shadow is using more resources than it\n󏿼󐀆 can store!", "󏿼󐀆 Territory Lake Rieke is using more resources than it can\n󏿼󐀆 store!", "󏿼󐀆 MemberA removed Larger Resource Storage bonus from \n󏿼󐀆 Citadel's Shadow", "󏿼\uFFFD\u0003\uFFFD MemberF removed Damage upgrade from Cliffheart Orc Camp", "󏿼󐀆 MemberB set Larger Resource Storage bonus to level 1 on \n󏿼󐀆 Citadel's Shadow", "󏿼󐀆 MemberG applied the loadout ragebait on Void Valley", "󏿼󐀆 MemberA applied the loadout 11x4 on Entrance to Olux, Lizardman Lake, Overtaken Outpost, and Mangled Lake", "󏿼󐀆 MemberH applied the loadout :void: on Royal Gate§b,\n" + "󏿼󐀆 §3Wellspring of Eternity§b, §3Fort Torann§b, §3Royal Dam§b,\n" + "󏿼󐀆 §3Xima Valley§b, §3Forts in Fall§b, §3The Frog Bog§b,\n" + "󏿼󐀆 §3Citadel's Shadow§b, §3Void Valley§b, §3Toxic Caves§b, " + "and §3Final Step", "󏿼󐀆 Territory Lake Rieke production has stabilised", "󏿼󐀆 Territory Lake Rieke production has stabilized", "&b&{fr:cp}󏿼\uFFFD\u0003\uFFFD&{fr:d} Territory &3Forts in Fall&b is producing more resources than\n" + "&{fr:cp}󏿼󐀆&{fr:d} it can store!"};

		for (String message : messages) {
			assertTrue(EconomyMessageFilter.isEconomyMessage(message), "Should match: " + message);
		}
	}

	@Test
	void doesNotMatchPlayerChatOrWarAlerts() {
		String[] nonEconomyMessages = {"MemberA: I changed 2 upgrades on my build", "MemberC: I changed the global tax to 70%", "MemberA: I changed the ally tax of our route to 67%", "MemberA: I changed the style of my house to fastest", "MemberE: I changed the borders of my build to open", "MemberF: I changed the global borders to close", "MemberE: I set the guild headquarters to Harnort Compound", "MemberF: I removed Damage upgrade from Cliffheart Orc Camp", "MemberG: I applied the loadout ragebait on Void Valley", "[Guild] MemberA: Player/Nick changed 3 bonuses on Waterfall Cave", "MemberA whispers: I set Health upgrade to level 4 on Waterfall Cave", "Territory Lake Rieke is under attack!", "Territory Lake Rieke production increased", "The war for Waterfall Cave will begin in 10 seconds!", "[EDEN] has taken control of Waterfall Cave!", "You have taken control of Waterfall Cave from [TITN]!", "You have taken control of Sablestone Orc Camp from [AVO]! Use /guild territory to defend this territory.", "Your guild has successfully defended Entrance to Olux.", "Your guild has lost the war for Mangled Lake.", "Compass set to [-1648, 0, -5034]"};

		for (String message : nonEconomyMessages) {
			assertFalse(EconomyMessageFilter.isEconomyMessage(message), "Should NOT match: " + message);
			assertFalse(EconomyMessageFilter.isEconomyMessage(Component.literal(message)), "Component should NOT match: " + message);
		}
	}

	@Test
	void handlesNullAndBlank() {
		assertFalse(EconomyMessageFilter.isEconomyMessage((String) null));
		assertFalse(EconomyMessageFilter.isEconomyMessage(""));
		assertFalse(EconomyMessageFilter.isEconomyMessage("   "));
		assertFalse(EconomyMessageFilter.isEconomyMessage((Component) null));
	}
}
