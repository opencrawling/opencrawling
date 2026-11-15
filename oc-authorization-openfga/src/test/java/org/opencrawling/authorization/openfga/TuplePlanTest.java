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

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TuplePlanTest {

    private static EffectiveAcl acl(List<String> allowed, List<String> denied) {
        List<PermissionRule> rules = new ArrayList<>();
        allowed.forEach(id -> rules.add(new PermissionRule(id, null, id, "read")));
        denied.forEach(id -> rules.add(new PermissionRule(id, null, id, "deny")));
        return EffectiveAcl.resolve(new SecurityConfig(false, rules), null);
    }

    @Test
    void grantsAndDenialsBecomeUserOrGroupNameTuples() {
        String doc = OpenFgaIdentifiers.documentObject("d1");
        Set<DocumentTuple> tuples = TuplePlan.desired("d1", acl(List.of("group:Finance", "public"), List.of("bob")));

        assertThat(tuples).containsExactly(
                new DocumentTuple(doc, "grantee", "group:g_finance#member"),
                new DocumentTuple(doc, "grantee", "user:*"),
                new DocumentTuple(doc, "blocked", "group:u_bob#member"),
                new DocumentTuple(doc, "blocked", "group:g_bob#member"));
    }

    @Test
    void typedUserAndGroupRulesOnlyGenerateTheirOwnTuples() {
        String doc = OpenFgaIdentifiers.documentObject("d2");
        EffectiveAcl acl = EffectiveAcl.resolve(new SecurityConfig(false, List.of(
                new PermissionRule("alice", "user", "Alice", "read"),
                new PermissionRule("finance", "group", "Finance", "read"),
                new PermissionRule("bob", "user", "Bob", "deny")
        )), null);
        Set<DocumentTuple> tuples = TuplePlan.desired("d2", acl);

        assertThat(tuples).containsExactly(
                new DocumentTuple(doc, "grantee", "group:u_alice#member"),
                new DocumentTuple(doc, "grantee", "group:g_finance#member"),
                new DocumentTuple(doc, "blocked", "group:u_bob#member"));
    }

    @Test
    void diffWritesOnlyWhatChanged() {
        Set<DocumentTuple> before = TuplePlan.desired("d1", acl(List.of("a", "b"), List.of()));
        Set<DocumentTuple> after = TuplePlan.desired("d1", acl(List.of("b", "c"), List.of()));

        TuplePlan plan = TuplePlan.diff(before, after);

        assertThat(plan.writes()).extracting(DocumentTuple::user).containsExactly("group:u_c#member", "group:g_c#member");
        assertThat(plan.deletes()).extracting(DocumentTuple::user).containsExactly("group:u_a#member", "group:g_a#member");
        assertThat(plan.steps()).hasSize(1);
        assertThat(TuplePlan.diff(after, after).steps()).isEmpty();
    }

    @Test
    void largeChangesAreSplitInAFailClosedOrder() {
        List<String> oldGrants = new ArrayList<>();
        List<String> newGrants = new ArrayList<>();
        List<String> oldBlocks = new ArrayList<>();
        List<String> newBlocks = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            oldGrants.add("old-reader-" + i);
            newGrants.add("new-reader-" + i);
            oldBlocks.add("old-blocked-" + i);
            newBlocks.add("new-blocked-" + i);
        }
        Set<DocumentTuple> before = TuplePlan.desired("d1", acl(oldGrants, oldBlocks));
        Set<DocumentTuple> after = TuplePlan.desired("d1", acl(newGrants, newBlocks));

        List<TuplePlan.Step> steps = TuplePlan.diff(before, after).steps();

        assertThat(steps).allSatisfy(step ->
                assertThat(step.writes().size() + step.deletes().size()).isLessThanOrEqualTo(TuplePlan.MAX_TUPLES_PER_WRITE));
        // Order: add blocks, add grants, remove grants, remove blocks.
        List<String> phases = new ArrayList<>();
        for (TuplePlan.Step step : steps) {
            String phase = (step.writes().isEmpty() ? "remove " : "add ")
                    + (step.writes().isEmpty() ? step.deletes() : step.writes()).getFirst().relation();
            if (phases.isEmpty() || !phases.getLast().equals(phase)) {
                phases.add(phase);
            }
        }
        assertThat(phases).containsExactly("add blocked", "add grantee", "remove grantee", "remove blocked");

        // After every step, each identity form readable then was readable before or after the change.
        Set<DocumentTuple> state = new LinkedHashSet<>(before);
        Set<String> allowedBefore = readable(before);
        Set<String> allowedAfter = readable(after);
        for (TuplePlan.Step step : steps) {
            state.addAll(step.writes());
            step.deletes().forEach(state::remove);
            Set<String> allowedNow = readable(state);
            for (String user : allowedNow) {
                assertThat(allowedBefore.contains(user) || allowedAfter.contains(user))
                        .as("%s became readable in an intermediate step", user).isTrue();
            }
        }
        assertThat(state).isEqualTo(after);
    }

    /** Usersets granted and not blocked. */
    private static Set<String> readable(Set<DocumentTuple> tuples) {
        Set<String> granted = new HashSet<>();
        Set<String> blocked = new HashSet<>();
        for (DocumentTuple t : tuples) {
            (t.relation().equals("grantee") ? granted : blocked).add(t.user());
        }
        granted.removeAll(blocked);
        return granted;
    }
}
