package tel.eden.mod.guild;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import tel.eden.mod.EdenLogger;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.chat.ChatText;
import tel.eden.mod.gui.AllyProgressScreen;

/**
 * Automates in-game ally addition and removal through Wynncraft's /guild manage
 * and Diplomacy menus using BackgroundContainerSession while displaying a modal
 * progress screen (AllyProgressScreen) to block gameplay input.
 */
public final class GuildAllyManager {
	private static final EdenLogger LOGGER = EdenLogger.get();
	private static final int MANAGE_SLOTS = 27;
	private static final int DIPLOMACY_SLOT = 26;
	private static final int DIPLOMACY_SLOTS = 27;
	private static final int ADD_ALLY_SLOT = 0;
	private static final int REMOVE_ALLIES_SLOT = 18;
	private static final int[] ALLY_SLOTS = {2, 3, 4, 5, 6, 7, 8};

	private static final Pattern ALLY_ITEM = Pattern.compile("^(.+?) \\[([A-Za-z0-9]{2,5})]$");
	private static final Pattern COLOR_CODE = Pattern.compile("[§&][0-9a-fk-orA-FK-OR]");
	private static final long MENU_TIMEOUT_MS = 6_000L;
	public static final String PERMISSION_ERROR = "Only guild Strategist+ members can manage allies.";

	private final BackgroundContainerSession session;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "edenmod-ally-manager");
		t.setDaemon(true);
		return t;
	});

	private volatile boolean running;
	private volatile boolean cancelled;
	private volatile boolean promptAppeared;
	private volatile boolean addAllySuccess;
	private volatile String addAllyFailureReason;
	private volatile boolean removeAllySuccess;
	private volatile String removeAllyFailureReason;

	public GuildAllyManager(BackgroundContainerSession session) {
		this.session = session;
	}

	public boolean isRunning() {
		return running;
	}

	public void requestCancel() {
		cancelled = true;
		onClientRun(session::close);
	}

	public void removeAlly(String nameOrTag) {
		if (EdenModClient.instance() != null && !EdenModClient.instance().isChiefOwnerOrStrategist()) {
			chat(PERMISSION_ERROR, ChatFormatting.RED);
			return;
		}
		if (running) {
			chat("An ally management action is already in progress.", ChatFormatting.RED);
			return;
		}
		String target = normalizeTarget(nameOrTag);
		if (target.isEmpty()) {
			chat("Please specify an ally name or tag to remove.", ChatFormatting.RED);
			return;
		}

		running = true;
		cancelled = false;
		removeAllySuccess = false;
		removeAllyFailureReason = null;
		AllyProgressScreen progress = showProgress("Removing Ally: " + target);

		worker.submit(() -> {
			try {
				progress.setStatus("Opening guild manage...");
				if (!openAndCommand("ally_remove", title -> title.toLowerCase(Locale.ROOT).contains("manage"), MANAGE_SLOTS, "guild manage")) {
					if (!cancelled) {
						chat("Couldn't open the guild manage menu — try again.", ChatFormatting.RED);
					}
					return;
				}
				if (cancelled)
					return;

				progress.setStatus("Opening diplomacy menu...");
				if (!transitionClick(DIPLOMACY_SLOT, title -> title.toLowerCase(Locale.ROOT).contains("diplomacy"), DIPLOMACY_SLOTS)) {
					if (!cancelled) {
						chat("Couldn't open the diplomacy menu.", ChatFormatting.RED);
					}
					return;
				}
				if (cancelled)
					return;

				List<ItemStack> items = session.items();
				if (items.size() <= REMOVE_ALLIES_SLOT || items.get(REMOVE_ALLIES_SLOT).isEmpty()) {
					chat(PERMISSION_ERROR, ChatFormatting.RED);
					return;
				}

				progress.setStatus("Entering removal mode...");
				Boolean enterClick = onClient(() -> session.click(REMOVE_ALLIES_SLOT, 0, ClickType.PICKUP));
				if (!Boolean.TRUE.equals(enterClick)) {
					chat("Failed to enter removal mode.", ChatFormatting.RED);
					return;
				}
				sleep(400);
				if (cancelled)
					return;

				int targetSlot = -1;
				String matchedName = null;
				items = session.items();
				for (int slot : ALLY_SLOTS) {
					if (slot >= items.size())
						continue;
					ItemStack stack = items.get(slot);
					if (stack.isEmpty())
						continue;
					String displayed = cleanFailureReason(stack.getHoverName().getString());
					Matcher m = ALLY_ITEM.matcher(displayed);
					if (m.matches()) {
						String guildName = m.group(1).trim();
						String tag = m.group(2).trim();
						AllyTagCache.remember(guildName, tag);
						if (guildName.equalsIgnoreCase(target) || tag.equalsIgnoreCase(target)) {
							targetSlot = slot;
							matchedName = guildName + " [" + tag + "]";
							break;
						}
					}
				}

				if (targetSlot == -1) {
					chat("Could not find ally matching \"" + target + "\" in diplomacy menu.", ChatFormatting.RED);
					return;
				}

				if (cancelled)
					return;
				progress.setStatus("Selecting " + matchedName + "...");
				final int slotToClick = targetSlot;
				Boolean selectClick = onClient(() -> session.click(slotToClick, 0, ClickType.PICKUP));
				if (!Boolean.TRUE.equals(selectClick)) {
					chat("Failed to select ally for removal.", ChatFormatting.RED);
					return;
				}
				sleep(400);

				if (cancelled)
					return;
				progress.setStatus("Confirming removal...");
				long beforeConfirmRev = session.snapshotRevision();
				removeAllySuccess = false;
				removeAllyFailureReason = null;
				Boolean confirmClick = onClient(() -> session.click(REMOVE_ALLIES_SLOT, 0, ClickType.PICKUP));
				if (!Boolean.TRUE.equals(confirmClick)) {
					chat("Failed to confirm ally removal.", ChatFormatting.RED);
					return;
				}

				boolean confirmed = waitUntil(() -> {
					if (cancelled || removeAllySuccess || removeAllyFailureReason != null) {
						return true;
					}
					if (session.snapshotRevision() > beforeConfirmRev && session.isSettled()) {
						return !isAllyPresentInMenu(target);
					}
					return session.isClosed();
				}, 4000L);

				if (cancelled)
					return;

				if (removeAllyFailureReason != null) {
					chat("Failed to remove ally: " + removeAllyFailureReason, ChatFormatting.RED);
					return;
				}

				boolean verifiedRemoved = removeAllySuccess || (session.snapshotRevision() > beforeConfirmRev && !isAllyPresentInMenu(target));
				if (verifiedRemoved) {
					chat("Successfully removed ally " + matchedName + ".", ChatFormatting.GREEN);
				} else if (session.isOpen() && isAllyPresentInMenu(target)) {
					chat("Failed to remove ally " + matchedName + " (ally still present in diplomacy menu).", ChatFormatting.RED);
				} else {
					chat("Could not verify removal of ally " + matchedName + " (timed out waiting for confirmation).", ChatFormatting.RED);
				}
			} catch (Exception e) {
				LOGGER.error("Error removing ally", e);
				chat("Failed to remove ally: " + e.getMessage(), ChatFormatting.RED);
			} finally {
				onClientRun(session::close);
				progress.markDone();
				running = false;
			}
		});
	}

	public void addAlly(String nameOrTag) {
		if (EdenModClient.instance() != null && !EdenModClient.instance().isChiefOwnerOrStrategist()) {
			chat(PERMISSION_ERROR, ChatFormatting.RED);
			return;
		}
		if (running) {
			chat("An ally management action is already in progress.", ChatFormatting.RED);
			return;
		}
		String target = normalizeTarget(nameOrTag);
		if (target.isEmpty()) {
			chat("Please specify an ally name or tag to add.", ChatFormatting.RED);
			return;
		}

		running = true;
		cancelled = false;
		promptAppeared = false;
		addAllySuccess = false;
		addAllyFailureReason = null;
		AllyProgressScreen progress = showProgress("Adding Ally: " + target);

		worker.submit(() -> {
			try {
				progress.setStatus("Opening guild manage...");
				if (!openAndCommand("ally_add", title -> title.toLowerCase(Locale.ROOT).contains("manage"), MANAGE_SLOTS, "guild manage")) {
					if (!cancelled) {
						chat("Couldn't open the guild manage menu — try again.", ChatFormatting.RED);
					}
					return;
				}
				if (cancelled)
					return;

				progress.setStatus("Opening diplomacy menu...");
				if (!transitionClick(DIPLOMACY_SLOT, title -> title.toLowerCase(Locale.ROOT).contains("diplomacy"), DIPLOMACY_SLOTS)) {
					if (!cancelled) {
						chat("Couldn't open the diplomacy menu.", ChatFormatting.RED);
					}
					return;
				}
				if (cancelled)
					return;

				List<ItemStack> items = session.items();
				if (items.size() <= ADD_ALLY_SLOT || items.get(ADD_ALLY_SLOT).isEmpty()) {
					chat(PERMISSION_ERROR, ChatFormatting.RED);
					return;
				}

				progress.setStatus("Checking ally capacity...");
				ItemStack slot0 = items.get(ADD_ALLY_SLOT);
				ItemLore lore = slot0.get(DataComponents.LORE);
				if (lore != null) {
					for (var line : lore.lines()) {
						String s = stripCodes(line.getString()).toLowerCase(Locale.ROOT);
						if (s.contains("do not have any") || s.contains("7/7") || s.contains("no ally slots available")) {
							chat("Cannot add ally: no ally slots available (all slots used).", ChatFormatting.RED);
							return;
						}
					}
				}

				if (cancelled)
					return;
				progress.setStatus("Submitting alliance request for " + target + "...");
				promptAppeared = false;
				Boolean addClick = onClient(() -> session.click(ADD_ALLY_SLOT, 0, ClickType.PICKUP));
				if (!Boolean.TRUE.equals(addClick)) {
					chat("Failed to click add ally.", ChatFormatting.RED);
					return;
				}

				// Wait for the server container to close or the prompt message to appear
				// before sending the chat packet, preventing blurting the tag into public chat under lag.
				boolean promptReady = waitUntil(() -> promptAppeared || session.isClosed() || cancelled, 5000L);
				if (cancelled)
					return;
				if (!promptReady) {
					chat("Timed out waiting for server ally prompt (server lag).", ChatFormatting.RED);
					return;
				}

				addAllySuccess = false;
				addAllyFailureReason = null;

				Minecraft mc = Minecraft.getInstance();
				onClientRun(() -> {
					if (mc.getConnection() != null) {
						mc.getConnection().sendChat(target);
					}
				});

				long deadline = System.currentTimeMillis() + 5000L;
				while (System.currentTimeMillis() < deadline && addAllyFailureReason == null && !addAllySuccess && !cancelled) {
					sleep(50);
				}
				if (cancelled)
					return;

				if (addAllyFailureReason != null) {
					String cleanReason = cleanFailureReason(addAllyFailureReason);
					chat("Failed to add ally: " + (cleanReason.isEmpty() ? "Unknown error" : cleanReason), ChatFormatting.RED);
				} else if (addAllySuccess) {
					chat("Sent alliance request to " + target + ".", ChatFormatting.GREEN);
				} else {
					chat("Timed out waiting for alliance response for " + target + " (server may be lagging).", ChatFormatting.YELLOW);
				}
			} catch (Exception e) {
				LOGGER.error("Error adding ally", e);
				chat("Failed to add ally: " + e.getMessage(), ChatFormatting.RED);
			} finally {
				onClientRun(session::close);
				progress.markDone();
				running = false;
			}
		});
	}

	private AllyProgressScreen showProgress(String title) {
		Minecraft mc = Minecraft.getInstance();
		AllyProgressScreen.ProgressListener listener = new AllyProgressScreen.ProgressListener() {
			@Override
			public void onProgress(String status) {
			}

			@Override
			public void onDone() {
			}

			@Override
			public void requestCancel() {
				GuildAllyManager.this.requestCancel();
			}
		};
		AllyProgressScreen screen = new AllyProgressScreen(title, listener);
		mc.execute(() -> {
			if (!screen.isDone()) {
				mc.setScreen(screen);
			}
		});
		return screen;
	}

	private boolean openAndCommand(String owner, Predicate<String> title, int slots, String command) {
		Boolean opened = onClient(() -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.getConnection() == null) {
				return false;
			}
			if (!session.begin(owner, title, slots)) {
				LOGGER.info("Guild ally: session.begin failed — {}", session.blockingState());
				return false;
			}
			mc.getConnection().sendCommand(command);
			return true;
		});
		if (!Boolean.TRUE.equals(opened)) {
			return false;
		}
		if (!waitUntil(() -> (session.isOpen() && session.isSettled() && session.items().size() >= slots) || session.isAborted() || cancelled, MENU_TIMEOUT_MS)) {
			onClientRun(session::close);
			return false;
		}
		return !session.isAborted() && !cancelled;
	}

	private boolean transitionClick(int slot, Predicate<String> nextTitle, int nextSlots) {
		Long before = onClient(() -> {
			long rev = session.snapshotRevision();
			return session.prepareInPlaceTransition(nextTitle, nextSlots) && session.click(slot, 0, ClickType.PICKUP) ? rev : null;
		});
		if (before == null) {
			return false;
		}
		boolean settled = waitUntil(() -> session.isAborted() || cancelled || (session.snapshotRevision() > before && session.isSettled()), MENU_TIMEOUT_MS);
		return settled && !session.isAborted() && !cancelled;
	}

	private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return true;
			}
			sleep(50);
		}
		return condition.getAsBoolean();
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static <T> T onClient(Supplier<T> action) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) {
			return action.get();
		}
		CompletableFuture<T> future = new CompletableFuture<>();
		mc.execute(() -> {
			try {
				future.complete(action.get());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		try {
			return future.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			return null;
		}
	}

	private static void onClientRun(Runnable action) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) {
			action.run();
		} else {
			mc.execute(action);
		}
	}

	private static void chat(String message, ChatFormatting color) {
		Minecraft mc = Minecraft.getInstance();
		onClientRun(() -> {
			if (mc.player != null) {
				mc.player.displayClientMessage(Component.literal(message).withStyle(color), false);
			}
		});
	}

	private boolean isAllyPresentInMenu(String target) {
		List<ItemStack> items = session.items();
		for (int slot : ALLY_SLOTS) {
			if (slot >= items.size()) {
				continue;
			}
			ItemStack stack = items.get(slot);
			if (stack.isEmpty()) {
				continue;
			}
			String displayed = cleanFailureReason(stack.getHoverName().getString());
			Matcher m = ALLY_ITEM.matcher(displayed);
			if (m.matches()) {
				String guildName = m.group(1).trim();
				String tag = m.group(2).trim();
				if (guildName.equalsIgnoreCase(target) || tag.equalsIgnoreCase(target)) {
					return true;
				}
			}
		}
		return false;
	}

	public static boolean isPromptMessage(String lowerText) {
		if (lowerText == null) {
			return false;
		}
		return (lowerText.contains("type") && (lowerText.contains("guild") || lowerText.contains("tag") || lowerText.contains("name"))) || (lowerText.contains("enter") && (lowerText.contains("guild") || lowerText.contains("tag") || lowerText.contains("name"))) || (lowerText.contains("ally") && (lowerText.contains("guild name") || lowerText.contains("guild tag") || lowerText.contains("type") || lowerText.contains("enter")));
	}

	public void onSystemChat(String text) {
		if (text == null || !running) {
			return;
		}
		String cleaned = cleanFailureReason(text);
		String lower = cleaned.toLowerCase(Locale.ROOT);
		if (isPromptMessage(lower)) {
			promptAppeared = true;
		}
		if (lower.contains("not found or invalid") || lower.contains("already allied") || lower.contains("already pending") || lower.contains("cannot ally") || (lower.contains("maximum") && lower.contains("allies")) || lower.contains("no ally slots") || lower.contains("do not have permission")) {
			addAllyFailureReason = cleaned;
		} else if (lower.contains("alliance request") || lower.contains("requested an alliance") || lower.contains("sent an alliance request") || lower.contains("alliance request sent")) {
			addAllySuccess = true;
		} else if (lower.contains("no longer allied") || (lower.contains("removed") && lower.contains("ally")) || lower.contains("ended alliance") || (lower.contains("alliance") && lower.contains("ended")) || lower.contains("successfully removed")) {
			removeAllySuccess = true;
		} else if (lower.contains("failed to remove") || lower.contains("cannot remove") || lower.contains("could not remove")) {
			removeAllyFailureReason = cleaned;
		}
	}

	static String normalizeTarget(String input) {
		if (input == null) {
			return "";
		}
		String trimmed = input.trim();
		if ((trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) || (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2)) {
			trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
		}
		return trimmed.replaceAll("\\s+", " ");
	}

	static String cleanFailureReason(String text) {
		if (text == null) {
			return "";
		}
		String noCodes = stripCodes(text);
		return ChatText.normalize(noCodes).strip();
	}

	private static String stripCodes(String text) {
		return COLOR_CODE.matcher(text).replaceAll("");
	}
}
