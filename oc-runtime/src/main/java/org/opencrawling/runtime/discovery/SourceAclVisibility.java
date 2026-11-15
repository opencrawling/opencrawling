/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.runtime.discovery;

import org.opencrawling.core.connector.discovery.VisibilityFilter;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.authorization.DocumentAccessGate;
import org.opencrawling.runtime.authorization.MetadataAuthorizationProvider;

import java.util.List;
import java.util.stream.IntStream;

/**
 * What a caller may see of a repository before anything is indexed: the metadata rules applied to each item's
 * <em>source</em> ACL, through a {@link DocumentAccessGate} on {@link MetadataAuthorizationProvider}, whatever
 * authorization mode the runtime is configured with.
 *
 * <p>A relationship provider such as OpenFGA only knows documents that have been indexed, so it would hide every
 * folder and every document not ingested yet. The cost: group memberships held only in OpenFGA are not taken into
 * account here, so a caller may see less than it may read, never more. An item with no ACL is hidden.
 */
public final class SourceAclVisibility {

    private static final DocumentAccessGate GATE = new DocumentAccessGate(new MetadataAuthorizationProvider());

    private SourceAclVisibility() {
    }

    /** Shows the nodes {@code caller} may read by the metadata rules on their source ACL. */
    public static VisibilityFilter forCaller(Caller caller) {
        return nodes -> {
            List<Integer> readable = GATE.filter(caller, IntStream.range(0, nodes.size()).boxed().toList(),
                    index -> nodes.get(index).toResourceRef());
            boolean[] visible = new boolean[nodes.size()];
            readable.forEach(index -> visible[index] = true);
            return visible;
        };
    }
}
