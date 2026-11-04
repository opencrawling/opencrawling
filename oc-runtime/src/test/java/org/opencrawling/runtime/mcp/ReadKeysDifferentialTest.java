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
package org.opencrawling.runtime.mcp;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.ReadKeys;
import org.opencrawling.core.security.SecurityConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pgvector pre-filter matches a chunk when the caller's read keys meet the chunk's. It must agree with
 * {@link McpAclEvaluator}: exactly on read grants, and never hide a document the evaluator allows.
 */
class ReadKeysDifferentialTest {

    private static final List<String> NAMES = List.of("alice", "Alice", "ALICE", "bob", "finance", "Finance", "hr", "public", "");
    private static final List<String> PREFIXES = List.of("", "", "user:", "USER:", "User:", "group:", "GROUP:", " ", "mfiles:");
    private static final List<String> ACCESS = List.of("read", "write", "admin", "deny", "READ");
    private static final List<String> TYPES = List.of("USER", "GROUP", "public", "acl");

    @Test
    void keysMatchExactlyWhenTheEvaluatorMatchesAGrant() {
        Random random = new Random(45224);
        for (int i = 0; i < 100_000; i++) {
            List<String> allowed = new ArrayList<>();
            int n = random.nextInt(4);
            for (int j = 0; j < n; j++) {
                allowed.add(identity(random));
            }
            String principal = random.nextInt(5) == 0 ? null : identity(random);
            List<String> groups = groups(random);

            boolean evaluator = McpAclEvaluator.matchesAnyIdentity(allowed, principal, groups);
            boolean keys = !Collections.disjoint(ReadKeys.forAllowed(allowed), ReadKeys.forCaller(principal, groups));
            assertThat(keys).as("allowed=%s principal=%s groups=%s", allowed, principal, groups).isEqualTo(evaluator);
        }
    }

    @Test
    void thePreFilterNeverHidesADocumentTheEvaluatorAllows() {
        Random random = new Random(203);
        int allowedSeen = 0;
        for (int i = 0; i < 100_000; i++) {
            List<PermissionRule> rules = new ArrayList<>();
            int n = random.nextInt(4);
            for (int j = 0; j < n; j++) {
                rules.add(new PermissionRule(identity(random), TYPES.get(random.nextInt(TYPES.size())), null,
                        ACCESS.get(random.nextInt(ACCESS.size()))));
            }
            String legacyAcl = random.nextBoolean() ? null : identity(random) + "," + identity(random);
            EffectiveAcl acl = EffectiveAcl.resolve(new SecurityConfig(true, rules), legacyAcl);

            // The chunk as the pgvector writer stores it.
            Map<String, Object> metadata = new HashMap<>();
            if (acl.security() != null) {
                metadata.put("security", acl.security());
            }
            metadata.put("acl", legacyAcl);
            metadata.put("security_allowed_read", acl.allowedRead());
            metadata.put("security_denied_read", acl.deniedRead());

            String principal = random.nextInt(5) == 0 ? null : identity(random);
            List<String> groups = groups(random);
            if (McpAclEvaluator.isAccessible(metadata, principal, groups)) {
                allowedSeen++;
                assertThat(ReadKeys.forAllowed(acl.allowedRead()))
                        .as("rules=%s acl=%s principal=%s groups=%s", rules, legacyAcl, principal, groups)
                        .containsAnyElementsOf(ReadKeys.forCaller(principal, groups));
            }
        }
        assertThat(allowedSeen).isGreaterThan(1_000);
    }

    private static String identity(Random random) {
        return PREFIXES.get(random.nextInt(PREFIXES.size())) + NAMES.get(random.nextInt(NAMES.size()));
    }

    private static List<String> groups(Random random) {
        List<String> groups = new ArrayList<>();
        int n = random.nextInt(3);
        for (int j = 0; j < n; j++) {
            String group = identity(random);
            // Callers never carry blank groups: Caller drops them before any provider sees them.
            if (!group.isBlank()) {
                groups.add(group);
            }
        }
        return groups;
    }
}
