/*
 * Copyright © 2026 Rafa Haro (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencrawling.vespa;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.opencrawling.vespa.config.VespaOutputProperties;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.Reader;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * TLS for the HTTP calls OpenCrawling makes to Vespa besides feeding (document lookups, searches): the same client
 * certificate, key and CA certificates the feed client uses ({@code ConnectorFactory}, {@code VespaClientConfig}), so
 * an endpoint secured with mutual TLS or a private CA answers them too.
 */
public final class VespaTls {

    private VespaTls() {}

    /** An HTTP client for the endpoint, with the configured client identity and trust when TLS is enabled. */
    public static HttpClient httpClient(VespaOutputProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties != null && properties.timeoutSeconds() > 0
                        ? properties.timeoutSeconds() : 30));
        if (properties != null && properties.tlsEnabled()) {
            builder.sslContext(sslContext(properties));
        }
        return builder.build();
    }

    /**
     * The SSL context for the configured PEM files: the client certificate and key when both are set, the CA
     * certificates when set (the JVM's default trust otherwise).
     */
    public static SSLContext sslContext(VespaOutputProperties properties) {
        try {
            KeyManagerFactory keyManagers = null;
            if (present(properties.tlsCertificate()) && present(properties.tlsPrivateKey())) {
                List<X509Certificate> chain = certificates(Path.of(properties.tlsCertificate()));
                KeyStore identity = KeyStore.getInstance(KeyStore.getDefaultType());
                identity.load(null, null);
                identity.setKeyEntry("client", privateKey(Path.of(properties.tlsPrivateKey())), new char[0],
                        chain.toArray(new X509Certificate[0]));
                keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(identity, new char[0]);
            }
            TrustManagerFactory trustManagers = null;
            if (present(properties.tlsCaCertificates())) {
                KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
                trust.load(null, null);
                int i = 0;
                for (X509Certificate ca : certificates(Path.of(properties.tlsCaCertificates()))) {
                    trust.setCertificateEntry("ca-" + i++, ca);
                }
                trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagers.init(trust);
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers != null ? keyManagers.getKeyManagers() : null,
                    trustManagers != null ? trustManagers.getTrustManagers() : null, null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot build the TLS context for Vespa endpoint " + properties.endpoint()
                    + ": " + e.getMessage(), e);
        }
    }

    static List<X509Certificate> certificates(Path file) throws IOException, GeneralSecurityException {
        List<X509Certificate> certificates = new ArrayList<>();
        JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
        try (Reader reader = Files.newBufferedReader(file); PEMParser parser = new PEMParser(reader)) {
            for (Object item = parser.readObject(); item != null; item = parser.readObject()) {
                if (item instanceof X509CertificateHolder holder) {
                    certificates.add(converter.getCertificate(holder));
                }
            }
        }
        if (certificates.isEmpty()) {
            throw new GeneralSecurityException("no certificate in " + file);
        }
        return certificates;
    }

    static PrivateKey privateKey(Path file) throws IOException, GeneralSecurityException {
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        try (Reader reader = Files.newBufferedReader(file); PEMParser parser = new PEMParser(reader)) {
            for (Object item = parser.readObject(); item != null; item = parser.readObject()) {
                if (item instanceof PEMKeyPair pair) {
                    return converter.getPrivateKey(pair.getPrivateKeyInfo());
                }
                if (item instanceof PrivateKeyInfo info) {
                    return converter.getPrivateKey(info);
                }
            }
        }
        throw new GeneralSecurityException("no private key in " + file);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
