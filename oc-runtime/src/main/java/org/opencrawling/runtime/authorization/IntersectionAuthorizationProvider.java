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
import org.opencrawling.core.security.ResourceRef;

import java.util.List;

/**
 * {@code intersection} mode: a document is readable only when both providers allow it. A failure of either
 * provider denies the whole request.
 */
public final class IntersectionAuthorizationProvider implements AuthorizationProvider {

    private final AuthorizationProvider first;
    private final AuthorizationProvider second;

    public IntersectionAuthorizationProvider(AuthorizationProvider first, AuthorizationProvider second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public String name() {
        return "intersection(" + first.name() + "&" + second.name() + ")";
    }

    @Override
    public boolean[] canRead(Caller caller, List<ResourceRef> candidates) {
        boolean[] a = first.canRead(caller, candidates);
        boolean[] b = second.canRead(caller, candidates);
        boolean[] decisions = new boolean[candidates.size()];
        for (int i = 0; i < decisions.length; i++) {
            decisions[i] = i < a.length && i < b.length && a[i] && b[i];
        }
        return decisions;
    }

    @Override
    public boolean metadataPrefilterSafe() {
        return first.metadataPrefilterSafe() && second.metadataPrefilterSafe();
    }
}
