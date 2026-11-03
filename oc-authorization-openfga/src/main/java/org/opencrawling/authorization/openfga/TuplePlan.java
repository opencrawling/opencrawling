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

import org.opencrawling.core.security.EffectiveAcl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The tuple changes that bring one document's relationships from what OpenFGA holds to what its current
 * permissions say.
 *
 * <p>A change that fits in one OpenFGA write (at most {@link #MAX_TUPLES_PER_WRITE} tuples) is applied as a
 * single transaction. A larger one is applied in steps ordered so that the document is never readable by
 * more callers than either its old or its new permissions allow: add blocks, add grants, remove stale
 * grants, remove stale blocks.
 */
public record TuplePlan(List<DocumentTuple> writes, List<DocumentTuple> deletes) {

    /** OpenFGA's default limit of tuples per write request (writes plus deletes). */
    public static final int MAX_TUPLES_PER_WRITE = 100;

    public TuplePlan {
        writes = List.copyOf(writes);
        deletes = List.copyOf(deletes);
    }

    /** The tuples that express a document's effective permissions. */
    public static Set<DocumentTuple> desired(String documentId, EffectiveAcl acl) {
        String object = OpenFgaIdentifiers.documentObject(documentId);
        Set<DocumentTuple> tuples = new LinkedHashSet<>();
        for (String identity : acl.allowedRead()) {
            addEntry(tuples, object, OpenFgaIdentifiers.GRANTEE, identity);
        }
        for (String identity : acl.deniedRead()) {
            addEntry(tuples, object, OpenFgaIdentifiers.BLOCKED, identity);
        }
        return tuples;
    }

    /** The changes from {@code current} to {@code desired}. */
    public static TuplePlan diff(Collection<DocumentTuple> current, Collection<DocumentTuple> desired) {
        Set<DocumentTuple> currentSet = new LinkedHashSet<>(current);
        Set<DocumentTuple> desiredSet = new LinkedHashSet<>(desired);
        List<DocumentTuple> writes = desiredSet.stream().filter(t -> !currentSet.contains(t)).toList();
        List<DocumentTuple> deletes = currentSet.stream().filter(t -> !desiredSet.contains(t)).toList();
        return new TuplePlan(writes, deletes);
    }

    public boolean isEmpty() {
        return writes.isEmpty() && deletes.isEmpty();
    }

    /**
     * The write requests to send, in order. Each step holds at most {@link #MAX_TUPLES_PER_WRITE} tuples.
     */
    public List<Step> steps() {
        if (writes.size() + deletes.size() <= MAX_TUPLES_PER_WRITE) {
            return isEmpty() ? List.of() : List.of(new Step(writes, deletes));
        }
        List<Step> steps = new ArrayList<>();
        chunk(steps, only(writes, OpenFgaIdentifiers.BLOCKED), true);
        chunk(steps, only(writes, OpenFgaIdentifiers.GRANTEE), true);
        chunk(steps, only(deletes, OpenFgaIdentifiers.GRANTEE), false);
        chunk(steps, only(deletes, OpenFgaIdentifiers.BLOCKED), false);
        return steps;
    }

    /** One OpenFGA write request. */
    public record Step(List<DocumentTuple> writes, List<DocumentTuple> deletes) {
        public Step {
            writes = List.copyOf(writes);
            deletes = List.copyOf(deletes);
        }
    }

    private static void addEntry(Set<DocumentTuple> tuples, String object, String relation, String identity) {
        if (EffectiveAcl.PUBLIC.equalsIgnoreCase(identity.trim())) {
            tuples.add(new DocumentTuple(object, relation, OpenFgaIdentifiers.EVERYONE));
            return;
        }
        for (String group : OpenFgaIdentifiers.aclEntryGroups(identity)) {
            tuples.add(new DocumentTuple(object, relation, OpenFgaIdentifiers.members(group)));
        }
    }

    private static List<DocumentTuple> only(List<DocumentTuple> tuples, String relation) {
        return tuples.stream().filter(t -> relation.equals(t.relation())).toList();
    }

    private static void chunk(List<Step> steps, List<DocumentTuple> tuples, boolean write) {
        for (int i = 0; i < tuples.size(); i += MAX_TUPLES_PER_WRITE) {
            List<DocumentTuple> part = tuples.subList(i, Math.min(i + MAX_TUPLES_PER_WRITE, tuples.size()));
            steps.add(write ? new Step(part, List.of()) : new Step(List.of(), part));
        }
    }
}
