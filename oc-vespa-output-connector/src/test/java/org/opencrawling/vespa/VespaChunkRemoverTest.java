/*
 * Copyright © ${year} the original author or authors (michael@michaelcizmar.com)
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
package org.opencrawling.vespa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.vespa.config.VespaOutputProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A tombstone removes every chunk of its document this output stored, and nothing else. */
class VespaChunkRemoverTest {

    private final VespaDocumentApiStandIn vespa;

    VespaChunkRemoverTest() throws Exception {
        vespa = new VespaDocumentApiStandIn();
    }

    @AfterEach
    void stop() {
        vespa.close();
    }

    @Test
    void theDocumentsChunksAreDeletedByExactDocumentIdInThisOutputsNamespaceAndTypesOnly() throws Exception {
        vespa.store("opencrawling", "opencrawling_chunk_1024", "a", "/data/Report.pdf", "s3://claims/x");
        vespa.store("opencrawling", "opencrawling_chunk", "b", "/data/Report.pdf", "s3://claims/x");
        // Another document that differs only in case, another namespace, a type this output does not write.
        vespa.store("opencrawling", "opencrawling_chunk_1024", "c", "/data/report.pdf", "s3://claims/y");
        vespa.store("other", "opencrawling_chunk_1024", "d", "/data/Report.pdf", "s3://claims/x");
        vespa.store("opencrawling", "somebody_elses_type", "e", "/data/Report.pdf", "s3://claims/x");

        // The tombstone carries the source URI, not the claim-check URI the chunks were written with.
        assertThat(new VespaChunkRemover(vespa.properties()).removeDocument("/data/Report.pdf", "https://repo.test/Report.pdf"))
                .isEqualTo(2);

        assertThat(vespa.documents).containsOnlyKeys("id:opencrawling:opencrawling_chunk_1024::c",
                "id:other:opencrawling_chunk_1024::d", "id:opencrawling:somebody_elses_type::e");
        assertThat(vespa.deletions).allMatch(deletion -> deletion.startsWith("opencrawling/opencrawling_chunk"))
                .anyMatch(deletion -> deletion.startsWith("opencrawling/opencrawling_chunk_384: "))
                .anyMatch(deletion -> deletion.startsWith("opencrawling/opencrawling_chunk: "));
    }

    @Test
    void chunksWithoutDocumentIdAreDeletedByUriAlongsideTheNewerOnes() throws Exception {
        // A re-crawl after the upgrade stored a new chunk with document_id; the old ones, fed before, have none.
        vespa.store("opencrawling", "opencrawling_chunk_1024", "new-0", "/data/doc-10.txt", "file:///data/doc-10.txt");
        vespa.store("opencrawling", "opencrawling_chunk_1024", "old-0", null, "file:///data/doc-10.txt");
        vespa.store("opencrawling", "opencrawling_chunk_1024", "old-1", null, "file:///data/doc-10.txt");
        // Another document stored under the same URI keeps its chunk.
        vespa.store("opencrawling", "opencrawling_chunk_1024", "other", "/data/other.txt", "file:///data/doc-10.txt");

        assertThat(new VespaChunkRemover(vespa.properties()).removeDocument("/data/doc-10.txt", "file:///data/doc-10.txt"))
                .isEqualTo(3);

        assertThat(vespa.documents).containsOnlyKeys("id:opencrawling:opencrawling_chunk_1024::other");
    }

    @Test
    void aLargeDeletionFollowsVespasContinuations() throws Exception {
        vespa.perCall = 2;
        for (int i = 0; i < 5; i++) {
            vespa.store("opencrawling", "opencrawling_chunk_1024", "chunk-" + i, "doc-11", "u");
        }

        assertThat(new VespaChunkRemover(vespa.properties()).removeDocument("doc-11", null)).isEqualTo(5);

        assertThat(vespa.documents).isEmpty();
    }

    @Test
    void aDeletionVespaRefusesFailsTheDocument() {
        vespa.store("opencrawling", "opencrawling_chunk_1024", "a", "doc-12", "u");
        VespaOutputProperties wrongCluster = VespaOutputProperties.builder()
                .endpoint(vespa.properties().endpoint())
                .timeoutSeconds(5)
                .contentCluster("no_such_cluster")
                .build();

        assertThatThrownBy(() -> new VespaChunkRemover(wrongCluster).removeDocument("doc-12", "u"))
                .hasMessageContaining("no_such_cluster");
        assertThat(vespa.documents).hasSize(1);
    }

    @Test
    void anUnreachableVespaFailsTheDocument() {
        VespaChunkRemover remover = new VespaChunkRemover(VespaOutputProperties.builder()
                .endpoint("http://127.0.0.1:59997")
                .timeoutSeconds(2)
                .build());

        assertThatThrownBy(() -> remover.removeDocument("doc-13", "u")).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void unreadableTlsFilesFailTheDeletionNotTheConstruction() {
        // The connector holding the remover is built at startup (the runtime's own output) or per job.
        VespaChunkRemover remover = new VespaChunkRemover(VespaOutputProperties.builder()
                .endpoint("https://vespa.test:8443")
                .timeoutSeconds(2)
                .tlsEnabled(true)
                .tlsCertificate("/absent/client.pem")
                .tlsPrivateKey("/absent/client-key.pem")
                .build());

        assertThatThrownBy(() -> remover.removeDocument("doc-14", "u")).hasMessageContaining("https://vespa.test:8443");
    }

    @Test
    void quotesAndBackslashesAreEscapedInSelectionLiterals() throws Exception {
        assertThat(VespaChunkRemover.literal("C:\\docs\\\"q\".txt")).isEqualTo("\"C:\\\\docs\\\\\\\"q\\\".txt\"");
        vespa.store("opencrawling", "opencrawling_chunk_1024", "q", "C:\\docs\\\"q\".txt", "u");

        assertThat(new VespaChunkRemover(vespa.properties()).removeDocument("C:\\docs\\\"q\".txt", null)).isEqualTo(1);
    }

    @Test
    void aCustomApplicationWithOnlySomeOfTheTypesStillDeletesFromThoseItHas() throws Exception {
        // A 1024-only application package: the configured fallback type and the other sizes are not deployed.
        vespa.deployedTypes = java.util.Set.of("opencrawling_chunk_1024");
        vespa.store("opencrawling", "opencrawling_chunk_1024", "a", "doc-15", "u");

        assertThat(new VespaChunkRemover(vespa.properties()).removeDocument("doc-15", "u")).isEqualTo(1);

        assertThat(vespa.documents).isEmpty();
    }

    @Test
    void anApplicationWithNoneOfTheTypesFailsTheDeletion() {
        // Most likely the wrong endpoint or cluster: reporting the document deleted would be wrong.
        vespa.deployedTypes = java.util.Set.of("unrelated_type");

        assertThatThrownBy(() -> new VespaChunkRemover(vespa.properties()).removeDocument("doc-16", "u"))
                .hasMessageContaining("None of the document types");
    }

    @Test
    void everyCallAsksForASliceShorterThanTheClientsTimeout() throws Exception {
        // The stand-in's properties time out after 5 s; Vespa's default slice of 60 s would outlast it.
        new VespaChunkRemover(vespa.properties()).removeDocument("doc-17", "u");

        assertThat(vespa.timeChunks).isNotEmpty().allMatch("2s"::equals);
    }
}
