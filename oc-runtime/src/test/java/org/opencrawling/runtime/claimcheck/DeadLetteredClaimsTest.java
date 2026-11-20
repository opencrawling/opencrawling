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
package org.opencrawling.runtime.claimcheck;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadLetteredClaimsTest {

    @TempDir
    Path tempDir;

    @Test
    void aDeadLetteredDocumentMessageReferencesItsUri() {
        byte[] message = "{\"documentId\":\"doc-1\",\"uri\":\"s3://claims/doc-1_report.pdf\",\"action\":\"UPSERT\"}"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(DeadLetteredClaims.claimUri(message)).isEqualTo("s3://claims/doc-1_report.pdf");
    }

    @Test
    void aPayloadWithoutAUriReferencesNoClaim() {
        assertThat(DeadLetteredClaims.claimUri("{not json".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(DeadLetteredClaims.claimUri("{\"documentId\":\"doc-1\"}".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(DeadLetteredClaims.claimUri("{\"uri\":\"  \"}".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(DeadLetteredClaims.claimUri(new byte[0])).isNull();
        assertThat(DeadLetteredClaims.claimUri(null)).isNull();
    }

    @Test
    void bothSpellingsOfAFileUriAreTheSameClaim() {
        // The local store hands out File.toURI() (file:/x); a sweep may compare it with Path.toUri() (file:///x).
        File claim = tempDir.resolve("doc-1_report.pdf").toFile();
        Predicate<URI> retain = DeadLetteredClaims.retaining(Set.of(DeadLetteredClaims.normalize(claim.toURI().toString())));

        assertThat(retain.test(claim.toPath().toUri())).isTrue();
        assertThat(retain.test(claim.toURI())).isTrue();
        assertThat(retain.test(tempDir.resolve("other.pdf").toUri())).isFalse();
    }

    @Test
    void anUnreachableBrokerFailsTheReadWithinItsBudget() {
        // Nothing listens on port 1: every call keeps retrying until its timeout. They share one budget.
        DeadLetteredClaims deadLetters = new DeadLetteredClaims("127.0.0.1:1", 1_000);
        long start = System.nanoTime();

        assertThatThrownBy(deadLetters::referencedClaims).isInstanceOf(TimeoutException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void anInvalidOffsetFailsTheReadInsteadOfSkippingDeadLetters() {
        // With Kafka's default (latest), retention deleting the segment being read would move the read to the end:
        // it would succeed with an incomplete list, and the sweep would delete claims still referenced.
        assertThat(new DeadLetteredClaims("127.0.0.1:9092", 30_000).consumerConfig())
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none")
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    }

    @Test
    void otherUrisMatchExactly() {
        Predicate<URI> retain = DeadLetteredClaims.retaining(Set.of(DeadLetteredClaims.normalize("s3://claims/a.pdf")));

        assertThat(retain.test(URI.create("s3://claims/a.pdf"))).isTrue();
        assertThat(retain.test(URI.create("s3://claims/b.pdf"))).isFalse();
        assertThat(retain.test(URI.create("ofs://s3v/claims/a.pdf"))).isFalse();
        assertThat(retain.test(null)).isFalse();
    }
}
