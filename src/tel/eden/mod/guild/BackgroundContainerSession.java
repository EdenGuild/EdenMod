package tel.eden.mod.guild;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tel.eden.mod.EdenLogger;

/**
 * The one owner of EdenMod's invisible server-container work.
 *
 * <p>A session captures packet snapshots instead of mutating {@code player.containerMenu},
 * so the interaction never opens a real screen. A foreground menu always wins: an
 * unexpected open aborts the session and vanilla receives that packet normally.
 */
public final class BackgroundContainerSession {
	private static final EdenLogger LOGGER = EdenLogger.get();
	private static final String MENU_CLICK_SOUND = "minecraft.block.wooden_pressure_plate.click_on";
	private static final String UI_BUTTON_CLICK_SOUND = "minecraft.ui.button.click";
	private static final String WYNN_UI_CLICK_SOUND = "minecraft.wynn.ui.click";
	private static final long STARTUP_ORPHAN_GRACE_MS = 10_000L;
	private static final long STARTUP_ORPHAN_WINDOW_MS = 30_000L;
	// Wynncraft sends its welcome message before a selected /class world accepts normal
	// commands. Never turn that transient state into a visible command rejection.
	private static final long WORLD_COMMAND_GRACE_MS = 4_000L;

	private volatile int observedServerContainerId = -1;
	private volatile String observedServerTitle = "";
	private volatile long joinedAt;
	private volatile String owner = "";
	private volatile Predicate<String> expectedTitle;
	private volatile int menuSlotCount;
	private volatile int containerId = -1;
	private volatile int stateId;
	private volatile boolean open;
	private volatile boolean aborted;
	private volatile List<ItemStack> items = List.of();
	private volatile long lastContentAt;
	private volatile long snapshotRevision;

	/** Reset for a connection or a {@code /class} world transition. Client thread only. */
	public void onWorldEntered() {
		joinedAt = System.currentTimeMillis();
		observedServerContainerId = -1;
		observedServerTitle = "";
		clearActive();
	}

	/** Stop an owned query before the player enters character selection. */
	public void onWorldLeaving() {
		close();
		observedServerContainerId = -1;
		observedServerTitle = "";
	}

	/** Claim the next matching server menu. Returns false if a real foreign menu is open. */
	public boolean begin(String owner, Predicate<String> expectedTitle, int menuSlotCount) {
		Minecraft mc = Minecraft.getInstance();
		if (owner == null || owner.isBlank() || expectedTitle == null || menuSlotCount <= 0 || mc.player == null || mc.getConnection() == null || open || !this.owner.isEmpty()) {
			return false;
		}
		if (System.currentTimeMillis() - joinedAt < WORLD_COMMAND_GRACE_MS) {
			return false;
		}
		if (mc.screen instanceof AbstractContainerScreen<?>) {
			return false;
		}
		if (observedServerContainerId > 0) {
			long elapsed = System.currentTimeMillis() - joinedAt;
			boolean localInventoryOnly = mc.player.containerMenu == mc.player.inventoryMenu && !(mc.screen instanceof AbstractContainerScreen<?>);
			if (elapsed >= STARTUP_ORPHAN_GRACE_MS && elapsed <= STARTUP_ORPHAN_WINDOW_MS && localInventoryOnly) {
				LOGGER.warn("Background container: closing startup orphan {} ('{}') after {}ms", observedServerContainerId, observedServerTitle, elapsed);
				mc.getConnection().send(new ServerboundContainerClosePacket(observedServerContainerId));
				observedServerContainerId = -1;
				observedServerTitle = "";
			} else {
				return false;
			}
		}
		this.owner = owner;
		this.expectedTitle = expectedTitle;
		this.menuSlotCount = menuSlotCount;
		this.aborted = false;
		return true;
	}

	/** Change the expected next menu after a background click. */
	public boolean expectTransition(Predicate<String> expectedTitle, int menuSlotCount) {
		if (owner.isEmpty() || !open || expectedTitle == null || menuSlotCount <= 0) {
			return false;
		}
		this.expectedTitle = expectedTitle;
		this.menuSlotCount = menuSlotCount;
		this.open = false;
		this.containerId = -1;
		this.items = List.of();
		return true;
	}

	/** Change the retained menu size before an in-place GUI transition. */
	public boolean prepareInPlaceTransition(Predicate<String> nextTitle, int menuSlotCount) {
		if (owner.isEmpty() || !open || nextTitle == null || menuSlotCount <= 0) {
			return false;
		}
		expectedTitle = nextTitle;
		this.menuSlotCount = menuSlotCount;
		// Keep the current snapshot through the click: the packet's carried hash must
		// describe the root-menu item being clicked. The next SET_CONTENT/SET_SLOT
		// packet replaces it and starts the settling delay.
		return true;
	}

	/** Send a packet-level GUI click without creating a visible client screen. */
	public boolean click(int slot, int button, ClickType clickType) {
		Minecraft mc = Minecraft.getInstance();
		if (!open || aborted || slot < 0 || slot >= menuSlotCount || slot >= items.size() || mc.getConnection() == null) {
			return false;
		}
		// Match Wynntils' proven query packet shape. Wynncraft's custom GUI buttons do
		// not use the vanilla state counter, but do expect a hashed target/cursor shape.
		var hashGenerator = mc.getConnection().decoratedHashOpsGenenerator();
		var changedSlots = new Int2ObjectOpenHashMap<HashedStack>();
		changedSlots.put(slot, HashedStack.create(new ItemStack(Items.AIR), hashGenerator));
		HashedStack carried = HashedStack.create(items.get(slot), hashGenerator);
		mc.getConnection().send(new ServerboundContainerClickPacket(containerId, 0, (short) slot, (byte) button, clickType, changedSlots, carried));
		return true;
	}

	/** Close only EdenMod's currently owned background container. */
	public void close() {
		Minecraft mc = Minecraft.getInstance();
		if (!aborted && containerId >= 0 && mc.getConnection() != null) {
			mc.getConnection().send(new ServerboundContainerClosePacket(containerId));
		}
		clearActive();
	}

	public boolean isOpen() {
		return open;
	}

	public boolean isAborted() {
		return aborted;
	}

	public boolean inStartupRecoveryWindow() {
		long elapsed = System.currentTimeMillis() - joinedAt;
		return elapsed >= 0L && elapsed <= STARTUP_ORPHAN_WINDOW_MS;
	}

	public int containerId() {
		return containerId;
	}

	public int stateId() {
		return stateId;
	}

	/** Increments only when the captured menu contents genuinely change. */
	public long snapshotRevision() {
		return snapshotRevision;
	}

	public boolean isOwnedBy(String expectedOwner) {
		return owner.equals(expectedOwner);
	}

	/** Whether a server menu-click sound belongs to Eden's hidden query. */
	public boolean shouldMuteMenuClick(SoundEvent sound, SoundSource source) {
		if (owner.isEmpty()) {
			return false;
		}
		String id = sound.location().toLanguageKey();
		return id.equals(MENU_CLICK_SOUND) || id.equals(UI_BUTTON_CLICK_SOUND) || id.equals(WYNN_UI_CLICK_SOUND);
	}

	public List<ItemStack> items() {
		return items;
	}

	/** Wynncraft frequently follows an opening packet with duplicate/partial contents. */
	public boolean isSettled() {
		return !items.isEmpty() && System.currentTimeMillis() - lastContentAt >= 250L;
	}

	public String blockingState() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return "no player";
		}
		String screen = mc.screen == null ? "none" : mc.screen.getClass().getName();
		long untilCommands = Math.max(0L, WORLD_COMMAND_GRACE_MS - (System.currentTimeMillis() - joinedAt));
		return "screen=" + screen + ", containerId=" + mc.player.containerMenu.containerId + ", inventoryMenu=" + (mc.player.containerMenu == mc.player.inventoryMenu) + ", observedServerContainerId=" + observedServerContainerId + ", observedServerTitle='" + observedServerTitle + "', commandGraceMs=" + untilCommands;
	}

	/** Packet hook: true hides a menu owned by the active background session. */
	public boolean onOpenScreen(ClientboundOpenScreenPacket packet) {
		String title = ChatFormatting.stripFormatting(packet.getTitle().getString());
		observedServerContainerId = packet.getContainerId();
		observedServerTitle = title;
		if (owner.isEmpty()) {
			return false;
		}
		if (!expectedTitle.test(title)) {
			aborted = true;
			LOGGER.info("Background container: {} yielded to foreground menu '{}'", owner, title);
			// The foreground packet must stay entirely foreground-owned. In particular, a
			// delayed expected packet from our already-aborted command must not be hidden
			// later with no worker left to consume/close it.
			owner = "";
			expectedTitle = null;
			open = false;
			containerId = -1;
			items = List.of();
			return false;
		}
		containerId = packet.getContainerId();
		items = List.of();
		open = true;
		LOGGER.info("Background container: {} intercepted {} ('{}')", owner, containerId, title);
		return true;
	}

	/** Packet hook: true consumes menu contents owned by the background session. */
	public boolean onContent(int packetContainerId, int packetStateId, List<ItemStack> packetItems) {
		if (!open || packetContainerId != containerId) {
			return false;
		}
		stateId = packetStateId;
		List<ItemStack> incoming = packetItems.stream().limit(menuSlotCount).map(ItemStack::copy).toList();
		if (sameSnapshot(items, incoming)) {
			return true;
		}
		items = incoming;
		snapshotRevision++;
		lastContentAt = System.currentTimeMillis();
		return true;
	}

	/** Packet hook: true consumes a slot update owned by the background session. */
	public boolean onSlot(int packetContainerId, int packetStateId, int slot, ItemStack item) {
		if (!open || packetContainerId != containerId || slot < 0 || slot >= menuSlotCount) {
			return false;
		}
		stateId = packetStateId;
		List<ItemStack> updated = new ArrayList<>(items);
		while (updated.size() < menuSlotCount) {
			updated.add(ItemStack.EMPTY);
		}
		if (sameStack(updated.get(slot), item)) {
			return true;
		}
		updated.set(slot, item.copy());
		items = List.copyOf(updated);
		snapshotRevision++;
		lastContentAt = System.currentTimeMillis();
		return true;
	}

	/** Packet hook for both server and client-initiated closes. */
	public void onContainerClosed(int closedId) {
		if (closedId == observedServerContainerId) {
			observedServerContainerId = -1;
			observedServerTitle = "";
		}
		if (closedId == containerId) {
			aborted = true;
		}
	}

	private void clearActive() {
		owner = "";
		expectedTitle = null;
		menuSlotCount = 0;
		containerId = -1;
		stateId = 0;
		open = false;
		aborted = false;
		items = List.of();
		lastContentAt = 0L;
		snapshotRevision = 0L;
	}

	private static boolean sameSnapshot(List<ItemStack> left, List<ItemStack> right) {
		if (left.size() != right.size()) {
			return false;
		}
		for (int i = 0; i < left.size(); i++) {
			if (!sameStack(left.get(i), right.get(i))) {
				return false;
			}
		}
		return true;
	}

	private static boolean sameStack(ItemStack left, ItemStack right) {
		return left.getCount() == right.getCount() && ItemStack.isSameItemSameComponents(left, right);
	}
}
