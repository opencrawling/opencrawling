/*
 * Copyright © 2026 Rafa Haro (piergiorgio@apache.org)
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

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim garbage collector sweeps with {@code deleteExpired(maxAge, retain)}. A store that never implemented that
 * sweep cannot honor {@code retain}, so it must be left unswept, never swept with {@code retain} ignored.
 */
class ClaimCheckStoreSweepContractTest {

    /** A store written before the retain-aware sweep: it only knows the age-based one. */
    private static final class AgeOnlyStore implements ClaimCheckStore {
        final AtomicInteger ageOnlySweeps = new AtomicInteger();

        @Override
        public URI put(String id, InputStream content, long contentLength, String contentType) {
            return URI.create("legacy://claims/" + id);
        }

        @Override
        public InputStream get(URI claimCheckUri) {
            return InputStream.nullInputStream();
        }

        @Override
        public void delete(URI claimCheckUri) {
        }

        @Override
        public int deleteExpired(Duration maxAge) {
            ageOnlySweeps.incrementAndGet();
            return 5;
        }

        @Override
        public boolean supports(URI claimCheckUri) {
            return "legacy".equals(claimCheckUri.getScheme());
        }
    }

    @Test
    void aStoreThatCannotHonorRetainIsLeftUnswept() throws Exception {
        AgeOnlyStore store = new AgeOnlyStore();

        int deleted = store.deleteExpired(Duration.ofHours(24), uri -> true);

        assertThat(deleted).isZero();
        assertThat(store.ageOnlySweeps).as("its retain-blind sweep is never run").hasValue(0);
    }

    @Test
    void everyOzoneStrategyImplementsTheRetainAwareSweep() throws Exception {
        // OzoneClientStrategy has no default for it: the compiler enforces this; the check documents why.
        assertThat(OzoneClientStrategy.class.getMethod("deleteExpired", Duration.class, java.util.function.Predicate.class)
                .isDefault()).isFalse();
    }
}
