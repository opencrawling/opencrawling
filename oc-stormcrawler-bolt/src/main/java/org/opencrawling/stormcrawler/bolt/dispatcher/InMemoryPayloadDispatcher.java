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
package org.opencrawling.stormcrawler.bolt.dispatcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory implementation of PayloadDispatcher for testing and embedded runtime validation.
 */
public class InMemoryPayloadDispatcher implements PayloadDispatcher {

    public record DispatchedItem(String documentId, String action, String jsonPayload) {}

    private final List<DispatchedItem> dispatchedItems = new CopyOnWriteArrayList<>();
    private volatile boolean initialized = false;
    private volatile boolean closed = false;

    @Override
    public void init(Map<String, Object> conf) {
        this.initialized = true;
    }

    @Override
    public void dispatch(String documentId, String action, String jsonPayload) {
        dispatchedItems.add(new DispatchedItem(documentId, action, jsonPayload));
    }

    public List<DispatchedItem> getDispatchedItems() {
        return Collections.unmodifiableList(dispatchedItems);
    }

    public void clear() {
        dispatchedItems.clear();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        this.closed = true;
    }
}
