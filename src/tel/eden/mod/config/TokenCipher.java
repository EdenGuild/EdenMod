package tel.eden.mod.config;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts the bridge JWT at rest so it is not sitting in {@code edenmod.json}
 * as readable plaintext.
 *
 * <p><strong>This is obfuscation, not real secrecy.</strong> A Minecraft client
 * fully controls its own JVM, so anyone determined can recover the key from the jar
 * and decrypt on the same machine. The goal is narrower and still worthwhile: stop
 * the token from being lifted by a casual glance at the config file, a screen-share,
 * or a cloud backup, and make a config copied to another machine/user useless (the
 * key is bound to the local OS user, so it won't decrypt elsewhere — the player just
 * re-links). The bridge backend remains the real authority and must re-validate the
 * token regardless.
 */
final class TokenCipher {
    // Static per-build salt mixed into the key. Not a secret (it ships in the jar);
    // it only ensures the derived key differs from a bare hash of the machine fields.
    private static final String PEPPER = "edenmod:v1:a7c1f0e9-key-derivation-salt";
    private static final String PREFIX = "enc:v1:";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenCipher() {}

    /** Encrypt a token for storage; returns "" for empty input, plaintext on failure. */
    static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            // Never lose the token over an encryption hiccup; fall back to plaintext.
            return plaintext;
        }
    }

    /**
     * Decrypt a stored token. A value without the {@link #PREFIX} is treated as a
     * legacy plaintext token and returned as-is (so existing configs keep working).
     * An undecryptable value (e.g. copied from another machine) yields "" so the
     * player is prompted to re-link rather than connecting with a broken token.
     */
    static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        if (!stored.startsWith(PREFIX)) {
            return stored; // legacy plaintext token from before encryption was added
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return "";
        }
    }

    /** Derive the AES key from the static pepper bound to the local OS user/platform. */
    private static SecretKeySpec key() throws GeneralSecurityException {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(PEPPER.getBytes(StandardCharsets.UTF_8));
        sha.update(System.getProperty("user.name", "").getBytes(StandardCharsets.UTF_8));
        sha.update(System.getProperty("os.name", "").getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(sha.digest(), "AES");
    }
}
