package com.ghostbrowser.browser;

import android.content.Context;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Bundle;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * Old Android releases ship root stores that predate many current CAs, so the WebView rejects
 * legitimate sites as "issuer not trusted". Firefox avoids this by carrying its own roots; the
 * WebView can't be given extra roots, so we re-check the exact certificate it rejected:
 *
 *  1. Offline path validation (preferred): fetch the intermediates named in the certificate's
 *     Authority Information Access "CA Issuers" field, then run Java's PKIX validator against the
 *     bundled Mozilla roots and check the hostname against the certificate's SAN entries.
 *  2. Fallback for certificates without AIA: our own TLS handshake trusting the bundled roots,
 *     requiring the server's leaf to be byte-identical to the WebView's. (Android 4.4's platform
 *     TLS lacks AES-GCM, so many modern servers refuse this handshake; hence it is only a fallback.)
 *
 * Only when one of these passes is the WebView told to proceed; otherwise the user sees a warning.
 */
final class CertRescue {
    private static final String TAG = "GhostCert";
    private static final byte[] CA_ISSUERS_OID = {0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02};

    private static Set<TrustAnchor> anchors;
    private static Set<String> anchorSubjects;
    private static SSLContext tlsContext;
    private static final Map<String, byte[]> verified = new HashMap<String, byte[]>();
    private static final Map<String, X509Certificate> aiaCache = new HashMap<String, X509Certificate>();

    private CertRescue() {}

    /** Only "untrusted issuer" errors can be fixed by extra roots. */
    static boolean applicable(SslError e) {
        return e.getPrimaryError() == SslError.SSL_UNTRUSTED;
    }

    /** Blocking; call off the UI thread. */
    static boolean verify(Context c, String host, int port, SslCertificate seen) {
        byte[] der = leafDer(seen);
        if (host == null || der == null) return false;
        String key = host + ":" + port;
        synchronized (verified) {
            byte[] ok = verified.get(key);
            if (ok != null && Arrays.equals(ok, der)) return true;
        }
        boolean ok;
        try {
            load(c);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate leaf = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
            if (!matchesHost(leaf, host)) {
                Log.w(TAG, host + ": certificate is not valid for this host");
                return false;
            }
            ok = validateViaAia(cf, leaf, host) || validateViaTls(host, port, der);
        } catch (Exception e) {
            Log.w(TAG, host + ": not rescued: " + e);
            ok = false;
        }
        if (ok) {
            synchronized (verified) {
                verified.put(key, der);
            }
        }
        return ok;
    }

    // ---------------------------------------------------------------- 1. offline PKIX via AIA

    private static boolean validateViaAia(CertificateFactory cf, X509Certificate leaf, String host) {
        try {
            List<X509Certificate> path = new ArrayList<X509Certificate>();
            path.add(leaf);
            X509Certificate cur = leaf;
            for (int depth = 0; depth < 5; depth++) {
                if (anchorSubjects.contains(cur.getIssuerX500Principal().getName())) break;
                String url = caIssuersUrl(cur);
                if (url == null) {
                    Log.w(TAG, host + ": no CA Issuers URL at depth " + depth);
                    return false;
                }
                X509Certificate next = fetchIssuer(cf, url);
                if (next == null) return false;
                if (next.getSubjectX500Principal().equals(next.getIssuerX500Principal())) {
                    // self-signed: only acceptable if it is one of the bundled roots
                    if (!anchorSubjects.contains(next.getSubjectX500Principal().getName())) return false;
                    break;
                }
                path.add(next);
                cur = next;
            }
            CertPath cp = cf.generateCertPath(path);
            PKIXParameters params = new PKIXParameters(anchors);
            params.setRevocationEnabled(false); // the system WebView on these releases doesn't check revocation either
            CertPathValidator.getInstance("PKIX").validate(cp, params);
            return true;
        } catch (Exception e) {
            Log.w(TAG, host + ": AIA path validation failed: " + e);
            return false;
        }
    }

    private static X509Certificate fetchIssuer(CertificateFactory cf, String url) throws Exception {
        synchronized (aiaCache) {
            X509Certificate hit = aiaCache.get(url);
            if (hit != null) return hit;
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        byte[] body;
        try {
            body = readAll(conn.getInputStream(), 64 * 1024);
        } finally {
            conn.disconnect();
        }
        Collection<? extends Certificate> certs = cf.generateCertificates(new ByteArrayInputStream(body)); // DER or PKCS#7
        if (certs.isEmpty()) return null;
        X509Certificate cert = (X509Certificate) certs.iterator().next();
        synchronized (aiaCache) {
            aiaCache.put(url, cert);
        }
        return cert;
    }

    /** Pulls the http CA Issuers URI out of the Authority Information Access extension. */
    static String caIssuersUrl(X509Certificate cert) {
        byte[] ext = cert.getExtensionValue("1.3.6.1.5.5.7.1.1");
        if (ext == null) return null;
        for (int i = 0; i + CA_ISSUERS_OID.length + 2 < ext.length; i++) {
            boolean match = true;
            for (int j = 0; j < CA_ISSUERS_OID.length && match; j++) match = ext[i + j] == CA_ISSUERS_OID[j];
            if (!match) continue;
            int p = i + CA_ISSUERS_OID.length;
            if ((ext[p] & 0xFF) != 0x86) continue; // [6] uniformResourceIdentifier
            int len = ext[p + 1] & 0xFF;
            if (len >= 0x80 || p + 2 + len > ext.length) continue;
            String uri = new String(ext, p + 2, len);
            if (uri.startsWith("http://")) return uri;
        }
        return null;
    }

    static boolean matchesHost(X509Certificate cert, String host) throws Exception {
        String h = host.toLowerCase(Locale.US);
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        if (sans == null) return false;
        for (List<?> san : sans) {
            if (san.size() < 2 || !(san.get(1) instanceof String)) continue;
            int type = (Integer) san.get(0);
            String name = ((String) san.get(1)).toLowerCase(Locale.US);
            if (type == 7 && name.equals(h)) return true; // IP address
            if (type != 2) continue;                      // DNS name
            if (name.equals(h)) return true;
            if (name.startsWith("*.")) {
                int dot = h.indexOf('.');
                if (dot > 0 && h.substring(dot).equals(name.substring(1))) return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 2. TLS fallback

    private static boolean validateViaTls(String host, int port, byte[] seenDer) {
        SSLSocket s = null;
        try {
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), 8000);
            raw.setSoTimeout(8000);
            s = (SSLSocket) tlsContext.getSocketFactory().createSocket(raw, host, port, true);
            s.setEnabledProtocols(s.getSupportedProtocols()); // TLS 1.1/1.2 are off by default before 5.0
            try {
                Method m = s.getClass().getMethod("setHostname", String.class);
                m.invoke(s, host); // SNI
            } catch (Exception ignored) {
            }
            s.startHandshake(); // chain validated against bundled roots
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) return false;
            Certificate[] chain = s.getSession().getPeerCertificates();
            return chain.length > 0 && Arrays.equals(chain[0].getEncoded(), seenDer);
        } catch (Exception e) {
            Log.w(TAG, host + ": TLS fallback failed: " + e.getMessage());
            return false;
        } finally {
            if (s != null) try { s.close(); } catch (IOException ignored) {}
        }
    }

    // ---------------------------------------------------------------- roots

    /** SslCertificate exposes the DER only via its saved state on API < 29. */
    static byte[] leafDer(SslCertificate cert) {
        if (cert == null) return null;
        Bundle b = SslCertificate.saveState(cert);
        return b == null ? null : b.getByteArray("x509-certificate");
    }

    private static synchronized void load(Context c) throws Exception {
        if (anchors != null) return;
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        Set<TrustAnchor> ta = new HashSet<TrustAnchor>();
        Set<String> subjects = new HashSet<String>();
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        String pem = new String(readAll(c.getAssets().open("cacerts.pem"), 4 * 1024 * 1024), "US-ASCII");
        int i = 0, n = 0;
        while ((i = pem.indexOf("-----BEGIN CERTIFICATE-----", i)) >= 0) {
            int end = pem.indexOf("-----END CERTIFICATE-----", i);
            if (end < 0) break;
            end += "-----END CERTIFICATE-----".length();
            try {
                X509Certificate cert = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(pem.substring(i, end).getBytes("US-ASCII")));
                ks.setCertificateEntry("root" + (n++), cert);
                ta.add(new TrustAnchor(cert, null));
                subjects.add(cert.getSubjectX500Principal().getName());
            } catch (Exception ignored) {
                // a root using an algorithm this Android build can't parse; skip it
            }
            i = end;
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        tlsContext = ctx;
        anchorSubjects = subjects;
        anchors = ta;
        Log.i(TAG, "loaded " + n + " bundled roots");
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                if (out.size() > limit) throw new IOException("response too large");
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
