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
import tel.eden.mod.chat.LevelUp;
import tel.eden.mod.chat.LevelUpParser;
import tel.eden.mod.chat.OccurrenceSequencer;
import tel.eden.mod.chat.PartyFormatter;
import tel.eden.mod.chat.RaidCompletion;
import tel.eden.mod.chat.RaidCompletionParser;
import tel.eden.mod.chat.RankChange;
import tel.eden.mod.chat.RankChangeParser;
import tel.eden.mod.chat.ShoutParser;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.gui.BridgeConfigScreen;
import tel.eden.mod.gui.CommandAliasScreen;
import tel.eden.mod.gui.CommandKeybindScreen;
import tel.eden.mod.gui.PartyCreateScreen;
import tel.eden.mod.gui.PartyListScreen;
import tel.eden.mod.gui.PartyManageScreen;
import tel.eden.mod.item.DecodedItem;
import tel.eden.mod.item.ItemCardRenderer;
import tel.eden.mod.item.ItemStringDetector;
import tel.eden.mod.item.WynntilsItemDecoder;
import tel.eden.mod.net.BridgeWebSocketClient;
import tel.eden.mod.net.PartyInfo;
import tel.eden.mod.net.PendingEntry;
import tel.eden.mod.net.WarCountEntry;
import tel.eden.mod.reward.GuildRewards;
import tel.eden.mod.update.UpdateChecker;
import tel.eden.mod.update.UpdateInfo;
import tel.eden.mod.update.UpdateInstaller;
import tel.eden.mod.util.Wynncraft;
import tel.eden.mod.war.AttackTimerMenu;
import tel.eden.mod.war.BeaconManager;
import tel.eden.mod.war.TerritoryData;
import tel.eden.mod.war.TerritoryMenuKeybind;
import tel.eden.mod.war.TerritoryOutlineRenderer;
import tel.eden.mod.war.WarDPS;
import tel.eden.mod.war.WarHud;
import tel.eden.mod.war.WarTracker;
import tel.eden.mod.util.WynntilsChatBridge;
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
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint: wires server gating, the chat-capture relay, the WebSocket
 * client, the keybind, and the re-auth timer together.
 */
public final class EdenModClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("edenmod");
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
	// Deterministic per-occurrence index so rapid identical bank events stay distinct.
	// Short window: only the current burst counts, so stale earlier deposits can't
	// make different clients assign divergent seqs (which duplicated Discord posts).
	private final OccurrenceSequencer bankSeq = new OccurrenceSequencer(10_000L);
	// Rewards are gifted in long runs (one line per item, ~600ms apart), so use a
	// wider window than bank events to keep a whole run's seqs monotonic for counting.
	private final OccurrenceSequencer rewardSeq = new OccurrenceSequencer(60_000L);
	// Guild-chat occurrence index: lets the backend distinguish a line legitimately
	// repeated (e.g. "d" then "d") from the same line relayed by multiple online
	// members' clients, which is clock-/arrival-skew independent (order, not time).
	private final OccurrenceSequencer chatSeq = new OccurrenceSequencer(15_000L);
	private final GuildRewards guildRewards = new GuildRewards();
	private final List<TrackedCommandKeybind> trackedCommandKeybinds = new ArrayList<>();
	private KeyMapping openConfigKey;
	private KeyMapping createPartyKey;
	private KeyMapping openEmotePickerKey;
	private BridgeWebSocketClient socket;

	public BridgeWebSocketClient socket() {
		return socket;
	}
	private String socketJwt;
	private boolean onWynncraft;
	// Mutated on the inbound-message path and read from GUI screens on the render
	// thread; copy-on-write keeps those cross-thread reads from racing the writes.
	private final java.util.List<PartyInfo> knownParties = new java.util.concurrent.CopyOnWriteArrayList<>();
	// Latest aspects-owed list from the backend, read by AspectsPayoutScreen on the
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
	// GitHub update check: run once per game session; the prompt offers a one-click
	// download (applied on game close) and a link to the release page.
	private final UpdateChecker updateChecker = new UpdateChecker();
	private final UpdateInstaller updateInstaller = new UpdateInstaller();
	private volatile UpdateInfo pendingUpdate;
	private volatile boolean updateChecked;
	private volatile boolean updateStaged;
	private volatile boolean pendingUpdateNotification;
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
	private long lastWeeklyWarsRefresh;
	// Set on game join, sent once the bridge connects, cleared on send/disconnect, so
	// a "logged in" notice fires per game session (not on every WS reconnect).
	private volatile boolean loginPending;
	// True when the player is confirmed to be in a Wynncraft game world (or class
	// selection screen). False while in queue, on the title screen, or AFK-queued.
	// Set by welcome-message detection and a periodic tab-list check; cleared on disconnect.
	private volatile boolean inGameWorld = false;
	// Tick counters for the periodic tab check and presence heartbeat.
	private int tabCheckTick = 0;
	private int presenceTick = 0;
	private int storageCheckTick = 0;
	private static final int TAB_CHECK_INTERVAL_TICKS = 60; // 3 s at 20 tps
	private static final int PRESENCE_INTERVAL_TICKS = 600; // 30 s at 20 tps
	private static final int STORAGE_CHECK_INTERVAL_TICKS = 10; // 0.5 s at 20 tps
	// Last {aspects, tomes, emeralds} relayed, so an unchanged read isn't re-sent.
	private volatile long[] lastStorageSent = null;
	// Silent JWT renewal: trigger this many seconds before expiry.
	private static final long RENEWAL_THRESHOLD_SECS = 30L * 24 * 60 * 60; // 30 days

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

	public boolean shouldOpenEmotePickerOnChatOpen() {
		Minecraft mc = Minecraft.getInstance();
		if (isOpenEmotePickerMouseBound() || mc.options == null) {
			return false;
		}
		return openEmotePickerKey.saveString().equals(mc.options.keyChat.saveString()) && openEmotePickerKey.isDown();
	}

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
		return java.util.List.copyOf(knownParties);
	}

	/** Members owed aspects, highest first, as of the last backend reply. */
	public java.util.List<PendingEntry> knownPendingAspects() {
		return knownPendingAspects;
	}

	/** The error from the last aspects-pending reply, or {@code null} if it succeeded. */
	public String pendingAspectsError() {
		return pendingAspectsError;
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

	/** The guild reward helper (rank lookups + gifting). */
	public GuildRewards guildRewards() {
		return guildRewards;
	}

	@Override
	public void onInitializeClient() {
		instance = this;
		config = BridgeConfig.load();
		refreshCommandKeybinds();

		// Report the exact count of each completed /eden gift run to the backend, so the
		// reward log shows the real total instead of a count inferred from chat.
		guildRewards.setReporter((receiver, type, count) -> {
			BridgeWebSocketClient current = socket;
			if (current != null) {
				current.sendGuildRewardSummary(playerName(), receiver, type.unitReward(), count);
			}
		});
		// Relay the guild's live reward storage to the backend counter: the exact value
		// after a gift run, and (in onClientTick) whenever a Chief opens the menu.
		guildRewards.setStorageReporter(this::relayStorage);

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
			loginPending = true;
			// Capture whether this server is Wynncraft before evaluateGating() may call
			// disconnect(), which clears onWynncraft even when the player is still on
			// Wynncraft (e.g. expired JWT). remindIfUnlinked needs the real server state.
			boolean joinedWynncraft = client.getSingleplayerServer() == null && client.getCurrentServer() != null && Wynncraft.isWynncraft(client.getCurrentServer().ip);
			evaluateGating(client);
			remindIfUnlinked(joinedWynncraft);
			checkForUpdateOnce();
			checkTokenRenewal();
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
			})).then(buildPartyCommand()).then(buildAnnihilationCommand()).then(buildCommandCommand()).then(ClientCommandManager.literal("update").executes(ctx -> {
				updatePrompt(ctx.getSource());
				return 1;
			}).then(ClientCommandManager.literal("download").executes(ctx -> {
				updateDownload(ctx.getSource());
				return 1;
			}))).then(buildGiftCommand()).then(ClientCommandManager.literal("dump").then(ClientCommandManager.argument("member", StringArgumentType.word()).suggests(this::suggestMembers).executes(ctx -> dumpEmeralds(ctx.getSource(), StringArgumentType.getString(ctx, "member"))))).then(ClientCommandManager.literal("wars").executes(ctx -> {
				requestWarCounts(ctx.getSource(), 7);
				return 1;
			}).then(ClientCommandManager.argument("days", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 365)).executes(ctx -> {
				requestWarCounts(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "days"));
				return 1;
			}))).then(ClientCommandManager.literal("congratulate").then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(ctx -> {
				congratulate(ctx.getSource(), StringArgumentType.getString(ctx, "name"));
				return 1;
			}))).then(ClientCommandManager.literal("wartest").executes(ctx -> {
				for (String line : AttackTimerMenu.debugSidebarLines()) {
					ctx.getSource().sendFeedback(Component.literal("[wartest] " + line).withStyle(ChatFormatting.AQUA));
				}
				return 1;
			})).then(ClientCommandManager.literal("help").executes(ctx -> {
				showHelp(ctx.getSource());
				return 1;
			})));
		});

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
			if (client.screen instanceof ChatScreen && isOpenEmotePickerMouseBound()) {
				continue;
			}
			openCenteredEmotePicker();
		}
		pollCommandKeybinds(client);
		if (pendingUpdateNotification && client.player != null) {
			pendingUpdateNotification = false;
			UpdateInfo update = pendingUpdate;
			if (update != null) {
				display(() -> DiscordChatFormatter.updateAvailable(update.version(), update.pageUrl()));
			}
		}
		String connCode = pendingConnectionCode;
		if (connCode != null && client.player != null) {
			pendingConnectionCode = null;
			switch (connCode) {
				case "version_rejected" -> display(() -> Component.literal("[EdenMod] This mod version is no longer accepted by the bridge — use /eden update to upgrade.").withStyle(ChatFormatting.RED));
				case "not_member" -> display(() -> Component.literal("[EdenMod] Only players in the Eden guild may use this mod.").withStyle(ChatFormatting.RED));
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
		if (onWynncraft && config.enabled && config.hasValidJwt()) {
			connect();
		} else {
			disconnect();
		}
	}

	private synchronized void connect() {
		// Rebuild the socket when the backend URL or JWT changes (e.g. after the
		// tunnel URL changes and the user re-links); otherwise a stale client can
		// get stuck retrying the old URL forever.
		if (socket != null) {
			if (config.jwt.equals(socketJwt)) {
				return;
			}
			socket.close();
			socket = null;
		}
		try {
			socket = BridgeWebSocketClient.create(BridgeConfig.DEFAULT_BACKEND_URL, config.jwt, MOD_VERSION, new BridgeWebSocketClient.MessageSink() {
				@Override
				public void onDiscordMessage(String author, String content, String replyTo, String replyExcerpt, String color) {
					displayColored(color, () -> DiscordChatFormatter.format(author, content, replyTo, replyExcerpt));
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
					// Rendered by AspectsPayoutScreen rather than chat; sort here so every
					// reader sees the biggest debts first.
					java.util.List<PendingEntry> sorted = new ArrayList<>(entries);
					sorted.sort(java.util.Comparator.comparingInt(PendingEntry::aspects).reversed());
					// Generation last: it is the reader's signal that the other two
					// fields are the ones belonging to this reply.
					knownPendingAspects = java.util.List.copyOf(sorted);
					pendingAspectsError = (error == null || error.isEmpty()) ? null : error;
					pendingAspectsGeneration.incrementAndGet();
				}

				@Override
				public void onPartyUpdate(String event, String actor, PartyInfo party, String color) {
					knownParties.removeIf(p -> p.id() == party.id());
					if (!event.equals("closed")) {
						knownParties.add(party);
					}
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
					knownParties.clear();
					knownParties.addAll(parties);
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
				public void onConnectionRejected(String code) {
					pendingConnectionCode = code;
					Minecraft.getInstance().execute(() -> socket = null);
				}

				@Override
				public void onPillMessage(String label, String content, String colorHex) {
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
					display(() -> DiscordChatFormatter.pill(label, content, finalColorRgb));
				}

				@Override
				public void onGameFeedback(String message, String color) {
					displayColored(color, () -> DiscordChatFormatter.systemLine(message, net.minecraft.ChatFormatting.GOLD));
				}
			}, this::onBridgeConnected);
			socketJwt = config.jwt;
			socket.start();
		} catch (IllegalArgumentException e) {
			LOGGER.warn("Not connecting: {}", e.getMessage());
			socket = null;
		}
	}

	/** A clickable "[B]" button (or whatever key is bound) that opens the config screen. */
	private Component openConfigButton() {
		String keyName = openConfigKey.getTranslatedKeyMessage().getString();
		Style style = Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/eden config")).withHoverEvent(new HoverEvent.ShowText(Component.literal("Open EdenMod settings")));
		return Component.literal("[" + keyName + "]").setStyle(style);
	}

	/** On login, nudge players who haven't linked yet to do so. */
	private void remindIfUnlinked(boolean isOnWynncraft) {
		if (!isOnWynncraft || !config.enabled)
			return;
		Component btn = openConfigButton();
		if (config.jwt.isEmpty()) {
			display(() -> DiscordChatFormatter.linkReminder(btn));
		} else if (!config.hasValidJwt()) {
			display(() -> DiscordChatFormatter.tokenExpired(btn));
		}
	}

	/**
	 * Silently renew the JWT when it is within 30 days of expiry, so players
	 * never need to re-link as long as they play at least once per token TTL.
	 */
	private void checkTokenRenewal() {
		if (config.jwt.isEmpty() || config.jwtExpiresAt == 0)
			return;
		long remaining = config.jwtExpiresAt - System.currentTimeMillis() / 1000L;
		// Already expired → remindIfUnlinked() shows the "token expired" message; skip renewal.
		if (remaining <= 0 || remaining > RENEWAL_THRESHOLD_SECS)
			return;
		LOGGER.info("JWT expires in {}s, attempting silent renewal", remaining);
		new AuthFlow().refresh(BridgeConfig.DEFAULT_BACKEND_URL, config.jwt, new AuthFlow.Callback() {
			@Override
			public void onSuccess(String jwt, long expiresAt) {
				config.jwt = jwt;
				config.jwtExpiresAt = expiresAt;
				config.save();
				LOGGER.info("JWT silently renewed");
			}

			@Override
			public void onError(String messageText) {
				LOGGER.warn("Silent JWT renewal failed: {}", messageText);
			}
		});
	}

	/** Check GitHub for a newer release once per session; prompt in chat if found. */
	private void checkForUpdateOnce() {
		if (updateChecked) {
			return;
		}
		updateChecked = true;
		Thread thread = new Thread(() -> updateChecker.check().ifPresent(update -> {
			pendingUpdate = update;
			pendingUpdateNotification = true;
		}), "edenmod-update-check");
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
				case FAILED -> display(() -> DiscordChatFormatter.systemLine("Update download failed. Use the link to download it manually.", ChatFormatting.RED));
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
		mc.execute(() -> mc.setScreen(new tel.eden.mod.gui.AspectsPayoutScreen(mc.screen, this)));
	}

	/** Build {@code /eden gift <member> <aspect|emerald|tome> <amount>} (Chiefs only). */
	private LiteralArgumentBuilder<FabricClientCommandSource> buildGiftCommand() {
		return ClientCommandManager.literal("gift").then(ClientCommandManager.argument("member", StringArgumentType.word()).suggests(this::suggestMembers).then(giftTypeArg("aspect", GuildRewards.RewardType.ASPECT)).then(giftTypeArg("emerald", GuildRewards.RewardType.EMERALD)).then(giftTypeArg("tome", GuildRewards.RewardType.TOME)));
	}

	private LiteralArgumentBuilder<FabricClientCommandSource> giftTypeArg(String literal, GuildRewards.RewardType type) {
		return ClientCommandManager.literal(literal).then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(1)).executes(ctx -> giftReward(ctx.getSource(), StringArgumentType.getString(ctx, "member"), type, IntegerArgumentType.getInteger(ctx, "amount"))));
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
		guildRewards.gift(member, type, amount);
		return 1;
	}

	private int dumpEmeralds(FabricClientCommandSource source, String member) {
		if (!ensureRewardsReady(source)) {
			return 0;
		}
		guildRewards.dumpEmeralds(member);
		return 1;
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
					.then(ClientCommandManager.literal("makeingame").then(ClientCommandManager.argument("members", StringArgumentType.greedyString()).executes(ctx -> makeInGameParty(ctx.getSource(), StringArgumentType.getString(ctx, "members"))))).then(ClientCommandManager.literal("note").executes(ctx -> openPartyManageGui(ctx.getSource())).then(ClientCommandManager.argument("text", StringArgumentType.greedyString()).executes(ctx -> partyManage(ctx.getSource(), "note", StringArgumentType.getString(ctx, "text"), 0, "")))).then(ClientCommandManager.literal("filled").executes(ctx -> openPartyManageGui(ctx.getSource())).then(ClientCommandManager.argument("slots", IntegerArgumentType.integer(0, 8)).executes(ctx -> partyManage(ctx.getSource(), "filled", "", IntegerArgumentType.getInteger(ctx, "slots"), "")))).then(ClientCommandManager.literal("add").executes(ctx -> openPartyManageGui(ctx.getSource())).then(ClientCommandManager.argument("player", StringArgumentType.word()).suggests(this::suggestMembers).executes(ctx -> partyManage(ctx.getSource(), "add", "", 0, StringArgumentType.getString(ctx, "player"))))).then(ClientCommandManager.literal("remove").executes(ctx -> openPartyManageGui(ctx.getSource())).then(ClientCommandManager.argument("player", StringArgumentType.word()).suggests(this::suggestMembers).executes(ctx -> partyManage(ctx.getSource(), "remove", "", 0, StringArgumentType.getString(ctx, "player")))));
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
			sendServerCommandLater("party " + ign, delay);
			delay += PARTY_COMMAND_DELAY_MS;
		}
	}

	/** Send one server command after {@code delayMs}, on the client thread. */
	private void sendServerCommandLater(String command, long delayMs) {
		partyCommandExecutor.schedule(() -> Minecraft.getInstance().execute(() -> {
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

	private record HelpEntry(String command, String description) {
	}

	private static final List<HelpEntry> HELP_ENTRIES = List.of(new HelpEntry("/eden config", "open the config screen"), new HelpEntry("/eden online", "who's connected to the bridge"), new HelpEntry("/eden cf", "flip a coin"), new HelpEntry("/eden diceroll", "roll a die"), new HelpEntry("/eden wars [days]", "guild war counts (same as Discord)"), new HelpEntry("/eden emojis", "open the chat emote picker"), new HelpEntry("/eden party", "list open parties (click to join)"), new HelpEntry("/eden party create <raid> [note]", "open a raid party"), new HelpEntry("/eden party join <id>", "join a party"), new HelpEntry("/eden party leave [id]", "leave your party"), new HelpEntry("/eden anni <size> [note]", "open an Annihilation party (2-10)"), new HelpEntry("/eden command alias", "open the command alias editor"), new HelpEntry("/eden command keybind", "open the command keybind editor"), new HelpEntry("/eden update", "check for a pending update"), new HelpEntry("/eden update download", "download the update now (applies on exit)"), new HelpEntry("/eden aspects pending", "members' pending aspects — Chiefs only"), new HelpEntry("/eden gift <member> <aspect|emerald|tome> <amount>", "gift guild rewards — Chiefs only"), new HelpEntry("/eden dump <member>", "gift all guild-bank emeralds to a member — Chiefs only"), new HelpEntry("/eden help", "this help screen"));

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

	/** On a fresh bridge connection, announce this session's login exactly once. */
	private void onBridgeConnected() {
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
		onWynncraft = false;
		loginPending = false;
		inGameWorld = false;
		tabCheckTick = 0;
		presenceTick = 0;
		pendingConnectionCode = null;
		if (socket != null) {
			socket.close();
			socket = null;
		}
	}

	/** Called from the chat-capture mixin for every system-chat component. */
	public void handleSystemChat(Component message) {
		// A real chat line breaks any in-progress Discord emblem block, so the next
		// relayed Discord message starts with a fresh shield (like guild chat).
		DiscordChatFormatter.onServerChatLine();
		if (onWynncraft) {
			// War chat cues (start countdown → attendance capture; end → HUD summary)
			// work even while the bridge socket is down. handleSystemChat runs on the netty
			// network thread (see ClientPacketListenerMixin), but the war subsystem's state
			// is otherwise only touched on the client thread (tick + render), so marshal
			// these onto it to avoid racing that state.
			String warLine = message.getString();
			Minecraft.getInstance().execute(() -> {
				WarTracker.onChat(warLine);
				WarDPS.onChat(warLine);
			});
		}
		BridgeWebSocketClient current = socket;
		if (!onWynncraft || current == null) {
			return;
		}
		// Entering a Wynncraft game world always sends this greeting. Use it for
		// immediate active-state detection, faster than the next tab-list check.
		if (message.getString().contains("Welcome to Wynncraft!")) {
			setInGameWorld(true);
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
			current.sendGuildChat(line.username(), line.nickname(), line.message(), seq);
		}
	}

	/** Send a parsed raid completion (local-dedup guarded), logging the outcome. */
	private void relayRaid(BridgeWebSocketClient current, RaidCompletion line, String how) {
		boolean sent = raidRelay.shouldSend(new CapturedMessage(line.raidName(), null, String.join(",", line.party())));
		LOGGER.info("Raid {}: {} party={} aspects={} emeralds={} -> {}", how, line.raidName(), line.party(), line.aspects(), line.emeralds(), sent ? "sent" : "deduped");
		if (sent) {
			current.sendRaidCompletion(line.party(), line.raidName(), line.aspects(), line.emeralds(), line.guildExp());
		}
	}

	/**
	 * If this guild-chat line carries a shared Wynncraft item string, decode it (via
	 * Wynntils), render a card, and relay it to the bridge as the sender — off-thread.
	 * No-op unless enabled, Wynntils is installed, and a sender can be resolved.
	 */
	private void maybeRelayItemCard(BridgeWebSocketClient current, Component message) {
		if (!WynntilsItemDecoder.isAvailable()) {
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
			Optional<DecodedItem> decoded = WynntilsItemDecoder.decode(item.itemString(), item.craftedName());
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

	/**
	 * Build and show a client-side chat line on the game thread. The component is
	 * built inside {@code execute} because the formatter touches the font/text
	 * splitter, which must run on the client thread (not the WebSocket thread).
	 */
	private void display(java.util.function.Supplier<Component> builder) {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			if (client.player != null) {
				Component component = builder.get();
				if (!WynntilsChatBridge.sendToTab(component)) {
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
		Integer rgb = parseHexColor(colorHex);
		if (rgb == null) {
			display(builder);
			return;
		}
		int color = rgb;
		display(() -> recolor(builder.get(), color));
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

	/** Start the browser link flow; persists the JWT on success and (re)connects. */
	public void startLinkFlow(Runnable onDone) {
		new AuthFlow().begin(BridgeConfig.DEFAULT_BACKEND_URL, new AuthFlow.Callback() {
			@Override
			public void onSuccess(String jwt, long expiresAt) {
				config.jwt = jwt;
				config.jwtExpiresAt = expiresAt;
				config.save();
				Minecraft.getInstance().execute(() -> {
					String name = playerName();
					if (name != null && !name.isEmpty()) {
						config.linkedUsername = name;
						config.save();
					}
					evaluateGating(Minecraft.getInstance());
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
}
