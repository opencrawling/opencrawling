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
package org.opencrawling.alfresco;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A stream over a downloaded temporary file that deletes the file when closed. Content is spooled eagerly so the
 * Alfresco HTTP connection is released immediately, allowing safe concurrent downstream pipeline processing.
 */
final class TempFileInputStream extends FilterInputStream {

    private final Path file;

    TempFileInputStream(Path file) throws IOException {
        super(Files.newInputStream(file));
        this.file = file;
    }

    /**
     * Spools {@code source} into a temporary file, enforcing {@code maxBytes} when positive. Returns {@code null}
     * (deleting the temp file) if the stream exceeds {@code maxBytes}.
     */
    static TempFileInputStream copyBounded(InputStream source, long maxBytes) throws IOException {
        Path tmp = Files.createTempFile("oc-alfresco-", ".bin");
        boolean ok = false;
        try (InputStream in = source; OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[16_384];
            long total = 0L;
            int read;
            while ((read = in.read(buf)) >= 0) {
                total += read;
                if (maxBytes > 0 && total > maxBytes) {
                    return null;
                }
                out.write(buf, 0, read);
            }
            ok = true;
        } finally {
            if (!ok) {
                Files.deleteIfExists(tmp);
            }
        }
        try {
            return new TempFileInputStream(tmp);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
