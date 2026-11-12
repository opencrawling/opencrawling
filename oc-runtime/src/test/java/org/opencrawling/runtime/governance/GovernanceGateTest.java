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
package org.opencrawling.runtime.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.governance.GovernanceDecision;
import org.opencrawling.core.governance.GovernanceSubject;
import org.opencrawling.core.governance.GovernanceUnavailableException;
import org.opencrawling.core.governance.PreEmbeddingGovernanceHook;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.runtime.governance.GovernanceGate.Status;
import org.opencrawling.runtime.governance.GovernanceProperties.OnRejectedUpdate;

class GovernanceGateTest {

    private static final Map<String, List<String>> METADATA = Map.of("title", List.of("Q3"), "acl", List.of("finance"),
            "security_allowed_read", List.of("finance"), "uri", List.of("file:///a"));

    private final List<String> calls = new ArrayList<>();

    private PreEmbeddingGovernanceHook hook(String name, Function<GovernanceSubject, GovernanceDecision> answer) {
        return new PreEmbeddingGovernanceHook() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public GovernanceDecision evaluate(GovernanceSubject subject) {
                calls.add(name + ":" + subject.text());
                return answer.apply(subject);
            }
        };
    }

    private static GovernanceGate.Result run(PreEmbeddingGovernanceHook... hooks) {
        return new GovernanceGate(List.of(hooks), OnRejectedUpdate.PURGE).evaluate("doc", "file:///a", "text", METADATA,
                SecurityConfig.createPublic());
    }

    @Test
    void hooksRunInOrderAndEachSeesTheLastSanitizedVersion() {
        GovernanceGate.Result result = run(
                hook("first", s -> GovernanceDecision.sanitize(s.text() + "+1", null, Map.of("n", "1"))),
                hook("second", s -> GovernanceDecision.approve(Map.of("checked", "yes"))),
                hook("third", s -> GovernanceDecision.sanitize(s.text() + "+3", null, Map.of())));

        assertThat(calls).containsExactly("first:text", "second:text+1", "third:text+1");
        assertThat(result.status()).isEqualTo(Status.SANITIZED);
        assertThat(result.embeds()).isTrue();
        assertThat(result.text()).isEqualTo("text+1+3");
        assertThat(result.metadata()).isEqualTo(METADATA);
        assertThat(result.audit()).containsEntry("first.n", "1").containsEntry("second.checked", "yes");
    }

    @Test
    void allApprovalsLeaveTheDocumentAsItIs() {
        GovernanceGate.Result result = run(hook("a", s -> GovernanceDecision.approve()), hook("b", s -> GovernanceDecision.approve()));

        assertThat(result.status()).isEqualTo(Status.APPROVED);
        assertThat(result.text()).isEqualTo("text");
        assertThat(result.metadata()).isEqualTo(METADATA);
    }

    @Test
    void theFirstRejectionEndsTheEvaluation() {
        GovernanceGate.Result result = run(hook("a", s -> GovernanceDecision.reject("credentials\nfound", Map.of())),
                hook("b", s -> GovernanceDecision.approve()));

        assertThat(calls).containsExactly("a:text");
        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.embeds()).isFalse();
        assertThat(result.decidedBy()).isEqualTo("a");
        assertThat(result.reason()).isEqualTo("credentials found");
        assertThat(result.text()).isNull();
    }

    @Test
    void aHookThatFailsOrGivesNoAnswerMeansNoDecision() {
        PreEmbeddingGovernanceHook unavailable = new PreEmbeddingGovernanceHook() {
            @Override
            public String name() {
                return "service";
            }

            @Override
            public GovernanceDecision evaluate(GovernanceSubject subject) throws GovernanceUnavailableException {
                throw new GovernanceUnavailableException("timed out");
            }
        };
        assertThat(run(unavailable, hook("b", s -> GovernanceDecision.approve())))
                .satisfies(r -> {
                    assertThat(r.status()).isEqualTo(Status.UNAVAILABLE);
                    assertThat(r.decidedBy()).isEqualTo("service");
                    assertThat(r.reason()).isEqualTo("timed out");
                });
        assertThat(calls).isEmpty();
        assertThat(run(hook("crash", s -> { throw new IllegalStateException("secret detail"); })))
                .satisfies(r -> {
                    assertThat(r.status()).isEqualTo(Status.UNAVAILABLE);
                    assertThat(r.reason()).doesNotContain("secret detail").contains("IllegalStateException");
                });
        assertThat(run(hook("silent", s -> null)).status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void protectedKeysKeepTheSourcesValues() {
        Map<String, List<String>> proposed = new LinkedHashMap<>();
        proposed.put("title", List.of("Q3 (masked)"));
        proposed.put("acl", List.of("public"));
        proposed.put("security_denied_read", List.of("nobody"));
        proposed.put("acl_ss", List.of("public"));
        proposed.put("documentId", List.of("other"));
        // "uri" and "security_allowed_read" are left out: they must come back anyway.

        GovernanceGate.Result result = run(hook("a", s -> GovernanceDecision.sanitize("t", proposed, Map.of())));

        assertThat(result.metadata()).containsExactlyInAnyOrderEntriesOf(Map.of("title", List.of("Q3 (masked)"),
                "acl", List.of("finance"), "security_allowed_read", List.of("finance"), "uri", List.of("file:///a")));
    }

    @Test
    void aSanitizedTextThatIsBlankIsARejection() {
        GovernanceGate.Result result = run(hook("a", s -> GovernanceDecision.sanitize("  ", null, Map.of())));

        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.reason()).contains("nothing was left");
    }

    @Test
    void reasonsAndAuditAreBounded() {
        Map<String, String> audit = new LinkedHashMap<>();
        for (int i = 0; i < 50; i++) {
            audit.put("k" + i, "v".repeat(500));
        }
        GovernanceGate.Result result = run(hook("a", s -> GovernanceDecision.reject("r".repeat(1000), audit)));

        assertThat(result.reason()).hasSizeLessThanOrEqualTo(203);
        assertThat(result.audit()).hasSizeLessThanOrEqualTo(32);
        assertThat(result.audit().values()).allSatisfy(v -> assertThat(v).hasSizeLessThanOrEqualTo(203));
    }

    @Test
    void aGateNeedsAHookAndKnowsWhatToDoWithRejectedUpdates() {
        assertThatThrownBy(() -> new GovernanceGate(List.of(), OnRejectedUpdate.PURGE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new GovernanceGate(List.of(hook("a", s -> null)), OnRejectedUpdate.PURGE).purgeOnReject()).isTrue();
        assertThat(new GovernanceGate(List.of(hook("a", s -> null)), OnRejectedUpdate.KEEP).purgeOnReject()).isFalse();
    }

    @Test
    void theProtectedKeys() {
        assertThat(List.of("acl", "security", "sys_acl", "documentId", "uri", "lastModified", "security_allowed_read",
                "security_read_keys", "acl_ss", "ois:provenance", "ois:governance", "ois:source",
                "ois:provenance.onBehalfOf", "ois:governance.decision", "ois:source.instance"))
                .allMatch(GovernanceGate::isProtectedKey);
        assertThat(List.of("title", "owner", "aclNote", "mimeType", "ois:other")).noneMatch(GovernanceGate::isProtectedKey);
    }

    @Test
    void decisionsKeepOnlyWhatTheirOutcomeUses() {
        assertThat(new GovernanceDecision(GovernanceDecision.Outcome.APPROVE, "t", Map.of("a", List.of("b")), "r", null))
                .isEqualTo(GovernanceDecision.approve());
        assertThatThrownBy(() -> GovernanceDecision.sanitize(null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(GovernanceDecision.reject("why", null).text()).isNull();
    }
}
