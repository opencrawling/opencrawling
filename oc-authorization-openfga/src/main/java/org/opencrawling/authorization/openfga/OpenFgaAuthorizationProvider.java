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
import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.configuration.ClientBatchCheckOptions;
import org.opencrawling.core.security.AuthorizationUnavailableException;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.RelationshipAuthorizationProvider;
import org.opencrawling.core.security.ResourceRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Decides read access with OpenFGA: one BatchCheck of {@code can_read} per distinct document, with the
 * caller's principal and roles passed as contextual group memberships.
 *
 * <p>Fails closed: a candidate without a document id is denied, and any check error or timeout raises
 * {@link AuthorizationUnavailableException} for the whole request.
 */
public final class OpenFgaAuthorizationProvider implements RelationshipAuthorizationProvider {

    public static final String NAME = "openfga";

    /** OpenFGA's limit of contextual tuples per check. */
    public static final int MAX_CONTEXTUAL_TUPLES = 100;

    private final OpenFgaClient client;
    private final OpenFgaProperties properties;

    public OpenFgaAuthorizationProvider(OpenFgaClient client, OpenFgaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean[] canRead(Caller caller, List<ResourceRef> candidates) {
        boolean[] decisions = new boolean[candidates.size()];
        Map<String, List<Integer>> positionsByObject = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            ResourceRef ref = candidates.get(i);
            if (ref == null || ref.documentId() == null || ref.documentId().isBlank()) {
                continue;
            }
            positionsByObject.computeIfAbsent(OpenFgaIdentifiers.documentObject(ref.documentId()),
                    k -> new ArrayList<>()).add(i);
        }
        if (positionsByObject.isEmpty()) {
            return decisions;
        }

        String user = OpenFgaIdentifiers.userObject(caller.principal());
        List<ClientTupleKey> context = contextualTuples(user, caller);
        List<String> objects = new ArrayList<>(positionsByObject.keySet());
        List<ClientBatchCheckItem> checks = new ArrayList<>(objects.size());
        for (int k = 0; k < objects.size(); k++) {
            checks.add(new ClientBatchCheckItem()
                    .user(user)
                    .relation(OpenFgaIdentifiers.CAN_READ)
                    ._object(objects.get(k))
                    .contextualTuples(context)
                    .correlationId("c" + k));
        }

        List<ClientBatchCheckSingleResponse> results;
        try {
            results = client.batchCheck(ClientBatchCheckRequest.ofChecks(checks), new ClientBatchCheckOptions()
                            .maxBatchSize(properties.batchSize())
                            .maxParallelRequests(properties.maxParallelRequests()))
                    .get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)
                    .getResult();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthorizationUnavailableException("OpenFGA check interrupted; access denied.", e);
        } catch (Exception e) {
            throw new AuthorizationUnavailableException("OpenFGA check failed; access denied.", e);
        }

        Set<String> answered = new LinkedHashSet<>();
        for (ClientBatchCheckSingleResponse result : results) {
            if (result.getError() != null) {
                throw new AuthorizationUnavailableException("OpenFGA check error for " + result.getCorrelationId()
                        + ": " + result.getError().getMessage() + "; access denied.");
            }
            String correlationId = result.getCorrelationId();
            int k = correlationId != null && correlationId.startsWith("c") ? parse(correlationId.substring(1)) : -1;
            if (k < 0 || k >= objects.size()) {
                throw new AuthorizationUnavailableException("OpenFGA returned an unknown correlation id '"
                        + correlationId + "'; access denied.");
            }
            answered.add(correlationId);
            if (result.isAllowed()) {
                for (int position : positionsByObject.get(objects.get(k))) {
                    decisions[position] = true;
                }
            }
        }
        if (answered.size() != objects.size()) {
            throw new AuthorizationUnavailableException("OpenFGA answered " + answered.size() + " of "
                    + objects.size() + " checks; access denied.");
        }
        return decisions;
    }

    /** The caller's memberships, sent with every check: its principal's and roles' groups. */
    static List<ClientTupleKey> contextualTuples(String user, Caller caller) {
        Set<String> groups = new LinkedHashSet<>();
        if (caller.principal() != null && !caller.principal().isBlank()) {
            groups.add(OpenFgaIdentifiers.principalGroup(caller.principal()));
        }
        for (String role : caller.groups()) {
            groups.add(OpenFgaIdentifiers.roleGroup(role));
        }
        if (groups.size() > MAX_CONTEXTUAL_TUPLES) {
            throw new AuthorizationUnavailableException("The caller has " + groups.size()
                    + " identity forms, more than the " + MAX_CONTEXTUAL_TUPLES
                    + " OpenFGA accepts per check; access denied.");
        }
        List<ClientTupleKey> tuples = new ArrayList<>(groups.size());
        for (String group : groups) {
            tuples.add(new ClientTupleKey().user(user).relation(OpenFgaIdentifiers.MEMBER)._object(group));
        }
        return tuples;
    }

    private static int parse(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
