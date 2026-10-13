package tel.eden.mod.net;

/**
 * The player who originally queued an upcoming territory war.
 *
 * @param name Minecraft username
 * @param uuid Player UUID string (or generated offline UUID)
 * @param inside whether the queuer is currently inside the territory boundary
 */
public record WarQueuer(String name, String uuid, boolean inside) {
}
