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
import dev.openfga.sdk.api.configuration.ClientListStoresOptions;
import dev.openfga.sdk.api.model.AuthorizationModel;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.Store;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Resolves the store and authorization model the client works against, provisioning them when allowed.
 */
public final class OpenFgaBootstrap {

    private static final Logger log = LoggerFactory.getLogger(OpenFgaBootstrap.class);

    public static final String MODEL_RESOURCE = "/openfga/model.json";

    private OpenFgaBootstrap() {
    }

    /** The bundled authorization model. */
    public static WriteAuthorizationModelRequest bundledModel() {
        try (InputStream in = OpenFgaBootstrap.class.getResourceAsStream(MODEL_RESOURCE)) {
            Objects.requireNonNull(in, MODEL_RESOURCE + " is missing from the classpath");
            return JsonMapper.builder().build().readValue(in, WriteAuthorizationModelRequest.class);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + MODEL_RESOURCE, e);
        }
    }

    /** The store and model a client was pointed at. */
    public record Target(String storeId, String modelId) {
    }

    /**
     * Points the client at the configured store and model, creating them when auto-provisioning is on.
     */
    public static Target configure(OpenFgaClient client, OpenFgaProperties properties) throws Exception {
        long timeout = properties.timeout().toMillis();
        String storeId = properties.storeId();
        if (OpenFgaProperties.blank(storeId)) {
            if (!properties.autoProvision()) {
                throw new IllegalStateException("opencrawling.security.authorization.openfga.store-id is required "
                        + "unless auto-provision is enabled.");
            }
            storeId = findOrCreateStore(client, properties.storeName(), timeout);
        }
        client.setStoreId(storeId.trim());

        String modelId = properties.modelId();
        if (OpenFgaProperties.blank(modelId)) {
            if (!properties.autoProvision()) {
                throw new IllegalStateException("opencrawling.security.authorization.openfga.model-id is required "
                        + "unless auto-provision is enabled.");
            }
            modelId = latestMatchingOrWrite(client, timeout);
        }
        client.setAuthorizationModelId(modelId.trim());
        log.info("OpenFGA authorization uses store {} and model {} at {}.", storeId, modelId, properties.apiUrl());
        return new Target(storeId.trim(), modelId.trim());
    }

    private static String findOrCreateStore(OpenFgaClient client, String name, long timeout) throws Exception {
        String token = null;
        do {
            ClientListStoresOptions options = new ClientListStoresOptions().name(name).pageSize(100);
            if (token != null) {
                options.continuationToken(token);
            }
            var page = client.listStores(options).get(timeout, TimeUnit.MILLISECONDS);
            for (Store store : page.getStores()) {
                if (name.equals(store.getName())) {
                    return store.getId();
                }
            }
            token = page.getContinuationToken();
        } while (token != null && !token.isBlank());
        String id = client.createStore(new CreateStoreRequest().name(name)).get(timeout, TimeUnit.MILLISECONDS).getId();
        log.info("Created OpenFGA store '{}' ({}).", name, id);
        return id;
    }

    private static String latestMatchingOrWrite(OpenFgaClient client, long timeout) throws Exception {
        WriteAuthorizationModelRequest bundled = bundledModel();
        AuthorizationModel latest = null;
        try {
            latest = client.readLatestAuthorizationModel().get(timeout, TimeUnit.MILLISECONDS).getAuthorizationModel();
        } catch (Exception e) {
            log.debug("No authorization model in the store yet: {}", e.getMessage());
        }
        if (latest != null && sameTypes(latest.getTypeDefinitions(), bundled.getTypeDefinitions())) {
            return latest.getId();
        }
        String id = client.writeAuthorizationModel(bundled).get(timeout, TimeUnit.MILLISECONDS).getAuthorizationModelId();
        log.info("Wrote OpenFGA authorization model {}.", id);
        return id;
    }

    /**
     * Compares type definitions ignoring the fields the server fills with empty strings (module, condition,
     * computed userset object) where the bundled model has none.
     */
    static boolean sameTypes(List<?> stored, List<?> bundled) {
        JsonMapper mapper = JsonMapper.builder().build();
        return canonical(mapper.valueToTree(stored)).equals(canonical(mapper.valueToTree(bundled)));
    }

    private static JsonNode canonical(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode copy = object.objectNode();
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                JsonNode value = field.getValue();
                if (value == null || value.isNull() || (value.isString() && value.asString().isEmpty())) {
                    continue;
                }
                copy.set(field.getKey(), canonical(value));
            }
            return copy;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = array.arrayNode();
            array.forEach(item -> copy.add(canonical(item)));
            return copy;
        }
        return node;
    }
}
