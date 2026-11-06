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

import org.junit.jupiter.api.Test;
import org.opencrawling.core.governance.GovernanceDecision;
import org.opencrawling.core.governance.GovernanceSubject;
import org.opencrawling.core.governance.PreEmbeddingGovernanceHook;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The gate exists only when it is on, and a gate that is on but cannot be built stops startup. */
class GovernanceConfigurationTest {

    private static final String SECRET = "governance-shared-secret-0123456789abcdef";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GovernanceConfiguration.class);

    static class CustomHook implements PreEmbeddingGovernanceHook {
        private final String name;

        CustomHook(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public GovernanceDecision evaluate(GovernanceSubject subject) {
            return GovernanceDecision.approve();
        }
    }

    @Test
    void offByDefaultWithTheSettingsStillBound() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(GovernanceGate.class);
            GovernanceProperties properties = context.getBean(GovernanceProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getHooks()).isEmpty();
            assertThat(properties.getOnRejectedUpdate()).isEqualTo(GovernanceProperties.OnRejectedUpdate.PURGE);
            assertThat(properties.getPii().getTypes()).containsExactlyInAnyOrder(GovernanceProperties.Pii.Type.values());
            assertThat(properties.getPii().isMaskMetadata()).isTrue();
            assertThat(properties.getHttp().getMaxAttempts()).isEqualTo(3);
        });
    }

    @Test
    void theBuiltInHooksAndHookBeansRunInTheListedOrder() {
        runner.withBean("custom", PreEmbeddingGovernanceHook.class, () -> new CustomHook("axmirror"))
                .withPropertyValues("opencrawling.governance.enabled=true",
                        "opencrawling.governance.hooks=pii-masking,axmirror,http",
                        "opencrawling.governance.on-rejected-update=keep",
                        "opencrawling.governance.pii.types=email,iban",
                        "opencrawling.governance.http.url=https://governance.example.com/check",
                        "opencrawling.governance.http.secret=" + SECRET,
                        "opencrawling.governance.http.max-attempts=5")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GovernanceGate gate = context.getBean(GovernanceGate.class);
                    assertThat(gate.hookNames()).containsExactly("pii-masking", "axmirror", "http");
                    assertThat(gate.purgeOnReject()).isFalse();
                    GovernanceProperties properties = context.getBean(GovernanceProperties.class);
                    assertThat(properties.getPii().getTypes()).containsExactlyInAnyOrder(
                            GovernanceProperties.Pii.Type.EMAIL, GovernanceProperties.Pii.Type.IBAN);
                    assertThat(properties.getHttp().getMaxAttempts()).isEqualTo(5);
                });
    }

    @Test
    void theValuesInApplicationYmlBind() {
        runner.withPropertyValues("opencrawling.governance.enabled=true", "opencrawling.governance.hooks=",
                "opencrawling.governance.pii.types=email,payment-card,iban").run(context -> {
            assertThat(context).getFailure().rootCause().hasMessageContaining("at least one hook");
        });
        runner.withPropertyValues("opencrawling.governance.enabled=true", "opencrawling.governance.hooks=pii-masking",
                "opencrawling.governance.on-rejected-update=purge",
                "opencrawling.governance.pii.types=email,payment-card,iban").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(GovernanceProperties.class).getPii().getTypes())
                    .containsExactlyInAnyOrder(GovernanceProperties.Pii.Type.values());
            assertThat(context.getBean(GovernanceGate.class).purgeOnReject()).isTrue();
        });
    }

    @Test
    void aGateThatCannotBeBuiltStopsStartup() {
        String on = "opencrawling.governance.enabled=true";
        runner.withPropertyValues(on).run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("at least one hook"));
        runner.withPropertyValues(on, "opencrawling.governance.hooks=nope").run(context ->
                assertThat(context).getFailure().rootCause().hasMessageContaining("Unknown governance hook 'nope'"));
        runner.withPropertyValues(on, "opencrawling.governance.hooks=http").run(context ->
                assertThat(context).getFailure().rootCause().hasMessageContaining("url is required"));
        runner.withPropertyValues(on, "opencrawling.governance.hooks=http",
                "opencrawling.governance.http.url=https://g.example.com", "opencrawling.governance.http.secret=short")
                .run(context -> assertThat(context).getFailure().rootCause().hasMessageContaining("at least 32"));
        runner.withPropertyValues(on, "opencrawling.governance.hooks=pii-masking,pii-masking").run(context ->
                assertThat(context).getFailure().rootCause().hasMessageContaining("listed twice"));
        runner.withPropertyValues(on, "opencrawling.governance.hooks=pii-masking", "opencrawling.governance.pii.types=")
                .run(context -> assertThat(context).getFailure().rootCause().hasMessageContaining("mask nothing"));
        runner.withBean("custom", PreEmbeddingGovernanceHook.class, () -> new CustomHook("http"))
                .withPropertyValues(on, "opencrawling.governance.hooks=pii-masking")
                .run(context -> assertThat(context).getFailure().rootCause().hasMessageContaining("needs a name of its own"));
    }
}
