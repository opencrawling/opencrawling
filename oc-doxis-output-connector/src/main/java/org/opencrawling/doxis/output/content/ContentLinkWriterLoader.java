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
package org.opencrawling.doxis.output.content;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * Loads a {@link ContentLinkWriter} implementation together with the SER Doxis client libraries from a directory, in an
 * isolated child-first class loader. Only {@code java.*}, {@code javax.*}, {@code jdk.*}, {@code sun.*}, SLF4J and the
 * writer contract ({@code org.opencrawling.doxis.output.content.*}) are shared with OpenCrawling, so the client's own Spring,
 * CXF and Jackson versions never clash with the runtime's.
 */
public final class ContentLinkWriterLoader {

    private static final Logger log = LoggerFactory.getLogger(ContentLinkWriterLoader.class);
    private static final List<String> SHARED_PREFIXES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.", "org.slf4j.",
            "org.opencrawling.doxis.output.content.");

    private ContentLinkWriterLoader() {
    }

    public static ContentLinkWriter load(Path libDir, Map<String, String> settings) throws IOException {
        if (libDir == null || !Files.isDirectory(libDir)) {
            throw new IOException("content-link.client-lib-dir '" + libDir + "' is not a directory");
        }
        URL[] jars;
        try (Stream<Path> files = Files.walk(libDir)) {
            jars = files.filter(p -> p.toString().endsWith(".jar")).map(p -> {
                try {
                    return p.toUri().toURL();
                } catch (IOException e) {
                    throw new IllegalArgumentException(e);
                }
            }).toArray(URL[]::new);
        }
        ClassLoader loader = new ChildFirstClassLoader(jars, ContentLinkWriter.class.getClassLoader());
        Iterator<ContentLinkWriter> writers = ServiceLoader.load(ContentLinkWriter.class, loader).iterator();
        if (!writers.hasNext()) {
            throw new IOException("No ContentLinkWriter implementation found in " + libDir
                    + " (add the oc-doxis-blueline-content-link jar and the SER Doxis client jars)");
        }
        ContentLinkWriter writer = writers.next();
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(loader);
        try {
            writer.configure(settings);
        } finally {
            current.setContextClassLoader(previous);
        }
        log.info("Loaded content-link writer {} from {} ({} jars).", writer.getClass().getName(), libDir, jars.length);
        return new ContextClassLoaderWriter(writer, loader);
    }

    /** Child-first: everything except the shared prefixes is resolved from the client jars first. */
    static final class ChildFirstClassLoader extends URLClassLoader {

        ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (SHARED_PREFIXES.stream().anyMatch(name::startsWith)) {
                        loaded = getParent().loadClass(name);
                    } else {
                        try {
                            loaded = findClass(name);
                        } catch (ClassNotFoundException e) {
                            loaded = getParent().loadClass(name);
                        }
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        @Override
        public URL getResource(String name) {
            URL url = findResource(name);
            return url != null ? url : super.getResource(name);
        }
    }

    /** Runs every call with the isolated loader as thread context class loader (CXF/Spring in the client rely on it). */
    private record ContextClassLoaderWriter(ContentLinkWriter delegate, ClassLoader loader) implements ContentLinkWriter {

        @Override
        public void configure(Map<String, String> settings) {
            run(() -> {
                delegate.configure(settings);
                return null;
            });
        }

        @Override
        public String createLinkedDocument(Request request) throws Exception {
            Thread current = Thread.currentThread();
            ClassLoader previous = current.getContextClassLoader();
            current.setContextClassLoader(loader);
            try {
                return delegate.createLinkedDocument(request);
            } finally {
                current.setContextClassLoader(previous);
            }
        }

        @Override
        public void close() {
            run(() -> {
                delegate.close();
                return null;
            });
        }

        private <T> T run(java.util.function.Supplier<T> action) {
            Thread current = Thread.currentThread();
            ClassLoader previous = current.getContextClassLoader();
            current.setContextClassLoader(loader);
            try {
                return action.get();
            } finally {
                current.setContextClassLoader(previous);
            }
        }
    }
}
