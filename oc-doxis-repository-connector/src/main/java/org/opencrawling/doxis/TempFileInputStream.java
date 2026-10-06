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
package org.opencrawling.doxis;

import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A stream over a downloaded temporary file that deletes the file when closed. Content is downloaded eagerly so the CSB
 * session can be logged out as soon as the scan ends, whenever the pipeline reads the stream.
 */
final class TempFileInputStream extends FilterInputStream {

    private final Path file;

    TempFileInputStream(Path file) throws IOException {
        super(Files.newInputStream(file));
        this.file = file;
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
