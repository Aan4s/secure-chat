package org.example;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * Builds {@link SSLContext} instances configured for TLS 1.3.
 *
 * <p>Use {@link #serverContext} on the server (private key + certificate) and
 * {@link #clientContext} on the client (truststore of CAs / pinned server cert).
 */
public final class TlsContextFactory {

    private static final String PROTOCOL = "TLSv1.3";
    private static final String STORE_TYPE = "PKCS12";

    private TlsContextFactory() {}

    public static SSLContext serverContext(Path keystorePath, char[] keystorePassword)
            throws GeneralSecurityException, IOException {
        KeyStore ks = load(keystorePath, keystorePassword);
        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keystorePassword);
        SSLContext ctx = SSLContext.getInstance(PROTOCOL);
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    public static SSLContext clientContext(Path truststorePath, char[] truststorePassword)
            throws GeneralSecurityException, IOException {
        KeyStore ts = load(truststorePath, truststorePassword);
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance(PROTOCOL);
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static KeyStore load(Path path, char[] password)
            throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(STORE_TYPE);
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, password);
        }
        return store;
    }
}
