package org.opencrawling.core.connector;

import org.opencrawling.core.document.RepositoryDocument;
import reactor.core.publisher.Mono;

public non-sealed interface OutputConnector extends Connector {
    Mono<Void> send(RepositoryDocument document);
}
