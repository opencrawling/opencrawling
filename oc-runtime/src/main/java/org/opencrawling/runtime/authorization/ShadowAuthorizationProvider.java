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
package org.opencrawling.runtime.authorization;

import org.opencrawling.core.security.AuthorizationProvider;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.RelationshipAuthorizationProvider;
import org.opencrawling.core.security.ResourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code shadow} mode: the stored ACL metadata decides, and the relationship provider is asked the same
 * question so that its answers can be compared before it is trusted. Disagreements and relationship errors
 * are logged and counted; they never change what the caller gets.
 */
public final class ShadowAuthorizationProvider implements AuthorizationProvider {

    private static final Logger log = LoggerFactory.getLogger(ShadowAuthorizationProvider.class);

    private final AuthorizationProvider primary;
    private final RelationshipAuthorizationProvider shadow;
    private final AtomicLong mismatches = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    public ShadowAuthorizationProvider(AuthorizationProvider primary, RelationshipAuthorizationProvider shadow) {
        this.primary = primary;
        this.shadow = shadow;
    }

    @Override
    public String name() {
        return "shadow(" + primary.name() + "|" + shadow.name() + ")";
    }

    @Override
    public boolean[] canRead(Caller caller, List<ResourceRef> candidates) {
        boolean[] decisions = primary.canRead(caller, candidates);
        try {
            boolean[] shadowDecisions = shadow.canRead(caller, candidates);
            for (int i = 0; i < decisions.length && i < shadowDecisions.length; i++) {
                if (decisions[i] != shadowDecisions[i]) {
                    mismatches.incrementAndGet();
                    log.warn("Authorization shadow mismatch for document '{}': {} says {}, {} says {}.",
                            candidates.get(i).documentId(), primary.name(), decisions[i], shadow.name(),
                            shadowDecisions[i]);
                }
            }
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            log.warn("Authorization shadow provider '{}' failed: {}", shadow.name(), e.getMessage());
        }
        return decisions;
    }

    /** Disagreements seen since startup. */
    public long mismatches() {
        return mismatches.get();
    }

    /** Shadow provider failures seen since startup. */
    public long errors() {
        return errors.get();
    }
}
