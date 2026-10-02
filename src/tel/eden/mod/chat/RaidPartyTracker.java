package tel.eden.mod.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
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
 * <p>They are recovered by proximity rather than by reading the sidebar, the same way war
 * attendance is collected: while a raid is running, the players loaded around the client are
 * sampled periodically and their appearances counted. On completion the announced party is
 * subtracted from that tally and the most-seen remainder fills the gap.
 *
 * <p>Proximity is used because it is the only source that yields <em>account</em> names. The
 * sidebar shows nicknames, and nothing on those rows maps a nickname back to the account
 * behind it. A player entity carries a game profile, and that profile is the real account —
 * a nickname is a display layer painted over it. Reading names off entities means there is
 * no nickname to undo.
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
	// Hold the tally this long after the raid ends: the completion message lands just after.
	private static final long RETENTION_MS = 60_000L;

	// Sampling runs on the client thread; a raid completion arrives on the netty thread
	// (the chat mixin injects ahead of vanilla's ensureRunningOnSameThread), and that is
	// what calls extraPlayers and reset. Every field below is therefore touched from both,
	// and is guarded by LOCK — without it the completion can read a stale "no raid is
	// running", judge the tally stale, and report no allies at all.
	private static final Object LOCK = new Object();
	private static final Map<String, Integer> sightings = new HashMap<>();
	private static boolean raiding;
	private static long raidEndedAt;
	private static int tickCounter;

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
			synchronized (LOCK) {
				updateRaidState(inRaid);
				sampling = raiding;
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
			}
			raiding = true;
			raidEndedAt = 0;
		} else if (!inRaid && raiding) {
			raiding = false;
			raidEndedAt = System.currentTimeMillis();
		}
	}

	/**
	 * Candidates for the players the completion did not name, most seen first.
	 *
	 * <p>This is a shortlist, not an answer: it is everyone present long enough to have run
	 * the raid, minus those the announcement already named. The backend decides which of
	 * them are really allies and caps the party at {@link #RAID_PARTY_SIZE}, because that
	 * needs the guild member list and the player API, neither of which the client has.
	 *
	 * <p>Empty when the completion already names a full party, when nothing was sampled
	 * (this client was not in the raid), or when the tally has gone stale. Comparison with
	 * the announced party is case-insensitive so a casing difference cannot duplicate a
	 * player.
	 */
	public static List<String> extraPlayers(List<String> announcedParty) {
		if (announcedParty.size() >= RAID_PARTY_SIZE) {
			return List.of();
		}
		Set<String> announced = announcedParty.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
		List<Map.Entry<String, Integer>> ranked;
		synchronized (LOCK) {
			if (isStale()) {
				return List.of();
			}
			ranked = sightings.entrySet().stream().filter(entry -> entry.getValue() >= MIN_SIGHTINGS).filter(entry -> !announced.contains(entry.getKey().toLowerCase(Locale.ROOT))).sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(MAX_CANDIDATES).collect(Collectors.toList());
		}
		return ranked.stream().map(Map.Entry::getKey).collect(Collectors.toUnmodifiableList());
	}

	/** Drop the tally once a completion has consumed it, or on world change. */
	public static void reset() {
		synchronized (LOCK) {
			raiding = false;
			raidEndedAt = 0;
			tickCounter = 0;
			sightings.clear();
		}
	}

	/**
	 * Whether the tally can still be trusted: a raid is running, or one ended recently enough
	 * that this completion is plausibly its own. Holds LOCK.
	 */
	private static boolean isStale() {
		if (raiding) {
			return false;
		}
		return raidEndedAt == 0 || System.currentTimeMillis() - raidEndedAt > RETENTION_MS;
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
			if (sightings.isEmpty()) {
				out.add("  (nobody sampled)");
				return out;
			}
			sightings.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).forEach(entry -> out.add("  " + entry.getKey() + " x" + entry.getValue() + (entry.getValue() >= MIN_SIGHTINGS ? "" : " (below threshold)")));
		}
		return out;
	}
}
