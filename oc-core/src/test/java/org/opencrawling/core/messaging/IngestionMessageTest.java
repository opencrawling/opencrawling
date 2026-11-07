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
package org.opencrawling.core.messaging;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.SecurityConfig;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionMessageTest {

    private static IngestionMessage message(String acl, SecurityConfig security) {
        return new IngestionMessage("doc-1", "fake://doc-1", Map.of(), acl, security, "2026-01-01T00:00:00Z",
                null, null, Map.of(), null, null, null);
    }

    @Test
    void aMessageWithoutSecurityIsNeverPublic() {
        IngestionMessage message = message("", null);

        assertThat(message.security().permissions()).isEmpty();
        EffectiveAcl acl = EffectiveAcl.resolve(message.security(), message.acl());
        assertThat(acl.allowedRead()).isEmpty();
        assertThat(acl.isPublic()).isFalse();
    }

    @Test
    void withoutSecurityTheAclStringDecides() {
        EffectiveAcl named = EffectiveAcl.resolve(message("alice,group:finance", null).security(), "alice,group:finance");
        assertThat(named.allowedRead()).containsExactly("alice", "group:finance");

        // A source that says "public" in its acl is still public: only silence is no longer read as public.
        EffectiveAcl declared = EffectiveAcl.resolve(message("public", null).security(), "public");
        assertThat(declared.isPublic()).isTrue();
    }

    @Test
    void anExplicitPublicPlaceholderIsUnchanged() {
        IngestionMessage message = message("", SecurityConfig.createPublic());

        assertThat(EffectiveAcl.resolve(message.security(), message.acl()).isPublic()).isTrue();
        assertThat(message.security().permissions()).hasSize(1);
        assertThat(List.copyOf(message.security().permissions())).isEqualTo(SecurityConfig.createPublic().permissions());
    }
}
