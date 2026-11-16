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
package org.opencrawling.core.claimcheck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompositeClaimCheckStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void testCompositeRouting() throws Exception {
        LocalFileClaimCheckStore localStore = new LocalFileClaimCheckStore(tempDir);
        CompositeClaimCheckStore compositeStore = new CompositeClaimCheckStore(localStore, List.of(localStore));

        byte[] bytes = "Composite Store test".getBytes(StandardCharsets.UTF_8);
        URI uri = compositeStore.put("doc-composite.txt", new ByteArrayInputStream(bytes), bytes.length);

        assertThat(compositeStore.supports(uri)).isTrue();

        try (InputStream in = compositeStore.get(uri)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(text).isEqualTo("Composite Store test");
        }

        compositeStore.delete(uri);
        assertThat(Path.of(uri)).doesNotExist();
    }

    @Test
    void theSweepPassesWhatToRetainToEveryStore() throws Exception {
        ClaimCheckStore primary = mock(ClaimCheckStore.class);
        ClaimCheckStore other = mock(ClaimCheckStore.class);
        when(primary.deleteExpired(any(Duration.class), any())).thenReturn(1);
        when(other.deleteExpired(any(Duration.class), any())).thenReturn(2);
        Predicate<URI> retain = uri -> true;

        int removed = new CompositeClaimCheckStore(primary, List.of(primary, other)).deleteExpired(Duration.ofHours(24), retain);

        assertThat(removed).isEqualTo(3);
        verify(primary).deleteExpired(Duration.ofHours(24), retain);
        verify(other).deleteExpired(Duration.ofHours(24), retain);
    }
}
