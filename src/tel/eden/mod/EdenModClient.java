package tel.eden.mod;

import tel.eden.mod.auth.AuthFlow;
import tel.eden.mod.chat.AnnihilationParser;
import tel.eden.mod.chat.BankEvent;
import tel.eden.mod.chat.BankEventParser;
import tel.eden.mod.chat.CapturedMessage;
import tel.eden.mod.chat.ChatRelay;
import tel.eden.mod.chat.DiscordChatFormatter;
import tel.eden.mod.chat.EmoteRegistry;
import tel.eden.mod.chat.GuildAnnounceParser;
import tel.eden.mod.chat.ChatDecorators;
import tel.eden.mod.chat.GuildChatParser;
import tel.eden.mod.chat.GuildEvent;
import tel.eden.mod.chat.GuildEventParser;
import tel.eden.mod.chat.GuildLevelUpParser;
import tel.eden.mod.chat.GuildReward;
import tel.eden.mod.chat.GuildRewardParser;
import tel.eden.mod.chat.RewardUnavailableParser;
import tel.eden.mod.chat.LevelUp;
import tel.eden.mod.chat.LevelUpParser;
import tel.eden.mod.chat.OccurrenceSequencer;
import tel.eden.mod.chat.PlayerNameResolver;
import tel.eden.mod.chat.ChatReplyManager;
import tel.eden.mod.chat.PartyFormatter;
import tel.eden.mod.chat.RaidCompletion;
import tel.eden.mod.chat.RaidCompletionParser;
import tel.eden.mod.chat.RaidPartyTracker;
import tel.eden.mod.chat.RaidReadyTracker;
import tel.eden.mod.party.PartyHealthTracker;
import tel.eden.mod.chat.RankChange;
import tel.eden.mod.chat.RankChangeParser;
import tel.eden.mod.chat.ShoutParser;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.guild.GuildLogSync;
import tel.eden.mod.guild.GuildMenuScraper;
import tel.eden.mod.guild.GuildRewardStorageSnapshot;
import tel.eden.mod.guild.BackgroundContainerSession;
import tel.eden.mod.gui.BridgeConfigScreen;
import tel.eden.mod.gui.CommandAliasScreen;
import tel.eden.mod.gui.CommandKeybindScreen;
import tel.eden.mod.gui.PartyCreateScreen;
import tel.eden.mod.gui.PartyListScreen;
import tel.eden.mod.gui.PartyManageScreen;
import tel.eden.mod.item.DecodedItem;
import tel.eden.mod.item.ItemCardRenderer;
import tel.eden.mod.item.ItemStringDetector;
import tel.eden.mod.net.BridgeWebSocketClient;
import tel.eden.mod.net.PartyInfo;
import tel.eden.mod.net.GiveawayCandidate;
import tel.eden.mod.net.PendingEntry;
import tel.eden.mod.net.WarBoardEntry;
import tel.eden.mod.net.WarCountEntry;
import tel.eden.mod.reward.GuildRewards;
import tel.eden.mod.update.UpdateChecker;
import tel.eden.mod.update.UpdateInfo;
import tel.eden.mod.update.UpdateInstaller;
import tel.eden.mod.util.Wynncraft;
import tel.eden.mod.war.AllianceMenuScraper;
import tel.eden.mod.war.AttackMenuScraper;
import tel.eden.mod.war.AttackTimerMenu;
import tel.eden.mod.war.ScoreboardCapture;
import tel.eden.mod.war.BeaconManager;
import tel.eden.mod.war.TerritoryData;
import tel.eden.mod.war.TerritoryMenuKeybind;
import tel.eden.mod.war.TerritoryOutlineRenderer;
import tel.eden.mod.war.WarDPS;
import tel.eden.mod.war.WarHud;
import tel.eden.mod.war.WarTracker;
import tel.eden.mod.util.WynntilsChatBridge;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.lwjgl.glfw.GLFW;
import tel.eden.mod.EdenLogger;

/**
 * Client entrypoint: wires server gating, the chat-capture relay, the WebSocket
 * client, the keybind, and the re-auth timer together.
 */
public final class EdenModClient implements ClientModInitializer {
	public static final EdenLogger LOGGER = EdenLogger.get();
	private static final String MOD_VERSION = FabricLoader.getInstance().getModContainer("edenmod").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");

	private static EdenModClient instance;

	private BridgeConfig config;
	private final ChatRelay chatRelay = new ChatRelay();
	private final ChatRelay raidRelay = new ChatRelay();
	private final ChatRelay rankRelay = new ChatRelay();
	private final ChatRelay guildEventRelay = new ChatRelay();
	private final ChatRelay guildAnnounceRelay = new ChatRelay();
	private final ChatRelay levelUpRelay = new ChatRelay();
	private final ChatRelay guildLevelUpRelay = new ChatRelay();
	private final ChatRelay shoutRelay = new ChatRelay();
	private final ChatRelay annihilationRelay = new ChatRelay();
	private final ChatRelay itemCardRelay = new ChatRelay();
	// Single-shot suppression of the shout-composition preview, armed by the compose prompt.
	private long shoutPreviewSuppressUntil;
	// Decoding (Wynntils reflection) + image rendering run off the game thread.
	private final ExecutorService itemCardExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "edenmod-item-card");
		t.setDaemon(true);
		return t;
	});
	// Spaces out the "party create" + each "party <ign>" invite so Wynncraft has time
	// to register the new party before the invites land.
	private static final long PARTY_COMMAND_DELAY_MS = 400L;
	private final ScheduledExecutorService partyCommandExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "edenmod-party-cmd");
		t.setDaemon(true);
		return t;
	});
	// Invalidates delayed party commands when the client changes server/world, so invites
	// queued for one session can never be delivered to the next connection.
	private final java.util.concurrent.atomic.AtomicInteger partyCommandGeneration = new java.util.concurrent.atomic.AtomicInteger();
	// Deterministic per-occurrence index so rapid identical bank events stay distinct.
	// Short window: only the current burst counts, so stale earlier deposits can't
	// make different clients assign divergent seqs (which duplicated Discord posts).
	private final OccurrenceSequencer bankSeq = new OccurrenceSequencer(10_000L);
	// Rewards are gifted in long runs (one line per item, ~600ms apart), so use a
	// wider window than bank events to keep a whole run's seqs monotonic for counting.
	// Reward ticker packets are authoritative individual handouts. Unlike bank lines,
	// never coalesce two that arrive within 200 ms: rapid legitimate gifts must both count.
	private final OccurrenceSequencer rewardSeq = new OccurrenceSequencer(60_000L, false);
	// Guild-chat occurrence index: lets the backend distinguish a line legitimately
	// repeated (e.g. "d" then "d") from the same line relayed by multiple online
	// members' clients, which is clock-/arrival-skew independent (order, not time).
	private final OccurrenceSequencer chatSeq = new OccurrenceSequencer(15_000L);
	private final BackgroundContainerSession backgroundContainers = new BackgroundContainerSession();
	private final GuildRewards guildRewards = new GuildRewards(backgroundContainers);
	private final GuildLogSync guildLogSync = new GuildLogSync(backgroundContainers);
	private final List<TrackedCommandKeybind> trackedCommandKeybinds = new ArrayList<>();
	private KeyMapping openConfigKey;
	private KeyMapping createPartyKey;
	private KeyMapping openEmotePickerKey;
	private BridgeWebSocketClient socket;
	// The in-flight giftLockAcquire's result slot: onGiftLockReply completes it (from
	// the bridge's read thread), GuildRewards.GiftLockGateway.acquire() blocks on it
	// (from the reward worker thread). At most one acquire is ever outstanding —
	// GuildRewards drives all gifting from its own single-threaded worker — so a single
	// slot (not a per-request map) is enough; a stale reply arriving after a timeout
	// already gave up just completes a future nobody is waiting on any more.
	private final java.util.concurrent.atomic.AtomicReference<CompletableFuture<String>> pendingGiftLock = new java.util.concurrent.atomic.AtomicReference<>();
	// How long GiftLockGateway.acquire() waits for a giftLockReply before treating the
	// bridge as unreachable and failing closed.
	private static final long GIFT_LOCK_TIMEOUT_SECONDS = 8;

	public BridgeWebSocketClient socket() {
		return socket;
	}

	/** This player's backend standing, learned live from {@code authOk} (never stored). */
	public enum BridgeStatus {
		/** Not connected/verified yet this session (or off Wynncraft). */
		UNKNOWN,
		/** Linked and a guild member — full bridge access. */
		FULL,
		/** Verified but not linked — the player is prompted to link. */
		NOT_LINKED,
		/** Linked but not a guild member — the bridge stays inactive. */
		NOT_MEMBER
	}

	private volatile BridgeStatus bridgeStatus = BridgeStatus.UNKNOWN;
	/** In-game guild rank (e.g. "Chief"), set on full access, cleared on disconnect. */
	private volatile String liveGuildRank = "";
	/** Discord rank (e.g. "Senate"), set on full access, cleared on disconnect. */
	private volatile String liveDiscordRank = "";

	/** This player's live bridge standing, for the config screen. */
	public BridgeStatus bridgeStatus() {
		return bridgeStatus;
	}

	/** In-game guild rank last reported by the backend, or empty if unknown. */
	public String liveGuildRank() {
		return liveGuildRank;
	}

	/** Discord rank last reported by the backend, or empty if unknown. */
	public String liveDiscordRank() {
		return liveDiscordRank;
	}

	private boolean onWynncraft;
	// Mutated on the inbound-message path and read from GUI screens on the render
	// thread. Each change publishes one immutable snapshot through this volatile field,
	// so readers cannot observe the remove/add or clear/addAll halves of an update.
	private volatile java.util.List<PartyInfo> knownParties = java.util.List.of();
	// Latest aspects-owed list from the backend, read by PayoutScreen on the
	// render thread. Each reply replaces the whole list, so publishing an immutable
	// one through a volatile field keeps readers from ever seeing a half-applied
	// update. The generation counter lets the screen tell "no reply yet" apart from
	// "replied with an empty list".
	private volatile java.util.List<PendingEntry> knownPendingAspects = java.util.List.of();
	private volatile String pendingAspectsError;
	// Atomic rather than a volatile int: a lost increment between two concurrent
	// replies would leave a reader's cached generation matching the live one, and
	// it would sit on a stale list believing it was current.
	private final java.util.concurrent.atomic.AtomicInteger pendingAspectsGeneration = new java.util.concurrent.atomic.AtomicInteger();
	// Emerald counterpart of the pending-aspects trio above, same shape, read by the
	// same PayoutScreen when its mode toggle is on Emeralds.
	private volatile java.util.List<PendingEntry> knownPendingEmeralds = java.util.List.of();
	private volatile String pendingEmeraldsError;
	private final java.util.concurrent.atomic.AtomicInteger pendingEmeraldsGeneration = new java.util.concurrent.atomic.AtomicInteger();
	// Same shape as the pending-aspects trio above, for AspectGiveawayScreen's member list.
	private volatile java.util.List<GiveawayCandidate> knownGiveawayCandidates = java.util.List.of();
	private volatile int giveawayStorageAspects;
	private volatile String giveawayError;
	private final java.util.concurrent.atomic.AtomicInteger giveawayGeneration = new java.util.concurrent.atomic.AtomicInteger();
	// GitHub update check: run once per game session; the prompt offers a one-click
	// download (applied on game close) and a link to the release page.
	private final UpdateChecker updateChecker = new UpdateChecker();
	private final UpdateInstaller updateInstaller = new UpdateInstaller();
	private volatile UpdateInfo pendingUpdate;
	private volatile boolean updateChecked;
	private volatile boolean updateStaged;
	private volatile boolean pendingUpdateNotification;
	// Set when the Fulcio root cert has actually expired; updates fall back to hash-only
	// and the player should update the mod as soon as possible.
	private volatile boolean pendingFulcioCertExpiredWarning;
	// Set when a mod update is available and the cert expires within 90 days; the 180-day
	// CI alarm gives plenty of runway, but we also warn in-game so players who auto-update
	// don't let the cert silently expire on versions that aren't built yet.
	private volatile boolean pendingFulcioCertExpiringSoonWarning;
	// Set when boot-time attestation fails — jar hash has no GitHub attestation, which
	// means the jar is either unofficial or has been tampered with.
	private volatile boolean pendingBootAttestationFailed;
	// Guards the one-shot attestation-failure report to the bridge (sent on first connect
	// after a failed boot check; skipped if attestation passed or was never run).
	private volatile boolean attestationFailureReported;
	private volatile boolean pendingCenteredEmotePicker;
	// Non-null when the bridge rejected our connection; holds the error code
	// ("version_rejected", "not_member", "http_401", etc.) so onClientTick can
	// show the right message once the player is loaded.
	private volatile String pendingConnectionCode;

	/** Suppresses chat output for the next warCountsReply (background chip refresh). */
	private volatile boolean suppressNextWarCountsDisplay;

	/** Detected wars not yet delivered because the socket was down; flushed on tick. */
	private record PendingWarReport(String territory, List<String> members) {
	}

	private final java.util.concurrent.ConcurrentLinkedQueue<PendingWarReport> pendingWarReports = new java.util.concurrent.ConcurrentLinkedQueue<>();

	/** A deduct request sent to the backend and still waiting for its reply. */
	private record PendingDeduct(String rewardKind, String target, int displayUnits) {
	}

	// Deduct replies carry no request id, but every one the backend can attribute echoes
	// the target and kind, which is enough to claim the matching request by name. What
	// this queue is for is the requests that get no such reply at all: a dropped socket,
	// or a payload the backend couldn't parse (the one refusal with nothing to echo).
	// Those are handed back to the Chief to settle manually, never retried.
	private final java.util.concurrent.ConcurrentLinkedQueue<PendingDeduct> pendingDeducts = new java.util.concurrent.ConcurrentLinkedQueue<>();
	private long lastWeeklyWarsRefresh;
	// Set on game join, sent once the bridge connects, cleared on send/disconnect, so
	// a "logged in" notice fires per game session (not on every WS reconnect).
	private volatile boolean loginPending;
	// True when the player is confirmed to be in a Wynncraft game world (or class
	// selection screen). False while in queue, on the title screen, or AFK-queued.
	// Set by welcome-message detection and a periodic tab-list check; cleared on disconnect.
	private volatile boolean inGameWorld = false;
	// /class selection has a Wynncraft tab list too, but commands such as /gu log are
	// unavailable until the selected class has entered an actual game world.
	private volatile boolean enteredWynnGameWorld;
	// Tick counters for the periodic tab check and presence heartbeat.
	private int tabCheckTick = 0;
	private int presenceTick = 0;
	private int storageCheckTick = 0;
	private static final int TAB_CHECK_INTERVAL_TICKS = 60; // 3 s at 20 tps
	private static final int PRESENCE_INTERVAL_TICKS = 600; // 30 s at 20 tps
	private static final int STORAGE_CHECK_INTERVAL_TICKS = 10; // 0.5 s at 20 tps
	// Paused pending Wynncraft moderator confirmation of what's allowed for
	// unattended background menu reads — GuildLogSync.tick()'s autonomous periodic
	// pass opens /gu man and /gu log invisibly with no player action behind it.
	// GiftProgressScreen-protected reads (the pre-gift baseline and post-gift
	// confirmation, both Chief-triggered and screen-blocked for their whole
	// duration) are a separate mechanism and are unaffected by this flag. Also
	// gated on isChief() below — the backend only trusts/acts on a Chief's own
	// report anyway (no more consensus from any member), so a non-Chief running
	// this scan would just be wasted background container work.
	private static final boolean BACKGROUND_SYNC_ENABLED = false;
	// Last {aspects, tomes, emeralds} relayed, so an unchanged read isn't re-sent.
	private volatile long[] lastStorageSent = null;

	/** The live mod instance (used by the chat-capture mixin). */
	public static EdenModClient instance() {
		return instance;
	}

	public BridgeConfig config() {
		return config;
	}

	public tel.eden.mod.update.UpdateInfo getPendingUpdate() {
		return pendingUpdate;
	}

	public void requestCenteredEmotePicker() {
		pendingCenteredEmotePicker = true;
	}

	public boolean consumeCenteredEmotePickerRequest() {
		boolean pending = pendingCenteredEmotePicker;
		pendingCenteredEmotePicker = false;
		return pending;
	}

	public boolean matchesOpenEmotePickerMouse(net.minecraft.client.input.MouseButtonEvent event) {
		return openEmotePickerKey.matchesMouse(event);
	}

	public boolean isOpenEmotePickerMouseBound() {
		return openEmotePickerKey.saveString().startsWith("key.mouse.");
	}

	public boolean matchesOpenEmotePickerKey(net.minecraft.client.input.KeyEvent event) {
		return openEmotePickerKey.matches(event);
	}

	/**
	 * If the picker shares chat's gameplay keybind, vanilla opens chat before the chat
	 * screen can inspect the triggering press. Detect that held key during chat init
	 * so the picker still opens without requiring a second press.
	 */
	public boolean shouldOpenEmotePickerOnChatOpen() {
		Minecraft mc = Minecraft.getInstance();
		if (!shouldAllowGameplayEmotePickerOpen() || isOpenEmotePickerMouseBound() || mc.options == null) {
			return false;
		}
		return edenmod$matchesGameplayChatKeybind(mc.options.keyChat) || edenmod$matchesGameplayChatKeybind(mc.options.keyCommand);
	}

	/** Queue the emote picker for the next chat screen, opening chat if needed. */
	public void openCenteredEmotePicker() {
		Minecraft mc = Minecraft.getInstance();
		requestCenteredEmotePicker();
		mc.execute(() -> {
			if (!(mc.screen instanceof ChatScreen)) {
				mc.setScreen(new ChatScreen("", false));
			}
		});
	}

	/** An immutable snapshot of the currently known parties, safe to read off-thread. */
	public java.util.List<PartyInfo> knownParties() {
		return knownParties;
	}

	private synchronized void updateKnownParty(String event, PartyInfo party) {
		java.util.List<PartyInfo> updated = new java.util.ArrayList<>(knownParties);
		updated.removeIf(existing -> existing.id() == party.id());
		if (!event.equals("closed")) {
			updated.add(party);
		}
		knownParties = java.util.List.copyOf(updated);
	}

	private synchronized void replaceKnownParties(java.util.List<PartyInfo> parties) {
		knownParties = java.util.List.copyOf(parties);
	}

	/** Members owed aspects, highest first, as of the last backend reply. */
	public java.util.List<PendingEntry> knownPendingAspects() {
		return knownPendingAspects;
	}

	/** Members owed emeralds, highest first, as of the last backend reply. */
	public java.util.List<PendingEntry> knownPendingEmeralds() {
		return knownPendingEmeralds;
	}

	private boolean shouldAllowGameplayEmotePickerOpen() {
		if (!config.emotePickerOpenFromGameplay) {
			return false;
		}
		return switch (config.chatEmoteToolsMode) {
			case UI, UI_AND_AUTO -> true;
			case AUTO, NONE -> false;
		};
	}

	private boolean shouldOpenEmotePickerFromGameplay(Minecraft client) {
		return shouldAllowGameplayEmotePickerOpen() && client.screen == null;
	}

	private boolean edenmod$matchesGameplayChatKeybind(KeyMapping vanillaKey) {
		return openEmotePickerKey.saveString().equals(vanillaKey.saveString()) && openEmotePickerKey.isDown();
	}

	/** The error from the last aspects-pending reply, or {@code null} if it succeeded. */
	public String pendingAspectsError() {
		return pendingAspectsError;
	}

	/** Emerald counterpart of {@link #pendingAspectsError()}. */
	public String pendingEmeraldsError() {
		return pendingEmeraldsError;
	}

	/**
	 * Bumped on every aspects-pending reply; 0 means none has arrived yet.
	 *
	 * <p>Readers must sample this <em>before</em> reading the list, mirroring the
	 * write order below: sampling it afterwards can pair an old list with a new
	 * generation, and the reader then never notices it missed a reply.
	 */
	public int pendingAspectsGeneration() {
		return pendingAspectsGeneration.get();
	}

	/** Emerald counterpart of {@link #pendingAspectsGeneration()}. */
	public int pendingEmeraldsGeneration() {
		return pendingEmeraldsGeneration.get();
	}

	/** The current guild members for the aspect-giveaway screen, as of the last reply. */
	public java.util.List<GiveawayCandidate> knownGiveawayCandidates() {
		return knownGiveawayCandidates;
	}

	/** The guild's aspect stock as of the last giveaway reply. */
	public int giveawayStorageAspects() {
		return giveawayStorageAspects;
	}

	/** The error from the last giveaway reply, or {@code null} if it succeeded. */
	public String giveawayError() {
		return giveawayError;
	}

	/** Bumped on every giveaway reply; 0 means none has arrived yet. */
	public int giveawayGeneration() {
		return giveawayGeneration.get();
	}

	/** The guild reward helper (rank lookups + gifting). */
	public GuildRewards guildRewards() {
		return guildRewards;
	}

	/** The Guild Log/Manage sync session, whose report methods a foreground scraper reuses. */
	public GuildLogSync guildLogSync() {
		return guildLogSync;
	}

	/** Packet hooks for the invisible guild-log query (mirrors Wynntils' query path). */
	public boolean onGuildLogOpenScreen(ClientboundOpenScreenPacket packet) {
		return guildLogSync.onOpenScreen(packet);
	}

	public boolean onGuildLogContent(int containerId, int stateId, List<net.minecraft.world.item.ItemStack> items) {
		return guildLogSync.onContainerContent(containerId, stateId, items);
	}

	public boolean onGuildLogSlot(int containerId, int stateId, int slot, net.minecraft.world.item.ItemStack item) {
		return guildLogSync.onContainerSlot(containerId, stateId, slot, item);
	}

	public void onGuildLogContainerClosed(int containerId) {
		guildLogSync.onContainerClosed(containerId);
	}

	/** Suppress only the packet sound produced by Eden-owned hidden menu clicks. */
	public boolean shouldMuteBackgroundMenuClick(SoundEvent sound, SoundSource source) {
		return backgroundContainers.shouldMuteMenuClick(sound, source);
	}

	@Override
	public void onInitializeClient() {
		instance = this;
		config = BridgeConfig.load();
		refreshCommandKeybinds();

		// Relay the guild's live reward storage to the backend counter: the exact value
		// after a gift run, and (in onClientTick) whenever a Chief opens the menu.
		guildRewards.setStorageReporter(this::relayStorage);
		// The in-game full-storage nudge now lives server-side as a chat warning
		// (storage_fullness_alert_tick), so this only needs to forward the counts.
		guildLogSync.setStorageReporter(storage -> relayStorage(storage.aspects(), storage.tomes(), storage.emeralds()));
		guildLogSync.setManageReporter(snapshot -> {
			BridgeWebSocketClient current = socket;
			if (current != null) {
				current.sendGuildManageStatus(snapshot);
			}
		});
		// The bounded Guild Log menu supplies reconciliation evidence only. Its typed
		// reward/bank rows are sent without raw lore; immediate chat/ticker reporting
		// remains the live path until production reconciliation proves itself.
		guildLogSync.setReporter((category, entries) -> {
			BridgeWebSocketClient current = socket;
			if (current == null) {
				return;
			}
			current.sendGuildLogSnapshot(category, entries.stream().map(tel.eden.mod.guild.GuildLogEventParser::parse).flatMap(Optional::stream).toList());
		});
		guildRewards.setPostGiftReconciler(guildLogSync::requestSoon);
		guildRewards.setPreGiftEvidenceSupplier(() -> {
			try {
				return guildLogSync.reconcileNow().get(20, TimeUnit.SECONDS);
			} catch (Exception e) {
				throw new IllegalStateException("Guild Log baseline unavailable", e);
			}
		});
		guildRewards.setPostGiftEvidenceSupplier(() -> {
			try {
				// GuildLogSync itself retries internally for up to 20s (Wynncraft's log
				// can lag behind a just-completed payout) — this outer bound just needs
				// margin over that plus per-attempt scan overhead, not to race it.
				return guildLogSync.reconcileAfterBaseline().get(45, TimeUnit.SECONDS);
			} catch (Exception e) {
				throw new IllegalStateException("Guild Log reconciliation unavailable", e);
			}
		});
		guildRewards.setRewardDeductSender((rewardKind, target, amount) -> {
			BridgeWebSocketClient current = socket;
			if (current != null) {
				current.sendRewardDeductRequest(rewardKind, target, amount, false);
			}
		});
		// Exclusive use of the gifting automation, coordinated through the bridge so two
		// Chiefs' mods can never drive the guild-manage menu at the same time.
		guildRewards.setGiftLockGateway(new GuildRewards.GiftLockGateway() {
			@Override
			public String acquire() {
				BridgeWebSocketClient current = socket;
				if (current == null) {
					return "not connected to the bridge";
				}
				CompletableFuture<String> future = new CompletableFuture<>();
				pendingGiftLock.set(future);
				if (!current.sendGiftLockAcquire()) {
					pendingGiftLock.compareAndSet(future, null);
					return "not connected to the bridge";
				}
				try {
					return future.get(GIFT_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
				} catch (Exception e) {
					pendingGiftLock.compareAndSet(future, null);
					return "no response from the bridge";
				}
			}

			@Override
			public void release() {
				BridgeWebSocketClient current = socket;
				if (current != null) {
					current.sendGiftLockRelease();
				}
			}
		});

		KeyMapping.Category edenCategory = new KeyMapping.Category(net.minecraft.resources.Identifier.parse("edenmod"));
		openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.edenmod.open_config", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, edenCategory));
		createPartyKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.edenmod.open_menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, edenCategory));
		openEmotePickerKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.edenmod.open_emote_picker", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, edenCategory));
		TerritoryMenuKeybind.register(edenCategory);
		TerritoryOutlineRenderer.register(edenCategory);
		tel.eden.mod.emote.EmoteWheel.register(edenCategory);
		WarTracker.setReporter(this::relayWarAttended);

		// War-suite 2D HUD overlays, drawn only while on Wynncraft.
		HudRenderCallback.EVENT.register((graphics, tickCounter) -> {
			if (onWynncraft) {
				WarHud.render(config, graphics);
			}
		});
		// War-suite territory outlines, added to the per-frame gizmo collector during
		// entity rendering.
		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			if (onWynncraft) {
				BeaconManager.render(config);
				TerritoryOutlineRenderer.render();
			}
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			enteredWynnGameWorld = false;
			guildLogSync.onWorldJoin();
			partyCommandGeneration.incrementAndGet();
			loginPending = true;
			// Fresh connection: drop the packet-captured scoreboard and all war-board state
			// so a previous world/session's timers, defence, and heads never linger.
			ScoreboardCapture.reset();
			RaidPartyTracker.reset();
			AllianceMenuScraper.reset();
			GuildMenuScraper.reset();
			AttackTimerMenu.reset();
			// Connect if applicable; the backend reports link/membership standing via
			// authOk (handleAuthStatus), which drives any "please link" prompt.
			evaluateGating(client);
			checkForUpdateOnce();
			if (onWynncraft) {
				guildRewards.ensureFresh(playerName());
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> disconnect());
		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
			dispatcher.register(ClientCommandManager.literal("eden").executes(ctx -> openOverviewGui(ctx.getSource())).then(ClientCommandManager.literal("config").executes(ctx -> {
				Minecraft mc = Minecraft.getInstance();
				mc.execute(() -> mc.setScreen(BridgeConfigScreen.create(mc.screen, EdenModClient.instance())));
				return 1;
			})).then(ClientCommandManager.literal("online").executes(ctx -> {
				requestOnline(ctx.getSource());
				return 1;
			})).then(ClientCommandManager.literal("aspects").then(ClientCommandManager.literal("pending").executes(ctx -> {
				requestAspectsPending(ctx.getSource());
				return 1;
			}))).then(ClientCommandManager.literal("cf").executes(ctx -> {
				requestCoinflip(ctx.getSource());
				return 1;
			})).then(ClientCommandManager.literal("diceroll").executes(ctx -> {
				requestDiceroll(ctx.getSource());
				return 1;
			})).then(ClientCommandManager.literal("emojis").executes(ctx -> {
				showEmojis(ctx.getSource());
				return 1;
			})).then(buildPartyCommand()).then(buildAnnihilationCommand()).then(buildCommandCommand()).then(ClientCommandManager.literal("reply").then(ClientCommandManager.argument("messageId", StringArgumentType.word()).executes(ctx -> handleReplyCommand(StringArgumentType.getString(ctx, "messageId"))))).then(ClientCommandManager.literal("update").executes(ctx -> {
				updatePrompt(ctx.getSource());
				return 1;
			}).then(ClientCommandManager.literal("download").executes(ctx -> {
				updateDownload(ctx.getSource());
				return 1;
			}))).then(buildGiftCommand()).then(buildDeductCommand()).then(ClientCommandManager.literal("dump").then(ClientCommandManager.argument("member", StringArgumentType.word()).suggests(this::suggestMembers).executes(ctx -> dumpEmeralds(ctx.getSource(), StringArgumentType.getString(ctx, "member"))))).then(ClientCommandManager.literal("wars").executes(ctx -> {
				requestWarCounts(ctx.getSource(), 7);
				return 1;
			}).then(ClientCommandManager.argument("days", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 365)).executes(ctx -> {
				requestWarCounts(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "days"));
				return 1;
			}))).then(ClientCommandManager.literal("congratulate").then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(ctx -> {
				congratulate(ctx.getSource(), StringArgumentType.getString(ctx, "name"));
				return 1;
			}))).then(ClientCommandManager.literal("debug").executes(ctx -> {
				for (String line : AttackTimerMenu.debugSidebarLines()) {
					ctx.getSource().sendFeedback(Component.literal("[debug] " + line).withStyle(ChatFormatting.AQUA));
				}
				for (String line : RaidPartyTracker.debugState()) {
					ctx.getSource().sendFeedback(Component.literal("[debug] " + line).withStyle(ChatFormatting.AQUA));
				}
				for (String line : RaidReadyTracker.debugState()) {
					ctx.getSource().sendFeedback(Component.literal("[debug] " + line).withStyle(ChatFormatting.AQUA));
				}
				for (String line : PartyHealthTracker.debugState()) {
					ctx.getSource().sendFeedback(Component.literal("[debug] " + line).withStyle(ChatFormatting.GREEN));
				}
				return 1;
			})).then(ClientCommandManager.literal("help").executes(ctx -> {
				showHelp(ctx.getSource());
				return 1;
			})));
		});

		Thread bootVerify = new Thread(() -> {
			if (!updateInstaller.verifyBootAttestation()) {
				pendingBootAttestationFailed = true;
			}
		}, "edenmod-boot-verify");
		bootVerify.setDaemon(true);
		bootVerify.start();

		LOGGER.info("EdenMod initialised");
	}

	/** Send a detected war's attendance now, or queue it until the socket is back. */
	private void relayWarAttended(String territory, List<String> members) {
		BridgeWebSocketClient current = socket;
		if (current == null || !current.sendWarAttended(territory, members)) {
			pendingWarReports.add(new PendingWarReport(territory, members));
		} else {
			LOGGER.info("War attended: {} ({} members)", territory, members.size());
		}
	}

	private void onClientTick(Minecraft client) {
		if (onWynncraft) {
			TerritoryData.onTick();
			TerritoryMenuKeybind.onTick(client);
			TerritoryOutlineRenderer.onTick(client);
			WarDPS.onTick(config);
			WarTracker.onTick();
			RaidPartyTracker.onTick();
			RaidReadyTracker.onClientTick();
			PartyHealthTracker.tick();
			AttackMenuScraper.onTick(client);
			AllianceMenuScraper.onTick(client);
			GuildMenuScraper.onTick(client);
			AttackTimerMenu.onTick(socket);
			tel.eden.mod.emote.EmoteWheel.onTick(client);
			tel.eden.mod.emote.UnlockedEmoteDetector.onTick(client);
			BridgeWebSocketClient current = socket;
			if (current != null) {
				// Flush war reports that were detected while disconnected.
				PendingWarReport pending;
				while ((pending = pendingWarReports.poll()) != null) {
					if (!current.sendWarAttended(pending.territory(), pending.members())) {
						pendingWarReports.add(pending);
						break;
					}
				}
				// Keep the weekly war-count chip fresh without spamming chat.
				if (config.warWeeklyCountHud && System.currentTimeMillis() - lastWeeklyWarsRefresh > 10 * 60 * 1000L) {
					lastWeeklyWarsRefresh = System.currentTimeMillis();
					suppressNextWarCountsDisplay = true;
					current.sendWarCountsRequest(7);
				}
			}
		}
		while (openConfigKey.consumeClick()) {
			client.setScreen(BridgeConfigScreen.create(client.screen, this));
		}
		while (createPartyKey.consumeClick()) {
			if (onWynncraft) {
				openMenuGui();
			} else {
				display(() -> Component.literal("You must be on Wynncraft to open the Eden menu.").withStyle(net.minecraft.ChatFormatting.RED));
			}
		}
		while (openEmotePickerKey.consumeClick()) {
			// ChatScreenMixin handles picker input while chat is already open. Only the
			// gameplay-entry path goes through this tick hook, keeping the hot path to one
			// cheap screen/config check and preventing stale queued clicks from replaying.
			if (shouldOpenEmotePickerFromGameplay(client)) {
				openCenteredEmotePicker();
			}
		}
		pollCommandKeybinds(client);
		if (pendingUpdateNotification && client.player != null) {
			pendingUpdateNotification = false;
			UpdateInfo update = pendingUpdate;
			if (update != null) {
				display(() -> DiscordChatFormatter.updateAvailable(update.version(), update.pageUrl()));
			}
		}
		if (pendingFulcioCertExpiredWarning && client.player != null) {
			pendingFulcioCertExpiredWarning = false;
			display(() -> Component.empty().append(Component.literal("[EdenMod] ").withStyle(ChatFormatting.RED)).append(Component.literal("CRITICAL: ").withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(true))).append(Component.literal("The update verification cert has expired — mod updates are no longer fully verified. " + "Please update EdenMod immediately!").withStyle(ChatFormatting.RED)));
		}
		if (pendingFulcioCertExpiringSoonWarning && client.player != null) {
			pendingFulcioCertExpiringSoonWarning = false;
			long d = UpdateChecker.fulcioCertDaysLeft();
			display(() -> Component.literal("[EdenMod] WARNING: The update verification cert expires in " + d + " days — " + "run /eden update to upgrade EdenMod before Sigstore verification is lost.").withStyle(ChatFormatting.RED));
		}
		if (pendingBootAttestationFailed && client.player != null) {
			pendingBootAttestationFailed = false;
			display(() -> Component.empty().append(Component.literal("[EdenMod] ").withStyle(ChatFormatting.RED)).append(Component.literal("WARNING: ").withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(true))).append(Component.literal("This mod jar could not be verified against any official EdenMod release — it may have been tampered with. Try restarting your game, then if the issue persists, reinstall EdenMod from the official releases. If it still remains, contact FadeDave.").withStyle(ChatFormatting.RED)));
		}

		String connCode = pendingConnectionCode;
		if (connCode != null && client.player != null) {
			pendingConnectionCode = null;
			switch (connCode) {
				case "version_rejected" -> display(() -> Component.literal("[EdenMod] This mod version is no longer accepted by the bridge — use /eden update to upgrade.").withStyle(ChatFormatting.RED));
				case "session_unverified" -> display(() -> Component.literal("[EdenMod] Couldn't verify your Minecraft session — make sure you're logged into the account you linked, then rejoin.").withStyle(ChatFormatting.RED));
				case "http_401" -> {
					Component btn = openConfigButton();
					display(() -> DiscordChatFormatter.tokenExpired(btn));
				}
				default -> display(() -> Component.literal("[EdenMod] Could not connect to the bridge. Try re-linking via /eden config.").withStyle(ChatFormatting.RED));
			}
		}
		// Tab check every 3 seconds: Wynncraft populates the tab list with "Global [XX]"
		// column headers when the player is in a game world or class selection screen,
		// and sends nothing in the queue. This drives the dormant/active state directly.
		if (onWynncraft && ++tabCheckTick >= TAB_CHECK_INTERVAL_TICKS) {
			tabCheckTick = 0;
			setInGameWorld(checkWynncraftTabActive(client));
		}
		// Presence heartbeat every 30 seconds: keeps the server's state fresh even if
		// an intermediate sendPresence call was dropped.
		if (++presenceTick >= PRESENCE_INTERVAL_TICKS) {
			presenceTick = 0;
			BridgeWebSocketClient current = socket;
			if (current != null && onWynncraft) {
				current.sendPresence(inGameWorld);
			}
		}
		// Passive guild-storage read: while a Chief has the rewards menu open (and no
		// gift run is driving it), snapshot aspects/tomes/emeralds and relay on change,
		// so the Discord counter stays live without the mod ever auto-opening a menu.
		if (onWynncraft && ++storageCheckTick >= STORAGE_CHECK_INTERVAL_TICKS) {
			storageCheckTick = 0;
			if (socket != null && !guildRewards.isGiftInProgress()) {
				long[] counts = guildRewards.readAllCounts();
				if (counts != null) {
					relayStorage((int) counts[0], (int) counts[1], counts[2]);
				}
			}
		}
		guildLogSync.tick(BACKGROUND_SYNC_ENABLED && onWynncraft && enteredWynnGameWorld && !guildRewards.isGiftInProgress() && guildRewards.isChief());
	}

	/** Relay a guild reward-storage snapshot over the bridge, skipping unchanged values. */
	private void relayStorage(int aspects, int tomes, long emeralds) {
		long[] snapshot = {aspects, tomes, emeralds};
		if (java.util.Arrays.equals(snapshot, lastStorageSent)) {
			return;
		}
		BridgeWebSocketClient current = socket;
		if (current == null) {
			return;
		}
		lastStorageSent = snapshot;
		current.sendGuildStorage(aspects, tomes, emeralds);
	}

	/** Re-evaluate server gating + connection state after a (re)connect. */
	public void evaluateGating(Minecraft client) {
		var server = client.getCurrentServer();
		boolean integrated = client.getSingleplayerServer() != null;
		onWynncraft = !integrated && server != null && Wynncraft.isWynncraft(server.ip);
		// No local link check: connect whenever enabled on Wynncraft. The backend
		// verifies the Minecraft session and reports standing (link/membership) via
		// authOk, keeping unlinked/non-member players connected as observers.
		if (onWynncraft && config.enabled) {
			connect();
		} else {
			disconnect();
		}
	}

	private synchronized void connect() {
		// The v2 endpoint authenticates the live Minecraft session on every
		// connection, so the socket no longer depends on a JWT and never needs
		// rebuilding: if one already exists, it is current.
		if (socket != null) {
			return;
		}
		try {
			socket = BridgeWebSocketClient.create(BridgeConfig.DEFAULT_BACKEND_URL, MOD_VERSION, new BridgeWebSocketClient.MessageSink() {
				@Override
				public void onDiscordMessage(String author, String content, String replyTo, String replyExcerpt, String color, String messageId) {
					displayColored(color, () -> DiscordChatFormatter.format(author, content, replyTo, replyExcerpt, messageId));
				}

				@Override
				public void onLoginNotice(String username, String color) {
					displayColored(color, () -> DiscordChatFormatter.loginNotice(username));
				}

				@Override
				public void onLogoutNotice(String username, String color) {
					displayColored(color, () -> DiscordChatFormatter.logoutNotice(username));
				}

				@Override
				public void onOnlineList(java.util.List<String> users, String color) {
					displayColored(color, () -> DiscordChatFormatter.onlineList(users));
				}

				@Override
				public void onAspectsPending(java.util.List<PendingEntry> entries, String error, String color) {
					// Rendered by PayoutScreen rather than chat; sort here so every
					// reader sees the biggest debts first.
					java.util.List<PendingEntry> sorted = new ArrayList<>(entries);
					sorted.sort(java.util.Comparator.comparingInt(PendingEntry::amount).reversed());
					// Generation last: it is the reader's signal that the other two
					// fields are the ones belonging to this reply.
					knownPendingAspects = java.util.List.copyOf(sorted);
					pendingAspectsError = (error == null || error.isEmpty()) ? null : error;
					pendingAspectsGeneration.incrementAndGet();
				}

				@Override
				public void onEmeraldsPending(java.util.List<PendingEntry> entries, String error, String color) {
					java.util.List<PendingEntry> sorted = new ArrayList<>(entries);
					sorted.sort(java.util.Comparator.comparingInt(PendingEntry::amount).reversed());
					knownPendingEmeralds = java.util.List.copyOf(sorted);
					pendingEmeraldsError = (error == null || error.isEmpty()) ? null : error;
					pendingEmeraldsGeneration.incrementAndGet();
				}

				@Override
				public void onAspectGiveaway(java.util.List<GiveawayCandidate> candidates, int storageAspects, String error, String color) {
					// Generation last, same reasoning as onAspectsPending above.
					knownGiveawayCandidates = java.util.List.copyOf(candidates);
					giveawayStorageAspects = storageAspects;
					giveawayError = (error == null || error.isEmpty()) ? null : error;
					giveawayGeneration.incrementAndGet();
				}

				@Override
				public void onRewardDeductReply(String target, String rewardKind, int amount, int remaining, String error, String color) {
					if (error != null && !error.isEmpty()) {
						displayColoredDirect(color, () -> DiscordChatFormatter.systemLine("Couldn't deduct pending rewards: " + error, ChatFormatting.RED));
						if (!target.isEmpty()) {
							// The refusal echoes what was attempted, so the request it
							// answers is known outright — no need to infer it from the queue.
							claimPendingDeduct(target, rewardKind);
							displayDirect(() -> GuildRewards.manageResetFallbackLine(rewardKind, target, amount));
						} else {
							// The one refusal that can't echo anything: the backend couldn't
							// parse the request, so it has no target to name and we can't
							// tell which one it was. That means a bug in what this mod sends,
							// so treat the whole batch as unsettled rather than guess.
							handBackOutstandingDeducts("were not deducted");
						}
						return;
					}
					// Claim by target rather than by position: a reply that goes missing
					// then costs one stale entry instead of shifting every later reply onto
					// the wrong player. Only a manual /rewardDeduct queues an entry here, so
					// this also tells us whether to confirm in chat: an automatic payout
					// already gets one summary line from the batch itself, so a
					// "Confirmed ... remaining" line per member on top of that is just noise.
					boolean wasManualRequest = claimPendingDeduct(target, rewardKind);
					if (wasManualRequest) {
						displayColoredDirect(color, () -> DiscordChatFormatter.systemLine("Confirmed " + amount + " " + rewardKind + " deduction for " + target + " — " + remaining + " remaining.", ChatFormatting.GREEN));
					}
				}

				@Override
				public void onGiftLockReply(boolean granted, String error, String holder, int retryAfterSeconds) {
					// Completes whatever GiftLockGateway.acquire() call is currently
					// blocked waiting on it; a reply arriving after that call already
					// timed out just completes a future nobody reads any more.
					CompletableFuture<String> future = pendingGiftLock.getAndSet(null);
					if (future != null) {
						future.complete(granted ? null : (error == null || error.isEmpty() ? "denied" : error));
					}
				}

				@Override
				public void onPartyUpdate(String event, String actor, PartyInfo party, String color) {
					updateKnownParty(event, party);
					// Auto-announce party activity only when the player has the
					// (default-on) party feed enabled.
					if (!config.partyAnnounce) {
						return;
					}
					switch (event) {
						case "open" -> displayColored(color, () -> PartyFormatter.partyOpen(party));
						case "full" -> displayColored(color, () -> PartyFormatter.partyFull(party));
						case "join" -> displayColored(color, () -> PartyFormatter.partyJoin(actor, party));
						case "leave" -> displayColored(color, () -> PartyFormatter.partyLeave(actor, party));
						default -> {
							/* closed/other: nothing to announce */ }
					}
				}

				@Override
				public void onPartyList(java.util.List<PartyInfo> parties, String color) {
					replaceKnownParties(parties);
					displayColored(color, () -> PartyFormatter.listing(parties));
				}

				@Override
				public void onPartyFeedback(String message, String color) {
					displayColored(color, () -> PartyFormatter.feedback(message));
				}

				@Override
				public void onWarCounts(int days, java.util.List<WarCountEntry> entries, String requester, String color) {
					// Cache the player's own 7-day count for the HUD chip regardless of
					// who triggered the request.
					if (days == 7 && !requester.isEmpty()) {
						int own = entries.stream().filter(e -> e.name().equalsIgnoreCase(requester)).mapToInt(WarCountEntry::wars).findFirst().orElse(0);
						WarTracker.setWeeklyWars(own);
					}
					if (suppressNextWarCountsDisplay) {
						suppressNextWarCountsDisplay = false;
						return;
					}
					displayColored(color, () -> DiscordChatFormatter.warCounts(days, entries, requester));
				}

				@Override
				public void onWarBoard(java.util.List<WarBoardEntry> entries) {
					// Marshal onto the client thread: the attack-timer maps are otherwise
					// only touched there (render + the menu scraper), so this keeps all
					// access single-threaded (this callback runs on the websocket thread).
					Minecraft.getInstance().execute(() -> AttackTimerMenu.updateBoard(entries));
				}

				@Override
				public void onConnectionRejected(String code) {
					pendingConnectionCode = code;
					Minecraft.getInstance().execute(() -> socket = null);
				}

				@Override
				public void onPillMessage(String label, String content, String colorHex, boolean bold) {
					if (GAMES_PILL_LABEL.equals(label) && config.gameDisplayMode != BridgeConfig.GameDisplayMode.ALL) {
						// React Only still shows your own coinflip/diceroll result; every
						// other "eden"-labelled message (streaks, gambit resets, reward
						// summaries, other players' rolls, ...) stays hidden.
						boolean ownRoll = config.gameDisplayMode == BridgeConfig.GameDisplayMode.REACTIONS && isOwnGameResult(content);
						if (!ownRoll) {
							return;
						}
					}
					if ("reactions".equals(label) && config.gameDisplayMode == BridgeConfig.GameDisplayMode.NONE) {
						return;
					}
					Integer colorRgb = null;
					if (colorHex != null && !colorHex.isEmpty()) {
						try {
							colorRgb = Integer.parseInt(colorHex, 16);
						} catch (NumberFormatException ignored) {
							// malformed color: fall back to the default gold pill
						}
					}
					Integer finalColorRgb = colorRgb;
					display(() -> DiscordChatFormatter.pill(label, content, finalColorRgb, bold));
				}

				@Override
				public void onGameFeedback(String message, String color) {
					displayColored(color, () -> DiscordChatFormatter.systemLine(message, net.minecraft.ChatFormatting.GOLD));
				}

				@Override
				public void onAuthStatus(boolean linked, boolean member, String guildRank, String discordRank) {
					handleAuthStatus(linked, member, guildRank, discordRank);
				}
			}, EdenModClient::authenticateSession);
			socket.start();
		} catch (IllegalArgumentException e) {
			LOGGER.warn("Not connecting: {}", e.getMessage());
			socket = null;
		}
	}

	/**
	 * Answer a v2 session challenge: run Mojang's {@code joinServer} for the current
	 * account against the server's {@code serverId} nonce and return the IGN to
	 * report. The access token is sent only to Mojang. The bridge then verifies the
	 * join via {@code hasJoined} — so a patched mod cannot fake this, and a copied
	 * token is useless to anyone not logged into this Minecraft account.
	 */
	private static String authenticateSession(String serverId) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		User user = mc.getUser();
		MinecraftSessionService sessionService = new YggdrasilAuthenticationService(mc.getProxy()).createMinecraftSessionService();
		sessionService.joinServer(user.getProfileId(), user.getAccessToken(), serverId);
		return user.getName();
	}

	/** A clickable "[B]" button (or whatever key is bound) that opens the config screen. */
	private Component openConfigButton() {
		String keyName = openConfigKey.getTranslatedKeyMessage().getString();
		Style style = Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/eden config")).withHoverEvent(new HoverEvent.ShowText(Component.literal("Open EdenMod settings")));
		return Component.literal("[" + keyName + "]").setStyle(style);
	}

	/** Check GitHub for a newer release once per session; prompt in chat if found. */
	private void checkForUpdateOnce() {
		if (updateChecked) {
			return;
		}
		updateChecked = true;
		Thread thread = new Thread(() -> {
			// Cert expiry is local — check it first, before any network call.
			long certDaysLeft = UpdateChecker.fulcioCertDaysLeft();
			boolean certExpired = certDaysLeft < 0;
			if (certExpired) {
				pendingFulcioCertExpiredWarning = true;
			}
			updateChecker.check().ifPresent(update -> {
				pendingUpdate = update;
				pendingUpdateNotification = true;
				// Warn if the cert is about to expire but not yet expired — this update
				// will carry a fresh cert, so nudging the player to update now preserves
				// full Sigstore verification for the next update cycle.
				if (!certExpired && certDaysLeft < 90) {
					pendingFulcioCertExpiringSoonWarning = true;
				}
			});
		}, "edenmod-update-check");
		thread.setDaemon(true);
		thread.start();
	}

	/** {@code /eden update}: show the prompt if one is pending, else re-check + report. */
	private void updatePrompt(FabricClientCommandSource source) {
		UpdateInfo cached = pendingUpdate;
		if (cached != null) {
			source.sendFeedback(DiscordChatFormatter.updateAvailable(cached.version(), cached.pageUrl()));
			return;
		}
		source.sendFeedback(DiscordChatFormatter.systemLine("Checking for updates...", ChatFormatting.GREEN));
		Thread thread = new Thread(() -> {
			UpdateInfo update = updateChecker.check().orElse(null);
			if (update != null) {
				pendingUpdate = update;
				display(() -> DiscordChatFormatter.updateAvailable(update.version(), update.pageUrl()));
			} else {
				String current = UpdateChecker.currentVersion();
				display(() -> DiscordChatFormatter.systemLine("EdenMod is up to date" + (current == null ? "" : " (" + current + ")") + ".", ChatFormatting.GREEN));
			}
		}, "edenmod-update-check");
		thread.setDaemon(true);
		thread.start();
	}

	/** {@code /eden update download}: fetch the pending update; it applies on game close. */
	private void updateDownload(FabricClientCommandSource source) {
		UpdateInfo info = pendingUpdate;
		if (info == null) {
			source.sendFeedback(DiscordChatFormatter.systemLine("No update available. Run /eden update to check.", ChatFormatting.GOLD));
			return;
		}
		if (updateStaged) {
			source.sendFeedback(DiscordChatFormatter.systemLine("Update already downloaded — it applies when you close the game.", ChatFormatting.GREEN));
			return;
		}
		source.sendFeedback(DiscordChatFormatter.systemLine("Downloading EdenMod " + info.version() + "...", ChatFormatting.GREEN));
		Thread thread = new Thread(() -> {
			UpdateInstaller.Result result = updateInstaller.downloadAndStage(info);
			switch (result) {
				case SCHEDULED -> {
					updateStaged = true;
					display(() -> DiscordChatFormatter.systemLine("EdenMod " + info.version() + " downloaded — it applies when you close the game.", ChatFormatting.GREEN));
				}
				case NOT_INSTALLED_FROM_JAR -> display(() -> DiscordChatFormatter.systemLine("Couldn't auto-update (not running from a jar). Use the link to download.", ChatFormatting.GOLD));
				case FAILED -> display(() -> DiscordChatFormatter.systemLine("Update download failed. Try again in a moment. If the issue persist, update the mod manually.", ChatFormatting.RED));
				case VERIFICATION_FAILED -> display(() -> DiscordChatFormatter.systemLine("EdenMod " + info.version() + " downloaded but could not be verified against the official release — try again later. If the issue persists, contact FadeDave.", ChatFormatting.RED));
			}
		}, "edenmod-update-download");
		thread.setDaemon(true);
		thread.start();
	}

	private void requestOnline(FabricClientCommandSource source) {
		BridgeWebSocketClient current = socket;
		if (current == null) {
			source.sendFeedback(notConnected());
			return;
		}
		current.sendOnlineRequest();
	}

	/** One-shot congratulate: DM the configured message if the button is unclaimed. */
	private void congratulate(FabricClientCommandSource source, String name) {
		if (!ChatDecorators.consumePending(name)) {
			source.sendFeedback(DiscordChatFormatter.systemLine(name + " has nothing to be congratulated for!", ChatFormatting.GOLD));
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() != null) {
			mc.getConnection().sendCommand("msg " + name + " " + config.congratsMessage);
		}
	}

	/** In-game {@code /eden wars}: backend-authoritative counts (matches Discord). */
	private void requestWarCounts(FabricClientCommandSource source, int days) {
		BridgeWebSocketClient current = socket;
		if (current == null) {
			source.sendFeedback(notConnected());
			return;
		}
		current.sendWarCountsRequest(days);
	}

	private void requestCoinflip(FabricClientCommandSource source) {
		BridgeWebSocketClient current = socket;
		if (current == null) {
			source.sendFeedback(notConnected());
			return;
		}
		current.sendCoinflip();
	}

	private void requestDiceroll(FabricClientCommandSource source) {
		BridgeWebSocketClient current = socket;
		if (current == null) {
			source.sendFeedback(notConnected());
			return;
		}
		current.sendDiceroll();
	}

	private void requestAspectsPending(FabricClientCommandSource source) {
		// Warm the rank cache so the payout table can show ranks straight away; the
		// screen itself issues the backend request from its init().
		guildRewards.ensureFresh(playerName());
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.setScreen(new tel.eden.mod.gui.PayoutScreen(mc.screen, this)));
	}

	/** Build {@code /eden gift <member> <aspect|emerald|tome> <amount>} (Chiefs only). */
	private LiteralArgumentBuilder<FabricClientCommandSource> buildGiftCommand() {
		return ClientCommandManager.literal("gift").then(ClientCommandManager.argument("member", StringArgumentType.word()).suggests(this::suggestMembers).then(giftTypeArg("aspect", GuildRewards.RewardType.ASPECT)).then(giftTypeArg("emerald", GuildRewards.RewardType.EMERALD)).then(giftTypeArg("tome", GuildRewards.RewardType.TOME)));
	}

	private LiteralArgumentBuilder<FabricClientCommandSource> giftTypeArg(String literal, GuildRewards.RewardType type) {
		return ClientCommandManager.literal(literal).then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(1)).executes(ctx -> giftReward(ctx.getSource(), StringArgumentType.getString(ctx, "member"), type, IntegerArgumentType.getInteger(ctx, "amount"))));
	}

	/** Build {@code /eden deduct <aspects|emeralds> <member> <amount>} (Chiefs only). */
	private LiteralArgumentBuilder<FabricClientCommandSource> buildDeductCommand() {
		return ClientCommandManager.literal("deduct").then(deductKindArg("aspects")).then(deductKindArg("emeralds"));
	}

	private LiteralArgumentBuilder<FabricClientCommandSource> deductKindArg(String kind) {
		// The bound matches the backend's own validation, so an out-of-range amount is
		// rejected by the command parser instead of making a doomed round trip.
		return ClientCommandManager.literal(kind).then(ClientCommandManager.argument("member", StringArgumentType.word()).suggests(this::suggestMembers).then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(1, 100_000)).executes(ctx -> deductReward(ctx.getSource(), kind, StringArgumentType.getString(ctx, "member"), IntegerArgumentType.getInteger(ctx, "amount")))));
	}

	private int deductReward(FabricClientCommandSource source, String rewardKind, String member, int amount) {
		guildRewards.ensureFresh(playerName());
		// Courtesy check only — the backend authorises by the linked account either way.
		// Skipped while the member list is still loading, so a cold rank cache can't refuse a
		// Chief; the unknown-member case is likewise left to the backend, whose view of
		// the guild is fresher than the cached member list.
		if (!guildRewards.memberNames().isEmpty() && !guildRewards.isChief()) {
			source.sendFeedback(Component.literal("Only guild Chiefs can deduct pending rewards.").withStyle(ChatFormatting.RED));
			return 0;
		}
		if (socket == null) {
			source.sendFeedback(notConnected());
			return 0;
		}
		sendDeduct(rewardKind, member, amount);
		return 1;
	}

	private CompletableFuture<Suggestions> suggestMembers(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
		String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
		for (String name : guildRewards.memberNames()) {
			if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
				builder.suggest(name);
			}
		}
		return builder.buildFuture();
	}

	private int giftReward(FabricClientCommandSource source, String member, GuildRewards.RewardType type, int amount) {
		if (!ensureRewardsReady(source)) {
			return 0;
		}
		showGiftProgress(type.name().charAt(0) + type.name().substring(1).toLowerCase(java.util.Locale.ROOT) + " Gift");
		guildRewards.gift(member, type, amount);
		return 1;
	}

	private int dumpEmeralds(FabricClientCommandSource source, String member) {
		if (!ensureRewardsReady(source)) {
			return 0;
		}
		showGiftProgress("Emerald Dump");
		guildRewards.dumpEmeralds(member);
		return 1;
	}

	/** Show the blocking gift-progress screen and attach it as the run's listener, before submitting the run. */
	private void showGiftProgress(String title) {
		Minecraft mc = Minecraft.getInstance();
		tel.eden.mod.gui.GiftProgressScreen progress = new tel.eden.mod.gui.GiftProgressScreen(title, guildRewards);
		guildRewards.setProgressListener(progress);
		// Deferred, not immediate: this runs from inside chat command dispatch, which
		// itself is inside ChatScreen's own send-and-close handling — a same-tick
		// setScreen() here loses the race against that close and gets silently
		// overwritten back to null (see requestAspectsPending for the same pattern).
		// isDone() guards the (unlikely but possible) case of an extremely fast run
		// already finishing before this deferred call runs — see GiftProgressScreen's
		// own isDone() doc for why that would otherwise leave the screen stuck open.
		mc.execute(() -> {
			if (!progress.isDone()) {
				mc.setScreen(progress);
			}
		});
	}

	/** Send one deliberate manual deduction request, falling back to chat guidance when offline. */
	private void sendDeduct(String rewardKind, String target, int displayUnits) {
		BridgeWebSocketClient current = socket;
		PendingDeduct entry = new PendingDeduct(rewardKind, target, displayUnits);
		// Queue before sending: the reply arrives on the websocket thread and would
		// otherwise be able to overtake the bookkeeping for its own request.
		pendingDeducts.add(entry);
		// A live client whose socket is mid-reconnect drops the send silently, so an
		// unsent request must not be left outstanding — it would be answered by some
		// later request's reply.
		if (current == null || !current.sendRewardDeductRequest(rewardKind, target, displayUnits, true)) {
			pendingDeducts.remove(entry);
			displayDirect(() -> DiscordChatFormatter.systemLine("Not connected to the bridge — deduct " + target + "'s " + displayUnits + " pending " + rewardKind + " by hand:", ChatFormatting.RED));
			displayDirect(() -> GuildRewards.manageResetFallbackLine(rewardKind, target, displayUnits));
			return;
		}
		// Announce the request, not just its answer: a reply can be slow, refused, or
		// never come at all, and without this line those cases are indistinguishable
		// from the deduction never having been attempted.
		displayDirect(() -> DiscordChatFormatter.systemLine("Sent deduction of " + displayUnits + " " + rewardKind + " for " + target + "...", ChatFormatting.GOLD));
	}

	/**
	 * Remove the outstanding request a successful reply answers, if still queued.
	 * Returns whether one was found — only a manual /rewardDeduct ever queues an
	 * entry (see sendDeduct), so this doubles as "was this reply to a manual request".
	 */
	private boolean claimPendingDeduct(String target, String rewardKind) {
		for (PendingDeduct entry : pendingDeducts) {
			if (entry.target().equalsIgnoreCase(target) && entry.rewardKind().equals(rewardKind)) {
				pendingDeducts.remove(entry);
				return true;
			}
		}
		return false;
	}

	/** Gate the reward commands to Wynncraft and ensure the member list is loaded. */
	private boolean ensureRewardsReady(FabricClientCommandSource source) {
		if (!onWynncraft) {
			source.sendFeedback(Component.literal("Guild rewards are only available on Wynncraft.").withStyle(net.minecraft.ChatFormatting.RED));
			return false;
		}
		guildRewards.ensureFresh(playerName());
		if (guildRewards.memberNames().isEmpty()) {
			source.sendFeedback(Component.literal("Fetching guild data — try again in a moment.").withStyle(net.minecraft.ChatFormatting.YELLOW));
			return false;
		}
		return true;
	}

	public static String playerName() {
		return Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getGameProfile().name() : null;
	}

	/**
	 * Whether an "eden"-pill message looks like a coinflip/diceroll result mentioning
	 * the local player, so React Only mode can carve out "your own rolls" from the
	 * rest of that label (streaks, gambit resets, reward summaries, other players'
	 * rolls). Matches the stable wording {@code games.py}'s ordinary (non-secret)
	 * results always use; a handful of rare jackpot outcomes with fully custom
	 * wording won't match, same as any other message this mode still hides.
	 */
	private static boolean isOwnGameResult(String content) {
		boolean looksLikeRoll = content.contains("flipped a coin") || content.contains("rolled a");
		if (!looksLikeRoll) {
			return false;
		}
		String name = playerName();
		return name != null && content.contains(name);
	}

	private int openOverviewGui(FabricClientCommandSource source) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(this::openMenuGui);
		return 1;
	}

	private void openMenuGui() {
		Minecraft mc = Minecraft.getInstance();
		mc.setScreen(new tel.eden.mod.gui.EdenMenuScreen());
	}

	private int openPartyCreateGui(FabricClientCommandSource source) {
		return openPartyCreateGui(source, null);
	}

	private int openPartyCreateGui(FabricClientCommandSource source, String target) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.setScreen(new PartyCreateScreen(mc.screen, this, target)));
		return 1;
	}

	private int openPartyListGui(FabricClientCommandSource source) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.setScreen(new PartyListScreen(mc.screen, this)));
		return 1;
	}

	private int openPartyManageGui(FabricClientCommandSource source) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			PartyInfo hostedParty = hostedParty();
			if (hostedParty != null) {
				mc.setScreen(new PartyManageScreen(mc.screen, this, hostedParty));
			} else if (mc.player != null) {
				mc.player.displayClientMessage(Component.literal("You are not hosting a party!").withStyle(net.minecraft.ChatFormatting.RED), false);
			}
		});
		return 1;
	}

	private PartyInfo hostedParty() {
		String ign = playerName();
		if (ign == null) {
			return null;
		}
		for (PartyInfo party : knownParties()) {
			if (party.host().equalsIgnoreCase(ign)) {
				return party;
			}
		}
		return null;
	}

	private int openCommandAliasGui(FabricClientCommandSource source) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.setScreen(new CommandAliasScreen(mc.screen, this)));
		return 1;
	}

	private int openCommandKeybindGui(FabricClientCommandSource source) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.setScreen(new CommandKeybindScreen(mc.screen, this)));
		return 1;
	}

	private LiteralArgumentBuilder<FabricClientCommandSource> buildCommandCommand() {
		return ClientCommandManager.literal("command").executes(ctx -> showCommandSectionHelp(ctx.getSource())).then(buildCommandAliasCommand()).then(buildCommandKeybindCommand());
	}

	private LiteralArgumentBuilder<FabricClientCommandSource> buildCommandAliasCommand() {
		return ClientCommandManager.literal("alias").executes(ctx -> openCommandAliasGui(ctx.getSource())).then(ClientCommandManager.literal("list").executes(ctx -> listCommandAliases(ctx.getSource()))).then(ClientCommandManager.literal("add").then(ClientCommandManager.argument("alias", StringArgumentType.word()).then(ClientCommandManager.argument("command", StringArgumentType.greedyString()).executes(ctx -> upsertCommandAlias(ctx.getSource(), StringArgumentType.getString(ctx, "alias"), StringArgumentType.getString(ctx, "command")))))).then(ClientCommandManager.literal("remove").executes(ctx -> openCommandAliasGui(ctx.getSource())).then(ClientCommandManager.argument("alias", StringArgumentType.word()).suggests(this::suggestCommandAliases).executes(ctx -> removeCommandAlias(ctx.getSource(), StringArgumentType.getString(ctx, "alias"))))).then(ClientCommandManager.literal("clear").executes(ctx -> clearCommandAliases(ctx.getSource())));
	}

	private LiteralArgumentBuilder<FabricClientCommandSource> buildCommandKeybindCommand() {
		return ClientCommandManager.literal("keybind").executes(ctx -> openCommandKeybindGui(ctx.getSource())).then(ClientCommandManager.literal("list").executes(ctx -> listCommandKeybinds(ctx.getSource()))).then(ClientCommandManager.literal("remove").executes(ctx -> openCommandKeybindGui(ctx.getSource())).then(ClientCommandManager.argument("input", StringArgumentType.word()).suggests(this::suggestCommandKeybindInputs).executes(ctx -> removeCommandKeybind(ctx.getSource(), StringArgumentType.getString(ctx, "input"))))).then(ClientCommandManager.literal("clear").executes(ctx -> clearCommandKeybinds(ctx.getSource())));
	}

	private LiteralArgumentBuilder<FabricClientCommandSource> otherLiteral() {
		return ClientCommandManager.literal("other").executes(ctx -> openPartyCreateGui(ctx.getSource(), "Other")).then(ClientCommandManager.argument("size", IntegerArgumentType.integer(2, 10)).executes(ctx -> partyOpen(ctx.getSource(), "Other", IntegerArgumentType.getInteger(ctx, "size"), "", 0)).then(ClientCommandManager.argument("filled", IntegerArgumentType.integer(0, 8)).executes(ctx -> partyOpen(ctx.getSource(), "Other", IntegerArgumentType.getInteger(ctx, "size"), "", IntegerArgumentType.getInteger(ctx, "filled"))).then(ClientCommandManager.argument("note", StringArgumentType.greedyString()).executes(ctx -> partyOpen(ctx.getSource(), "Other", IntegerArgumentType.getInteger(ctx, "size"), StringArgumentType.getString(ctx, "note"), IntegerArgumentType.getInteger(ctx, "filled"))))).then(ClientCommandManager.argument("note", StringArgumentType.greedyString()).executes(ctx -> partyOpen(ctx.getSource(), "Other", IntegerArgumentType.getInteger(ctx, "size"), StringArgumentType.getString(ctx, "note"), 0)))).then(ClientCommandManager.argument("note", StringArgumentType.greedyString()).executes(ctx -> partyOpen(ctx.getSource(), "Other", 4, StringArgumentType.getString(ctx, "note"), 0)));
	}

	/** Build the {@code /eden party ...} subcommand tree (list/create/join/leave). */
	private LiteralArgumentBuilder<FabricClientCommandSource> buildPartyCommand() {
		return ClientCommandManager.literal("party").executes(ctx -> openPartyListGui(ctx.getSource())).then(ClientCommandManager.literal("list").executes(ctx -> openPartyListGui(ctx.getSource()))).then(ClientCommandManager.literal("create").executes(ctx -> openPartyCreateGui(ctx.getSource())).then(raidLiteral("notg", "Nest of the Grootslangs")).then(raidLiteral("nol", "Orphion's Nexus of Light")).then(raidLiteral("tcc", "The Canyon Colossus")).then(raidLiteral("tna", "The Nameless Anomaly")).then(raidLiteral("wtp", "The Wartorn Palace")).then(otherLiteral())).then(ClientCommandManager.literal("join").executes(ctx -> openPartyListGui(ctx.getSource())).then(ClientCommandManager.argument("id", IntegerArgumentType.integer()).executes(ctx -> partyJoin(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id"))))).then(ClientCommandManager.literal("leave").executes(ctx -> partyLeave(ctx.getSource(), null)).then(ClientCommandManager.argument("id", IntegerArgumentType.integer()).executes(ctx -> partyLeave(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
					// Driven by the "[Create party]" prompt shown when a party fills: runs
					// /party create then invites each listed member in-game.
					.then(ClientCommandManager.literal("makeingame").then(ClientCommandManager.argument("members", StringArgumentType.greedyString()).executes(ctx -> makeInGameParty(ctx.getSource(), StringArgumentType.getString(ctx, "members"))))).then(ClientCommandManager.literal("note").executes(ctx -> openPartyManageGui(ctx.getSource())).then(ClientCommandManager.argument("text", StringArgumentType.greedyString()).executes(ctx -> partyManage(ctx.getSource(), "note", StringArgumentType.getString(ctx, "text"), 0, "")))).then(ClientCommandManager.literal("filled").executes(ctx -> openPartyManageGui(ctx.getSource())).then(ClientCommandManager.argument("slots", IntegerArgumentType.integer(0, 8)).executes(ctx -> partyManage(ctx.getSource(), "filled", "", IntegerArgumentType.getInteger(ctx, "slots"), "")))).then(ClientCommandManager.literal("add").executes(ctx -> openPartyManageGui(ctx.getSource())).then(ClientCommandManager.argument("player", StringArgumentType.word()).suggests(this::suggestMembers).executes(ctx -> partyManage(ctx.getSource(), "add", "", 0, StringArgumentType.getString(ctx, "player"))))).then(ClientCommandManager.literal("remove").executes(ctx -> openPartyManageGui(ctx.getSource())).then(ClientCommandManager.argument("player", StringArgumentType.word()).suggests(this::suggestMembers).executes(ctx -> partyManage(ctx.getSource(), "remove", "", 0, StringArgumentType.getString(ctx, "player"))))).then(ClientCommandManager.literal("disband").executes(ctx -> partyManage(ctx.getSource(), "close", "", 0, ""))).then(ClientCommandManager.literal("close").executes(ctx -> partyManage(ctx.getSource(), "close", "", 0, "")));
	}

	private int partyManage(FabricClientCommandSource source, String action, String text, int value, String ign) {
		BridgeWebSocketClient current = socket;
		if (current == null) {
			source.sendFeedback(notConnected());
			return 0;
		}
		current.sendPartyManage(action, text, value, ign);
		return 1;
	}

	/** Run {@code /party create} then {@code /party <ign>} for each member except yourself. */
	private int makeInGameParty(FabricClientCommandSource source, String membersArg) {
		String self = playerName();
		List<String> invites = new ArrayList<>();
		for (String name : membersArg.trim().split("\\s+")) {
			if (!name.isEmpty() && (self == null || !name.equalsIgnoreCase(self)) && !name.startsWith("*")) {
				invites.add(name);
			}
		}
		createInGameParty(invites);
		source.sendFeedback(PartyFormatter.feedback("Creating your in-game party and inviting " + invites.size() + " player(s)..."));
		return 1;
	}

	public void createInGameParty(List<String> invites) {
		sendServerCommandLater("party create", 0L);
		long delay = PARTY_COMMAND_DELAY_MS;
		for (String ign : invites) {
			if (ign.equals("*filled*") || ign.equalsIgnoreCase(playerName()))
				continue;
			if (!ign.matches("[A-Za-z0-9_]{1,16}"))
				continue;
			sendServerCommandLater("party " + ign, delay);
			delay += PARTY_COMMAND_DELAY_MS;
		}
	}

	/** Send one server command after {@code delayMs}, on the client thread. */
	private void sendServerCommandLater(String command, long delayMs) {
		int generation = partyCommandGeneration.get();
		partyCommandExecutor.schedule(() -> Minecraft.getInstance().execute(() -> {
			if (partyCommandGeneration.get() != generation) {
				return;
			}
			var connection = Minecraft.getInstance().getConnection();
			if (connection != null) {
				connection.sendCommand(command);
			}
		}), delayMs, TimeUnit.MILLISECONDS);
	}

	public synchronized List<BridgeConfig.CommandAlias> commandAliases() {
		return config.commandAliases.stream().map(alias -> new BridgeConfig.CommandAlias(alias.alias, alias.command)).toList();
	}

	public synchronized List<BridgeConfig.CommandKeybind> commandKeybinds() {
		return config.commandKeybinds.stream().map(keybind -> new BridgeConfig.CommandKeybind(keybind.input, keybind.command)).toList();
	}

	public synchronized boolean saveCommandAlias(String alias, String command) {
		String normalizedAlias = normalizeAlias(alias);
		String normalizedCommand = normalizeStoredCommand(command);
		if (normalizedAlias == null || normalizedCommand == null) {
			return false;
		}
		String aliasCommandForm = normalizeStoredCommand(normalizedAlias);
		if (aliasCommandForm != null && aliasCommandForm.equalsIgnoreCase(normalizedCommand)) {
			return false;
		}
		for (BridgeConfig.CommandAlias entry : config.commandAliases) {
			if (entry.alias.equalsIgnoreCase(normalizedAlias)) {
				entry.alias = normalizedAlias;
				entry.command = normalizedCommand;
				config.save();
				return true;
			}
		}
		config.commandAliases.add(new BridgeConfig.CommandAlias(normalizedAlias, normalizedCommand));
		config.save();
		return true;
	}

	public synchronized boolean deleteCommandAlias(String alias) {
		String normalizedAlias = normalizeAlias(alias);
		if (normalizedAlias == null) {
			return false;
		}
		boolean removed = config.commandAliases.removeIf(entry -> entry.alias.equalsIgnoreCase(normalizedAlias));
		if (removed) {
			config.save();
		}
		return removed;
	}

	public synchronized void clearAllCommandAliases() {
		if (!config.commandAliases.isEmpty()) {
			config.commandAliases.clear();
			config.save();
		}
	}

	public synchronized boolean deleteCommandKeybind(String input) {
		String normalizedInput = normalizeKeybindInput(input);
		if (normalizedInput == null) {
			return false;
		}
		boolean removed = config.commandKeybinds.removeIf(entry -> entry.input.equalsIgnoreCase(normalizedInput));
		if (removed) {
			config.save();
			refreshCommandKeybinds();
		}
		return removed;
	}

	public synchronized void clearAllCommandKeybinds() {
		if (!config.commandKeybinds.isEmpty()) {
			config.commandKeybinds.clear();
			config.save();
			refreshCommandKeybinds();
		}
	}

	public synchronized boolean replaceCommandAliases(List<BridgeConfig.CommandAlias> aliases) {
		List<BridgeConfig.CommandAlias> normalized = new ArrayList<>();
		for (BridgeConfig.CommandAlias alias : aliases) {
			String normalizedAlias = normalizeAlias(alias.alias);
			String normalizedCommand = normalizeStoredCommand(alias.command);
			if (normalizedAlias == null && normalizedCommand == null) {
				continue;
			}
			if (normalizedAlias == null || normalizedCommand == null) {
				return false;
			}
			String aliasCommandForm = normalizeStoredCommand(normalizedAlias);
			if (aliasCommandForm != null && aliasCommandForm.equalsIgnoreCase(normalizedCommand)) {
				return false;
			}
			boolean duplicate = normalized.stream().anyMatch(entry -> entry.alias.equalsIgnoreCase(normalizedAlias));
			if (duplicate) {
				return false;
			}
			normalized.add(new BridgeConfig.CommandAlias(normalizedAlias, normalizedCommand));
		}
		config.commandAliases.clear();
		config.commandAliases.addAll(normalized);
		config.save();
		return true;
	}

	public synchronized boolean replaceCommandKeybinds(List<BridgeConfig.CommandKeybind> keybinds) {
		List<BridgeConfig.CommandKeybind> normalized = new ArrayList<>();
		for (BridgeConfig.CommandKeybind keybind : keybinds) {
			String normalizedInput = normalizeKeybindInput(keybind.input);
			String normalizedCommand = normalizeStoredCommand(keybind.command);
			if (normalizedInput == null && normalizedCommand == null) {
				continue;
			}
			if (normalizedInput == null || normalizedCommand == null) {
				return false;
			}
			boolean duplicate = normalized.stream().anyMatch(entry -> entry.input.equalsIgnoreCase(normalizedInput));
			if (duplicate) {
				return false;
			}
			normalized.add(new BridgeConfig.CommandKeybind(normalizedInput, normalizedCommand));
		}
		config.commandKeybinds.clear();
		config.commandKeybinds.addAll(normalized);
		config.save();
		refreshCommandKeybinds();
		return true;
	}

	public synchronized String rewriteOutgoingCommand(String commandLine) {
		String normalizedInput = normalizeCommandInput(commandLine);
		if (normalizedInput == null) {
			return null;
		}
		String resolved = normalizedInput;
		boolean changed = false;
		Set<String> seen = new HashSet<>();
		seen.add(normalizedInput.toLowerCase(Locale.ROOT));
		for (int depth = 0; depth < 16; depth++) {
			String next = rewriteCommandAliasOnce(resolved);
			if (next == null || next.equalsIgnoreCase(resolved)) {
				break;
			}
			if (!seen.add(next.toLowerCase(Locale.ROOT))) {
				break;
			}
			resolved = next;
			changed = true;
		}
		return changed ? resolved : null;
	}

	/** Observe the player's /class aliases before their server command is sent. */
	public void onOutgoingCommand(String commandLine) {
		String normalized = normalizeCommandInput(commandLine);
		if (normalized == null) {
			return;
		}
		String command = normalized.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
		if (command.equals("class") || command.equals("char") || command.equals("characters")) {
			onCharacterSelection();
		}
	}

	/** Wynncraft's character-selection action bar is present with or without Wynntils. */
	public void handleWynncraftActionBar(Component actionBar) {
		if (!onWynncraft || actionBar == null) {
			return;
		}
		String text = actionBar.getString();
		if (text.contains("Left-Click to play") && text.contains("Right-Click to switch")) {
			onCharacterSelection();
		}
	}

	private void onCharacterSelection() {
		enteredWynnGameWorld = false;
		guildLogSync.onWorldTransition();
		setInGameWorld(false);
	}

	private LiteralArgumentBuilder<FabricClientCommandSource> raidLiteral(String alias, String raid) {
		return ClientCommandManager.literal(alias).executes(ctx -> openPartyCreateGui(ctx.getSource(), raid)).then(ClientCommandManager.argument("filled", IntegerArgumentType.integer(0, 2)).executes(ctx -> partyOpen(ctx.getSource(), raid, 4, "", IntegerArgumentType.getInteger(ctx, "filled"))).then(ClientCommandManager.argument("note", StringArgumentType.greedyString()).executes(ctx -> partyOpen(ctx.getSource(), raid, 4, StringArgumentType.getString(ctx, "note"), IntegerArgumentType.getInteger(ctx, "filled"))))).then(ClientCommandManager.argument("note", StringArgumentType.greedyString()).executes(ctx -> partyOpen(ctx.getSource(), raid, 4, StringArgumentType.getString(ctx, "note"), 0)));
	}

	/** Build {@code /eden anni <size> [note]} — open an Annihilation party of 2-10. */
	private LiteralArgumentBuilder<FabricClientCommandSource> buildAnnihilationCommand() {
		return ClientCommandManager.literal("anni").executes(ctx -> openPartyCreateGui(ctx.getSource(), "Annihilation")).then(ClientCommandManager.argument("size", IntegerArgumentType.integer(2, 10)).executes(ctx -> partyOpen(ctx.getSource(), "Annihilation", IntegerArgumentType.getInteger(ctx, "size"), "", 0)).then(ClientCommandManager.argument("filled", IntegerArgumentType.integer(0, 8)).executes(ctx -> partyOpen(ctx.getSource(), "Annihilation", IntegerArgumentType.getInteger(ctx, "size"), "", IntegerArgumentType.getInteger(ctx, "filled"))).then(ClientCommandManager.argument("note", StringArgumentType.greedyString()).executes(ctx -> partyOpen(ctx.getSource(), "Annihilation", IntegerArgumentType.getInteger(ctx, "size"), StringArgumentType.getString(ctx, "note"), IntegerArgumentType.getInteger(ctx, "filled"))))).then(ClientCommandManager.argument("note", StringArgumentType.greedyString()).executes(ctx -> partyOpen(ctx.getSource(), "Annihilation", IntegerArgumentType.getInteger(ctx, "size"), StringArgumentType.getString(ctx, "note"), 0))));
	}

	private int partyList(FabricClientCommandSource source) {
		BridgeWebSocketClient current = socket;
		if (current == null) {
			source.sendFeedback(notConnected());
			return 0;
		}
		current.sendPartyList();
		return 1;
	}

	private int partyOpen(FabricClientCommandSource source, String raid, int maxSize, String note, int filled) {
		BridgeWebSocketClient current = socket;
		if (current == null) {
			source.sendFeedback(notConnected());
			return 0;
		}
		current.sendPartyOpen(raid, maxSize, note, filled);
		return 1;
	}

	private int partyJoin(FabricClientCommandSource source, int id) {
		BridgeWebSocketClient current = socket;
		if (current == null) {
			source.sendFeedback(notConnected());
			return 0;
		}
		current.sendPartyJoin(id);
		return 1;
	}

	private int partyLeave(FabricClientCommandSource source, Integer id) {
		BridgeWebSocketClient current = socket;
		if (current == null) {
			source.sendFeedback(notConnected());
			return 0;
		}
		current.sendPartyLeave(id);
		return 1;
	}

	// Suppress a shout preview for this long after the compose prompt (bounds the rare
	// cancel-before-preview case); the single-shot consume is the real guard.
	private static final long SHOUT_PREVIEW_WINDOW_MS = 5_000L;
	// The pill label the bridge games (cf/diceroll/8ball) ride on; hidden by config toggle.
	private static final String GAMES_PILL_LABEL = "eden";

	private static Component notConnected() {
		return Component.literal("Not connected to the Eden bridge.").withStyle(net.minecraft.ChatFormatting.RED);
	}

	private int showCommandSectionHelp(FabricClientCommandSource source) {
		source.sendFeedback(Component.literal("Command tools: /eden command alias, /eden command keybind").withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private int upsertCommandAlias(FabricClientCommandSource source, String alias, String command) {
		if (!saveCommandAlias(alias, command)) {
			source.sendFeedback(Component.literal("Alias and command must both be non-empty, and they cannot be identical.").withStyle(ChatFormatting.RED));
			return 0;
		}
		String normalizedAlias = normalizeAlias(alias);
		String normalizedCommand = normalizeCommandDisplay(command);
		source.sendFeedback(Component.literal("Saved command alias ").withStyle(ChatFormatting.GREEN).append(Component.literal(normalizedAlias).withStyle(ChatFormatting.AQUA)).append(Component.literal(" -> ").withStyle(ChatFormatting.GRAY)).append(Component.literal(normalizedCommand).withStyle(ChatFormatting.AQUA)));
		return 1;
	}

	private int removeCommandAlias(FabricClientCommandSource source, String alias) {
		String normalizedAlias = normalizeAlias(alias);
		if (normalizedAlias == null || !deleteCommandAlias(alias)) {
			source.sendFeedback(Component.literal("No command alias exists for " + alias + ".").withStyle(ChatFormatting.RED));
			return 0;
		}
		source.sendFeedback(Component.literal("Removed command alias " + normalizedAlias + ".").withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private int clearCommandAliases(FabricClientCommandSource source) {
		if (commandAliases().isEmpty()) {
			source.sendFeedback(Component.literal("No command aliases are configured.").withStyle(ChatFormatting.YELLOW));
			return 1;
		}
		clearAllCommandAliases();
		source.sendFeedback(Component.literal("Cleared all command aliases.").withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private int listCommandAliases(FabricClientCommandSource source) {
		List<BridgeConfig.CommandAlias> aliases = commandAliases();
		if (aliases.isEmpty()) {
			source.sendFeedback(Component.literal("No command aliases configured. Use /eden command alias to add one.").withStyle(ChatFormatting.YELLOW));
			return 1;
		}
		MutableComponent out = Component.literal("Command aliases:").withStyle(ChatFormatting.GREEN);
		for (BridgeConfig.CommandAlias entry : aliases) {
			Style aliasStyle = Style.EMPTY.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.SuggestCommand(entry.alias)).withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to put " + entry.alias + " in your chat box")));
			String removeAlias = normalizeCommandInput(entry.alias);
			Style removeStyle = Style.EMPTY.withColor(ChatFormatting.RED).withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/eden command alias remove " + removeAlias)).withHoverEvent(new HoverEvent.ShowText(Component.literal("Remove " + entry.alias)));
			out.append(Component.literal("\n  ")).append(Component.literal(entry.alias).setStyle(aliasStyle)).append(Component.literal(" -> ").withStyle(ChatFormatting.GRAY)).append(Component.literal(normalizeCommandDisplay(entry.command)).withStyle(ChatFormatting.WHITE)).append(Component.literal(" ")).append(Component.literal("[remove]").setStyle(removeStyle));
		}
		source.sendFeedback(out);
		return 1;
	}

	private CompletableFuture<Suggestions> suggestCommandAliases(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
		String remaining = builder.getRemainingLowerCase();
		for (BridgeConfig.CommandAlias entry : commandAliases()) {
			if (entry.alias.toLowerCase(Locale.ROOT).startsWith(remaining)) {
				builder.suggest(entry.alias);
			}
		}
		return builder.buildFuture();
	}

	private int removeCommandKeybind(FabricClientCommandSource source, String input) {
		String normalizedInput = normalizeKeybindInput(input);
		if (normalizedInput == null || !deleteCommandKeybind(input)) {
			source.sendFeedback(Component.literal("No command keybind exists for " + input + ".").withStyle(ChatFormatting.RED));
			return 0;
		}
		source.sendFeedback(Component.literal("Removed command keybind ").withStyle(ChatFormatting.GREEN).append(Component.literal(describeKeybindInput(normalizedInput)).withStyle(ChatFormatting.AQUA)).append(Component.literal(".").withStyle(ChatFormatting.GREEN)));
		return 1;
	}

	private int clearCommandKeybinds(FabricClientCommandSource source) {
		if (commandKeybinds().isEmpty()) {
			source.sendFeedback(Component.literal("No command keybinds are configured.").withStyle(ChatFormatting.YELLOW));
			return 1;
		}
		clearAllCommandKeybinds();
		source.sendFeedback(Component.literal("Cleared all command keybinds.").withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private int listCommandKeybinds(FabricClientCommandSource source) {
		List<BridgeConfig.CommandKeybind> keybinds = commandKeybinds();
		if (keybinds.isEmpty()) {
			source.sendFeedback(Component.literal("No command keybinds configured. Use /eden command keybind to add one.").withStyle(ChatFormatting.YELLOW));
			return 1;
		}
		MutableComponent out = Component.literal("Command keybinds:").withStyle(ChatFormatting.GREEN);
		for (BridgeConfig.CommandKeybind entry : keybinds) {
			String displayInput = describeKeybindInput(entry.input);
			Style removeStyle = Style.EMPTY.withColor(ChatFormatting.RED).withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/eden command keybind remove " + entry.input)).withHoverEvent(new HoverEvent.ShowText(Component.literal("Remove " + displayInput)));
			out.append(Component.literal("\n  ")).append(Component.literal(displayInput).withStyle(ChatFormatting.AQUA)).append(Component.literal(" -> ").withStyle(ChatFormatting.GRAY)).append(Component.literal(normalizeCommandDisplay(entry.command)).withStyle(ChatFormatting.WHITE)).append(Component.literal(" ")).append(Component.literal("[remove]").setStyle(removeStyle));
		}
		source.sendFeedback(out);
		return 1;
	}

	private CompletableFuture<Suggestions> suggestCommandKeybindInputs(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
		String remaining = builder.getRemainingLowerCase();
		for (BridgeConfig.CommandKeybind entry : commandKeybinds()) {
			if (entry.input.toLowerCase(Locale.ROOT).startsWith(remaining)) {
				builder.suggest(entry.input);
			}
		}
		return builder.buildFuture();
	}

	private static String normalizeAlias(String alias) {
		String normalized = normalizeCommandInput(alias);
		return normalized == null ? null : "/" + normalized.toLowerCase(Locale.ROOT);
	}

	private synchronized void refreshCommandKeybinds() {
		trackedCommandKeybinds.clear();
		for (BridgeConfig.CommandKeybind entry : config.commandKeybinds) {
			String normalizedInput = normalizeKeybindInput(entry.input);
			String normalizedCommand = normalizeCommandInput(entry.command);
			if (normalizedInput == null || normalizedCommand == null) {
				continue;
			}
			InputConstants.Key key = parseInputKey(normalizedInput);
			if (key == null) {
				continue;
			}
			trackedCommandKeybinds.add(new TrackedCommandKeybind(normalizedInput, normalizedCommand, key));
		}
	}

	private void pollCommandKeybinds(Minecraft client) {
		if (client.player == null || client.getConnection() == null || client.screen != null) {
			releaseAllCommandKeybinds();
			return;
		}
		var window = client.getWindow();
		List<TrackedCommandKeybind> pressed = new ArrayList<>();
		synchronized (this) {
			for (TrackedCommandKeybind keybind : trackedCommandKeybinds) {
				boolean down = isInputDown(window, keybind.key());
				if (down && !keybind.down()) {
					pressed.add(keybind);
				}
				keybind.setDown(down);
			}
		}
		for (TrackedCommandKeybind keybind : pressed) {
			sendConfiguredCommand(keybind.command());
		}
	}

	private synchronized void releaseAllCommandKeybinds() {
		for (TrackedCommandKeybind keybind : trackedCommandKeybinds) {
			keybind.setDown(false);
		}
	}

	private void sendConfiguredCommand(String commandLine) {
		Minecraft client = Minecraft.getInstance();
		var connection = client.getConnection();
		if (connection == null) {
			return;
		}
		String outgoing = rewriteOutgoingCommand(commandLine);
		connection.sendCommand(outgoing != null ? outgoing : normalizeTargetCommand(commandLine));
	}

	private String rewriteCommandAliasOnce(String normalizedInput) {
		BridgeConfig.CommandAlias best = null;
		for (BridgeConfig.CommandAlias entry : config.commandAliases) {
			String alias = normalizeCommandInput(entry.alias);
			String target = normalizeCommandInput(entry.command);
			if (alias == null || target == null || alias.equalsIgnoreCase(target)) {
				continue;
			}
			if (normalizedInput.equalsIgnoreCase(alias) || normalizedInput.toLowerCase(Locale.ROOT).startsWith(alias.toLowerCase(Locale.ROOT) + " ")) {
				if (best == null || alias.length() > best.alias.length()) {
					best = new BridgeConfig.CommandAlias(alias, target);
				}
			}
		}
		if (best == null) {
			return null;
		}
		if (normalizedInput.length() == best.alias.length()) {
			return best.command;
		}
		return best.command + normalizedInput.substring(best.alias.length());
	}

	public static String describeKeybindInput(String input) {
		InputConstants.Key key = parseInputKey(input);
		return key == null ? input : key.getDisplayName().getString();
	}

	public static String normalizeKeybindInput(String input) {
		if (input == null) {
			return null;
		}
		String normalized = input.trim();
		if (normalized.isEmpty()) {
			return null;
		}
		String lower = normalized.toLowerCase(Locale.ROOT);
		if (lower.startsWith("key.keyboard.") || lower.startsWith("key.mouse.") || lower.startsWith("scancode.")) {
			return lower;
		}
		if (lower.startsWith("mouse:")) {
			return normalizeMouseInput(lower.substring("mouse:".length()).trim());
		}
		if (lower.startsWith("mouse.")) {
			return normalizeMouseInput(lower.substring("mouse.".length()).trim());
		}
		if (lower.startsWith("keyboard:")) {
			return normalizeKeyboardInput(lower.substring("keyboard:".length()).trim());
		}
		if (lower.startsWith("key:")) {
			return normalizeKeyboardInput(lower.substring("key:".length()).trim());
		}
		if (normalized.length() == 1) {
			return normalizeKeyboardInput(normalized);
		}
		InputConstants.Key key = parseInputKey(normalized);
		return key == null ? null : key.getName();
	}

	private static String normalizeKeyboardInput(String token) {
		if (token.isEmpty()) {
			return null;
		}
		if (token.length() == 1) {
			char value = Character.toLowerCase(token.charAt(0));
			if ((value >= 'a' && value <= 'z') || (value >= '0' && value <= '9')) {
				return parseInputKey("key.keyboard." + value).getName();
			}
		}
		InputConstants.Key key = parseInputKey(token.startsWith("key.keyboard.") ? token : "key.keyboard." + token.toLowerCase(Locale.ROOT));
		return key == null ? null : key.getName();
	}

	private static String normalizeMouseInput(String token) {
		if (token.isEmpty()) {
			return null;
		}
		return switch (token) {
			case "left", "1" -> InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_LEFT).getName();
			case "right", "2" -> InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_RIGHT).getName();
			case "middle", "3" -> InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_MIDDLE).getName();
			case "4", "button4", "back" -> InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_4).getName();
			case "5", "button5", "forward" -> InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_5).getName();
			default -> {
				InputConstants.Key key = parseInputKey(token.startsWith("key.mouse.") ? token : "key.mouse." + token.toLowerCase(Locale.ROOT));
				yield key == null ? null : key.getName();
			}
		};
	}

	private static InputConstants.Key parseInputKey(String input) {
		try {
			InputConstants.Key key = InputConstants.getKey(input);
			return key.equals(InputConstants.UNKNOWN) ? null : key;
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static boolean isInputDown(com.mojang.blaze3d.platform.Window window, InputConstants.Key key) {
		return key.getType() == InputConstants.Type.MOUSE ? GLFW.glfwGetMouseButton(window.handle(), key.getValue()) == GLFW.GLFW_PRESS : InputConstants.isKeyDown(window, key.getValue());
	}

	private static String normalizeTargetCommand(String command) {
		return normalizeCommandInput(command);
	}

	private static String normalizeStoredCommand(String command) {
		String normalized = normalizeCommandInput(command);
		return normalized == null ? null : "/" + normalized;
	}

	private static String normalizeCommandInput(String command) {
		if (command == null) {
			return null;
		}
		String normalized = command.trim();
		if (normalized.isEmpty()) {
			return null;
		}
		if (normalized.startsWith("/")) {
			normalized = normalized.substring(1).trim();
		}
		return normalized.isEmpty() ? null : normalized;
	}

	private static String normalizeCommandDisplay(String command) {
		String normalized = normalizeStoredCommand(command);
		return normalized == null ? "/" : normalized;
	}

	/** Open the chat emote picker, centered in the chat screen. */
	private int showEmojis(FabricClientCommandSource source) {
		java.util.List<String> codes = EmoteRegistry.shortcodes();
		if (codes.isEmpty()) {
			source.sendFeedback(DiscordChatFormatter.systemLine("No chat emojis are loaded.", ChatFormatting.YELLOW));
			return 1;
		}
		openCenteredEmotePicker();
		return 1;
	}

	private int handleReplyCommand(String messageId) {
		ChatReplyManager.setActiveReply(messageId);
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (!(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) {
				mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen("/g ", false));
			} else {
				ChatReplyManager.ensureGuildPrefix();
			}
		});
		return 1;
	}

	private record HelpEntry(String command, String description) {
	}

	private static final List<HelpEntry> HELP_ENTRIES = List.of(new HelpEntry("/eden config", "open the config screen"), new HelpEntry("/eden online", "who's connected to the bridge"), new HelpEntry("/eden cf", "flip a coin"), new HelpEntry("/eden diceroll", "roll a die"), new HelpEntry("/eden wars [days]", "guild war counts (same as Discord)"), new HelpEntry("/eden emojis", "open the chat emote picker"), new HelpEntry("/eden party", "list open parties (click to join)"), new HelpEntry("/eden party create <raid> [note]", "open a raid party"), new HelpEntry("/eden party join <id>", "join a party"), new HelpEntry("/eden party leave [id]", "leave your party"), new HelpEntry("/eden party disband", "disband your hosted party"), new HelpEntry("/eden anni <size> [note]", "open an Annihilation party (2-10)"), new HelpEntry("/eden command alias", "open the command alias editor"), new HelpEntry("/eden command keybind", "open the command keybind editor"), new HelpEntry("/eden update", "check for a pending update"), new HelpEntry("/eden update download", "download the update now (applies on exit)"), new HelpEntry("/eden aspects pending", "members' pending aspects — Chiefs only"), new HelpEntry("/eden gift <member> <aspect|emerald|tome> <amount>", "gift guild rewards — Chiefs only"), new HelpEntry("/eden dump <member>", "gift all guild-bank emeralds to a member — Chiefs only"), new HelpEntry("/eden deduct <aspects|emeralds> <member> <amount>", "deduct a payout from pending rewards — Chiefs only"), new HelpEntry("/eden help", "this help screen"));

	private static final class TrackedCommandKeybind {
		private final String input;
		private final String command;
		private final InputConstants.Key key;
		private boolean down;

		private TrackedCommandKeybind(String input, String command, InputConstants.Key key) {
			this.input = input;
			this.command = command;
			this.key = key;
		}

		private String input() {
			return input;
		}

		private String command() {
			return command;
		}

		private InputConstants.Key key() {
			return key;
		}

		private boolean down() {
			return down;
		}

		private void setDown(boolean down) {
			this.down = down;
		}
	}

	/** Print the in-game command list client-side. */
	private void showHelp(FabricClientCommandSource source) {
		MutableComponent help = Component.literal("EdenMod — in-game commands:").withStyle(net.minecraft.ChatFormatting.GREEN);
		for (HelpEntry entry : HELP_ENTRIES) {
			help.append(helpLine(entry.command(), entry.description()));
		}
		help.append(Component.literal("\nPress ").withStyle(ChatFormatting.DARK_GRAY)).append(openConfigButton()).append(Component.literal(" to open the bridge config screen.").withStyle(ChatFormatting.DARK_GRAY));
		source.sendFeedback(help);
	}

	private static MutableComponent helpLine(String command, String description) {
		return Component.empty().append(Component.literal("\n  " + command).withStyle(net.minecraft.ChatFormatting.AQUA)).append(Component.literal(" — " + description).withStyle(net.minecraft.ChatFormatting.GRAY));
	}

	/** Immediately send presence and reconnect the socket if the world state changed. */
	private void setInGameWorld(boolean active) {
		if (inGameWorld == active)
			return;
		inGameWorld = active;
		BridgeWebSocketClient current = socket;
		if (current != null && onWynncraft) {
			current.sendPresence(active);
		}
	}

	/** Returns true when the Wynncraft tab list is visible (game world or /class screen). */
	private boolean checkWynncraftTabActive(Minecraft mc) {
		net.minecraft.client.multiplayer.ClientPacketListener conn = mc.getConnection();
		if (conn == null)
			return false;
		for (net.minecraft.client.multiplayer.PlayerInfo info : conn.getOnlinePlayers()) {
			net.minecraft.network.chat.Component name = info.getTabListDisplayName();
			if (name != null && (name.getString().contains("Global [") || name.getString().contains("Island Info"))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Hand every deduct still awaiting a reply back to the Chief, naming each one and
	 * what became of it ({@code reason} completes "<player>'s N aspects ..."). Retrying
	 * isn't safe — the backend may well have applied a request whose reply went missing
	 * — so an unanswered deduct is always settled by hand.
	 */
	private void handBackOutstandingDeducts(String reason) {
		for (PendingDeduct stale = pendingDeducts.poll(); stale != null; stale = pendingDeducts.poll()) {
			PendingDeduct entry = stale;
			displayDirect(() -> DiscordChatFormatter.systemLine(entry.target() + "'s " + entry.displayUnits() + " " + entry.rewardKind() + " " + reason + " — check and reset if needed:", ChatFormatting.RED));
			displayDirect(() -> GuildRewards.manageResetFallbackLine(entry.rewardKind(), entry.target(), entry.displayUnits()));
		}
	}

	/**
	 * Apply the backend's {@code authOk} standing. Full access (linked and a member)
	 * begins the session; otherwise the socket stays open with no guild access and the
	 * player is prompted once per standing change. Runs off the game thread.
	 */
	private void handleAuthStatus(boolean linked, boolean member, String guildRank, String discordRank) {
		BridgeStatus previous = bridgeStatus;
		if (linked && member) {
			bridgeStatus = BridgeStatus.FULL;
			liveGuildRank = guildRank;
			liveDiscordRank = discordRank;
			onBridgeConnected();
		} else if (!linked) {
			bridgeStatus = BridgeStatus.NOT_LINKED;
			if (previous != BridgeStatus.NOT_LINKED) {
				display(() -> DiscordChatFormatter.linkReminder(openConfigButton()));
			}
		} else {
			bridgeStatus = BridgeStatus.NOT_MEMBER;
			if (previous != BridgeStatus.NOT_MEMBER) {
				display(() -> DiscordChatFormatter.systemLine("You're not in the Eden guild — the bridge is inactive.", ChatFormatting.YELLOW));
			}
		}
	}

	/** On full access, begin this session: hand back deducts and announce login once. */
	private void onBridgeConnected() {
		handBackOutstandingDeducts("were not confirmed deducted before the bridge dropped");
		if (!attestationFailureReported) {
			String failedSha = updateInstaller.bootAttestationFailureSha();
			if (failedSha != null) {
				attestationFailureReported = true;
				BridgeWebSocketClient current = socket;
				if (current != null) {
					current.sendAttestationFailure(failedSha);
				}
			}
		}
		if (loginPending) {
			loginPending = false;
			BridgeWebSocketClient current = socket;
			if (current != null) {
				// Only broadcast our own login when the player hasn't opted out; the
				// backend pairs the logout to this, so skipping it keeps both quiet.
				// Presence is always sent — it drives the online/dormant state, not a
				// chat notice.
				if (config.announceSelfPresence) {
					current.sendLogin();
				}
				current.sendPresence(inGameWorld); // send whatever state we already detected
			}
		}
	}

	private synchronized void disconnect() {
		partyCommandGeneration.incrementAndGet();
		onWynncraft = false;
		loginPending = false;
		bridgeStatus = BridgeStatus.UNKNOWN;
		liveGuildRank = "";
		liveDiscordRank = "";
		inGameWorld = false;
		enteredWynnGameWorld = false;
		tabCheckTick = 0;
		presenceTick = 0;
		pendingConnectionCode = null;
		// Drop the captured scoreboard + war-board state so nothing carries into the next
		// connection (stale timers/defence/heads).
		ScoreboardCapture.reset();
		AttackTimerMenu.reset();
		// Likewise the raid tally: it holds players from the session that just ended, and
		// after an account switch could attribute them to another guild's raid.
		RaidPartyTracker.reset();
		RaidReadyTracker.reset();
		PartyHealthTracker.reset();
		ChatReplyManager.reset();
		// Before the socket goes: an outstanding deduct must not outlive the session that
		// made it, or it resurfaces at the next connect naming a player from another
		// server — or, after an account switch, another guild.
		handBackOutstandingDeducts("were not confirmed deducted before the bridge dropped");
		if (socket != null) {
			socket.close();
			socket = null;
		}
	}

	/**
	 * Feed reward success/rejection packets into the gift driver. Called for both normal
	 * system chat and overlay/ticker packets, independently of bridge connectivity.
	 */
	public void handleRewardFeedback(Component message) {
		if (!onWynncraft) {
			return;
		}
		if (RewardUnavailableParser.matches(message)) {
			guildRewards.onRewardUnavailable();
			return;
		}
		if (RewardUnavailableParser.isOutOfStock(message)) {
			guildRewards.onRewardOutOfStock();
			return;
		}
		GuildRewardParser.parse(message).ifPresent(guildRewards::onConfirmedReward);
	}

	/**
	 * Run Wynncraft's {@code /stream} for the auto-stream option.
	 *
	 * <p>Must be called on the client thread, where the connection is owned.
	 */
	private static void sendStreamCommand() {
		var connection = Minecraft.getInstance().getConnection();
		if (connection != null) {
			connection.sendCommand("stream");
		}
	}

	/** Called from the chat-capture mixin for every non-overlay system-chat component. */
	public void handleSystemChat(Component message) {
		PlayerNameResolver.observeMessage(message);
		handleRewardFeedback(message);
		// A real chat line breaks any in-progress Discord emblem block, so the next
		// relayed Discord message starts with a fresh shield (like guild chat).
		DiscordChatFormatter.onServerChatLine();
		if (onWynncraft) {
			RaidReadyTracker.onSystemChat(message);
			// War chat cues (start countdown → attendance capture; end → HUD summary)
			// work even while the bridge socket is down. Keep these deferred until after
			// this packet handler returns, alongside the rest of the client-tick state.
			String warLine = message.getString();
			Minecraft.getInstance().execute(() -> {
				WarTracker.onChat(warLine);
				WarDPS.onChat(warLine);
			});
			// Auto /stream sits above the socket guard on purpose: it is a local
			// convenience and has nothing to do with the bridge, so it keeps working
			// while the backend is down or the player is unlinked.
			if (config.autoStream && warLine.contains("Welcome to Wynncraft")) {
				Minecraft.getInstance().execute(EdenModClient::sendStreamCommand);
			}
		}
		// Entering a Wynncraft game world always sends this greeting. Use it for
		// immediate active-state detection and to keep /gu log out of class selection.
		if (onWynncraft && message.getString().contains("Welcome to Wynncraft!")) {
			enteredWynnGameWorld = true;
			// /class returns to a new world without a Fabric connection-JOIN event. Treat
			// its welcome exactly like a fresh query lifecycle: WynnExtras/Wynntils can
			// leave a packet-only character-selection container behind, and GuildLogSync
			// needs a new bounded startup-recovery window for it.
			guildLogSync.onWorldJoin();
			setInGameWorld(true);
		}
		BridgeWebSocketClient current = socket;
		if (!onWynncraft || current == null) {
			return;
		}
		if (message.getString().contains("Welcome to Wynncraft!")) {
			return;
		}
		// Guild raid completions are aqua announcements (not player chat); handle
		// them first so they are tracked rather than mistaken for guild chat.
		Optional<RaidCompletion> raid = RaidCompletionParser.parse(message);
		if (raid.isPresent()) {
			relayRaid(current, raid.get(), "parsed");
			return;
		}
		// Guild bank deposits/withdrawals — also announcements, not player chat. A
		// per-occurrence seq keeps rapid identical deposits from being deduped away.
		Optional<BankEvent> bank = BankEventParser.parse(message);
		if (bank.isPresent()) {
			BankEvent line = bank.get();
			String signature = line.action() + "|" + line.player() + "|" + line.quantity() + "|" + line.item() + "|" + line.accessTier();
			current.sendBankEvent(line.action(), line.player(), line.quantity(), line.item(), line.charges(), line.accessTier(), bankSeq.next(signature));
			return;
		}
		// Guild rank changes (promotions/demotions) — announcements, not chat.
		Optional<RankChange> rank = RankChangeParser.parse(message);
		if (rank.isPresent()) {
			RankChange line = rank.get();
			if (rankRelay.shouldSend(new CapturedMessage(line.target(), line.newRank(), line.oldRank() + ">" + line.newRank()))) {
				current.sendRankChange(line.target(), line.oldRank(), line.newRank(), line.setter());
			}
			return;
		}
		// Guild membership + alliance announcements (joins, invites, kicks, alliances).
		Optional<GuildEvent> guildEvent = GuildEventParser.parse(message);
		if (guildEvent.isPresent()) {
			GuildEvent line = guildEvent.get();
			if (guildEventRelay.shouldSend(new CapturedMessage(line.subject(), line.actor(), line.kind()))) {
				current.sendGuildEvent(line.kind(), line.actor(), line.subject());
			}
			return;
		}
		// Guild reward handouts (aspects/tomes/emeralds granted to a member). A
		// per-occurrence seq keeps a run of identical handouts distinct so the bot
		// can count them, while collapsing the same handout seen by other clients.
		Optional<GuildReward> reward = GuildRewardParser.parse(message);
		if (reward.isPresent()) {
			GuildReward line = reward.get();
			String signature = line.giver() + "|" + line.reward() + "|" + line.receiver();
			current.sendGuildReward(line.giver(), line.reward(), line.receiver(), rewardSeq.next(signature));
			return;
		}
		// Annihilation world-event warning (the only advance timing signal we get).
		java.util.OptionalInt anni = AnnihilationParser.parse(message);
		if (anni.isPresent()) {
			int seconds = anni.getAsInt();
			if (annihilationRelay.shouldSend(new CapturedMessage("annihilation", null, String.valueOf(seconds)))) {
				current.sendAnnihilation(seconds);
			}
			return;
		}
		// The compose prompt precedes a live "<name> shouts: ..." preview of an as-yet-unsent
		// shout; arm a short single-shot so that preview isn't relayed. (Handles the prompt and
		// preview arriving as separate messages or one bundled component — the check runs first.)
		if (ShoutParser.isComposePrompt(message)) {
			shoutPreviewSuppressUntil = System.currentTimeMillis() + SHOUT_PREVIEW_WINDOW_MS;
		}
		// Guild shouts, mirrored into the bridge chat channel.
		Optional<String> shout = ShoutParser.parse(message);
		if (shout.isPresent()) {
			if (System.currentTimeMillis() < shoutPreviewSuppressUntil) {
				shoutPreviewSuppressUntil = 0L; // consume: this is the preview, not a sent shout
				return;
			}
			String text = shout.get();
			if (shoutRelay.shouldSend(new CapturedMessage("shout", null, text))) {
				current.sendGuildAnnounce(text);
			}
			return;
		}
		// Level-up announcements, relayed to the bridge chat ONLY for Eden members
		// (the member list is fetched from the API on join and refreshed periodically).
		Optional<LevelUp> levelUp = LevelUpParser.parse(message);
		if (levelUp.isPresent()) {
			LevelUp lu = levelUp.get();
			guildRewards.ensureFresh(playerName());
			if (guildRewards.isMember(lu.name()) && levelUpRelay.shouldSend(new CapturedMessage(lu.name(), null, lu.detail()))) {
				current.sendGuildAnnounce(lu.name() + " reached " + lu.detail() + "!");
			}
			return;
		}
		// Guild flavour announcements (weekly objective done, boosting) mirrored
		// verbatim into the Discord bridge chat.
		Optional<String> announce = GuildAnnounceParser.parse(message);
		if (announce.isPresent()) {
			String text = announce.get();
			if (guildAnnounceRelay.shouldSend(new CapturedMessage("announce", null, text))) {
				current.sendGuildAnnounce(text);
			}
			return;
		}
		// Guild level-up banner ("Eden is now level N  +<reward>"), relayed verbatim
		// into the bridge chat since the reward tail differs at every level.
		Optional<String> guildLevel = GuildLevelUpParser.parse(message);
		if (guildLevel.isPresent()) {
			String text = guildLevel.get();
			if (guildLevelUpRelay.shouldSend(new CapturedMessage("guildlevel", null, text))) {
				current.sendGuildAnnounce(text);
			}
			return;
		}
		if (!GuildChatParser.looksLikeGuildChat(message)) {
			return;
		}
		// A shared Wynncraft item string (rendered to a card) may ride alongside or
		// instead of normal guild-chat text; handle it before the text relay.
		maybeRelayItemCard(current, message);
		Optional<CapturedMessage> captured = GuildChatParser.parse(message);
		if (captured.isPresent() && config.warAttackTimers) {
			// Fold any "<territory> defense is <rating>" report (e.g. Wynntils') into the
			// attack-timer HUD as fresher intel than the scraped advancement value. Marshal
			// onto the client thread — the HUD reads chatDefenses on the render thread, and
			// this runs on the netty network thread.
			String username = captured.get().username();
			String body = captured.get().message();
			Minecraft.getInstance().execute(() -> AttackTimerMenu.intakeChat(username, body));
		}
		if (captured.isPresent() && chatRelay.shouldSend(captured.get())) {
			CapturedMessage line = captured.get();
			// 0-based: a first occurrence is seq 0, which matches what an old mod (no
			// seq field) defaults to on the backend, so mixed mod versions still dedup.
			int seq = chatSeq.next(line.username() + "|" + line.message()) - 1;
			String replyToId = ChatReplyManager.consumePendingReply(line.username(), line.message());
			current.sendGuildChat(line.username(), line.nickname(), line.message(), seq, replyToId);
		}
	}

	/** Send a parsed raid completion (local-dedup guarded), logging the outcome. */
	private void relayRaid(BridgeWebSocketClient current, RaidCompletion line, String how) {
		boolean sent = raidRelay.shouldSend(new CapturedMessage(line.raidName(), null, String.join(",", line.party())));
		// Allies are omitted from the announcement, so a short party means this was an
		// allied raid; who else was present fills in the rest. Only for a raid this
		// player was actually in — the tally is of players around us, so attaching it to
		// another group's completion would name whoever we happened to be standing near.
		boolean ownRaid = isOwnRaid(line.party());
		java.util.List<String> extras = ownRaid ? RaidPartyTracker.extraPlayers(line.party()) : java.util.List.of();
		LOGGER.info("Raid {}: {} party={} extras={} aspects={} emeralds={} -> {}", how, line.raidName(), line.party(), extras, line.aspects(), line.emeralds(), sent ? "sent" : "deduped");
		if (sent) {
			current.sendRaidCompletion(line.party(), line.raidName(), line.aspects(), line.emeralds(), line.guildExp(), extras);
		}
		// One raid, one tally — dropped even when the send was deduped, or a completion
		// arriving within the retention window would inherit this raid's players.
		if (ownRaid) {
			RaidPartyTracker.reset();
			RaidReadyTracker.reset();
		}
	}

	/** Whether the local player is named in a raid completion's party. */
	private boolean isOwnRaid(java.util.List<String> party) {
		String self = playerName();
		if (self == null || self.isBlank()) {
			return false;
		}
		for (String member : party) {
			if (self.equalsIgnoreCase(member)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * If this guild-chat line carries a shared Wynncraft item string, decode it (via
	 * Wynntils), render a card, and relay it to the bridge as the sender — off-thread.
	 * No-op unless enabled, Wynntils is installed, and a sender can be resolved.
	 */
	private void maybeRelayItemCard(BridgeWebSocketClient current, Component message) {
		if (!isItemDecoderAvailable()) {
			return;
		}
		Optional<ItemStringDetector.Detected> detected = ItemStringDetector.detect(message.getString());
		Optional<CapturedMessage> sender = GuildChatParser.parseSender(message);
		if (detected.isEmpty() || sender.isEmpty()) {
			return;
		}
		ItemStringDetector.Detected item = detected.get();
		CapturedMessage who = sender.get();
		// Local dedup: each system-chat line is delivered twice (netty + render thread).
		if (!itemCardRelay.shouldSend(new CapturedMessage(who.username(), null, item.itemString()))) {
			return;
		}
		itemCardExecutor.submit(() -> renderAndSendItemCard(current, who, item));
	}

	private void renderAndSendItemCard(BridgeWebSocketClient current, CapturedMessage who, ItemStringDetector.Detected item) {
		try {
			Optional<DecodedItem> decoded = decodeSharedItem(item.itemString(), item.craftedName());
			if (decoded.isEmpty()) {
				return;
			}
			DecodedItem card = decoded.get();
			String image = Base64.getEncoder().encodeToString(ItemCardRenderer.render(card));
			String overall = card.hasOverall() ? String.format(Locale.ROOT, "%.2f", card.overallPercent()) : "";
			current.sendItemCard(who.username(), who.nickname(), image, card.name() + "|" + overall);
		} catch (Exception e) {
			LOGGER.warn("Failed to render/relay shared item card", e);
		}
	}

	private boolean isItemDecoderAvailable() {
		try {
			Class<?> decoder = Class.forName("tel.eden.mod.item.WynntilsItemDecoder");
			Object available = decoder.getMethod("isAvailable").invoke(null);
			return available instanceof Boolean b && b;
		} catch (Throwable t) {
			LOGGER.warn("Shared-item decoder unavailable", t);
			return false;
		}
	}

	@SuppressWarnings("unchecked")
	private Optional<DecodedItem> decodeSharedItem(String itemString, String craftedName) {
		try {
			Class<?> decoder = Class.forName("tel.eden.mod.item.WynntilsItemDecoder");
			Object decoded = decoder.getMethod("decode", String.class, String.class).invoke(null, itemString, craftedName);
			return decoded instanceof Optional<?> optional ? (Optional<DecodedItem>) optional : Optional.empty();
		} catch (Throwable t) {
			LOGGER.warn("Shared-item decoder failed to load", t);
			return Optional.empty();
		}
	}

	/**
	 * Build and show a client-side chat line on the game thread. The component is
	 * built inside {@code execute} because the formatter touches the font/text
	 * splitter, which must run on the client thread (not the WebSocket thread).
	 */
	private void display(java.util.function.Supplier<Component> builder) {
		display(builder, true);
	}

	/**
	 * Show a line that must not be routed through the Wynntils tab bridge. That bridge
	 * reports success as soon as tabs are enabled, but whether the line is ever rendered
	 * is up to the tab filters — one that matches nothing is dropped silently. Fine for
	 * ordinary bridge chatter; not for reward bookkeeping, where a swallowed line is
	 * indistinguishable from the deduction never having been attempted.
	 */
	private void displayDirect(java.util.function.Supplier<Component> builder) {
		display(builder, false);
	}

	private void display(java.util.function.Supplier<Component> builder, boolean viaChatTab) {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			if (client.player != null) {
				Component component = builder.get();
				if (!viaChatTab || !WynntilsChatBridge.sendToTab(component)) {
					client.player.displayClientMessage(component, false);
				}
			}
		});
	}

	/**
	 * Display a bridge line, honouring an optional backend colour override: when the
	 * backend attaches a {@code "RRGGBB"} colour to the message, the whole rendered line
	 * is painted in it; otherwise the line keeps its built-in colours. This lets the
	 * backend retune any message's colour without a mod update.
	 */
	private void displayColored(String colorHex, java.util.function.Supplier<Component> builder) {
		displayColored(colorHex, builder, true);
	}

	/** {@link #displayColored} for a line that must bypass the Wynntils tab bridge. */
	private void displayColoredDirect(String colorHex, java.util.function.Supplier<Component> builder) {
		displayColored(colorHex, builder, false);
	}

	private void displayColored(String colorHex, java.util.function.Supplier<Component> builder, boolean viaChatTab) {
		Integer rgb = parseHexColor(colorHex);
		if (rgb == null) {
			display(builder, viaChatTab);
			return;
		}
		int color = rgb;
		display(() -> recolor(builder.get(), color), viaChatTab);
	}

	/** Parse a {@code "RRGGBB"} hex colour, or {@code null} if empty/malformed. */
	private static Integer parseHexColor(String hex) {
		if (hex == null || hex.isEmpty()) {
			return null;
		}
		try {
			return Integer.parseInt(hex, 16);
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	/** Deep-copy a component, forcing every span to {@code rgb} (fonts/events preserved). */
	private static Component recolor(Component c, int rgb) {
		net.minecraft.network.chat.MutableComponent out = net.minecraft.network.chat.MutableComponent.create(c.getContents());
		out.setStyle(c.getStyle().withColor(rgb));
		for (Component sibling : c.getSiblings()) {
			out.append(recolor(sibling, rgb));
		}
		return out;
	}

	/**
	 * Start the browser link flow. On success the account link is recorded on the
	 * backend (no token is stored locally); we then re-handshake so the bridge re-reads
	 * our now-linked standing — /ws/v2 authenticates the live Minecraft session.
	 */
	public void startLinkFlow(Runnable onDone) {
		new AuthFlow().begin(BridgeConfig.DEFAULT_BACKEND_URL, new AuthFlow.Callback() {
			@Override
			public void onSuccess() {
				Minecraft.getInstance().execute(() -> {
					// An existing observer connection won't upgrade itself; a fresh
					// handshake makes the backend re-read the now-linked standing.
					reconnectBridge();
					display(DiscordChatFormatter::linkSuccess);
					onDone.run();
				});
			}

			@Override
			public void onError(String messageText) {
				LOGGER.warn("Link error: {}", messageText);
				Minecraft.getInstance().execute(onDone);
			}
		});
	}

	/** Drop any current bridge socket and re-evaluate, forcing a fresh /ws/v2 handshake. */
	private synchronized void reconnectBridge() {
		if (socket != null) {
			socket.close();
			socket = null;
		}
		evaluateGating(Minecraft.getInstance());
	}
}
