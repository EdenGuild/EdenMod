package tel.eden.mod.guild;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import tel.eden.mod.EdenLogger;

/** Periodically reads root guild status and the newest General guild-log page invisibly. */
public final class GuildLogSync {
	private static final EdenLogger LOGGER = EdenLogger.get();
	private static final long INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);
	private static final long OPEN_TIMEOUT_MS = 5_000L;
	private static final long POLL_MS = 25L;
	private static final long BLOCKED_RETRY_MS = 5_000L;
	// Package-visible: GuildMenuScraper validates a foreground screen's own container
	// slot count against these same numbers before treating it as settled.
	static final int MANAGE_SLOTS = 27;
	static final int LOG_SLOTS = 54;
	private static final int PUBLIC_BANK_FILTER_SLOT = 6;
	private static final int HIGH_RANKED_BANK_FILTER_SLOT = 7;
	// Guild Log navigation: column 1 / row 3 is Previous Page; column 1 / row 6
	// is the Next Page button, which moves to older entries.  Entry slots are
	// deliberately bounded to the 4 x 8 log area and never include player inventory.
	private static final int NEXT_PAGE_SLOT = 45;
	private static final int MAX_POST_GIFT_LOG_PAGES = 8;
	// Wynncraft's own Guild Log can take a while to show a just-completed payout
	// (see GUILD_SYNC_TODO.md's "slow log propagation" note) — finding the boundary
	// with zero newer rows on the first look is not proof the payout is missing.
	// Retry for a while so a slow log doesn't read as "nothing happened", and log
	// each attempt's elapsed time so the real-world delay is visible in production.
	private static final long POST_GIFT_LOG_WAIT_MS = 20_000L;
	private static final long POST_GIFT_LOG_RETRY_INTERVAL_MS = 2_000L;
	private static final int[] ENTRY_SLOTS = {19, 20, 21, 22, 23, 24, 25, 26, 28, 29, 30, 31, 32, 33, 34, 35, 37, 38, 39, 40, 41, 42, 43, 44, 46, 47, 48, 49, 50, 51, 52, 53};
	// entryKey() is only as precise as Wynncraft's own log timestamp, which has no
	// seconds field — two genuinely distinct rows (e.g. the same player self-gifted
	// the same reward type twice within one minute, common while testing) can produce
	// an identical key. Matching a run of several CONSECUTIVE keys against the
	// baseline's own order, rather than single-key set membership, is immune to this:
	// a newly-inserted row shifts every older row down by exactly one position, so the
	// aligned run can only start after all genuinely new rows have been counted.
	private static final int PAYOUT_BOUNDARY_WINDOW = 3;

	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "eden-guild-log");
		thread.setDaemon(true);
		return thread;
	});
	private final BackgroundContainerSession session;
	private volatile long nextSyncAt;
	private volatile boolean running;
	private volatile long lastProgressLogAt;
	private volatile boolean dumpedLogContents;
	private volatile GuildManageSnapshot lastManageSnapshot;
	private volatile boolean worldTransition;
	private volatile BiConsumer<String, List<GuildLogEntry>> reporter;
	private volatile Consumer<GuildManageSnapshot> manageReporter;
	private volatile Consumer<GuildRewardStorageSnapshot> storageReporter;
	// Fixed for the whole session (see reportEntries), not refreshed per read/per
	// gift — cleared only on world join/leave. Retained only in memory to mark the
	// payout boundary; raw lore is never sent to the bridge.
	private volatile List<GuildLogEntry> lastGeneralBaseline = List.of();

	public GuildLogSync(BackgroundContainerSession session) {
		this.session = session;
	}

	public void setReporter(BiConsumer<String, List<GuildLogEntry>> reporter) {
		this.reporter = reporter;
	}

	/** Receives every complete root-menu status read for optional bridge reporting. */
	public void setManageReporter(Consumer<GuildManageSnapshot> manageReporter) {
		this.manageReporter = manageReporter;
	}

	public void setStorageReporter(Consumer<GuildRewardStorageSnapshot> storageReporter) {
		this.storageReporter = storageReporter;
	}
	public void requestSoon() {
		nextSyncAt = 0L;
	}

	/**
	 * Read only the General Guild Log filter and return its typed rows, lazily
	 * establishing {@link #lastGeneralBaseline} if this session doesn't have one yet
	 * (it does not refresh an existing one — see that field's doc). Used right before
	 * a payout: only the General filter ever feeds that baseline, so scanning the two
	 * bank filters here too (as a periodic {@link #sync()} does) would just add
	 * avoidable round-trip time before every gift.
	 *
	 * <p>Submitted to {@link #worker} unconditionally rather than fast-failing when
	 * {@link #running} is already true: the worker is single-threaded, so this simply
	 * queues behind whatever's currently running (typically a periodic {@link #sync()}
	 * a few seconds from finishing) instead of refusing the whole gift outright.
	 */
	public CompletableFuture<List<GuildLogEvent>> reconcileNow() {
		if (worldTransition) {
			return CompletableFuture.failedFuture(new IllegalStateException("Guild Log sync is unavailable during a world transition"));
		}
		CompletableFuture<List<GuildLogEvent>> result = new CompletableFuture<>();
		worker.submit(() -> {
			running = true;
			try {
				result.complete(syncGeneralRowsOnly());
			} catch (Exception e) {
				result.completeExceptionally(e);
			} finally {
				running = false;
				LOGGER.info("Guild log: payout reconciliation finished");
			}
		});
		return result;
	}

	/**
	 * Read General-log pages after a payout until the saved pre-payout page is found,
	 * retrying for up to {@link #POST_GIFT_LOG_WAIT_MS} if nothing newer turns up yet.
	 * Only the rows newer than that boundary are returned.  Unlike a normal sync,
	 * these rows are not published as a generic snapshot: they are an explicit,
	 * bounded settlement record and must never make old history look newly observed.
	 *
	 * <p>Submitted to {@link #worker} unconditionally — see {@link #reconcileNow()}'s
	 * doc for why a busy {@link #running} worker is queued behind, not failed on.
	 */
	public CompletableFuture<List<GuildLogEvent>> reconcileAfterBaseline() {
		List<GuildLogEntry> baseline = lastGeneralBaseline;
		if (baseline.isEmpty()) {
			return CompletableFuture.failedFuture(new IllegalStateException("no Guild Log baseline is available"));
		}
		if (worldTransition) {
			return CompletableFuture.failedFuture(new IllegalStateException("Guild Log sync is unavailable during a world transition"));
		}
		CompletableFuture<List<GuildLogEvent>> result = new CompletableFuture<>();
		worker.submit(() -> {
			running = true;
			try {
				result.complete(syncGeneralRowsAfterBaselineWithRetry(baseline));
			} catch (Exception e) {
				result.completeExceptionally(e);
			} finally {
				running = false;
				LOGGER.info("Guild log: post-payout reconciliation finished");
			}
		});
		return result;
	}

	/** Reset packet ownership on a connection or {@code /class} transition. */
	public void onWorldJoin() {
		worldTransition = false;
		nextSyncAt = 0L;
		// A new world's log isn't meaningfully comparable to the old one's — start
		// this session's fixed baseline (see reportEntries) over, lazily re-set by
		// whichever General read happens first here.
		lastGeneralBaseline = List.of();
		onClientRun(session::onWorldEntered);
	}

	/** Suspend all automatic commands immediately when /class begins. */
	public void onWorldTransition() {
		worldTransition = true;
		nextSyncAt = Long.MAX_VALUE;
		onClientRun(session::onWorldLeaving);
	}

	public void tick(boolean eligible) {
		long now = System.currentTimeMillis();
		if (!eligible || worldTransition || running || now < nextSyncAt)
			return;
		nextSyncAt = now + INTERVAL_MS;
		dumpedLogContents = false;
		running = true;
		LOGGER.info("Guild sync: starting background pass");
		worker.submit(this::sync);
	}

	private void sync() {
		try {
			if (worldTransition)
				return;
			if (!openAndCommand("guild manage", title -> title.contains("Manage"), MANAGE_SLOTS, "gu man"))
				return;
			try {
				if (waitUntil(() -> session.isAborted() || isManageLoaded() && session.isSettled()) && !session.isAborted()) {
					onClientRun(() -> reportManageSnapshot(session.items()));
					readRewardStorage();
				} else if (!session.isAborted())
					LOGGER.warn("Guild manage: timed out waiting for root-menu contents");
			} finally {
				onClientRun(session::close);
			}
			syncLogRows();
		} finally {
			running = false;
			LOGGER.info("Guild sync: background pass finished");
		}
	}

	private List<GuildLogEvent> syncLogRows() {
		if (worldTransition || !openAndCommand("guild log", title -> title.contains("Log:"), LOG_SLOTS, "gu log")) {
			return List.of();
		}
		try {
			if (!waitForLoadedLog("General")) {
				LOGGER.warn("Guild log: timed out waiting for populated rows");
				return List.of();
			}
			List<GuildLogEvent> events = new ArrayList<>(reportEntries("General", readEntries()));
			events.addAll(readLogCategory(PUBLIC_BANK_FILTER_SLOT, "Public Bank"));
			events.addAll(readLogCategory(HIGH_RANKED_BANK_FILTER_SLOT, "High Ranked Bank"));
			return List.copyOf(events);
		} finally {
			onClientRun(session::close);
		}
	}

	/** The General-only counterpart of {@link #syncLogRows()} — see {@link #reconcileNow()}. */
	private List<GuildLogEvent> syncGeneralRowsOnly() {
		if (worldTransition || !openAndCommand("guild log", title -> title.contains("Log:"), LOG_SLOTS, "gu log")) {
			return List.of();
		}
		try {
			if (!waitForLoadedLog("General")) {
				LOGGER.warn("Guild log: timed out waiting for populated rows");
				return List.of();
			}
			return reportEntries("General", readEntries());
		} finally {
			onClientRun(session::close);
		}
	}

	/**
	 * Retry {@link #syncGeneralRowsAfterBaseline} for up to {@link #POST_GIFT_LOG_WAIT_MS}
	 * while it keeps finding the boundary with nothing newer — see the constant's doc
	 * for why that's not proof the payout is missing. Each attempt fully closes and
	 * reopens the log (the same proven-correct scan the periodic sync also uses)
	 * rather than assuming a held-open menu would reflect a server-side log update.
	 */
	private List<GuildLogEvent> syncGeneralRowsAfterBaselineWithRetry(List<GuildLogEntry> baseline) {
		long start = System.currentTimeMillis();
		long deadline = start + POST_GIFT_LOG_WAIT_MS;
		int attempt = 0;
		while (true) {
			attempt++;
			List<GuildLogEvent> newer = syncGeneralRowsAfterBaseline(baseline);
			long elapsed = System.currentTimeMillis() - start;
			if (!newer.isEmpty() || System.currentTimeMillis() >= deadline) {
				LOGGER.info("Guild log: post-payout scan found {} newer row(s) after {}ms ({} attempt(s))", newer.size(), elapsed, attempt);
				return newer;
			}
			LOGGER.info("Guild log: post-payout scan found nothing yet after {}ms (attempt {}) — retrying", elapsed, attempt);
			try {
				Thread.sleep(POST_GIFT_LOG_RETRY_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return newer;
			}
		}
	}

	private List<GuildLogEvent> syncGeneralRowsAfterBaseline(List<GuildLogEntry> baseline) {
		if (worldTransition || !openAndCommand("guild log", title -> title.contains("Log:"), LOG_SLOTS, "gu log")) {
			throw new IllegalStateException("couldn't open the Guild Log");
		}
		try {
			if (!waitForLoadedLog("General")) {
				throw new IllegalStateException("Guild Log did not finish loading");
			}
			List<String> baselineKeys = baseline.stream().map(GuildLogSync::entryKey).toList();
			int window = Math.min(PAYOUT_BOUNDARY_WINDOW, baselineKeys.size());
			List<GuildLogEntry> combinedEntries = new ArrayList<>();
			List<String> combinedKeys = new ArrayList<>();
			for (int page = 1; page <= MAX_POST_GIFT_LOG_PAGES; page++) {
				List<GuildLogEntry> pageEntries = readEntries();
				if (page == 1 && !pageEntries.isEmpty()) {
					// Diagnostic for the "is this genuinely a fresh read, or stale/cached
					// server data behind a freshly-allocated container?" question: the
					// relative-time text ("N minutes ago") must strictly advance across
					// retries for a real live requery. If it's frozen across attempts
					// despite several seconds passing, that points at server-side caching
					// or a command cooldown, not slow reward propagation.
					LOGGER.info("Guild log: top row on this read is \"{}\"", pageEntries.get(0).occurredAt());
				}
				for (GuildLogEntry entry : pageEntries) {
					combinedEntries.add(entry);
					combinedKeys.add(entryKey(entry));
				}
				int boundary = payoutBoundary(combinedKeys, baselineKeys, window);
				if (boundary >= 0) {
					LOGGER.info("Guild log: found payout boundary on page {}; {} newer row(s)", page, boundary);
					List<GuildLogEvent> newer = new ArrayList<>();
					for (GuildLogEntry entry : combinedEntries.subList(0, boundary)) {
						var parsed = GuildLogEventParser.parse(entry);
						if (parsed.isPresent()) {
							newer.add(parsed.get());
						} else {
							// A row genuinely newer than the baseline, but not recognized as a
							// Reward/Bank/Raid — the "post-payout scan found nothing yet" retry
							// message reports this row's *absence*, which is misleading when the
							// row is actually right here but our own pattern doesn't match its
							// real text. Log the raw text (kept local only, never sent to the
							// bridge) so the real format can be read off instead of guessed at.
							LOGGER.warn("Guild log: newer row did not match any known event pattern: \"{}\"", entry.text().replace("\n", " / "));
						}
					}
					if (newer.size() < boundary) {
						LOGGER.warn("Guild log: {} of {} newer row(s) went unparsed — see above", boundary - newer.size(), boundary);
					}
					return List.copyOf(newer);
				}
				if (page == MAX_POST_GIFT_LOG_PAGES || !openNextLogPage()) {
					break;
				}
			}
			throw new IllegalStateException("Guild Log baseline was not found within " + MAX_POST_GIFT_LOG_PAGES + " pages");
		} finally {
			onClientRun(session::close);
		}
	}

	/**
	 * Smallest {@code k} such that {@code combined[k .. k+window)} equals
	 * {@code baselineKeys[0 .. window)}, or {@code -1} if no such alignment exists yet
	 * within what's been read so far (i.e. keep reading more pages).
	 */
	static int payoutBoundary(List<String> combined, List<String> baselineKeys, int window) {
		if (window == 0) {
			// No baseline to align against at all (a brand new guild log) — everything
			// read so far is new.
			return combined.isEmpty() ? -1 : 0;
		}
		for (int k = 0; k + window <= combined.size(); k++) {
			if (combined.subList(k, k + window).equals(baselineKeys.subList(0, window))) {
				return k;
			}
		}
		return -1;
	}

	private boolean waitForLoadedLog(String category) {
		boolean loaded = waitUntil(() -> session.isAborted() || isGuildLogLoaded());
		if (!loaded || session.isAborted()) {
			return false;
		}
		LOGGER.info("Guild log: {} filter loaded", category);
		return true;
	}

	private List<GuildLogEvent> readLogCategory(int filterSlot, String category) {
		if (session.isAborted() || worldTransition) {
			return List.of();
		}
		Long before = onClient(() -> {
			long revision = session.snapshotRevision();
			return session.prepareInPlaceTransition(title -> title.contains("Log:"), LOG_SLOTS) && session.click(filterSlot, 0, net.minecraft.world.inventory.ClickType.PICKUP) ? revision : null;
		});
		if (before == null || !waitUntil(() -> session.isAborted() || session.snapshotRevision() > before && isGuildLogLoaded())) {
			LOGGER.warn("Guild log: timed out opening {} filter", category);
			return List.of();
		}
		if (!session.isAborted()) {
			return reportEntries(category, readEntries());
		}
		return List.of();
	}

	/** Open the Guild Log's Next Page button (slot 45), which shows older rows. */
	private boolean openNextLogPage() {
		Long before = onClient(() -> {
			if (session.items().size() != LOG_SLOTS || session.items().get(NEXT_PAGE_SLOT).isEmpty()) {
				return null;
			}
			long revision = session.snapshotRevision();
			return session.prepareInPlaceTransition(title -> title.contains("Log:"), LOG_SLOTS) && session.click(NEXT_PAGE_SLOT, 0, net.minecraft.world.inventory.ClickType.PICKUP) ? revision : null;
		});
		if (before == null || !waitUntil(() -> session.isAborted() || session.snapshotRevision() > before && isGuildLogLoaded())) {
			return false;
		}
		return !session.isAborted();
	}

	/**
	 * Classify and report a Guild Log category's currently-visible rows. Source-agnostic
	 * (takes the entries directly rather than reading {@link #session}) — see
	 * {@link #reportManageSnapshot(List)}'s doc for why, and for why this isn't deduped
	 * here either. {@code GuildMenuScraper} calls this with whatever page/category a
	 * Chief/Owner currently has the Guild Log open to, without ever flipping a page or
	 * switching a filter itself.
	 */
	public List<GuildLogEvent> reportEntries(String category, List<GuildLogEntry> entries) {
		if (category.equals("General") && lastGeneralBaseline.isEmpty()) {
			// Set once per session (cleared on world join/leave below), not refreshed on
			// every read. Wynncraft's log timestamps have no seconds field, so several
			// gifts of the same kind/amount within one minute — routine during rapid
			// self-gift testing, and not implausible in a busy guild either — produce
			// byte-identical rows. Re-baselining before every single gift meant a
			// second such gift within the same minute could never be told apart from
			// the first one already in the (just-refreshed) baseline, so its Guild Log
			// evidence was silently never sent for settlement. Keeping one fixed
			// baseline for the whole session means every gift's rows accumulate on top
			// of it instead, and the backend's own claim_settlement already tracks
			// per-key multiplicity precisely to reconcile a growing, re-sent evidence
			// set like this correctly — it only needed this side to stop discarding the
			// evidence before ever sending it.
			lastGeneralBaseline = List.copyOf(entries);
		}
		List<GuildLogEvent> events = parseEntries(entries);
		long rewards = events.stream().filter(GuildLogEvent.Reward.class::isInstance).count();
		long banks = events.stream().filter(GuildLogEvent.Bank.class::isInstance).count();
		long raids = events.stream().filter(GuildLogEvent.Raid.class::isInstance).count();
		long unclassified = entries.size() - rewards - banks - raids;
		LOGGER.info("Guild log: {} rows={} rewards={} banks={} raids={} unclassified={}", category, entries.size(), rewards, banks, raids, unclassified);
		BiConsumer<String, List<GuildLogEntry>> current = reporter;
		if (current != null) {
			current.accept(category, entries);
		}
		return events;
	}

	private static List<GuildLogEvent> parseEntries(List<GuildLogEntry> entries) {
		return entries.stream().map(GuildLogEventParser::parse).flatMap(java.util.Optional::stream).toList();
	}

	private static String entryKey(GuildLogEntry entry) {
		String occurredAt = entry.occurredAt();
		int opening = occurredAt.lastIndexOf('(');
		int closing = occurredAt.lastIndexOf(')');
		String absoluteTime = opening >= 0 && closing > opening ? occurredAt.substring(opening + 1, closing) : occurredAt;
		return normalized(absoluteTime) + "|" + normalized(entry.text());
	}

	private static String normalized(String value) {
		return value.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
	}

	private boolean openAndCommand(String owner, java.util.function.Predicate<String> title, int slots, String command) {
		Boolean opened = onClient(() -> {
			if (worldTransition)
				return false;
			if (!session.begin(owner, title, slots))
				return false;
			Minecraft.getInstance().getConnection().sendCommand(command);
			return true;
		});
		if (!Boolean.TRUE.equals(opened)) {
			LOGGER.info("Guild sync: skipped {} — {}", owner, onClient(session::blockingState));
			if (session.inStartupRecoveryWindow())
				nextSyncAt = Math.min(nextSyncAt, System.currentTimeMillis() + BLOCKED_RETRY_MS);
			return false;
		}
		if (!waitUntil(() -> session.isOpen() || session.isAborted())) {
			LOGGER.warn("Guild sync: timed out opening {}", owner);
			onClientRun(session::close);
			return false;
		}
		if (session.isAborted() && session.inStartupRecoveryWindow()) {
			nextSyncAt = Math.min(nextSyncAt, System.currentTimeMillis() + BLOCKED_RETRY_MS);
		}
		return !session.isAborted();
	}

	private boolean isManageLoaded() {
		return session.items().size() == MANAGE_SLOTS && session.items().stream().anyMatch(stack -> !stack.isEmpty());
	}

	private void readRewardStorage() {
		Boolean clicked = onClient(() -> session.prepareInPlaceTransition(title -> title.contains("Manage") || title.contains("Members"), 45) && session.click(0, 0, net.minecraft.world.inventory.ClickType.PICKUP));
		if (!Boolean.TRUE.equals(clicked))
			return;
		if (!waitUntil(() -> session.isAborted() || session.isSettled() && GuildRewardStorageParser.parse(session.items()).isPresent()) || session.isAborted()) {
			LOGGER.warn("Guild rewards: timed out reading background storage summary");
			return;
		}
		reportRewardStorage(session.items());
	}

	/**
	 * Parse the Members menu's reward-storage summary and report it. Not deduped here
	 * for the same reason as {@link #reportManageSnapshot(List)} — see its doc.
	 */
	public void reportRewardStorage(List<ItemStack> items) {
		GuildRewardStorageParser.parse(items).ifPresent(snapshot -> {
			LOGGER.info("Guild rewards: storage {}", snapshot);
			Consumer<GuildRewardStorageSnapshot> current = storageReporter;
			if (current != null)
				current.accept(snapshot);
		});
	}

	private boolean isGuildLogLoaded() {
		List<GuildLogEntry> entries = readEntries();
		boolean loading = hasLoadingPlaceholder() || entries.stream().anyMatch(GuildLogSync::isLoadingPlaceholder);
		if (entries.isEmpty() || loading || !session.isSettled()) {
			if (!dumpedLogContents) {
				dumpedLogContents = true;
				logMenuContents();
			}
			logWait("waiting — " + entries.size() + " parsed row(s), loadingPlaceholder=" + loading);
			return false;
		}
		return true;
	}

	/** The temporary "Preparing data" item has no timestamp, so it is not a GuildLogEntry yet. */
	private boolean hasLoadingPlaceholder() {
		for (ItemStack stack : session.items()) {
			ItemLore lore = stack.get(DataComponents.LORE);
			if (lore != null && lore.lines().stream().map(line -> ChatFormatting.stripFormatting(line.getString())).anyMatch(line -> line.contains("Please wait while all logged messages are prepared"))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Parse the root Manage menu's items and report the snapshot (log line deduped,
	 * the report itself is not — a caller wanting to avoid re-reporting an unchanged
	 * per-tick reading, like a foreground scraper watching a menu the player left
	 * open, owns that dedup itself; this background pass runs only once a minute and
	 * has never needed it). Source-agnostic (takes the items directly rather than
	 * reading {@link #session}) so it can be fed either the background session's own
	 * read or a foreground scraper's — see {@code GuildMenuScraper}, which calls this
	 * for a Chief/Owner/Strategist who opens their own Manage menu, no mod-driven
	 * interaction involved.
	 */
	public void reportManageSnapshot(List<ItemStack> items) {
		GuildManageMenuParser.parse(items).ifPresent(parsed -> {
			if (!parsed.equals(lastManageSnapshot)) {
				lastManageSnapshot = parsed;
				LOGGER.info("Guild manage: status {}", parsed);
			}
			Consumer<GuildManageSnapshot> current = manageReporter;
			if (current != null) {
				current.accept(parsed);
			}
		});
	}

	private void logMenuContents() {
		LOGGER.info("Guild log: menu opened; waiting for populated rows");
	}

	private void logWait(String message) {
		long now = System.currentTimeMillis();
		if (now - lastProgressLogAt >= 500L) {
			lastProgressLogAt = now;
			LOGGER.info("Guild log: {}", message);
		}
	}

	private List<GuildLogEntry> readEntries() {
		return entriesFrom(session.items());
	}

	/**
	 * Parse whichever log entries are present in {@code items} at the Guild Log's known
	 * entry slots. Source-agnostic — see {@link #reportManageSnapshot(List)}'s doc —
	 * so {@code GuildMenuScraper} can build entries from a foreground screen's own menu
	 * the same way the background session does.
	 */
	public static List<GuildLogEntry> entriesFrom(List<ItemStack> items) {
		if (items == null || items.size() != LOG_SLOTS)
			return List.of();
		List<GuildLogEntry> entries = new ArrayList<>(ENTRY_SLOTS.length);
		for (int slot : ENTRY_SLOTS)
			GuildLogEntryParser.parse(items.get(slot)).ifPresent(entries::add);
		return List.copyOf(entries);
	}

	static boolean isLoadingPlaceholder(GuildLogEntry entry) {
		String text = ChatFormatting.stripFormatting(entry.occurredAt() + " " + entry.text()).replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
		return text.contains("please wait while all logged messages are prepared");
	}

	public boolean onOpenScreen(ClientboundOpenScreenPacket packet) {
		return session.onOpenScreen(packet);
	}
	public boolean onContainerContent(int containerId, int stateId, List<ItemStack> items) {
		return session.onContent(containerId, stateId, items);
	}
	public boolean onContainerSlot(int containerId, int stateId, int slot, ItemStack item) {
		return session.onSlot(containerId, stateId, slot, item);
	}
	public void onContainerClosed(int containerId) {
		session.onContainerClosed(containerId);
	}

	private static boolean waitUntil(Supplier<Boolean> condition) {
		long deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			if (Boolean.TRUE.equals(onClient(condition)))
				return true;
			try {
				Thread.sleep(POLL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	private static <T> T onClient(Supplier<T> action) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread())
			return action.get();
		CompletableFuture<T> future = new CompletableFuture<>();
		mc.execute(() -> {
			try {
				future.complete(action.get());
			} catch (Throwable error) {
				future.completeExceptionally(error);
			}
		});
		try {
			return future.get(5, TimeUnit.SECONDS);
		} catch (Exception error) {
			return null;
		}
	}

	private static void onClientRun(Runnable action) {
		onClient(() -> {
			action.run();
			return null;
		});
	}
}
