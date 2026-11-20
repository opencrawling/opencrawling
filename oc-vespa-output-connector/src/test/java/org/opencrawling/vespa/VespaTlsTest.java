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

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.vespa.config.VespaOutputProperties;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The HTTP calls to Vespa besides feeding present the configured client certificate and trust the configured CA. */
class VespaTlsTest {

    @TempDir
    Path dir;

    private HttpsServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void anEndpointRequiringAClientCertificateFromAPrivateCaAnswers() throws Exception {
        KeyPair serverKey = keyPair();
        X509Certificate serverCertificate = certificate("CN=vespa.test", serverKey, true);
        KeyPair clientKey = keyPair();
        X509Certificate clientCertificate = certificate("CN=opencrawling", clientKey, false);
        startServer(serverKey, serverCertificate, clientCertificate);

        Path ca = pem("ca.pem", serverCertificate);
        Path certificate = pem("client.pem", clientCertificate);
        Path key = pkcs8("client-key.pem", clientKey);
        VespaOutputProperties properties = VespaOutputProperties.builder()
                .endpoint(endpoint())
                .timeoutSeconds(5)
                .tlsEnabled(true)
                .tlsCertificate(certificate.toString())
                .tlsPrivateKey(key.toString())
                .tlsCaCertificates(ca.toString())
                .build();

        HttpResponse<String> response = VespaTls.httpClient(properties).send(
                HttpRequest.newBuilder(URI.create(endpoint() + "/search/")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        // Without the configured identity and trust, the same endpoint refuses the call.
        assertThatThrownBy(() -> HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(endpoint() + "/search/")).GET().build(), HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void keysInEitherPemFormatAreRead() throws Exception {
        KeyPair pair = keyPair();
        Path pkcs1 = dir.resolve("pkcs1.pem");
        try (JcaPEMWriter writer = new JcaPEMWriter(Files.newBufferedWriter(pkcs1))) {
            writer.writeObject(pair);
        }

        assertThat(VespaTls.privateKey(pkcs1).getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
        assertThat(VespaTls.privateKey(pkcs8("pkcs8.pem", pair)).getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
    }

    @Test
    void aMissingFileIsReportedWithTheEndpoint() {
        VespaOutputProperties properties = VespaOutputProperties.builder()
                .endpoint("https://vespa.test:8443")
                .timeoutSeconds(5)
                .tlsEnabled(true)
                .tlsCertificate(dir.resolve("absent.pem").toString())
                .tlsPrivateKey(dir.resolve("absent-key.pem").toString())
                .build();

        assertThatThrownBy(() -> VespaTls.sslContext(properties)).hasMessageContaining("https://vespa.test:8443");
    }

    private void startServer(KeyPair serverKey, X509Certificate serverCertificate, X509Certificate trustedClient)
            throws Exception {
        KeyStore identity = KeyStore.getInstance(KeyStore.getDefaultType());
        identity.load(null, null);
        identity.setKeyEntry("server", serverKey.getPrivate(), new char[0], new X509Certificate[]{serverCertificate});
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(identity, new char[0]);
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("client", trustedClient);
        TrustManagerFactory trusted = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trusted.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trusted.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters parameters = context.getDefaultSSLParameters();
                parameters.setNeedClientAuth(true);
                params.setSSLParameters(parameters);
            }
        });
        server.createContext("/search/", exchange -> {
            byte[] answer = "{\"root\":{}}".getBytes();
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
    }

    private String endpoint() {
        return "https://127.0.0.1:" + server.getAddress().getPort();
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(String subject, KeyPair pair, boolean forLocalhost) throws Exception {
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(new X500Name(subject),
                BigInteger.valueOf(now.toEpochMilli()), Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(1, ChronoUnit.DAYS)), new X500Name(subject), pair.getPublic());
        if (forLocalhost) {
            builder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1")));
        }
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
    }

    private Path pem(String name, Object object) throws Exception {
        StringWriter text = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(text)) {
            writer.writeObject(object);
        }
        return Files.writeString(dir.resolve(name), text.toString());
    }

    private Path pkcs8(String name, KeyPair pair) throws Exception {
        return pem(name, new JcaPKCS8Generator(pair.getPrivate(), null));
    }
}
