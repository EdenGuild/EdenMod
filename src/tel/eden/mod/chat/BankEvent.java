package tel.eden.mod.chat;

/**
 * One parsed guild-bank deposit or withdrawal.
 *
 * @param action     {@code "deposit"} or {@code "withdrawal"}
 * @param player     the depositor/withdrawer's real account username
 * @param quantity   the item count (e.g. {@code 1} from {@code "1x"}), or null if absent
 * @param item       the item name (e.g. {@code "Dernic Axe T12"})
 * @param charges    optional bracketed suffix (e.g. consumable charges), or null
 * @param accessTier the bank tier — {@code "Everyone"} or {@code "High Ranked"}
 */
public record BankEvent(
        String action,
        String player,
        Integer quantity,
        String item,
        String charges,
        String accessTier) {}
