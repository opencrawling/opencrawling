package org.opencrawling.core.connector;

import org.opencrawling.core.document.RepositoryDocument;
import reactor.core.publisher.Flux;

public non-sealed interface RepositoryConnector extends Connector {
    Flux<RepositoryDocument> scan(String basePath);
}
