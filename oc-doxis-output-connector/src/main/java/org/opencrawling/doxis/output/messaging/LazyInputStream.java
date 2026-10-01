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
package org.opencrawling.doxis.output.messaging;

import java.io.IOException;
import java.io.InputStream;

/**
 * Opens the underlying stream only on the first read, so a claim-check object is never fetched when the content plan does
 * not upload it (in-place / reference-only documents).
 */
class LazyInputStream extends InputStream {

    @FunctionalInterface
    interface Opener {
        InputStream open() throws Exception;
    }

    private final Opener opener;
    private InputStream delegate;
    private boolean closed;

    LazyInputStream(Opener opener) {
        this.opener = opener;
    }

    private InputStream delegate() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        if (delegate == null) {
            try {
                delegate = opener.open();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Cannot open content stream: " + e.getMessage(), e);
            }
        }
        return delegate;
    }

    boolean isOpened() {
        return delegate != null;
    }

    @Override
    public int read() throws IOException {
        return delegate().read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return delegate().read(b, off, len);
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (delegate != null) {
            delegate.close();
        }
    }
}
