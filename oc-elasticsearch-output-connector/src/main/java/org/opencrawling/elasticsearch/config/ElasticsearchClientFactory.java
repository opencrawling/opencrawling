/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.elasticsearch.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.TransportUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;

/**
 * Builds the {@link ElasticsearchClient} from {@link ElasticsearchOutputProperties}. The one place that knows about
 * hosts, authentication and TLS, shared by the Spring bean and a hand-built connector's lazy client.
 */
public final class ElasticsearchClientFactory {

    private ElasticsearchClientFactory() {
    }

    public static ElasticsearchClient create(ElasticsearchOutputProperties properties) {
        List<URI> hosts = properties.hostUris();
        SSLContext sslContext = sslContext(properties.ssl());
        return ElasticsearchClient.of(b -> {
            b.hosts(hosts).jsonMapper(new JacksonJsonpMapper());
            if (properties.hasApiKey()) {
                b.apiKey(properties.apiKey());
            } else if (properties.hasBasicAuth()) {
                b.usernameAndPassword(properties.username(), properties.password());
            }
            if (sslContext != null) {
                b.sslContext(sslContext);
            }
            String mediaType = compatibleMediaType(properties.compatibleWith());
            if (mediaType != null) {
                b.transportOptions(o -> o.setHeader("Accept", mediaType).setHeader("Content-Type", mediaType));
            }
            return b;
        });
    }

    /**
     * The versioned media type that asks the server for REST API compatibility with major {@code version}, or
     * {@code null} to keep the client's own (its major, 9). The 9.x client otherwise sends {@code compatible-with=9},
     * which Elasticsearch 8.x rejects with HTTP 400; {@code compatible-with=8} is accepted by 8.x natively and by
     * 9.x through REST API compatibility.
     */
    static String compatibleMediaType(Integer version) {
        if (version == null || version <= 0) {
            return null;
        }
        return "application/vnd.elasticsearch+json; compatible-with=" + version;
    }

    /** The TLS context for the configured trust, or {@code null} for the JVM default. */
    static SSLContext sslContext(ElasticsearchOutputProperties.Ssl ssl) {
        if (ssl == null) {
            return null;
        }
        if (ssl.caFingerprint() != null && !ssl.caFingerprint().isBlank()) {
            return TransportUtils.sslContextFromCaFingerprint(ssl.caFingerprint().trim());
        }
        if (ssl.trustStorePath() != null && !ssl.trustStorePath().isBlank()) {
            try (InputStream in = Files.newInputStream(Path.of(ssl.trustStorePath()))) {
                KeyStore trustStore = KeyStore.getInstance(
                        ssl.trustStoreType() == null || ssl.trustStoreType().isBlank() ? "PKCS12" : ssl.trustStoreType());
                char[] password = ssl.trustStorePassword() == null ? new char[0] : ssl.trustStorePassword().toCharArray();
                trustStore.load(in, password);
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, tmf.getTrustManagers(), null);
                return context;
            } catch (Exception e) {
                throw new IllegalStateException("Cannot load Elasticsearch trust store " + ssl.trustStorePath(), e);
            }
        }
        return null;
    }
}
