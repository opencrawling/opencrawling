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
package org.opencrawling.core.claimcheck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.util.List;

/**
 * Composite ClaimCheckStore implementation that delegates operations to matching store implementations.
 */
public class CompositeClaimCheckStore implements ClaimCheckStore {

    private static final Logger log = LoggerFactory.getLogger(CompositeClaimCheckStore.class);

    private final ClaimCheckStore primaryStore;
    private final List<ClaimCheckStore> stores;

    public CompositeClaimCheckStore(ClaimCheckStore primaryStore, List<ClaimCheckStore> stores) {
        this.primaryStore = primaryStore;
        this.stores = stores != null ? stores : List.of(primaryStore);
    }

    public ClaimCheckStore getPrimaryStore() {
        return primaryStore;
    }

    @Override
    public URI put(String id, InputStream content, long contentLength, String contentType) throws Exception {
        return primaryStore.put(id, content, contentLength, contentType);
    }

    @Override
    public InputStream get(URI claimCheckUri) throws Exception {
        for (ClaimCheckStore store : stores) {
            if (store.supports(claimCheckUri)) {
                return store.get(claimCheckUri);
            }
        }
        log.warn("No registered ClaimCheckStore explicitly supports URI {}, falling back to primary store", claimCheckUri);
        return primaryStore.get(claimCheckUri);
    }

    @Override
    public void delete(URI claimCheckUri) throws Exception {
        for (ClaimCheckStore store : stores) {
            if (store.supports(claimCheckUri)) {
                store.delete(claimCheckUri);
                return;
            }
        }
        log.warn("No registered ClaimCheckStore explicitly supports URI {}, falling back to primary store", claimCheckUri);
        primaryStore.delete(claimCheckUri);
    }

    @Override
    public boolean supports(URI claimCheckUri) {
        return stores.stream().anyMatch(s -> s.supports(claimCheckUri));
    }
}
