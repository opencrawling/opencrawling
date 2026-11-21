/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.runtime.connector;

import org.opencrawling.googledrive.GoogleDriveCrawlState;
import org.opencrawling.runtime.controlplane.ControlPlaneStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;

/**
 * Persists Google Drive incremental crawl state (Changes API {@code startPageToken}, {@code lastFullScanAt}, and known
 * folder/file snapshots) in OpenCrawling's shared {@link ControlPlaneStore} (#339, #395), so scheduled runs can
 * execute on any runtime replica without losing or diverging state.
 */
@Component
public class ControlPlaneGoogleDriveStateStore implements GoogleDriveCrawlState.StateStore {

    /** Wrapper record serialized cleanly by both FileControlPlaneStore and JdbcControlPlaneStore. */
    public record Envelope(String payload) {
    }

    private final ControlPlaneStore controlPlaneStore;

    public ControlPlaneGoogleDriveStateStore(ControlPlaneStore controlPlaneStore) {
        this.controlPlaneStore = Objects.requireNonNull(controlPlaneStore, "controlPlaneStore");
    }

    @Override
    public GoogleDriveCrawlState.State load(String scopeKey) throws IOException {
        String key = GoogleDriveCrawlState.stateName(scopeKey);
        Envelope envelope = controlPlaneStore.loadObject(key, Envelope.class, new Envelope(null));
        if (envelope == null || envelope.payload() == null || envelope.payload().isBlank()) {
            return GoogleDriveCrawlState.State.empty();
        }
        return GoogleDriveCrawlState.fromJson(envelope.payload());
    }

    @Override
    public void save(String scopeKey, GoogleDriveCrawlState.State state) throws IOException {
        String key = GoogleDriveCrawlState.stateName(scopeKey);
        String json = GoogleDriveCrawlState.toJson(state);
        controlPlaneStore.locked(() -> {
            controlPlaneStore.save(key, new Envelope(json));
            return null;
        });
    }
}
