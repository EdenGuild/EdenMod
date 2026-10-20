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

	// Track the last locally applied and successfully sent readings separately. This avoids
	// repeated local work while still retrying a reading that arrived during a reconnect.
	// Reset whenever the menu closes so reopening it always re-reports (covers backend TTL).
	private static String lastObservedTerritory = "";
	private static String lastObservedDefense = "";
	private static String lastSentTerritory = "";
	private static String lastSentDefense = "";
	private static volatile String pendingAttackTerritory;
	private static volatile long pendingAttackTime;
	// The last "Attacking: <territory>" container the server opened, by id, so a click in
	// it is recognised even when no screen for it is showing (the click can be sent before
	// the screen opens, or without one).
	private static int attackContainerId = -1;
	private static String attackContainerTerritory;

	/**
	 * Called when a container slot click is sent. A click on the attack slot of an
	 * "Attacking: &lt;territory&gt;" menu is recorded as a pending attack, confirmed by the
	 * attack sound or by the new timer appearing on the sidebar.
	 */
	public static void onContainerClick(int containerId, int slot) {
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread()) {
			mc.execute(() -> onContainerClick(containerId, slot));
			return;
		}
		if (slot != DEFENSE_SLOT) {
			return;
		}
		String territory = mc.screen instanceof AbstractContainerScreen<?> screen ? attackTargetTitle(screen) : null;
		if (territory == null && containerId == attackContainerId) {
			territory = attackContainerTerritory;
		}
		if (territory == null) {
			return;
		}
		pendingAttackTerritory = territory;
		pendingAttackTime = System.currentTimeMillis();
		AttackTimerMenu.noteAttackClick(territory);
	}

	/**
	 * Called when the client receives an entity.ender_dragon.growl sound packet,
	 * which Wynncraft plays upon a successful attack commit.
	 */
	public static void onAttackSound() {
		String territory = pendingAttackTerritory;
		if (territory != null && System.currentTimeMillis() - pendingAttackTime < 3000L) {
			pendingAttackTerritory = null;
			AttackTimerMenu.confirmQueued(territory);
		}
	}

	/** Called for every server-opened container (client thread): remembers the attack menu's id. */
	public static void onOpenScreen(int containerId, Component title) {
		String territory = attackTerritory(title);
		attackContainerId = territory != null ? containerId : -1;
		attackContainerTerritory = territory;
	}

	/** Client-thread tick: if the attack menu is open, scrape + relay slot 13's defence. */
	public static void onTick(Minecraft mc) {
		if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
			resetLastReading();
			return;
		}
		String territory = attackTargetTitle(screen);
		if (territory == null) {
			resetLastReading();
			return;
		}
		AbstractContainerMenu menu = screen.getMenu();
		if (DEFENSE_SLOT >= menu.slots.size()) {
			return;
		}
		String defense = defenceFromLore(menu.getSlot(DEFENSE_SLOT).getItem());
		if (defense == null) {
			return;
		}
		if (!territory.equals(lastObservedTerritory) || !defense.equals(lastObservedDefense)) {
			lastObservedTerritory = territory;
			lastObservedDefense = defense;
			AttackTimerMenu.reportScrapedDefense(territory, defense);
		}
		BridgeWebSocketClient socket = EdenModClient.instance().socket();
		if (socket != null && (!territory.equals(lastSentTerritory) || !defense.equals(lastSentDefense)) && socket.sendWarDefense(territory, defense)) {
			lastSentTerritory = territory;
			lastSentDefense = defense;
		}
	}

	private static void resetLastReading() {
		lastObservedTerritory = "";
		lastObservedDefense = "";
		lastSentTerritory = "";
		lastSentDefense = "";
	}

	/** The territory named in an open "Attacking: &lt;territory&gt;" menu, or null. */
	private static String attackTargetTitle(AbstractContainerScreen<?> screen) {
		return attackTerritory(screen.getTitle());
	}

	/** The territory named by an "Attacking: &lt;territory&gt;" title, or null. */
	static String attackTerritory(Component titleComponent) {
		if (titleComponent == null) {
			return null;
		}
		String title = strip(titleComponent.getString());
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
