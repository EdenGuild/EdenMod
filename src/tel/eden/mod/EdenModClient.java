package tel.eden.mod;

import tel.eden.mod.auth.AuthFlow;
import tel.eden.mod.chat.AnnihilationParser;
import tel.eden.mod.chat.BankEvent;
import tel.eden.mod.chat.BankEventParser;
import tel.eden.mod.chat.CapturedMessage;
import tel.eden.mod.chat.ChatRelay;
import tel.eden.mod.chat.DiscordChatFormatter;
import tel.eden.mod.chat.GuildAnnounceParser;
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
import tel.eden.mod.item.DecodedItem;
import tel.eden.mod.item.ItemCardRenderer;
import tel.eden.mod.item.ItemStringDetector;
import tel.eden.mod.item.WynntilsItemDecoder;
import tel.eden.mod.net.BridgeWebSocketClient;
import tel.eden.mod.net.PartyInfo;
import tel.eden.mod.net.PendingEntry;
import tel.eden.mod.reward.GuildRewards;
import tel.eden.mod.util.Wynncraft;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint: wires server gating, the chat-capture relay, the WebSocket
 * client, the keybind, and the re-auth timer together.
 */
public final class EdenModClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("edenmod");

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
    // Decoding (Wynntils reflection) + image rendering run off the game thread.
    private final ExecutorService itemCardExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "edenmod-item-card");
        t.setDaemon(true);
        return t;
    });
    // Spaces out the "party create" + each "party <ign>" invite so Wynncraft has time
    // to register the new party before the invites land.
    private static final long PARTY_COMMAND_DELAY_MS = 400L;
    private final ScheduledExecutorService partyCommandExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
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
    private KeyMapping openConfigKey;
    private BridgeWebSocketClient socket;
    private String socketUrl;
    private String socketJwt;
    private boolean onWynncraft;
    // Set on game join, sent once the bridge connects, cleared on send/disconnect, so
    // a "logged in" notice fires per game session (not on every WS reconnect).
    private volatile boolean loginPending;

    /** The live mod instance (used by the chat-capture mixin). */
    public static EdenModClient instance() {
        return instance;
    }

    public BridgeConfig config() {
        return config;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        config = BridgeConfig.load();

        // Report the exact count of each completed /gift run to the backend, so the
        // reward log shows the real total instead of a count inferred from chat.
        guildRewards.setReporter((receiver, type, count) -> {
            BridgeWebSocketClient current = socket;
            if (current != null) {
                current.sendGuildRewardSummary(playerName(), receiver, type.unitReward(), count);
            }
        });

        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.edenmod.open_config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                KeyMapping.Category.MISC));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            loginPending = true;
            evaluateGating(client);
            remindIfUnlinked();
            if (onWynncraft) {
                guildRewards.ensureFresh(playerName());
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> disconnect());
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            dispatcher.register(ClientCommandManager.literal("eden")
                    .then(ClientCommandManager.literal("online").executes(ctx -> {
                        requestOnline(ctx.getSource());
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("aspects")
                            .then(ClientCommandManager.literal("pending").executes(ctx -> {
                                requestAspectsPending(ctx.getSource());
                                return 1;
                            })))
                    .then(buildPartyCommand())
                    .then(buildAnnihilationCommand())
                    .then(ClientCommandManager.literal("help").executes(ctx -> {
                        showHelp(ctx.getSource());
                        return 1;
                    })));
            dispatcher.register(buildGiftCommand());
            dispatcher.register(ClientCommandManager.literal("dumpemeralds")
                    .then(ClientCommandManager.argument("member", StringArgumentType.word())
                            .suggests(this::suggestMembers)
                            .executes(ctx -> dumpEmeralds(
                                    ctx.getSource(), StringArgumentType.getString(ctx, "member")))));
        });

        LOGGER.info("EdenMod initialised");
    }

    private void onClientTick(Minecraft client) {
        while (openConfigKey.consumeClick()) {
            client.setScreen(new BridgeConfigScreen(client.screen, this));
        }
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
            if (config.backendBaseUrl.equals(socketUrl) && config.jwt.equals(socketJwt)) {
                return;
            }
            socket.close();
            socket = null;
        }
        try {
            socket = BridgeWebSocketClient.create(
                    config.backendBaseUrl,
                    config.jwt,
                    new BridgeWebSocketClient.MessageSink() {
                        @Override
                        public void onDiscordMessage(
                                String author, String content, String replyTo, String replyExcerpt) {
                            display(() ->
                                    DiscordChatFormatter.format(author, content, replyTo, replyExcerpt));
                        }

                        @Override
                        public void onLoginNotice(String username) {
                            display(() -> DiscordChatFormatter.loginNotice(username));
                        }

                        @Override
                        public void onLogoutNotice(String username) {
                            display(() -> DiscordChatFormatter.logoutNotice(username));
                        }

                        @Override
                        public void onOnlineList(java.util.List<String> users) {
                            display(() -> DiscordChatFormatter.onlineList(users));
                        }

                        @Override
                        public void onAspectsPending(
                                java.util.List<PendingEntry> entries, String error) {
                            display(() -> DiscordChatFormatter.aspectsPending(entries, error));
                        }

                        @Override
                        public void onPartyUpdate(String event, String actor, PartyInfo party) {
                            // Auto-announce party activity only when the player has the
                            // (default-on) party feed enabled.
                            if (!config.partyAnnounce) {
                                return;
                            }
                            switch (event) {
                                case "open" -> display(() -> PartyFormatter.partyOpen(party));
                                case "full" -> display(() -> PartyFormatter.partyFull(party));
                                case "join" -> display(() -> PartyFormatter.partyJoin(actor, party));
                                case "leave" ->
                                    display(() -> PartyFormatter.partyLeave(actor, party));
                                default -> { /* closed/other: nothing to announce */ }
                            }
                        }

                        @Override
                        public void onPartyList(java.util.List<PartyInfo> parties) {
                            display(() -> PartyFormatter.listing(parties));
                        }

                        @Override
                        public void onPartyFeedback(String message) {
                            display(() -> PartyFormatter.feedback(message));
                        }
                    },
                    this::onBridgeConnected);
            socketUrl = config.backendBaseUrl;
            socketJwt = config.jwt;
            socket.start();
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Not connecting: {}", e.getMessage());
            socket = null;
        }
    }

    /** On login, nudge players who haven't linked yet to do so. */
    private void remindIfUnlinked() {
        if (onWynncraft && config.enabled && !config.hasValidJwt()) {
            display(DiscordChatFormatter::linkReminder);
        }
    }

    private void requestOnline(FabricClientCommandSource source) {
        BridgeWebSocketClient current = socket;
        if (current == null) {
            source.sendFeedback(notConnected());
            return;
        }
        current.sendOnlineRequest();
    }

    private void requestAspectsPending(FabricClientCommandSource source) {
        BridgeWebSocketClient current = socket;
        if (current == null) {
            source.sendFeedback(notConnected());
            return;
        }
        current.sendAspectsPendingRequest();
    }

    /** Build {@code /gift <member> <aspect|emerald|tome> <amount>} (Chiefs only). */
    private LiteralArgumentBuilder<FabricClientCommandSource> buildGiftCommand() {
        return ClientCommandManager.literal("gift")
                .then(ClientCommandManager.argument("member", StringArgumentType.word())
                        .suggests(this::suggestMembers)
                        .then(giftTypeArg("aspect", GuildRewards.RewardType.ASPECT))
                        .then(giftTypeArg("emerald", GuildRewards.RewardType.EMERALD))
                        .then(giftTypeArg("tome", GuildRewards.RewardType.TOME)));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> giftTypeArg(
            String literal, GuildRewards.RewardType type) {
        return ClientCommandManager.literal(literal)
                .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> giftReward(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "member"),
                                type,
                                IntegerArgumentType.getInteger(ctx, "amount"))));
    }

    private CompletableFuture<Suggestions> suggestMembers(
            CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String name : guildRewards.memberNames()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    private int giftReward(
            FabricClientCommandSource source, String member, GuildRewards.RewardType type,
            int amount) {
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
            source.sendFeedback(Component.literal("Guild rewards are only available on Wynncraft.")
                    .withStyle(net.minecraft.ChatFormatting.RED));
            return false;
        }
        guildRewards.ensureFresh(playerName());
        if (guildRewards.memberNames().isEmpty()) {
            source.sendFeedback(Component.literal("Fetching guild data — try again in a moment.")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
            return false;
        }
        return true;
    }

    private static String playerName() {
        return Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getGameProfile().name()
                : null;
    }

    /** Build the {@code /eden party ...} subcommand tree (list/create/join/leave/announce). */
    private LiteralArgumentBuilder<FabricClientCommandSource> buildPartyCommand() {
        return ClientCommandManager.literal("party")
                .executes(ctx -> partyList(ctx.getSource()))
                .then(ClientCommandManager.literal("list")
                        .executes(ctx -> partyList(ctx.getSource())))
                .then(ClientCommandManager.literal("create")
                        .then(raidLiteral("notg", "Nest of the Grootslangs"))
                        .then(raidLiteral("nol", "Orphion's Nexus of Light"))
                        .then(raidLiteral("tcc", "The Canyon Colossus"))
                        .then(raidLiteral("tna", "The Nameless Anomaly"))
                        .then(raidLiteral("wtp", "The Wartorn Palace")))
                .then(ClientCommandManager.literal("join")
                        .then(ClientCommandManager.argument("id", IntegerArgumentType.integer())
                                .executes(ctx -> partyJoin(
                                        ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
                .then(ClientCommandManager.literal("leave")
                        .executes(ctx -> partyLeave(ctx.getSource(), null))
                        .then(ClientCommandManager.argument("id", IntegerArgumentType.integer())
                                .executes(ctx -> partyLeave(
                                        ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
                .then(ClientCommandManager.literal("announce")
                        .then(ClientCommandManager.literal("on")
                                .executes(ctx -> partyAnnounce(ctx.getSource(), true)))
                        .then(ClientCommandManager.literal("off")
                                .executes(ctx -> partyAnnounce(ctx.getSource(), false))))
                // Driven by the "[Create party]" prompt shown when a party fills: runs
                // /party create then invites each listed member in-game.
                .then(ClientCommandManager.literal("makeingame")
                        .then(ClientCommandManager.argument("members", StringArgumentType.greedyString())
                                .executes(ctx -> makeInGameParty(
                                        ctx.getSource(), StringArgumentType.getString(ctx, "members")))));
    }

    /** Run {@code /party create} then {@code /party <ign>} for each member except yourself. */
    private int makeInGameParty(FabricClientCommandSource source, String membersArg) {
        String self = playerName();
        List<String> invites = new ArrayList<>();
        for (String name : membersArg.trim().split("\\s+")) {
            if (!name.isEmpty() && (self == null || !name.equalsIgnoreCase(self))) {
                invites.add(name);
            }
        }
        sendServerCommandLater("party create", 0L);
        long delay = PARTY_COMMAND_DELAY_MS;
        for (String ign : invites) {
            sendServerCommandLater("party " + ign, delay);
            delay += PARTY_COMMAND_DELAY_MS;
        }
        source.sendFeedback(PartyFormatter.feedback(
                "Creating your in-game party and inviting " + invites.size() + " player(s)..."));
        return 1;
    }

    /** Send one server command after {@code delayMs}, on the client thread. */
    private void sendServerCommandLater(String command, long delayMs) {
        partyCommandExecutor.schedule(
                () -> Minecraft.getInstance().execute(() -> {
                    var connection = Minecraft.getInstance().getConnection();
                    if (connection != null) {
                        connection.sendCommand(command);
                    }
                }),
                delayMs,
                TimeUnit.MILLISECONDS);
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> raidLiteral(String alias, String raid) {
        return ClientCommandManager.literal(alias)
                .executes(ctx -> partyOpen(ctx.getSource(), raid, 4, ""))
                .then(ClientCommandManager.argument("note", StringArgumentType.greedyString())
                        .executes(ctx -> partyOpen(
                                ctx.getSource(), raid, 4, StringArgumentType.getString(ctx, "note"))));
    }

    /** Build {@code /eden anni <size> [note]} — open an Annihilation party of 2-10. */
    private LiteralArgumentBuilder<FabricClientCommandSource> buildAnnihilationCommand() {
        return ClientCommandManager.literal("anni")
                .then(ClientCommandManager.argument("size", IntegerArgumentType.integer(2, 10))
                        .executes(ctx -> partyOpen(
                                ctx.getSource(), "Annihilation",
                                IntegerArgumentType.getInteger(ctx, "size"), ""))
                        .then(ClientCommandManager.argument("note", StringArgumentType.greedyString())
                                .executes(ctx -> partyOpen(
                                        ctx.getSource(), "Annihilation",
                                        IntegerArgumentType.getInteger(ctx, "size"),
                                        StringArgumentType.getString(ctx, "note")))));
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

    private int partyOpen(FabricClientCommandSource source, String raid, int maxSize, String note) {
        BridgeWebSocketClient current = socket;
        if (current == null) {
            source.sendFeedback(notConnected());
            return 0;
        }
        current.sendPartyOpen(raid, maxSize, note);
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

    private int partyAnnounce(FabricClientCommandSource source, boolean on) {
        config.partyAnnounce = on;
        config.save();
        source.sendFeedback(Component.literal("Party announcements " + (on ? "enabled." : "disabled."))
                .withStyle(net.minecraft.ChatFormatting.GREEN));
        return 1;
    }

    private static Component notConnected() {
        return Component.literal("Not connected to the Eden bridge.")
                .withStyle(net.minecraft.ChatFormatting.RED);
    }

    /** Print the in-game command list client-side. */
    private void showHelp(FabricClientCommandSource source) {
        MutableComponent help = Component.literal("EdenMod — in-game commands:")
                .withStyle(net.minecraft.ChatFormatting.GREEN);
        help.append(helpLine("/eden online", "who's connected to the bridge"));
        help.append(helpLine("/eden party", "list open parties (click to join)"));
        help.append(helpLine("/eden party create <raid> [note]", "open a raid party"));
        help.append(helpLine("/eden party join <id>", "join a party"));
        help.append(helpLine("/eden party leave [id]", "leave your party"));
        help.append(helpLine("/eden party announce on|off", "toggle the party feed"));
        help.append(helpLine("/eden anni <size> [note]", "open an Annihilation party (2-10)"));
        help.append(helpLine("/eden help", "this help"));
        help.append(Component.literal("\nPress B to open the bridge config screen.")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        source.sendFeedback(help);
    }

    private static MutableComponent helpLine(String command, String description) {
        return Component.empty()
                .append(Component.literal("\n  " + command)
                        .withStyle(net.minecraft.ChatFormatting.AQUA))
                .append(Component.literal(" — " + description)
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    /** On a fresh bridge connection, announce this session's login exactly once. */
    private void onBridgeConnected() {
        if (loginPending) {
            loginPending = false;
            BridgeWebSocketClient current = socket;
            if (current != null) {
                current.sendLogin();
            }
        }
    }

    private synchronized void disconnect() {
        onWynncraft = false;
        loginPending = false;
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
        BridgeWebSocketClient current = socket;
        if (!onWynncraft || current == null) {
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
            String signature = line.action() + "|" + line.player() + "|" + line.quantity()
                    + "|" + line.item() + "|" + line.accessTier();
            current.sendBankEvent(
                    line.action(),
                    line.player(),
                    line.quantity(),
                    line.item(),
                    line.charges(),
                    line.accessTier(),
                    bankSeq.next(signature));
            return;
        }
        // Guild rank changes (promotions/demotions) — announcements, not chat.
        Optional<RankChange> rank = RankChangeParser.parse(message);
        if (rank.isPresent()) {
            RankChange line = rank.get();
            if (rankRelay.shouldSend(new CapturedMessage(
                    line.target(), line.newRank(), line.oldRank() + ">" + line.newRank()))) {
                current.sendRankChange(
                        line.target(), line.oldRank(), line.newRank(), line.setter());
            }
            return;
        }
        // Guild membership + alliance announcements (joins, invites, kicks, alliances).
        Optional<GuildEvent> guildEvent = GuildEventParser.parse(message);
        if (guildEvent.isPresent()) {
            GuildEvent line = guildEvent.get();
            if (guildEventRelay.shouldSend(
                    new CapturedMessage(line.subject(), line.actor(), line.kind()))) {
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
            current.sendGuildReward(
                    line.giver(), line.reward(), line.receiver(), rewardSeq.next(signature));
            return;
        }
        // Annihilation world-event warning (the only advance timing signal we get).
        java.util.OptionalInt anni = AnnihilationParser.parse(message);
        if (anni.isPresent()) {
            int seconds = anni.getAsInt();
            if (annihilationRelay.shouldSend(
                    new CapturedMessage("annihilation", null, String.valueOf(seconds)))) {
                current.sendAnnihilation(seconds);
            }
            return;
        }
        // Guild shouts, mirrored into the bridge chat channel.
        Optional<String> shout = ShoutParser.parse(message);
        if (shout.isPresent()) {
            String text = shout.get();
            if (shoutRelay.shouldSend(new CapturedMessage("shout", null, text))) {
                current.sendGuildAnnounce(text);
            }
            return;
        }
        // Level-up announcements, relayed to the bridge chat ONLY for Eden members
        // (the roster is fetched from the API on join and refreshed periodically).
        Optional<LevelUp> levelUp = LevelUpParser.parse(message);
        if (levelUp.isPresent()) {
            LevelUp lu = levelUp.get();
            guildRewards.ensureFresh(playerName());
            if (guildRewards.isMember(lu.name())
                    && levelUpRelay.shouldSend(
                            new CapturedMessage(lu.name(), null, lu.detail()))) {
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
        boolean sent = raidRelay.shouldSend(
                new CapturedMessage(line.raidName(), null, String.join(",", line.party())));
        LOGGER.info("Raid {}: {} party={} aspects={} emeralds={} -> {}", how,
                line.raidName(), line.party(), line.aspects(), line.emeralds(),
                sent ? "sent" : "deduped");
        if (sent) {
            current.sendRaidCompletion(line.party(), line.raidName(),
                    line.aspects(), line.emeralds(), line.guildExp());
        }
    }

    /**
     * If this guild-chat line carries a shared Wynncraft item string, decode it (via
     * Wynntils), render a card, and relay it to the bridge as the sender — off-thread.
     * No-op unless enabled, Wynntils is installed, and a sender can be resolved.
     */
    private void maybeRelayItemCard(BridgeWebSocketClient current, Component message) {
        if (!config.relayItemCards || !WynntilsItemDecoder.isAvailable()) {
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
        if (!itemCardRelay.shouldSend(
                new CapturedMessage(who.username(), null, item.itemString()))) {
            return;
        }
        itemCardExecutor.submit(() -> renderAndSendItemCard(current, who, item));
    }

    private void renderAndSendItemCard(
            BridgeWebSocketClient current, CapturedMessage who, ItemStringDetector.Detected item) {
        try {
            Optional<DecodedItem> decoded =
                    WynntilsItemDecoder.decode(item.itemString(), item.craftedName());
            if (decoded.isEmpty()) {
                return;
            }
            DecodedItem card = decoded.get();
            String image = Base64.getEncoder().encodeToString(ItemCardRenderer.render(card));
            String overall = card.hasOverall()
                    ? String.format(Locale.ROOT, "%.2f", card.overallPercent())
                    : "";
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
                client.player.displayClientMessage(builder.get(), false);
            }
        });
    }

    /** Start the browser link flow; persists the JWT on success and (re)connects. */
    public void startLinkFlow(Runnable onDone) {
        if (config.backendBaseUrl.isBlank()) {
            LOGGER.warn("Cannot link: backend URL is not set");
            onDone.run();
            return;
        }
        new AuthFlow().begin(config.backendBaseUrl, new AuthFlow.Callback() {
            @Override
            public void onSuccess(String jwt, long expiresAt) {
                config.jwt = jwt;
                config.jwtExpiresAt = expiresAt;
                config.save();
                Minecraft.getInstance().execute(() -> {
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
