package tel.eden.mod.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import tel.eden.mod.EdenLogger;
import tel.eden.mod.party.WynntilsPartyBridge;
import tel.eden.mod.war.ScoreboardCapture;

/**
 * Records who was physically present while a raid was running, so a completion can name the
 * players the chat announcement leaves out.
 *
 * <p>Wynncraft only names <em>your own guild's</em> members in a raid completion. A raid
 * cannot start without four players, so a completion naming fewer than four was run with
 * allies, and {@code 4 - named} is exactly how many are missing. Their names appear nowhere
 * in chat.
 *
 * <p>They are recovered in two steps. First the party itself, which is authoritative because
 * nobody can join or leave mid-raid: the Wynntils party captured during the raid and read
 * again at completion, or, without Wynntils, a hidden {@code /party list} (real account names,
 * never nicknames). Only if that is still short of the gap does proximity fill in, the same
 * way war attendance is collected: while a raid is running, the players loaded around the
 * client are sampled periodically and their appearances counted, and the most-seen remainder
 * goes after the party. A player entity carries a game profile, the real account under any
 * nickname, so proximity also yields account names.
 *
 * <p>Proximity is a fair proxy here in a way it would not be in the open world: a raid is an
 * instance containing only its own party. Counting appearances rather than taking a single
 * snapshot covers the two ways that breaks down — someone briefly out of render distance in
 * another room is still seen across the rest of the raid, and a passer-by caught on the way
 * in is not.
 *
 * <p>What proximity cannot rule out is that Wynncraft dresses some NPCs as players, and a
 * stationary one is present for every sample of the raid. So this class does not try to
 * pick the allies; it produces a ranked shortlist and the backend picks, because deciding
 * needs the guild member list and the player API. That also settles the NPCs: an NPC is not a
 * Wynncraft account, so the ally lookup finds nothing and drops it.
 */
public final class RaidPartyTracker {
	private static final EdenLogger LOGGER = EdenLogger.get();

	private RaidPartyTracker() {
	}

	/** A raid party is always four players; a completion naming fewer omitted allies. */
	public static final int RAID_PARTY_SIZE = 4;

	// The sidebar's raid objective block ("Raid:" heading the time left, challenges and loot
	// chests). Its presence is the whole of what we read from the sidebar — it says a raid is
	// running. No names are taken from it.
	private static final String RAID_HEADER = "Raid:";
	private static final Pattern IGN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
	private static final Pattern COLOR_CODE = Pattern.compile("[§&][0-9a-fk-orA-FK-OR]");
	private static final Pattern PARTY_MEMBERS_PATTERN = Pattern.compile("^Party members:\\s*(.+)", Pattern.CASE_INSENSITIVE);
	// Matched whole-line: while a reply is expected every system line passes through here
	// and a matched one is hidden and never relayed, so a loose word match ate guild chat.
	private static final Pattern NO_PARTY_PATTERN = Pattern.compile("^(?:You must be in a party to use this|You are not (?:currently )?in a party)\\.?$", Pattern.CASE_INSENSITIVE);
	// A raid instance is compact and the party has to stay together to clear it, so this only
	// has to reach across a room. Kept near the war radius for the same reason: wide enough to
	// hold the group, tight enough that a lobby crowd does not all land in the tally.
	private static final int SAMPLE_RADIUS = 48;
	// How often (in ticks) to sample. Raids run for minutes, so once every two seconds still
	// gives hundreds of samples while costing a quarter of what war capture does.
	private static final int SAMPLE_INTERVAL_TICKS = 40;
	// How often (in ticks) to re-read the sidebar. Reading it means formatting every row on
	// the board — team prefix and suffix into a component, then a string, then a colour
	// strip — so it is not something to do per frame. Once a second costs at most a second
	// of latency on noticing a raid start or end, against a threshold measured in tens of
	// seconds and a raid measured in minutes.
	private static final int SIDEBAR_CHECK_INTERVAL_TICKS = 20;
	// Floor for counting as present at all: twenty seconds within the radius. Anyone who
	// actually ran the raid clears this by orders of magnitude; someone walked past on the
	// way in does not.
	private static final int MIN_PRESENCE_MS = 20_000;
	private static final int MIN_SIGHTINGS = MIN_PRESENCE_MS / (SAMPLE_INTERVAL_TICKS * 50);
	// How many candidates to hand the backend. Deliberately more than a party can be short
	// by: Wynncraft dresses some NPCs as players, and a stationary one standing beside you
	// out-counts a real ally who spent the raid moving between rooms. Only the backend can
	// tell the difference — an NPC is not a Wynncraft account, so the ally lookup drops it —
	// so send the whole shortlist and let it do the cutting.
	private static final int MAX_CANDIDATES = 8;
	// The raid line vanishing for longer than this means a different raid, and the tally from
	// the abandoned one is dropped. Shorter breaks are room transitions. Kept tight because
	// raids can be re-queued quickly: a threshold longer than the time it takes to start the
	// next one would carry an abandoned raid's players into it.
	private static final long NEW_RAID_GAP_MS = 10_000L;
	// No expiry timer on the tally once the raid sidebar goes: the sidebar vanishes in the
	// reward room, a minute or more before the completion line (60-101s in logs), and a player
	// who skips the loot swaps server even earlier. Only this player's own completion reads
	// the tally, and the next raid's start clears it, so it cannot reach the wrong raid.

	// Sampling runs on the client thread; a raid completion arrives on the netty thread
	// (the chat mixin injects ahead of vanilla's ensureRunningOnSameThread), and that is
	// what calls extraPlayers and reset. Every field below is therefore touched from both,
	// and is guarded by LOCK — without it the completion can read a stale "no raid is
	// running", judge the tally stale, and report no allies at all.
	private static final int MAX_PARTY_LIST_ATTEMPTS = 2;
	private static final Object LOCK = new Object();
	private static final Map<String, Integer> sightings = new HashMap<>();
	private static final Set<String> partyMembers = new LinkedHashSet<>();
	private static boolean expectingPartyList;
	private static long partyListRequestedAt;
	private static int partyListAttempts;
	private static boolean raiding;
	private static long raidEndedAt;
	private static int tickCounter;
	// Wynntils' current party, copied once a second on the client thread so a completion on
	// the netty thread never reads Wynntils' own list while it is being updated.
	private static List<String> liveParty = List.of();

	/**
	 * Drive the tracker. Called every client tick, but both the things it does are throttled:
	 * reading the sidebar once a second and sampling once every two. Neither is free — the
	 * sidebar read formats every row on the board — and neither gains anything from running
	 * at frame rate, against a raid that lasts minutes and a presence threshold in seconds.
	 */
	public static void onTick() {
		int tick;
		boolean sampling;
		synchronized (LOCK) {
			tick = ++tickCounter;
			sampling = raiding;
		}
		// Read the sidebar outside the lock: it walks the whole scoreboard, and the only
		// other holder is a raid completion that should never wait on that.
		if (tick % SIDEBAR_CHECK_INTERVAL_TICKS == 0) {
			boolean inRaid = sidebarShowsRaid();
			List<String> live = wynntilsParty();
			synchronized (LOCK) {
				updateRaidState(inRaid);
				liveParty = live;
				sampling = raiding;
			}
			if (inRaid) {
				checkOrRequestParty(live);
			}
		}
		if (!sampling) {
			return;
		}
		if (tick % SAMPLE_INTERVAL_TICKS == 0) {
			sampleNearbyPlayers();
		}
	}

	/** Fold the latest sidebar reading into whether a raid is being tracked. Holds LOCK. */
	private static void updateRaidState(boolean inRaid) {
		if (inRaid && !raiding) {
			// A gap long enough to be a different raid drops the previous tally; a short one is
			// a room transition briefly clearing the sidebar, so the count carries on.
			if (raidEndedAt != 0 && System.currentTimeMillis() - raidEndedAt > NEW_RAID_GAP_MS) {
				sightings.clear();
				partyMembers.clear();
				expectingPartyList = false;
				partyListRequestedAt = 0L;
				partyListAttempts = 0;
			}
			raiding = true;
			raidEndedAt = 0;
		} else if (!inRaid && raiding) {
			raiding = false;
			raidEndedAt = System.currentTimeMillis();
			LOGGER.info("[RaidParty] raid sidebar vanished; holding tally until the completion, next raid or disconnect: {}", tallySummary());
		}
	}

	/** Wynntils' current party with names resolved to accounts, or empty without Wynntils. */
	private static List<String> wynntilsParty() {
		if (!WynntilsPartyBridge.isAvailable()) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		for (String m : WynntilsPartyBridge.getPartyMembers()) {
			String resolved = PlayerNameResolver.resolve(null, m);
			names.add(resolved != null && !resolved.isBlank() ? resolved : m);
		}
		return List.copyOf(names);
	}

	private static void checkOrRequestParty(List<String> wynntilsParty) {
		if (WynntilsPartyBridge.isAvailable()) {
			if (!wynntilsParty.isEmpty()) {
				synchronized (LOCK) {
					partyMembers.addAll(wynntilsParty);
				}
			}
		} else {
			boolean needRequest;
			long now = System.currentTimeMillis();
			synchronized (LOCK) {
				needRequest = partyMembers.isEmpty() && partyListAttempts < MAX_PARTY_LIST_ATTEMPTS && (!expectingPartyList || now - partyListRequestedAt > 5000L) && (now - partyListRequestedAt > 10_000L);
				if (needRequest) {
					partyListAttempts++;
					expectingPartyList = true;
					partyListRequestedAt = now;
				}
			}
			if (needRequest) {
				try {
					Minecraft mc = Minecraft.getInstance();
					if (mc.getConnection() != null) {
						mc.getConnection().sendCommand("party list");
					}
				} catch (Throwable ignored) {
				}
			}
		}
	}

	public static boolean isExpectingPartyList() {
		synchronized (LOCK) {
			if (!expectingPartyList) {
				return false;
			}
			if (partyListRequestedAt != 0L && System.currentTimeMillis() - partyListRequestedAt > 5000L) {
				expectingPartyList = false;
				return false;
			}
			return true;
		}
	}

	public static boolean handlePartyListChat(Component message) {
		if (message == null) {
			return false;
		}
		String plain = stripCodes(ChatText.normalize(message.getString())).trim();
		synchronized (LOCK) {
			if (!expectingPartyList) {
				return false;
			}
			if (NO_PARTY_PATTERN.matcher(plain).matches()) {
				expectingPartyList = false;
				return true;
			}
			Matcher matcher = PARTY_MEMBERS_PATTERN.matcher(plain);
			if (matcher.find()) {
				expectingPartyList = false;
				String tail = matcher.group(1).trim();
				String[] tokens = tail.split("\\s*,\\s*(?:and\\s+)?|\\s+and\\s+");
				for (String token : tokens) {
					String item = token.trim().replaceAll("[^a-zA-Z0-9_]", "");
					if (!item.isEmpty()) {
						String real = ChatText.resolveRealName(message, item);
						if (real == null) {
							real = PlayerNameResolver.resolve(message, item);
						}
						String finalName = (real != null && !real.isBlank()) ? real : item;
						partyMembers.add(finalName);
					}
				}
				return true;
			}
		}
		return false;
	}

	/**
	 * Candidates for the players the completion did not name: the party first, then the
	 * most-seen nearby players if the party is still short of the gap.
	 *
	 * <p>This is a shortlist, not an answer. The backend decides which of them are really
	 * allies and caps the party at {@link #RAID_PARTY_SIZE}, because that needs the guild
	 * member list and the player API, neither of which the client has.
	 *
	 * <p>The party is the roster captured during the raid plus the live Wynntils party now;
	 * the live read covers a client that relogged mid-raid or never saw the raid sidebar, and
	 * is still the raid's party because it cannot change until the raid is completed. Empty
	 * when the completion already names a full party. Comparison with the announced party is
	 * case-insensitive so a casing difference cannot duplicate a player.
	 */
	public static List<String> extraPlayers(List<String> announcedParty) {
		if (announcedParty.size() >= RAID_PARTY_SIZE) {
			return List.of();
		}
		int missing = RAID_PARTY_SIZE - announcedParty.size();
		Set<String> seen = announcedParty.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
		List<String> result = new ArrayList<>();
		synchronized (LOCK) {
			for (String member : partyMembers) {
				addUnseen(member, seen, result);
			}
			for (String member : liveParty) {
				addUnseen(member, seen, result);
			}
			if (result.size() < missing) {
				sightings.entrySet().stream().filter(entry -> entry.getValue() >= MIN_SIGHTINGS).sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).forEach(entry -> {
					if (result.size() < MAX_CANDIDATES) {
						addUnseen(entry.getKey(), seen, result);
					}
				});
			}
			if (result.size() < missing) {
				LOGGER.info("[RaidParty] only {} of {} missing for {}: live={} {}", result.size(), missing, announcedParty, liveParty, tallySummary());
			}
		}
		return Collections.unmodifiableList(result);
	}

	private static void addUnseen(String name, Set<String> seen, List<String> result) {
		if (seen.add(name.toLowerCase(Locale.ROOT))) {
			result.add(name);
		}
	}

	/** Drop the tally once a completion has consumed it, or on disconnect. */
	public static void reset() {
		synchronized (LOCK) {
			raiding = false;
			raidEndedAt = 0;
			tickCounter = 0;
			sightings.clear();
			partyMembers.clear();
			expectingPartyList = false;
			partyListRequestedAt = 0L;
			partyListAttempts = 0;
			liveParty = List.of();
		}
	}

	/** Roster and the most-seen players, for the diagnostic log lines. Holds LOCK. */
	private static String tallySummary() {
		String seen = sightings.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(MAX_CANDIDATES).map(entry -> entry.getKey() + " x" + entry.getValue()).collect(Collectors.joining(", "));
		return "roster=" + partyMembers + " sightings=[" + seen + "] (need x" + MIN_SIGHTINGS + ")";
	}

	/** Whether the sidebar is showing a raid's objective block. */
	private static boolean sidebarShowsRaid() {
		Scoreboard scoreboard = sidebarScoreboard();
		if (scoreboard == null) {
			return false;
		}
		Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (sidebar == null) {
			return false;
		}
		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
			if (stripCodes(sidebarLineText(scoreboard, entry)).contains(RAID_HEADER)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The scoreboard to read the sidebar from: the packet-captured shadow when it carries one
	 * (so Wynntils hiding the vanilla segment cannot blind us), else the game's own. Mirrors
	 * how {@link tel.eden.mod.war.AttackTimerMenu} sources its timer lines.
	 */
	private static Scoreboard sidebarScoreboard() {
		if (ScoreboardCapture.hasSidebar()) {
			return ScoreboardCapture.scoreboard();
		}
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null ? mc.level.getScoreboard() : null;
	}

	/**
	 * One sidebar row's full text. Wynncraft builds these out of the row's team prefix and
	 * suffix wrapped around the entry name, so reading the entry name alone would miss most
	 * of the line — including, usually, all of it.
	 */
	private static String sidebarLineText(Scoreboard scoreboard, PlayerScoreEntry entry) {
		PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
		if (team != null) {
			return PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString();
		}
		return entry.ownerName().getString();
	}

	/**
	 * Count the players currently within range, by account name.
	 *
	 * <p>The name is taken from the game profile rather than from anything displayed, because
	 * the profile is the account: it is unaffected by a nickname, and the backend keys guild
	 * membership and API lookups on account names.
	 */
	private static void sampleNearbyPlayers() {
		Minecraft mc = Minecraft.getInstance();
		Player self = mc.player;
		if (self == null || mc.level == null) {
			return;
		}
		AABB box = new AABB(self.getX() - SAMPLE_RADIUS, self.getY() - SAMPLE_RADIUS, self.getZ() - SAMPLE_RADIUS, self.getX() + SAMPLE_RADIUS, self.getY() + SAMPLE_RADIUS, self.getZ() + SAMPLE_RADIUS);
		List<Player> players = mc.level.getEntitiesOfClass(Player.class, box);
		synchronized (LOCK) {
			for (Player player : players) {
				String name = accountName(player);
				if (name != null) {
					sightings.merge(name, 1, Integer::sum);
				}
			}
		}
	}

	/** A player entity's account name, or null if it carries nothing that looks like one. */
	private static String accountName(Player player) {
		String profileName = player.getGameProfile().name();
		return profileName != null && IGN.matcher(profileName).matches() ? profileName : null;
	}

	private static String stripCodes(String text) {
		return COLOR_CODE.matcher(text).replaceAll("");
	}

	/**
	 * Diagnostic for {@code /eden wartest}: whether a raid is being sampled, and the tally so
	 * far with each player's sighting count against the threshold they have to clear.
	 */
	public static List<String> debugState() {
		List<String> out = new ArrayList<>();
		synchronized (LOCK) {
			out.add("raid sampling: " + raiding + " (need " + MIN_SIGHTINGS + " sightings, " + (MIN_PRESENCE_MS / 1000) + "s)");
			out.add("party members (" + partyMembers.size() + "): " + (partyMembers.isEmpty() ? "(none)" : String.join(", ", partyMembers)) + " (expectingList=" + expectingPartyList + ")");
			if (sightings.isEmpty()) {
				out.add("  (nobody sampled via proximity)");
				return out;
			}
			sightings.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).forEach(entry -> out.add("  " + entry.getKey() + " x" + entry.getValue() + (entry.getValue() >= MIN_SIGHTINGS ? "" : " (below threshold)")));
		}
		return out;
	}

	/**
	 * The current raid's party as last read from Wynntils or the hidden {@code /party list}
	 * reply (real IGNs where they could be resolved). Empty outside a raid or before the
	 * reply arrives.
	 */
	public static List<String> partyMembers() {
		synchronized (LOCK) {
			return new ArrayList<>(partyMembers);
		}
	}

	public static Set<String> partyMembersForTesting() {
		synchronized (LOCK) {
			return new LinkedHashSet<>(partyMembers);
		}
	}

	public static void setRaidingForTesting(boolean isRaiding) {
		synchronized (LOCK) {
			raiding = isRaiding;
			raidEndedAt = 0;
		}
	}

	public static void endRaidForTesting() {
		synchronized (LOCK) {
			updateRaidState(false);
		}
	}

	public static void setLivePartyForTesting(List<String> party) {
		synchronized (LOCK) {
			liveParty = List.copyOf(party);
		}
	}

	public static void setExpectingPartyListForTesting(boolean expecting) {
		synchronized (LOCK) {
			expectingPartyList = expecting;
			partyListRequestedAt = expecting ? System.currentTimeMillis() : 0L;
		}
	}

	public static void addPartyMemberForTesting(String member) {
		synchronized (LOCK) {
			partyMembers.add(member);
		}
	}

	public static void recordSightingForTesting(String name, int count) {
		synchronized (LOCK) {
			sightings.put(name, count);
		}
	}
}
