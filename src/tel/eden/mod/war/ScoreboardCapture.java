package tel.eden.mod.war;

import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * A shadow {@link Scoreboard} fed directly from the raw clientbound scoreboard packets,
 * independent of the game's own scoreboard.
 *
 * <p>Wynntils hides the guild war-timer scoreboard segment: it processes the scoreboard
 * inside the packet <em>listener</em> ({@code handleSetScore} &amp; co.) and strips that
 * segment, so by the time the war suite reads {@code level.getScoreboard()} the timer
 * lines are gone. {@link tel.eden.mod.mixin.ConnectionScoreboardMixin} captures each
 * scoreboard packet one level upstream — at the head of {@code Connection.channelRead0},
 * before {@code packet.handle(...)} ever dispatches to the listener — and feeds it here.
 * Because this mirror is built from the wire and never touched by Wynntils, the timer
 * lines survive. {@link AttackTimerMenu} reads this shadow when it carries a sidebar.
 *
 * <p>The apply methods replicate {@code ClientPacketListener}'s own scoreboard handlers
 * exactly, so the shadow is byte-for-byte what the vanilla scoreboard would have been.
 * All access is marshalled onto the client thread by the mixin, matching where the HUD
 * reads it, so no locking is needed.
 */
public final class ScoreboardCapture {
	private ScoreboardCapture() {
	}

	private static Scoreboard shadow = new Scoreboard();

	/** The shadow scoreboard (client thread only). */
	public static Scoreboard scoreboard() {
		return shadow;
	}

	/** Whether the shadow currently has a sidebar objective (i.e. it captured timer data). */
	public static boolean hasSidebar() {
		return shadow.getDisplayObjective(DisplaySlot.SIDEBAR) != null;
	}

	/**
	 * Drop everything captured so far (call on world change / disconnect). The shadow is a
	 * static singleton reused across connections; without this, a previous session's
	 * objectives/teams/scores linger — e.g. a stale sidebar makes {@link #hasSidebar()}
	 * stay true and old timers show after a world hop.
	 */
	public static void reset() {
		shadow = new Scoreboard();
	}

	/** Apply a captured objective add/remove/change to the shadow. */
	public static void apply(ClientboundSetObjectivePacket packet) {
		String name = packet.getObjectiveName();
		if (packet.getMethod() == ClientboundSetObjectivePacket.METHOD_ADD) {
			shadow.addObjective(name, ObjectiveCriteria.DUMMY, packet.getDisplayName(), packet.getRenderType(), false, packet.getNumberFormat().orElse(null));
			return;
		}
		Objective objective = shadow.getObjective(name);
		if (objective == null) {
			return;
		}
		if (packet.getMethod() == ClientboundSetObjectivePacket.METHOD_REMOVE) {
			shadow.removeObjective(objective);
		} else if (packet.getMethod() == ClientboundSetObjectivePacket.METHOD_CHANGE) {
			objective.setDisplayName(packet.getDisplayName());
			objective.setRenderType(packet.getRenderType());
			objective.setNumberFormat(packet.getNumberFormat().orElse(null));
		}
	}

	/** Apply which objective occupies a display slot (e.g. the sidebar). */
	public static void apply(ClientboundSetDisplayObjectivePacket packet) {
		String name = packet.getObjectiveName();
		Objective objective = name == null ? null : shadow.getObjective(name);
		shadow.setDisplayObjective(packet.getSlot(), objective);
	}

	/** Apply a single score update to the shadow. */
	public static void apply(ClientboundSetScorePacket packet) {
		Objective objective = shadow.getObjective(packet.objectiveName());
		if (objective == null) {
			return;
		}
		ScoreAccess access = shadow.getOrCreatePlayerScore(ScoreHolder.forNameOnly(packet.owner()), objective);
		access.set(packet.score());
		access.display(packet.display().orElse(null));
		access.numberFormatOverride(packet.numberFormat().orElse(null));
	}

	/** Apply a score reset (one objective, or all when the name is null). */
	public static void apply(ClientboundResetScorePacket packet) {
		ScoreHolder holder = ScoreHolder.forNameOnly(packet.owner());
		String objectiveName = packet.objectiveName();
		if (objectiveName == null) {
			shadow.resetAllPlayerScores(holder);
			return;
		}
		Objective objective = shadow.getObjective(objectiveName);
		if (objective != null) {
			shadow.resetSinglePlayerScore(holder, objective);
		}
	}

	/** Apply a team add/change/remove and its player join/leave to the shadow. */
	public static void apply(ClientboundSetPlayerTeamPacket packet) {
		ClientboundSetPlayerTeamPacket.Action teamAction = packet.getTeamAction();
		PlayerTeam team;
		if (teamAction == ClientboundSetPlayerTeamPacket.Action.ADD) {
			team = shadow.addPlayerTeam(packet.getName());
		} else {
			team = shadow.getPlayerTeam(packet.getName());
			if (team == null) {
				return;
			}
		}
		packet.getParameters().ifPresent(parameters -> {
			team.setDisplayName(parameters.getDisplayName());
			team.setColor(parameters.getColor());
			team.unpackOptions(parameters.getOptions());
			team.setNameTagVisibility(parameters.getNametagVisibility());
			team.setCollisionRule(parameters.getCollisionRule());
			team.setPlayerPrefix(parameters.getPlayerPrefix());
			team.setPlayerSuffix(parameters.getPlayerSuffix());
		});
		ClientboundSetPlayerTeamPacket.Action playerAction = packet.getPlayerAction();
		if (playerAction == ClientboundSetPlayerTeamPacket.Action.ADD) {
			for (String player : packet.getPlayers()) {
				shadow.addPlayerToTeam(player, team);
			}
		} else if (playerAction == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
			for (String player : packet.getPlayers()) {
				shadow.removePlayerFromTeam(player, team);
			}
		}
		if (teamAction == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
			shadow.removePlayerTeam(team);
		}
	}
}
