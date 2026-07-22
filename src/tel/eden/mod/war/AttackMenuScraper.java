package tel.eden.mod.war;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.net.BridgeWebSocketClient;

/**
 * Scrapes the enemy territory's defence rating from the {@code /guild attack} GUI.
 *
 * <p>That menu's title is "Attacking: &lt;territory&gt;" and slot 13 is the emerald
 * ("Attack for free") whose lore reads "Territory Defences: &lt;rating&gt;". This is
 * the freshest, most accurate defence source — the advancement scrape in
 * {@link TerritoryData} only covers our <em>own</em> territories, not the enemy one
 * we're about to hit. On a change the value is shown locally at once and reported to
 * the backend, which broadcasts it to every member's {@link AttackTimerMenu}.
 */
public final class AttackMenuScraper {
	private AttackMenuScraper() {
	}

	private static final int DEFENSE_SLOT = 13;
	private static final String TITLE_PREFIX = "Attacking:";
	private static final Pattern DEFENSE = Pattern.compile("Territory Defences:\\s*(.+)");

	// Last (territory, rating) sent, so we only transmit on a change. Reset whenever the
	// attack menu isn't open, so reopening it always re-reports (covers a backend TTL
	// expiry mid-war).
	private static String lastTerritory = "";
	private static String lastDefense = "";

	/** Client-thread tick: if the attack menu is open, scrape + relay slot 13's defence. */
	public static void onTick(Minecraft mc) {
		if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
			lastTerritory = "";
			lastDefense = "";
			return;
		}
		String territory = attackTargetTitle(screen);
		if (territory == null) {
			lastTerritory = "";
			lastDefense = "";
			return;
		}
		AbstractContainerMenu menu = screen.getMenu();
		if (DEFENSE_SLOT >= menu.slots.size()) {
			return;
		}
		String defense = defenceFromLore(menu.getSlot(DEFENSE_SLOT).getItem());
		if (defense == null || (territory.equals(lastTerritory) && defense.equals(lastDefense))) {
			return;
		}
		lastTerritory = territory;
		lastDefense = defense;
		AttackTimerMenu.reportScrapedDefense(territory, defense);
		BridgeWebSocketClient socket = EdenModClient.instance().socket();
		if (socket != null) {
			socket.sendWarDefense(territory, defense);
		}
	}

	/** The territory named in an open "Attacking: &lt;territory&gt;" menu, or null. */
	private static String attackTargetTitle(AbstractContainerScreen<?> screen) {
		String title = strip(screen.getTitle().getString());
		if (!title.startsWith(TITLE_PREFIX)) {
			return null;
		}
		String territory = title.substring(TITLE_PREFIX.length()).trim();
		return territory.isEmpty() ? null : territory;
	}

	private static String defenceFromLore(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) {
			return null;
		}
		for (Component line : lore.lines()) {
			Matcher matcher = DEFENSE.matcher(strip(line.getString()));
			if (matcher.find()) {
				return matcher.group(1).trim();
			}
		}
		return null;
	}

	private static String strip(String text) {
		return text.replaceAll("§.", "").trim();
	}
}
