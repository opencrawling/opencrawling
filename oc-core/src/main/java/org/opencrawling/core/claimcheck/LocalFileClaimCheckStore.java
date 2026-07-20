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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Local filesystem implementation of ClaimCheckStore.
 * Stores claim check payloads in a shared local directory (e.g., /data/claims).
 */
public class LocalFileClaimCheckStore implements ClaimCheckStore {

    private static final Logger log = LoggerFactory.getLogger(LocalFileClaimCheckStore.class);

    private final Path claimsDir;

    public LocalFileClaimCheckStore(String claimsDirPath) {
        this.claimsDir = Paths.get(claimsDirPath != null && !claimsDirPath.isBlank() ? claimsDirPath : "target/claims").toAbsolutePath();
        ensureDirectoryExists();
    }

    public LocalFileClaimCheckStore(Path claimsDir) {
        this.claimsDir = claimsDir.toAbsolutePath();
        ensureDirectoryExists();
    }

    private void ensureDirectoryExists() {
        try {
            if (!Files.exists(claimsDir)) {
                Files.createDirectories(claimsDir);
                log.info("Created local claim check directory: {}", claimsDir);
            }
        } catch (Exception e) {
            log.error("Failed to create local claim check directory: {}", claimsDir, e);
        }
    }

    public Path getClaimsDir() {
        return claimsDir;
    }

    @Override
    public URI put(String id, InputStream content, long contentLength, String contentType) throws Exception {
        ensureDirectoryExists();
        String safeName = id.replaceAll("[^a-zA-Z0-9.-]", "_");
        File claimFile = new File(claimsDir.toFile(), safeName);

        try (OutputStream out = new FileOutputStream(claimFile)) {
            content.transferTo(out);
        }

        URI uri = claimFile.toURI();
        log.info("Saved local claim check content to: {}", uri);
        return uri;
    }

    @Override
    public InputStream get(URI claimCheckUri) throws Exception {
        Path path = Paths.get(claimCheckUri);
        return Files.newInputStream(path);
    }

    @Override
    public void delete(URI claimCheckUri) throws Exception {
        Path path = Paths.get(claimCheckUri).toAbsolutePath();
        // Safety check: Only delete if the file is inside the managed claims directory
        if (path.startsWith(claimsDir)) {
            boolean deleted = Files.deleteIfExists(path);
            if (deleted) {
                log.info("Deleted local claim check file: {}", path);
            }
        } else {
            log.debug("Skipping deletion of non-managed external local file: {}", path);
        }
    }

    @Override
    public boolean supports(URI claimCheckUri) {
        if (claimCheckUri == null) {
            return false;
        }
        String scheme = claimCheckUri.getScheme();
        return scheme == null || "file".equalsIgnoreCase(scheme);
    }
}
