package tel.eden.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class TlsPolicyTest {
	/**
	 * Parses a PEM from a Java text block. Indentation is stripped line by line on purpose: the text
	 * block keeps whatever indent the code formatter leaves between its content and its closing
	 * delimiter, and JDK 21's PEM parser rejects a header line that starts with whitespace ("Empty
	 * input") where JDK 25 accepts it - which once passed locally and failed in CI.
	 */
	private static X509Certificate parse(String pem) throws Exception {
		String flat = pem.lines().map(String::strip).filter(line -> !line.isEmpty()).collect(Collectors.joining("\n", "", "\n"));
		return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(flat.getBytes(StandardCharsets.US_ASCII)));
	}

	/** Records whether the wrapped (default) trust manager was ever consulted. */
	private static final class RecordingTrustManager extends X509ExtendedTrustManager {
		int serverChecks;

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) {
			serverChecks++;
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
			serverChecks++;
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
			serverChecks++;
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) {
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}
	}

	/** A default trust manager that accepts anything and holds the given roots, as a JVM's store would. */
	private static class StoreTrustManager extends X509ExtendedTrustManager {
		private final X509Certificate[] store;
		int serverChecks;

		StoreTrustManager(X509Certificate... store) {
			this.store = store;
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			serverChecks++;
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
			serverChecks++;
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
			serverChecks++;
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) {
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return store;
		}
	}

	private static Set<String> pinTo(X509Certificate... roots) {
		Set<String> pins = new HashSet<>();
		for (X509Certificate root : roots)
			pins.add(TlsPolicy.spkiSha256(root));
		return pins;
	}

	@Test
	void theBuiltInPinsAreExactlyLetsEncryptsFourRoots() throws Exception {
		Set<String> roots = pinTo(parse(PinnedCerts.ISRG_ROOT_X1), parse(PinnedCerts.ISRG_ROOT_X2), parse(PinnedCerts.ISRG_ROOT_YE), parse(PinnedCerts.ISRG_ROOT_YR));

		assertEquals(4, roots.size());
		assertEquals(roots, TlsPolicy.PINNED_ROOT_SPKI_SHA256);
		assertEquals("eden.tel", TlsPolicy.PINNED_DOMAIN);
	}

	@Test
	void onlyOurDomainAndItsSubdomainsAreInScope() {
		for (String name : new String[]{"eden.tel", "bridge.eden.tel", "BRIDGE.Eden.Tel", "*.eden.tel", "a.b.eden.tel"})
			assertTrue(TlsPolicy.isDomainOrSubdomain(name, "eden.tel"), name);
		for (String name : new String[]{"noteden.tel", "eden.tel.evil.example", "eden.telx", "xeden.tel", "example.com", ""})
			assertFalse(TlsPolicy.isDomainOrSubdomain(name, "eden.tel"), name);
	}

	@Test
	void aCertificateIsInScopeByItsDnsNames() throws Exception {
		assertTrue(TlsPolicy.coversDomain(parse(PinnedCerts.GOOD_LEAF), "eden.tel"));
		assertTrue(TlsPolicy.coversDomain(parse(PinnedCerts.ROGUE_EDEN_LEAF), "eden.tel"));
		assertFalse(TlsPolicy.coversDomain(parse(PinnedCerts.ROGUE_OTHER_LEAF), "eden.tel"));
		assertFalse(TlsPolicy.coversDomain(parse(PinnedCerts.PINNED_CA), "eden.tel"));
	}

	@Test
	void ourDomainPassesWhenItsChainLeadsToAPinnedRoot() throws Exception {
		X509Certificate ca = parse(PinnedCerts.PINNED_CA);
		StoreTrustManager base = new StoreTrustManager();
		X509ExtendedTrustManager policy = TlsPolicy.wrap(base, pinTo(ca), "eden.tel");
		X509Certificate[] chain = {parse(PinnedCerts.GOOD_LEAF), ca};

		policy.checkServerTrusted(chain, "ECDHE_ECDSA");
		policy.checkServerTrusted(chain, "ECDHE_ECDSA", (Socket) null);
		policy.checkServerTrusted(chain, "ECDHE_ECDSA", (SSLEngine) null);
		assertEquals(3, base.serverChecks);
	}

	@Test
	void ourDomainPassesWhenTheServerLeavesOutARootThatTheStoreHolds() throws Exception {
		X509Certificate ca = parse(PinnedCerts.PINNED_CA);
		X509ExtendedTrustManager policy = TlsPolicy.wrap(new StoreTrustManager(ca), pinTo(ca), "eden.tel");

		policy.checkServerTrusted(new X509Certificate[]{parse(PinnedCerts.GOOD_LEAF)}, "ECDHE_ECDSA");
	}

	@Test
	void aForgedCertificateForOurDomainIsRefusedEvenThoughTheDefaultStoreAcceptsIt() throws Exception {
		X509Certificate pinned = parse(PinnedCerts.PINNED_CA);
		X509Certificate rogue = parse(PinnedCerts.ROGUE_CA);
		StoreTrustManager base = new StoreTrustManager(rogue, pinned);
		X509ExtendedTrustManager policy = TlsPolicy.wrap(base, pinTo(pinned), "eden.tel");
		X509Certificate[] forged = {parse(PinnedCerts.ROGUE_EDEN_LEAF), rogue};

		assertThrows(CertificateException.class, () -> policy.checkServerTrusted(forged, "ECDHE_ECDSA"));
		assertThrows(CertificateException.class, () -> policy.checkServerTrusted(forged, "ECDHE_ECDSA", (Socket) null));
		assertThrows(CertificateException.class, () -> policy.checkServerTrusted(forged, "ECDHE_ECDSA", (SSLEngine) null));
	}

	@Test
	void appendingAPinnedCertificateToAForgedChainDoesNotHelp() throws Exception {
		X509Certificate pinned = parse(PinnedCerts.PINNED_CA);
		X509Certificate rogue = parse(PinnedCerts.ROGUE_CA);
		X509ExtendedTrustManager policy = TlsPolicy.wrap(new StoreTrustManager(rogue, pinned), pinTo(pinned), "eden.tel");

		for (X509Certificate[] chain : new X509Certificate[][]{{parse(PinnedCerts.ROGUE_EDEN_LEAF), rogue, pinned}, {parse(PinnedCerts.ROGUE_EDEN_LEAF), pinned, rogue}})
			assertThrows(CertificateException.class, () -> policy.checkServerTrusted(chain, "ECDHE_ECDSA"));
	}

	@Test
	void otherHostsAreNotPinned() throws Exception {
		X509Certificate rogue = parse(PinnedCerts.ROGUE_CA);
		StoreTrustManager base = new StoreTrustManager(rogue);
		X509ExtendedTrustManager policy = TlsPolicy.wrap(base, pinTo(parse(PinnedCerts.PINNED_CA)), "eden.tel");

		policy.checkServerTrusted(new X509Certificate[]{parse(PinnedCerts.ROGUE_OTHER_LEAF), rogue}, "ECDHE_ECDSA");
		assertEquals(1, base.serverChecks);
	}

	@Test
	void theDefaultStoresVerdictStillComesFirst() throws Exception {
		X509Certificate ca = parse(PinnedCerts.PINNED_CA);
		X509ExtendedTrustManager failing = new StoreTrustManager(ca) {
			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
				throw new CertificateException("hostname mismatch");
			}
		};
		X509ExtendedTrustManager policy = TlsPolicy.wrap(failing, pinTo(ca), "eden.tel");

		CertificateException e = assertThrows(CertificateException.class, () -> policy.checkServerTrusted(new X509Certificate[]{parse(PinnedCerts.GOOD_LEAF), ca}, "ECDHE_ECDSA"));
		assertEquals("hostname mismatch", e.getMessage());
	}

	@Test
	void denyListMatchesTheResearchersDataExactly() throws Exception {
		assertEquals(8, BlacklistedCerts.INTERMEDIATES.length);
		Set<String> keysInData = new HashSet<>();
		for (String[] entry : BlacklistedCerts.INTERMEDIATES) {
			String spki = TlsPolicy.spkiSha256(parse(entry[2]));
			assertTrue(TlsPolicy.DENIED_SPKI_SHA256.contains(spki), entry[0] + " (" + entry[1] + ") is not denied: " + spki);
			keysInData.add(spki);
		}
		// Every key in the data is denied (above) and nothing else is: catches a typo'd or stray hash.
		assertEquals(8, keysInData.size(), "the fixtures are expected to be one certificate per distinct key");
		assertEquals(keysInData, TlsPolicy.DENIED_SPKI_SHA256);
	}

	@Test
	void theCrossSigningRootItselfStaysTrusted() throws Exception {
		// AAA Certificate Services still anchors legitimate Sectigo chains; only the compromised keys under it are denied.
		assertFalse(TlsPolicy.isDenied(parse(BlacklistedCerts.AAA_ROOT)));
	}

	@Test
	void rejectsAnyChainContainingADeniedKeyAndNeverConsultsTheDefaultStore() throws Exception {
		X509Certificate denied = parse(BlacklistedCerts.INTERMEDIATES[0][2]);
		X509Certificate innocent = parse(BlacklistedCerts.AAA_ROOT);
		RecordingTrustManager base = new RecordingTrustManager();
		X509ExtendedTrustManager policy = TlsPolicy.wrap(base);

		// leaf-first chains as a server would send them: [leaf, intermediate...]; position must not matter
		X509Certificate[] deniedSecond = {innocent, denied};
		X509Certificate[] deniedFirst = {denied, innocent};
		for (X509Certificate[] chain : new X509Certificate[][]{deniedSecond, deniedFirst}) {
			assertThrows(CertificateException.class, () -> policy.checkServerTrusted(chain, "ECDHE_ECDSA"));
			assertThrows(CertificateException.class, () -> policy.checkServerTrusted(chain, "ECDHE_ECDSA", (Socket) null));
			assertThrows(CertificateException.class, () -> policy.checkServerTrusted(chain, "ECDHE_ECDSA", (SSLEngine) null));
		}
		assertEquals(0, base.serverChecks, "a denied chain must be rejected before the default trust manager is asked");
	}

	@Test
	void passesCleanChainsThroughToTheDefaultTrustManager() throws Exception {
		X509Certificate[] clean = {parse(BlacklistedCerts.AAA_ROOT)};
		RecordingTrustManager base = new RecordingTrustManager();
		X509ExtendedTrustManager policy = TlsPolicy.wrap(base);

		policy.checkServerTrusted(clean, "ECDHE_ECDSA");
		policy.checkServerTrusted(clean, "ECDHE_ECDSA", (Socket) null);
		policy.checkServerTrusted(clean, "ECDHE_ECDSA", (SSLEngine) null);

		assertEquals(3, base.serverChecks);
	}

	@Test
	void buildsAUsableContext() {
		assertNotNull(TlsPolicy.context());
		assertNotNull(TlsPolicy.context().getSocketFactory());
		assertNotNull(TlsPolicy.newHttpClientBuilder().build());
	}

	// --- Real network: needs internet and the researchers' public test hosts. Run with EDENMOD_NETWORK_TESTS=1 ---

	private static String fetch(String url) throws Exception {
		HttpClient client = TlsPolicy.newHttpClientBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		return Integer.toString(client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "EDENMOD_NETWORK_TESTS", matches = "1")
	void liveRejectsTheCompromisedChains() {
		for (String host : new String[]{"dv-g3b-emudhra", "dv-g3b-aaa-emudhra"}) {
			Exception e = assertThrows(Exception.class, () -> fetch("https://" + host + ".we-have-a-problem.com/"));
			Throwable t = e;
			while (t != null && !(t instanceof SSLException))
				t = t.getCause();
			assertNotNull(t, host + " must fail with a TLS error, not " + e);
			assertTrue(t.getMessage().contains("eMudhra"), host + ": " + t.getMessage());
		}
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "EDENMOD_NETWORK_TESTS", matches = "1")
	void liveStillReachesLegitimateHosts() throws Exception {
		assertEquals("200", fetch("https://github.com/"));
	}

	@Test
	void buildsContextsWithAndWithoutRevocationChecking() {
		assertNotNull(TlsPolicy.buildContext(false).getSocketFactory());
		assertNotNull(TlsPolicy.buildContext(true).getSocketFactory());
	}

	private static HttpClient revocationCheckingClient() {
		return HttpClient.newBuilder().sslContext(TlsPolicy.buildContext(true)).connectTimeout(Duration.ofSeconds(30)).build();
	}

	private static String send(HttpClient client, String url) throws Exception {
		return Integer.toString(client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
	}

	/**
	 * Regression: with {@code -Dcom.sun.net.ssl.checkRevocation=true} the JDK hard-failed every
	 * OCSP-only certificate ("Could not determine revocation status"), which broke the updater and
	 * attestation checks against GitHub. The context used for that mode must still connect.
	 */
	@Test
	@EnabledIfEnvironmentVariable(named = "EDENMOD_NETWORK_TESTS", matches = "1")
	void liveRevocationCheckingStillReachesOcspOnlyHosts() throws Exception {
		HttpClient client = revocationCheckingClient();
		assertEquals("200", send(client, "https://api.github.com/"));
		assertEquals("200", send(client, "https://github.com/"));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "EDENMOD_NETWORK_TESTS", matches = "1")
	void liveRevocationCheckingStillRejectsARevokedCertificate() {
		try {
			String status = send(revocationCheckingClient(), "https://revoked.badssl.com/");
			org.junit.jupiter.api.Assertions.fail("a revoked certificate was accepted (HTTP " + status + ")");
		} catch (Exception e) {
			Throwable t = e;
			boolean revoked = false;
			while (t != null) {
				revoked |= String.valueOf(t.getMessage()).toLowerCase().contains("revoked");
				t = t.getCause();
			}
			// If the third-party test site is unreachable we learn nothing; only a clean "revoked" counts as a pass.
			org.junit.jupiter.api.Assumptions.assumeTrue(revoked, "revoked.badssl.com unreachable or failed for another reason: " + e);
		}
	}
}
