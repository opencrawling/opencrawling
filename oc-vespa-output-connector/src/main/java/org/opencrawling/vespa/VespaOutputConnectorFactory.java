/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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

import ai.vespa.feed.client.FeedClient;
import ai.vespa.feed.client.FeedClientBuilder;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.vespa.config.VespaOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** A Vespa output for one run, from its saved configuration (#319). */
public class VespaOutputConnectorFactory implements OutputConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(VespaOutputConnectorFactory.class);

    @Override
    public String className() {
        return VespaOutputConnector.class.getName();
    }

    @Override
    public OutputConnector create(Map<String, String> config, OutputContext context) {
        String endpoint = config.getOrDefault("vespaEndpoint", "http://localhost:8080");
        String namespace = config.getOrDefault("vespaNamespace", "opencrawling");
        String documentType = config.getOrDefault("vespaDocumentType", "opencrawling_chunk");
        int dimensions = OutputContext.integer(config, 1024, "vespaDimensions");
        int timeoutSeconds = OutputContext.integer(config, 30, "vespaTimeoutSeconds");
        boolean tlsEnabled = Boolean.parseBoolean(config.getOrDefault("vespaTlsEnabled", "false"));
        String tlsCertificate = config.getOrDefault("vespaTlsCertificate", "");
        String tlsPrivateKey = config.getOrDefault("vespaTlsPrivateKey", "");
        String tlsCaCertificates = config.getOrDefault("vespaTlsCaCertificates", "");

        FeedClientBuilder feedClientBuilder = FeedClientBuilder.create(URI.create(endpoint));
        if (tlsEnabled && !tlsCertificate.isBlank() && !tlsPrivateKey.isBlank()) {
            feedClientBuilder.setCertificate(Path.of(tlsCertificate), Path.of(tlsPrivateKey));
            if (!tlsCaCertificates.isBlank()) {
                feedClientBuilder.setCaCertificatesFile(Path.of(tlsCaCertificates));
            }
        }
        FeedClient feedClient = feedClientBuilder.build();

        VespaOutputProperties props = VespaOutputProperties.builder()
                .endpoint(endpoint)
                .namespace(namespace)
                .documentType(documentType)
                .dimensions(dimensions)
                .timeoutSeconds(timeoutSeconds)
                .tlsEnabled(tlsEnabled)
                .tlsCertificate(tlsCertificate.isBlank() ? null : tlsCertificate)
                .tlsPrivateKey(tlsPrivateKey.isBlank() ? null : tlsPrivateKey)
                .tlsCaCertificates(tlsCaCertificates.isBlank() ? null : tlsCaCertificates)
                .contentCluster(config.getOrDefault("vespaContentCluster", VespaOutputProperties.DEFAULT_CONTENT_CLUSTER))
                .build();

        log.info("Resolving dynamic Vespa output connector at endpoint '{}'", endpoint);
        // Unless the pipeline's writer feeds this destination, the run's documents go to it directly.
        return new VespaOutputConnector(feedClient, props, new VespaDocumentMapper(),
                context.service(EmbeddingModel.class), context.service(TextExtractionService.class),
                !pipelineWrites(context, props));
    }

    /**
     * Whether the runtime's pipeline writer feeds this Vespa destination: its output is Vespa with the same endpoint,
     * namespace and configured document type. Otherwise a RAG run sends the run's documents to it directly.
     *
     * <p>Which document type a chunk lands in depends on its embedding's actual length, which is not known here (the
     * usual sizes go to the dimension-specific types whatever type is configured), so only an identical configuration
     * counts as the same destination. A difference never loses documents; a run pointing at the runtime's endpoint
     * and namespace with another type stores its documents twice. This compares this process's configuration as
     * written: it cannot tell which writer consumes the embedded topic in a decoupled deployment (it assumes the
     * crawler and the writer share the output settings), nor that two host names reach the same server (then
     * documents are written both ways).
     */
    public static boolean pipelineWrites(OutputContext context, VespaOutputProperties run) {
        VespaOutputConnector pipeline = context.pipelineOutput(VespaOutputConnector.class);
        return pipeline != null && sameDestination(pipeline.getProperties(), run);
    }

    public static boolean sameDestination(VespaOutputProperties a, VespaOutputProperties b) {
        return a != null && b != null && Objects.equals(endpoint(a.endpoint()), endpoint(b.endpoint()))
                && Objects.equals(a.namespace(), b.namespace())
                && Objects.equals(a.documentType(), b.documentType());
    }

    private static String endpoint(String endpoint) {
        return endpoint != null && endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }
}
