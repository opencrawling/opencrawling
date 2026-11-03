/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.authorization.openfga;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientReadRequest;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.openfga.sdk.api.configuration.ClientReadOptions;
import dev.openfga.sdk.api.configuration.ClientWriteOptions;
import dev.openfga.sdk.api.model.Tuple;
import dev.openfga.sdk.api.model.WriteRequestDeletes;
import dev.openfga.sdk.api.model.WriteRequestWrites;
import org.opencrawling.core.security.EffectiveAcl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a document's OpenFGA tuples in step with its permissions: reads what the store holds for the
 * document, diffs it against {@link TuplePlan#desired} and applies the {@link TuplePlan#steps()}. Writes and
 * deletes are idempotent ({@code on_duplicate} / {@code on_missing} = ignore), so a message can be replayed.
 */
public class OpenFgaTupleWriter {

    private static final Logger log = LoggerFactory.getLogger(OpenFgaTupleWriter.class);

    private final OpenFgaClient client;
    private final OpenFgaProperties properties;

    public OpenFgaTupleWriter(OpenFgaClient client, OpenFgaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** Writes the tuples for a document's current permissions and removes stale ones. */
    public TuplePlan sync(String documentId, EffectiveAcl acl) throws Exception {
        return apply(documentId, TuplePlan.desired(documentId, acl));
    }

    /** Removes every grant and block of a deleted document. */
    public TuplePlan delete(String documentId) throws Exception {
        return apply(documentId, Set.of());
    }

    /** The grantee/blocked tuples OpenFGA holds for a document. */
    public List<DocumentTuple> read(String documentId) throws Exception {
        String object = OpenFgaIdentifiers.documentObject(documentId);
        List<DocumentTuple> tuples = new ArrayList<>();
        String token = null;
        do {
            ClientReadOptions options = new ClientReadOptions().pageSize(100);
            if (token != null) {
                options.continuationToken(token);
            }
            var page = client.read(new ClientReadRequest()._object(object), options)
                    .get(timeoutMillis(), TimeUnit.MILLISECONDS);
            for (Tuple tuple : page.getTuples()) {
                String relation = tuple.getKey().getRelation();
                if (OpenFgaIdentifiers.GRANTEE.equals(relation) || OpenFgaIdentifiers.BLOCKED.equals(relation)) {
                    tuples.add(new DocumentTuple(tuple.getKey().getObject(), relation, tuple.getKey().getUser()));
                }
            }
            token = page.getContinuationToken();
        } while (token != null && !token.isBlank());
        return tuples;
    }

    private TuplePlan apply(String documentId, Set<DocumentTuple> desired) throws Exception {
        TuplePlan plan = TuplePlan.diff(read(documentId), desired);
        ClientWriteOptions options = new ClientWriteOptions()
                .onDuplicate(WriteRequestWrites.OnDuplicateEnum.IGNORE)
                .onMissing(WriteRequestDeletes.OnMissingEnum.IGNORE);
        for (TuplePlan.Step step : plan.steps()) {
            ClientWriteRequest request = new ClientWriteRequest();
            if (!step.writes().isEmpty()) {
                request.writes(step.writes().stream().map(t -> new ClientTupleKey()
                        .user(t.user()).relation(t.relation())._object(t.object())).toList());
            }
            if (!step.deletes().isEmpty()) {
                request.deletes(step.deletes().stream().map(t -> new ClientTupleKeyWithoutCondition()
                        .user(t.user()).relation(t.relation())._object(t.object())).toList());
            }
            client.write(request, options).get(timeoutMillis(), TimeUnit.MILLISECONDS);
        }
        if (!plan.isEmpty()) {
            log.debug("Synced OpenFGA tuples for document {}: +{} -{}.", documentId,
                    plan.writes().size(), plan.deletes().size());
        }
        return plan;
    }

    private long timeoutMillis() {
        return properties.timeout().toMillis();
    }
}
