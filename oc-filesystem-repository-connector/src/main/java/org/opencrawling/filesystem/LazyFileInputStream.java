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
package org.opencrawling.filesystem;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link InputStream} that opens the underlying file only on first access.
 *
 * <p>The scan emits documents much faster than they are consumed, so opening every file eagerly would hold one
 * file handle per buffered document (and leak it when the content is never read, e.g. local claim-check
 * references). A lazy stream holds a handle only while a consumer actually reads it.
 */
final class LazyFileInputStream extends InputStream {

    private final Path file;
    private InputStream delegate;
    private boolean closed;

    LazyFileInputStream(Path file) {
        this.file = file;
    }

    /** Whether the file has been opened (visible for tests). */
    synchronized boolean isOpened() {
        return delegate != null;
    }

    private InputStream delegate() throws IOException {
        if (closed) {
            throw new IOException("Stream closed: " + file);
        }
        if (delegate == null) {
            delegate = Files.newInputStream(file);
        }
        return delegate;
    }

    @Override
    public synchronized int read() throws IOException {
        return delegate().read();
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) throws IOException {
        return delegate().read(b, off, len);
    }

    @Override
    public synchronized byte[] readAllBytes() throws IOException {
        return delegate().readAllBytes();
    }

    @Override
    public synchronized long transferTo(OutputStream out) throws IOException {
        return delegate().transferTo(out);
    }

    @Override
    public synchronized long skip(long n) throws IOException {
        return delegate().skip(n);
    }

    @Override
    public synchronized int available() throws IOException {
        return delegate().available();
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        if (delegate != null) {
            delegate.close();
        }
    }
}
