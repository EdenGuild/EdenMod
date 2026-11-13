package tel.eden.mod.net;

import java.util.Set;

/**
 * Which outbound message types the mod may send for the player's standing. The server
 * enforces this too; this stops a traveler's own guild data from ever leaving the client.
 */
public final class OutboundPolicy {
	public enum Standing {
		/** Before {@code authOk}. */
		UNKNOWN, NOT_LINKED, NOT_MEMBER, TRAVELER, FULL;

		public static Standing of(boolean linked, boolean member, boolean traveler) {
			if (!linked) {
				return NOT_LINKED;
			}
			if (member) {
				return FULL;
			}
			return traveler ? TRAVELER : NOT_MEMBER;
		}
	}

	static final Set<String> TRAVELER_TYPES = Set.of("authResponse", "presence", "mailSend", "mailAck", "tccMapPos", "tccGolemDefeated", "tccExitReached", "witnessKill", "partyOpen", "partyJoin", "partyLeave", "partyList", "partyManage", "travelerChat", "travelerPrefs", "coinflip", "diceroll", "attestationFailure");

	private OutboundPolicy() {
	}

	private static final Set<String> GUILD_ONLY_INBOUND = Set.of("warBoard", "discordMessage", "loginNotice", "logoutNotice", "onlineList");

	/** Defence in depth: the server never sends these to a traveler, so drop them if one arrives. */
	public static boolean inboundAllowed(Standing standing, String type) {
		return standing != Standing.TRAVELER || !GUILD_ONLY_INBOUND.contains(type);
	}

	public static boolean allowed(Standing standing, String type) {
		return switch (standing) {
			case FULL -> true;
			case TRAVELER -> TRAVELER_TYPES.contains(type);
			default -> "authResponse".equals(type);
		};
	}
}
