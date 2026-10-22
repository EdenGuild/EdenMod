package tel.eden.mod.net;

import java.io.IOException;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertificateException;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * TLS trust for every outbound connection the mod makes itself: the JVM's default trust store,
 * minus a deny-list of CA keys known to be compromised.
 *
 * <p>The mod runs in the player's own Java, whose {@code cacerts} we cannot patch. In October 2026
 * the private keys of eight eMudhra intermediate CAs were found to be trivially recoverable, so
 * anyone could mint certificates for any site that a JVM still trusts through them. Browsers
 * block these via revocation lists, but a plain JVM does not check revocation. Until the player's
 * Java ships the fix, this policy rejects any server chain that contains one of those keys.
 *
 * <p>Keys are matched by the SHA-256 of their {@code SubjectPublicKeyInfo}, not by certificate
 * fingerprint: each compromised key exists in two certificates (one signed by an emSign root and
 * one cross-signed by Sectigo's {@code AAA Certificate Services}), and a re-issue would change the
 * fingerprint but not the key. The roots themselves are <em>not</em> denied; their keys are not
 * compromised, and {@code AAA Certificate Services} still anchors legitimate Sectigo chains.
 *
 * <p>Source: eMudhra incident data (impacted-intermediates.json,
 * https://emudhra.we-have-a-problem.com/), all 16 certificates re-hashed locally and verified.
 * Only covers connections that use this class; Minecraft's own and other mods' are unaffected.
 *
 * <p>If the JVM runs with {@code -Dcom.sun.net.ssl.checkRevocation=true}, revocation is checked
 * here via OCSP and CRLs in soft-fail mode, instead of the JDK's CRL-only hard-fail behaviour that
 * breaks connections to OCSP-only certificates such as GitHub's (see
 * {@link #revocationAwareTrustManager}).
 */
public final class TlsPolicy {
	/** SHA-256 (upper-case hex) of the SubjectPublicKeyInfo of each compromised intermediate CA. */
	static final Set<String> DENIED_SPKI_SHA256 = Set.of("EBED6475C2C2D80FCB199F3FE8DD6E6B3CE60B77EB15D4E42C3DEC0C6F5B5105", // EM DV TLS CA - G3B
				"43BFFA19D6EF651451F6841E019931E96F6F2EAEFF28AB4B6F1CE4576DE2717B", // EM OV TLS CA - G3B
				"E0254C7FCDC265E2F0F8A428DABB75AB4AE039B4872D62F5EE57F0877D5A8A41", // EM EV TLS CA - G3B
				"49DD353DC2B919A3770645117D0E60C16115E7D1BC259EEFE734B00EA434FE5F", // EM DV TLS CA - G3C
				"63ECEFEE431D49523BC1136C4E72D0206A477086CAF606CD5CF025686C109CF8", // EM OV TLS CA - G3C
				"5C0660A2B164910D8FF589C18E14552E9E0EC237DCEFE2CB736D41A4811F81A0", // EM EV TLS CA - G3C
				"EC4FFA1AD0AC88081314B7AAFFEF6A91F97CD57291BB5BA686C5415F5EB4F6B6", // EM OV CS CA - G3B (code signing)
				"92C063D67E8F14DB71D288E11266FFDAF69CBF115F7BAB14F3BC2F68D25E0E30" // EM OV CS CA - G3C (code signing)
	);

	private static final SSLContext CONTEXT = buildContext(revocationCheckingRequested());

	private TlsPolicy() {
	}

	/** An {@link SSLContext} that trusts the JVM default store minus the deny-list. */
	public static SSLContext context() {
		return CONTEXT;
	}

	/** {@link HttpClient#newBuilder()} preconfigured with this trust policy. */
	public static HttpClient.Builder newHttpClientBuilder() {
		return HttpClient.newBuilder().sslContext(CONTEXT);
	}

	/**
	 * {@link URL#openConnection()} with this trust policy applied to HTTPS connections. Plain
	 * HTTP URLs are returned unchanged.
	 */
	public static URLConnection open(URL url) throws IOException {
		URLConnection conn = url.openConnection();
		if (conn instanceof HttpsURLConnection https)
			https.setSSLSocketFactory(CONTEXT.getSocketFactory());
		return conn;
	}

	/** Whether {@code cert}'s public key is on the deny-list. */
	static boolean isDenied(X509Certificate cert) {
		return DENIED_SPKI_SHA256.contains(spkiSha256(cert));
	}

	/** Throws if any certificate in {@code chain} is on the deny-list. */
	static void rejectDenied(X509Certificate[] chain) throws CertificateException {
		if (chain == null)
			return;
		for (X509Certificate cert : chain) {
			if (cert != null && isDenied(cert))
				throw new CertificateException("Untrusted CA key (compromised eMudhra intermediate): " + cert.getSubjectX500Principal().getName());
		}
	}

	static String spkiSha256(X509Certificate cert) {
		try {
			return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getPublicKey().getEncoded()));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	/** Wraps {@code delegate} so deny-listed chains are rejected before it is consulted. */
	static X509ExtendedTrustManager wrap(X509ExtendedTrustManager delegate) {
		return new DenyListTrustManager(delegate);
	}

	/** Whether the JVM was started with {@code -Dcom.sun.net.ssl.checkRevocation=true}. */
	private static boolean revocationCheckingRequested() {
		return Boolean.getBoolean("com.sun.net.ssl.checkRevocation");
	}

	/**
	 * Fails closed: if the default trust manager cannot be built there is no safe fallback, and
	 * silently using an unfiltered context would defeat the point of this class.
	 *
	 * @param revocationChecking whether to check certificate revocation (see
	 *        {@link #revocationAwareTrustManager})
	 */
	static SSLContext buildContext(boolean revocationChecking) {
		try {
			X509ExtendedTrustManager base = defaultTrustManager();
			if (revocationChecking)
				base = revocationAwareTrustManager(base);
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, new TrustManager[]{wrap(base)}, null);
			return context;
		} catch (Exception e) {
			throw new IllegalStateException("Cannot build the TLS trust policy", e);
		}
	}

	private static X509ExtendedTrustManager defaultTrustManager() throws GeneralSecurityException {
		TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		factory.init((KeyStore) null);
		for (TrustManager tm : factory.getTrustManagers()) {
			if (tm instanceof X509ExtendedTrustManager extended)
				return extended;
		}
		throw new IllegalStateException("No X509ExtendedTrustManager in the default trust store");
	}

	/**
	 * The same trust anchors as the JVM default, but with revocation checking that works for today's
	 * certificates.
	 *
	 * <p>With {@code -Dcom.sun.net.ssl.checkRevocation=true} the JDK's own trust manager hard-fails
	 * whenever it cannot determine a certificate's status, and by default it can only use CRLs. Many
	 * CAs now issue certificates with an OCSP responder and no CRL distribution point (GitHub's
	 * Sectigo certificate is one), so every such connection dies with "Could not determine revocation
	 * status", including the updater and attestation checks. Here an explicit
	 * {@link PKIXRevocationChecker} uses OCSP with a CRL fallback and treats a status that genuinely
	 * cannot be obtained (responder down, no endpoint published) as unknown rather than fatal
	 * ({@code SOFT_FAIL}). A certificate that is actually revoked is still rejected.
	 */
	private static X509ExtendedTrustManager revocationAwareTrustManager(X509ExtendedTrustManager defaults) throws GeneralSecurityException {
		Set<TrustAnchor> anchors = new HashSet<>();
		for (X509Certificate issuer : defaults.getAcceptedIssuers())
			anchors.add(new TrustAnchor(issuer, null));
		PKIXBuilderParameters params = new PKIXBuilderParameters(anchors, new X509CertSelector());
		PKIXRevocationChecker checker = (PKIXRevocationChecker) CertPathBuilder.getInstance("PKIX").getRevocationChecker();
		checker.setOptions(EnumSet.of(PKIXRevocationChecker.Option.SOFT_FAIL));
		params.addCertPathChecker(checker);
		TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
		factory.init(new CertPathTrustManagerParameters(params));
		for (TrustManager tm : factory.getTrustManagers()) {
			if (tm instanceof X509ExtendedTrustManager extended)
				return extended;
		}
		throw new IllegalStateException("No X509ExtendedTrustManager from the PKIX trust manager factory");
	}

	private static final class DenyListTrustManager extends X509ExtendedTrustManager {
		private final X509ExtendedTrustManager delegate;

		DenyListTrustManager(X509ExtendedTrustManager delegate) {
			this.delegate = delegate;
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			rejectDenied(chain);
			delegate.checkServerTrusted(chain, authType);
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
			rejectDenied(chain);
			delegate.checkServerTrusted(chain, authType, socket);
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
			rejectDenied(chain);
			delegate.checkServerTrusted(chain, authType, engine);
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			delegate.checkClientTrusted(chain, authType);
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
			delegate.checkClientTrusted(chain, authType, socket);
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
			delegate.checkClientTrusted(chain, authType, engine);
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return delegate.getAcceptedIssuers();
		}
	}
}
