package tel.eden.mod.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import tel.eden.mod.EdenLogger;
import tel.eden.mod.net.TlsPolicy;

/**
 * Downloads a newer EdenMod jar and swaps it in when the game closes.
 *
 * <p>The new jar is downloaded to {@code edenmod/} at the game root — outside the mods
 * folder, so Fabric never loads it a second time and causes a duplicate-mod crash. A JVM
 * shutdown hook then launches {@link UpdateApplier} as a small detached JVM (via
 * {@code javaw}/{@code java}, so no console window appears) that deletes the old jar and
 * copies the new one into the mods folder. On Windows the old jar is file-locked while
 * the game runs, so the helper retries until the game has fully exited; on Unix the swap
 * applies immediately and simply takes effect on the next launch.
 *
 * <p>Persistent files live in three places: {@code config/edenmod.json} (config,
 * idiomatic Fabric location), {@code mods/edenmod-<version>.jar} (the running jar),
 * and {@code edenmod/} at the game root (staged jars, attestation bundles, and cached
 * Fulcio intermediate certs).
 *
 * <p>The applier runs from a copy of the <em>current</em> jar (not the downloaded one):
 * a release older than this self-updater wouldn't contain {@link UpdateApplier}, and the
 * old jar can't be its own classpath because it must stay deletable.
 *
 * <p>Failure is non-destructive: the staged jar lives outside the Fabric scan path, and
 * the old jar is only removed once the swap actually runs, so a failed update never
 * leaves the mods folder with two EdenMod jars.
 */
public final class UpdateInstaller {
	private static final EdenLogger LOGGER = EdenLogger.get();
	private static final String MOD_ID = "edenmod";
	private static final int HTTP_OK = 200;
	private static final String ATTESTATION_API = "https://api.github.com/repos/EdenGuild/EdenMod/attestations/sha256:";
	/** GitHub's Fulcio instance — provides the intermediate cert chain in PEM format. */
	private static final String FULCIO_TRUST_BUNDLE_URL = "https://fulcio.githubapp.com/api/v2/trustBundle";

	private final HttpClient http = TlsPolicy.newHttpClientBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

	/**
	 * The SHA-256 hex of the running jar when boot attestation failed, or {@code null} if
	 * attestation passed (or was skipped). Set once by the boot-verify thread; read by
	 * {@link tel.eden.mod.EdenModClient} when the bridge connects to report the failure.
	 */
	private volatile String bootAttestationFailureSha;

	/** The SHA-256 of the running jar when boot attestation failed, or {@code null} if it passed. */
	public String bootAttestationFailureSha() {
		return bootAttestationFailureSha;
	}

	/** Outcome of a download-and-stage attempt. */
	public enum Result {
		/** Downloaded and staged; the swap will run when the game closes. */
		SCHEDULED,
		/** The mod isn't running from a jar (dev env) — can't self-apply. */
		NOT_INSTALLED_FROM_JAR,
		/** The download or staging failed. */
		FAILED,
		/** The jar downloaded successfully but its Sigstore attestation could not be verified. */
		VERIFICATION_FAILED
	}

	/** Download {@code info}'s jar and arrange the on-close swap. */
	public Result downloadAndStage(UpdateInfo info) {
		Path oldJar = currentJar();
		if (oldJar == null || oldJar.getParent() == null) {
			return Result.NOT_INSTALLED_FROM_JAR;
		}
		try {
			Path modsDir = oldJar.getParent();
			Path edenDir = FabricLoader.getInstance().getGameDir().resolve("edenmod");
			Files.createDirectories(edenDir);
			sweepStaleStaging(edenDir);
			Path staged = edenDir.resolve("edenmod-" + info.version() + ".jar");
			int maxAttempts = 3;
			for (int attempt = 1; attempt <= maxAttempts; attempt++) {
				try {
					download(info.jarUrl(), staged);
					break;
				} catch (IOException e) {
					Files.deleteIfExists(staged);
					if (attempt == maxAttempts)
						throw e;
					LOGGER.warn("Update download attempt {}/{} failed — retrying in 5s: {}", attempt, maxAttempts, e.getMessage());
					try {
						Thread.sleep(5_000L);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						return Result.FAILED;
					}
				}
			}
			if (!Files.isRegularFile(staged) || Files.size(staged) <= 0) {
				return Result.FAILED;
			}
			if (!verifyAttestation(staged, info.version(), edenDir.resolve("edenmod-" + info.version() + "-attestation.json"))) {
				Files.deleteIfExists(staged);
				return Result.VERIFICATION_FAILED;
			}
			LOGGER.info("Update edenmod-{}.jar staged; will apply when the game closes", info.version());
			// newJar goes into the mods folder root — that's where Fabric picks it up.
			Path newJar = modsDir.resolve("edenmod-" + info.version() + ".jar");
			// The swap helper runs from a copy of the current jar, which is guaranteed
			// to contain UpdateApplier and is neither the (deletable) old jar nor the
			// staged jar. It is left behind and swept on the next launch.
			Path helper = edenDir.resolve("edenmod-helper.jar");
			Files.copy(oldJar, helper, StandardCopyOption.REPLACE_EXISTING);
			Runtime.getRuntime().addShutdownHook(new Thread(() -> spawnSwapper(oldJar, staged, newJar, helper), "edenmod-apply"));
			return Result.SCHEDULED;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Update download/stage failed", e);
			return Result.FAILED;
		}
	}

	/**
	 * Verifies the downloaded jar via a full Sigstore DSSE certificate-chain check.
	 *
	 * <ol>
	 *   <li>Compute SHA-256 of the jar.
	 *   <li>Pre-check whether the embedded Fulcio root has actually expired (local, not
	 *       attacker-inducible). If so, degrade to hash-only attestation — same trust level
	 *       as before Sigstore was added — and log a warning to update the mod. The 180-day
	 *       CI alarm ({@code checkFulcioCert}) and in-game warnings handle the expiry-soon
	 *       case; users retain full verification for the entire grace window.
	 *   <li>Fetch the GitHub attestation bundle for this hash. A missing/HTTP-error response
	 *       is a hard rejection regardless of mode.
	 *   <li>In full mode: validate the Sigstore DSSE bundle — cert chain → embedded root,
	 *       SAN URI, OIDC issuer, ECDSA signature over PAE(type, payload), subject hash.
	 *       Any failure is a hard reject — no fallback. Falling back on network-sourced
	 *       errors would allow an attacker to bypass the check by deliberately malforming
	 *       the attestation bundle.
	 * </ol>
	 *
	 * <p>On success the bundle JSON is written to {@code bundleSavePath} so it can be
	 * re-verified on every subsequent boot without a network call.
	 */
	private boolean verifyAttestation(Path jar, String version, Path bundleSavePath) {

		// 1. Hash the jar.
		String hex;
		try {
			hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
		} catch (Exception e) {
			LOGGER.warn("Failed to hash jar for attestation check", e);
			return false;
		}

		// 2. Pre-check embedded root expiry — local only, not attacker-inducible.
		boolean fullSigstore = !FulcioTrust.isRootExpired();
		if (!fullSigstore) {
			LOGGER.warn("Embedded Fulcio root cert has EXPIRED — falling back to hash-only attestation (update the mod!)");
		}

		// 3. Fetch the attestation bundle.
		JsonObject bundle;
		try {
			HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(ATTESTATION_API + hex)).header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "EdenMod-updater").timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != HTTP_OK) {
				LOGGER.warn("Attestation API returned HTTP {} for edenmod-{}.jar", resp.statusCode(), version);
				return false;
			}
			JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
			JsonArray attestations = body.has("attestations") ? body.getAsJsonArray("attestations") : null;
			if (attestations == null || attestations.isEmpty()) {
				LOGGER.warn("No attestation found for edenmod-{}.jar (sha256={})", version, hex);
				return false;
			}
			bundle = attestations.get(0).getAsJsonObject().getAsJsonObject("bundle");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.warn("Attestation check interrupted for edenmod-{}.jar", version);
			return false;
		} catch (Exception e) {
			LOGGER.warn("Attestation fetch failed for edenmod-{}.jar", version, e);
			return false;
		}

		// 4a. Hash-only mode: attestation record exists — that's enough.
		if (!fullSigstore) {
			LOGGER.info("Hash-only attestation check passed for edenmod-{}.jar (sha256={})", version, hex);
			return true;
		}

		// 4b. Full Sigstore verification — hard reject on any failure, no fallback.
		try {
			Path edenDir = bundleSavePath.getParent();
			X509Certificate[] chain = resolveIntermediates(edenDir, bundle);
			verifySigstoreBundle(bundle, hex, version, chain);
			LOGGER.info("Attestation verified for edenmod-{}.jar (sha256={})", version, hex);
			saveBundle(bundle, bundleSavePath);
			return true;
		} catch (Exception e) {
			LOGGER.warn("Sigstore verification FAILED for edenmod-{}.jar: {}", version, e.getMessage());
			return false;
		}
	}

	// ── Boot-time attestation ────────────────────────────────────────────────

	/**
	 * Verifies the currently-running jar against its persisted attestation bundle.
	 *
	 * <p>Called once on every boot so the mod stays verified regardless of how it was
	 * installed — Modrinth, manual GitHub download, or the built-in auto-updater all
	 * produce identical JARs (same SHA-256) as long as nobody has tampered with them.
	 * The attestation is permanently anchored to the JAR hash on GitHub's servers, so
	 * once saved it can be re-checked entirely offline on subsequent boots.
	 *
	 * <p>Flow:
	 * <ol>
	 *   <li>Hash the running jar.
	 *   <li>Sweep stale attestation files left from older versions.
	 *   <li>If the bundle file exists, re-verify locally (no network for the bundle;
	 *       intermediates are loaded from disk cache or fetched once if missing).
	 *   <li>If not, fetch the bundle from GitHub by hash, verify, and save it.
	 * </ol>
	 *
	 * <p>The only non-failure returns are: not running from a jar (dev classpath),
	 * version unknown, or the embedded Fulcio root has actually expired. All other
	 * failures are hard.
	 *
	 * @return {@code false} when verification failed; {@code true} on success or
	 *     the narrow set of skip conditions above
	 */
	public boolean verifyBootAttestation() {
		Path jar = currentJar();
		if (jar == null) {
			LOGGER.debug("Not running from a jar — skipping boot attestation check");
			return true;
		}
		String version = UpdateChecker.currentVersion();
		if (version == null) {
			return true;
		}
		Path edenDir = FabricLoader.getInstance().getGameDir().resolve("edenmod");
		try {
			Files.createDirectories(edenDir);
		} catch (IOException e) {
			LOGGER.warn("Boot attestation: could not create edenmod dir", e);
			return false;
		}
		sweepOldAttestations(edenDir, version);

		String hex;
		try {
			hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
		} catch (Exception e) {
			LOGGER.warn("Boot attestation: failed to hash edenmod-{}.jar", version, e);
			return true;
		}

		Path bundlePath = edenDir.resolve("edenmod-" + version + "-attestation.json");
		if (!Files.isRegularFile(bundlePath)) {
			// No cached bundle yet — must fetch from GitHub (network). Retry a few times
			// so a transient connection error doesn't immediately show the tamper warning.
			int maxAttempts = 3;
			for (int attempt = 1; attempt <= maxAttempts; attempt++) {
				if (fetchVerifyAndSaveBundle(bundlePath, hex, version)) {
					return true;
				}
				if (attempt < maxAttempts) {
					LOGGER.info("Boot attestation attempt {}/{} failed — retrying in 5s", attempt, maxAttempts);
					try {
						Thread.sleep(5_000L);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						bootAttestationFailureSha = hex;
						return false;
					}
				}
			}
			bootAttestationFailureSha = hex;
			return false;
		}

		// Bundle already on disk — verify locally; intermediates loaded from cache or fetched once.
		if (FulcioTrust.isRootExpired()) {
			LOGGER.warn("Fulcio root expired — skipping boot attestation check");
			return true;
		}
		try {
			JsonObject bundle = JsonParser.parseString(Files.readString(bundlePath, StandardCharsets.UTF_8)).getAsJsonObject();
			X509Certificate[] chain = resolveIntermediates(edenDir, bundle);
			verifySigstoreBundle(bundle, hex, version, chain);
			LOGGER.info("Boot attestation verified for edenmod-{}.jar (sha256={})", version, hex);
			return true;
		} catch (Exception e) {
			LOGGER.warn("Boot attestation FAILED for edenmod-{}.jar: {}", version, e.getMessage());
			bootAttestationFailureSha = hex;
			return false;
		}
	}

	/**
	 * Fetch the attestation bundle from GitHub by hash, verify it, and save it.
	 * The only non-failure return is a passed verification or an expired Fulcio root.
	 * Network errors, missing attestations, and verification failures all return false.
	 */
	private boolean fetchVerifyAndSaveBundle(Path bundlePath, String hex, String version) {
		if (FulcioTrust.isRootExpired()) {
			LOGGER.warn("Fulcio root expired — skipping attestation fetch for edenmod-{}.jar", version);
			return true;
		}
		LOGGER.info("No attestation bundle for edenmod-{}.jar — fetching from GitHub (sha256={})...", version, hex);
		JsonObject bundle;
		try {
			HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(ATTESTATION_API + hex)).header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "EdenMod-updater").timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != HTTP_OK) {
				LOGGER.warn("No GitHub attestation for edenmod-{}.jar (HTTP {}, sha256={}) — jar may not be an official release", version, resp.statusCode(), hex);
				return false;
			}
			JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
			JsonArray attestations = body.has("attestations") ? body.getAsJsonArray("attestations") : null;
			if (attestations == null || attestations.isEmpty()) {
				LOGGER.warn("No GitHub attestation found for edenmod-{}.jar (sha256={}) — jar may not be an official release", version, hex);
				return false;
			}
			bundle = attestations.get(0).getAsJsonObject().getAsJsonObject("bundle");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.warn("Boot attestation fetch interrupted for edenmod-{}.jar — verification failed", version);
			return false;
		} catch (Exception e) {
			LOGGER.warn("Boot attestation fetch failed for edenmod-{}.jar — verification failed: {}", version, e.getMessage());
			return false;
		}
		try {
			Path edenDir = bundlePath.getParent();
			X509Certificate[] chain = resolveIntermediates(edenDir, bundle);
			verifySigstoreBundle(bundle, hex, version, chain);
			LOGGER.info("Boot attestation verified for edenmod-{}.jar (sha256={})", version, hex);
			saveBundle(bundle, bundlePath);
			return true;
		} catch (Exception e) {
			LOGGER.warn("Boot attestation FAILED for edenmod-{}.jar: {}", version, e.getMessage());
			return false;
		}
	}

	/** Remove attestation JSON files in {@code edenDir} that belong to older versions. */
	private static void sweepOldAttestations(Path edenDir, String currentVersion) {
		String keep = "edenmod-" + currentVersion + "-attestation.json";
		try (java.util.stream.Stream<Path> files = Files.list(edenDir)) {
			files.filter(p -> {
				String n = p.getFileName().toString();
				return n.startsWith("edenmod-") && n.endsWith("-attestation.json") && !n.equals(keep);
			}).forEach(p -> {
				try {
					Files.deleteIfExists(p);
					LOGGER.debug("Removed stale attestation: {}", p.getFileName());
				} catch (IOException ignored) {
				}
			});
		} catch (IOException e) {
			LOGGER.debug("Could not sweep old attestations in {}", edenDir, e);
		}
	}

	private static void saveBundle(JsonObject bundle, Path path) {
		try {
			Files.writeString(path, bundle.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException e) {
			LOGGER.warn("Failed to save attestation bundle to {}", path, e);
		}
	}

	// ── Intermediate cert resolution ─────────────────────────────────────────

	/**
	 * Returns the GitHub Fulcio CA chain {@code [l2, l1, root]} needed to verify a bundle.
	 *
	 * <p>Sources, tried in order:
	 * <ol>
	 *   <li>Disk cache ({@code edenDir/fulcio_inter.b64} and {@code fulcio_inter2.b64}) —
	 *       used if both files exist, are not expired, and cryptographically chain to the
	 *       committed root.
	 *   <li>Bundle's cert chain — extracted from {@code x509CertificateChain} if the bundle
	 *       uses the v0.2 format (GitHub release-service attestations include the full chain).
	 *   <li>GitHub's Fulcio trust bundle API ({@value #FULCIO_TRUST_BUNDLE_URL}) — fetched,
	 *       verified against the committed root, and saved to disk for subsequent boots.
	 * </ol>
	 *
	 * @throws SecurityException if no valid chain can be obtained from any source
	 */
	private X509Certificate[] resolveIntermediates(Path edenDir, JsonObject bundle) throws SecurityException {
		// 1. Disk cache.
		X509Certificate[] cached = tryLoadCachedIntermediates(edenDir);
		if (cached != null) {
			LOGGER.debug("Using cached Fulcio intermediate certs");
			return cached;
		}
		// 2. Bundle cert chain (v0.2 format only).
		if (bundle != null) {
			X509Certificate[] fromBundle = tryExtractFromBundle(bundle);
			if (fromBundle != null) {
				LOGGER.debug("Extracted Fulcio intermediates from bundle cert chain");
				saveCertToFile(fromBundle[0], edenDir.resolve("fulcio_inter2.b64"));
				saveCertToFile(fromBundle[1], edenDir.resolve("fulcio_inter.b64"));
				return fromBundle;
			}
		}
		// 3. Network fetch from GitHub's Fulcio API — retry a few times for transient failures.
		int maxAttempts = 3;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			X509Certificate[] fetched = tryFetchAndCacheIntermediates(edenDir);
			if (fetched != null) {
				return fetched;
			}
			if (attempt < maxAttempts) {
				LOGGER.info("Fulcio intermediates fetch attempt {}/{} failed — retrying in 5s", attempt, maxAttempts);
				try {
					Thread.sleep(5_000L);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		throw new SecurityException("Fulcio intermediate certs unavailable — tried disk cache, bundle chain, and " + FULCIO_TRUST_BUNDLE_URL);
	}

	/**
	 * Tries to load cached intermediates from {@code edenDir}. Returns {@code null} if
	 * either file is missing, expired, or does not cryptographically chain to the committed root.
	 */
	private static X509Certificate[] tryLoadCachedIntermediates(Path edenDir) {
		Path inter2Path = edenDir.resolve("fulcio_inter2.b64");
		Path interPath = edenDir.resolve("fulcio_inter.b64");
		if (!Files.isRegularFile(inter2Path) || !Files.isRegularFile(interPath)) {
			return null;
		}
		try {
			X509Certificate l2 = loadCertFromFile(inter2Path);
			X509Certificate l1 = loadCertFromFile(interPath);
			Date now = new Date();
			l2.checkValidity(now);
			l1.checkValidity(now);
			l1.verify(FulcioTrust.ROOT.getPublicKey());
			l2.verify(l1.getPublicKey());
			return new X509Certificate[]{l2, l1, FulcioTrust.ROOT};
		} catch (Exception e) {
			LOGGER.debug("Cached Fulcio intermediates invalid ({}), will re-fetch", e.getMessage());
			return null;
		}
	}

	/**
	 * Tries to extract intermediates from a v0.2 bundle's {@code x509CertificateChain}.
	 * Returns {@code null} if the bundle is v0.3 format (leaf cert only) or the chain
	 * does not validate against the committed root.
	 */
	private static X509Certificate[] tryExtractFromBundle(JsonObject bundle) {
		try {
			JsonObject verMat = bundle.getAsJsonObject("verificationMaterial");
			if (verMat == null || !verMat.has("x509CertificateChain")) {
				return null;
			}
			JsonArray certs = verMat.getAsJsonObject("x509CertificateChain").getAsJsonArray("certificates");
			if (certs == null || certs.size() < 3) {
				return null;
			}
			// v0.2 format: certs[0]=leaf, certs[1]=l2, certs[2]=l1 (possibly certs[3]=root).
			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			X509Certificate l2 = parseRawBytes(certs.get(1).getAsJsonObject().get("rawBytes").getAsString(), cf);
			X509Certificate l1 = parseRawBytes(certs.get(2).getAsJsonObject().get("rawBytes").getAsString(), cf);
			l1.verify(FulcioTrust.ROOT.getPublicKey());
			l2.verify(l1.getPublicKey());
			l1.checkValidity();
			l2.checkValidity();
			return new X509Certificate[]{l2, l1, FulcioTrust.ROOT};
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Fetches the intermediate cert chain from GitHub's Fulcio trust bundle API, verifies
	 * it against the committed root, caches it to disk, and returns {@code [l2, l1, root]}.
	 * Returns {@code null} on any failure (network error, parse failure, chain mismatch).
	 */
	private X509Certificate[] tryFetchAndCacheIntermediates(Path edenDir) {
		LOGGER.info("Fetching Fulcio intermediate certs from {}...", FULCIO_TRUST_BUNDLE_URL);
		try {
			HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(FULCIO_TRUST_BUNDLE_URL)).header("Accept", "application/json").header("User-Agent", "EdenMod-updater").timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != HTTP_OK) {
				LOGGER.warn("Fulcio trust bundle API returned HTTP {}", resp.statusCode());
				return null;
			}
			JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
			JsonArray chains = body.getAsJsonArray("chains");
			if (chains == null || chains.isEmpty()) {
				LOGGER.warn("No chains in Fulcio trust bundle response");
				return null;
			}
			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			for (var chainEl : chains) {
				JsonArray certs = chainEl.getAsJsonObject().getAsJsonArray("certificates");
				if (certs == null || certs.size() < 2) {
					continue;
				}
				try {
					// certs[0]=l2, certs[1]=l1, (certs[2]=root, optional); PEM-encoded strings.
					X509Certificate l2 = parsePemCert(certs.get(0).getAsString(), cf);
					X509Certificate l1 = parsePemCert(certs.get(1).getAsString(), cf);
					l1.verify(FulcioTrust.ROOT.getPublicKey());
					l2.verify(l1.getPublicKey());
					l1.checkValidity();
					l2.checkValidity();
					saveCertToFile(l2, edenDir.resolve("fulcio_inter2.b64"));
					saveCertToFile(l1, edenDir.resolve("fulcio_inter.b64"));
					LOGGER.info("Fulcio intermediates cached (l1 expires: {}, l2 expires: {})", l1.getNotAfter(), l2.getNotAfter());
					return new X509Certificate[]{l2, l1, FulcioTrust.ROOT};
				} catch (Exception e) {
					LOGGER.debug("Fulcio chain candidate rejected: {}", e.getMessage());
				}
			}
			LOGGER.warn("No valid Fulcio chain found in trust bundle that chains to committed root");
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.warn("Fulcio trust bundle fetch interrupted");
			return null;
		} catch (Exception e) {
			LOGGER.warn("Fulcio trust bundle fetch failed: {}", e.getMessage());
			return null;
		}
	}

	private static X509Certificate loadCertFromFile(Path path) throws Exception {
		String b64 = Files.readString(path, StandardCharsets.US_ASCII).trim();
		byte[] der = Base64.getDecoder().decode(b64);
		return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
	}

	private static void saveCertToFile(X509Certificate cert, Path path) {
		try {
			String b64 = Base64.getEncoder().encodeToString(cert.getEncoded());
			Files.writeString(path, b64 + "\n", StandardCharsets.US_ASCII, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (Exception e) {
			LOGGER.warn("Failed to cache Fulcio cert to {}: {}", path.getFileName(), e.getMessage());
		}
	}

	private static X509Certificate parsePemCert(String pem, CertificateFactory cf) throws Exception {
		return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
	}

	private static X509Certificate parseRawBytes(String base64, CertificateFactory cf) throws Exception {
		return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
	}

	// ── Sigstore DSSE verification ────────────────────────────────────────────

	private static void verifySigstoreBundle(JsonObject bundle, String expectedSha256, String version, X509Certificate[] caChain) throws Exception {
		JsonObject envelope = bundle.getAsJsonObject("dsseEnvelope");
		if (envelope == null)
			throw new IllegalArgumentException("bundle missing dsseEnvelope");

		String payloadType = envelope.get("payloadType").getAsString();
		byte[] payloadBytes = Base64.getDecoder().decode(envelope.get("payload").getAsString());
		JsonArray sigs = envelope.getAsJsonArray("signatures");
		if (sigs == null || sigs.isEmpty())
			throw new IllegalArgumentException("bundle dsseEnvelope has no signatures");
		byte[] sigBytes = Base64.getDecoder().decode(sigs.get(0).getAsJsonObject().get("sig").getAsString());

		// Extract the leaf cert from the bundle.
		X509Certificate leaf = extractLeafCert(bundle);

		// Walk the CA chain (l2 → l1 → root) to find which intermediate signed the leaf,
		// then verify the sub-chain upward to the root.
		//
		// GitHub's Fulcio hierarchy has evolved: old attestations (e.g. 1.4.0) were signed
		// by l1 directly; newer ones are signed by l2 (with l1 signing l2). Walking the
		// chain handles both without requiring a mod update on intermediate CA rotation.
		//
		// We use direct verify() instead of PKIX CertPathValidator because PKIX compares
		// X500Principal objects byte-for-byte (raw DER), and different ASN.1 string
		// encodings of the same DN produce a false "name chaining" failure even when the
		// chain is cryptographically valid.
		int signerIdx = -1;
		for (int i = 0; i < caChain.length - 1; i++) {
			try {
				leaf.verify(caChain[i].getPublicKey());
				signerIdx = i;
				break;
			} catch (Exception ignored) {
				// Not signed by this CA; try the next one.
			}
		}
		if (signerIdx < 0)
			throw new SecurityException("leaf cert not signed by any cert in the GitHub Fulcio chain");

		// Validate at signing time — the 10-minute Fulcio leaf is always expired
		// by the time the user downloads an update, so we can't use the current time.
		Date signingTime = leaf.getNotBefore();
		leaf.checkValidity(signingTime);
		for (int i = signerIdx; i < caChain.length; i++) {
			caChain[i].checkValidity(signingTime);
		}

		// Verify the sub-chain: caChain[signerIdx] ← caChain[signerIdx+1] ← ... ← root.
		for (int i = signerIdx; i < caChain.length - 1; i++) {
			if (caChain[i].getBasicConstraints() < 0)
				throw new SecurityException(caChain[i].getSubjectX500Principal() + " is not a CA");
			boolean[] ku = caChain[i].getKeyUsage();
			if (ku != null && !ku[5])
				throw new SecurityException(caChain[i].getSubjectX500Principal() + " missing keyCertSign");
			caChain[i].verify(caChain[i + 1].getPublicKey());
		}

		// Verify the leaf cert's identity claims.
		verifyCertClaims(leaf);

		// Verify the DSSE signature: ECDSA over PAE(payloadType, payload).
		verifyDsseSignature(buildPAE(payloadType, payloadBytes), sigBytes, leaf);

		// Verify the in-toto subject hash matches the jar we downloaded.
		verifySubjectHash(payloadBytes, expectedSha256, version);
	}

	/** Extracts the signing leaf cert from a Sigstore bundle (both v0.2 and v0.3 formats). */
	private static X509Certificate extractLeafCert(JsonObject bundle) throws Exception {
		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		JsonObject verMat = bundle.getAsJsonObject("verificationMaterial");
		if (verMat == null)
			throw new IllegalArgumentException("bundle missing verificationMaterial");
		if (verMat.has("certificate")) {
			// v0.3+ format: single leaf cert under "certificate".
			byte[] der = Base64.getDecoder().decode(verMat.getAsJsonObject("certificate").get("rawBytes").getAsString());
			return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
		}
		if (verMat.has("x509CertificateChain")) {
			// v0.2 format: leaf is certificates[0], chain is leaf-first.
			JsonArray certs = verMat.getAsJsonObject("x509CertificateChain").getAsJsonArray("certificates");
			if (certs == null || certs.isEmpty())
				throw new IllegalArgumentException("x509CertificateChain is empty");
			byte[] der = Base64.getDecoder().decode(certs.get(0).getAsJsonObject().get("rawBytes").getAsString());
			return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
		}
		throw new IllegalArgumentException("no leaf certificate in verificationMaterial");
	}

	private static void verifyCertClaims(X509Certificate leaf) throws Exception {
		// SAN URI must identify an entity authorised to produce official EdenMod releases.
		// Two signers are recognised:
		//   1. GitHub Actions workflow  — SAN contains "EdenGuild/EdenMod" (the repo path)
		//   2. GitHub release service  — SAN is "https://dotcom.releases.github.com"
		//      (GitHub's own service that signs artifacts when a Release is published)
		// In both cases the attestation API endpoint is scoped to EdenGuild/EdenMod, so
		// repository provenance is already enforced before we reach this check.
		Collection<List<?>> sans = leaf.getSubjectAlternativeNames();
		if (sans == null)
			throw new SecurityException("signing cert has no SubjectAlternativeNames");
		boolean sanFound = false;
		for (List<?> san : sans) {
			if (san.size() >= 2 && (Integer) san.get(0) == 6 && san.get(1) instanceof String uri) {
				String lower = uri.toLowerCase(Locale.ROOT);
				if (lower.contains("edenguild/edenmod") || lower.contains("dotcom.releases.github.com")) {
					sanFound = true;
					break;
				}
			}
		}
		if (!sanFound) {
			StringBuilder found = new StringBuilder();
			for (List<?> san : sans) {
				if (found.length() > 0)
					found.append(", ");
				found.append("type=").append(san.size() >= 1 ? san.get(0) : "?").append(" val=").append(san.size() >= 2 ? san.get(1) : "?");
			}
			throw new SecurityException("signing cert SAN is not a recognised EdenMod signer [" + found + "]");
		}

		// Defence-in-depth: OIDC issuer extension (OID 1.3.6.1.4.1.57264.1.1), when
		// present, must indicate GitHub Actions. Release-service certs omit this extension.
		byte[] issuerExt = leaf.getExtensionValue("1.3.6.1.4.1.57264.1.1");
		if (issuerExt != null && !new String(issuerExt, StandardCharsets.UTF_8).contains("token.actions.githubusercontent.com")) {
			throw new SecurityException("signing cert OIDC issuer is not GitHub Actions");
		}
	}

	/**
	 * DSSE Pre-Authentication Encoding:
	 * {@code PAE(type, body) = "DSSEv1" SP LEN(type) SP type SP LEN(body) SP body}.
	 */
	private static byte[] buildPAE(String payloadType, byte[] payloadBytes) {
		byte[] typeBytes = payloadType.getBytes(StandardCharsets.UTF_8);
		byte[] prefix = ("DSSEv1 " + typeBytes.length + " ").getBytes(StandardCharsets.UTF_8);
		byte[] infix = (" " + payloadBytes.length + " ").getBytes(StandardCharsets.UTF_8);
		byte[] out = new byte[prefix.length + typeBytes.length + infix.length + payloadBytes.length];
		int pos = 0;
		System.arraycopy(prefix, 0, out, pos, prefix.length);
		pos += prefix.length;
		System.arraycopy(typeBytes, 0, out, pos, typeBytes.length);
		pos += typeBytes.length;
		System.arraycopy(infix, 0, out, pos, infix.length);
		pos += infix.length;
		System.arraycopy(payloadBytes, 0, out, pos, payloadBytes.length);
		return out;
	}

	private static void verifyDsseSignature(byte[] pae, byte[] sig, X509Certificate leaf) throws Exception {
		if (!(leaf.getPublicKey() instanceof ECPublicKey ecKey)) {
			throw new SecurityException("unexpected key type in signing cert: " + leaf.getPublicKey().getAlgorithm());
		}
		// Select hash algorithm from key curve size (P-256 → SHA-256, P-384 → SHA-384).
		int fieldSize = ecKey.getParams().getCurve().getField().getFieldSize();
		String algorithm = fieldSize <= 256 ? "SHA256withECDSA" : "SHA384withECDSA";
		Signature verifier = Signature.getInstance(algorithm);
		verifier.initVerify(ecKey);
		verifier.update(pae);
		if (!verifier.verify(sig))
			throw new SecurityException("DSSE signature verification failed");
	}

	private static void verifySubjectHash(byte[] payloadBytes, String expectedHex, String version) {
		JsonObject stmt = JsonParser.parseString(new String(payloadBytes, StandardCharsets.UTF_8)).getAsJsonObject();
		if (stmt.has("subject") && stmt.get("subject").isJsonArray()) {
			for (var el : stmt.getAsJsonArray("subject")) {
				JsonObject digest = el.getAsJsonObject().getAsJsonObject("digest");
				if (digest != null && expectedHex.equalsIgnoreCase(digest.has("sha256") ? digest.get("sha256").getAsString() : "")) {
					return;
				}
			}
		}
		throw new SecurityException("attestation subject hash does not match edenmod-" + version + ".jar (expected sha256=" + expectedHex + ")");
	}

	// ── Download / install helpers ────────────────────────────────────────────

	private void download(String url, Path target) throws IOException {
		try {
			HttpResponse<Path> resp = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "EdenMod-updater").timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofFile(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
			if (resp.statusCode() != HTTP_OK) {
				throw new IOException("download returned HTTP " + resp.statusCode());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("download interrupted", e);
		}
	}

	private static Path currentJar() {
		try {
			return FabricLoader.getInstance().getModContainer(MOD_ID).flatMap(c -> c.getOrigin().getPaths().stream().findFirst()).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).filter(Files::isRegularFile).orElse(null);
		} catch (RuntimeException e) {
			// Some origins (nested jars/dev classpath) don't expose a single path.
			return null;
		}
	}

	/** Launch the detached {@link UpdateApplier} JVM (runs after this JVM exits). */
	private void spawnSwapper(Path oldJar, Path staged, Path newJar, Path helper) {
		try {
			ProcessBuilder pb = new ProcessBuilder(javaExecutable(), "-cp", helper.toString(), "tel.eden.mod.update.UpdateApplier", oldJar.toString(), staged.toString(), newJar.toString());
			pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			pb.redirectError(ProcessBuilder.Redirect.DISCARD);
			pb.start();
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Failed to launch the update applier", e);
		}
	}

	private static String javaExecutable() {
		Path bin = Path.of(System.getProperty("java.home", ""), "bin");
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		if (windows) {
			Path javaw = bin.resolve("javaw.exe");
			if (Files.isRegularFile(javaw))
				return javaw.toString();
			Path java = bin.resolve("java.exe");
			return Files.isRegularFile(java) ? java.toString() : "javaw";
		}
		Path java = bin.resolve("java");
		return Files.isRegularFile(java) ? java.toString() : "java";
	}

	/** Best-effort removal of jars staged in {@code edenDir} by an already-applied update. */
	private static void sweepStaleStaging(Path edenDir) {
		try (java.util.stream.Stream<Path> files = Files.list(edenDir)) {
			files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException e) {
					// Possibly still locked by an in-flight applier; leave it.
				}
			});
		} catch (IOException e) {
			LOGGER.debug("Could not sweep stale update staging", e);
		}
	}
}
