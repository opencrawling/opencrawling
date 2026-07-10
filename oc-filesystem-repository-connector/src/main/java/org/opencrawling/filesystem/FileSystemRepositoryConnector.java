/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.StructuredTaskScope;

import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

@Component
public class FileSystemRepositoryConnector implements RepositoryConnector {

    @Override
    public String getName() {
        return "FileSystemConnector";
    }

    @Override
    public void connect() throws Exception {}

    @Override
    public void disconnect() throws Exception {}

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(sink -> {
            try {
                Path rootPath = Paths.get(basePath);
                scanDirectory(rootPath, sink);
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }

    @SuppressWarnings("preview")
	private void scanDirectory(Path dir, reactor.core.publisher.FluxSink<RepositoryDocument> sink) throws InterruptedException {
        // Java 25: StructuredTaskScope.open() defaults to "shutdown on failure" behavior
        try (var scope = StructuredTaskScope.open()) {
            
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        scope.fork(() -> {
                            scanDirectory(entry, sink);
                            return null;
                        });
                    } else if (Files.isRegularFile(entry)) {
                        try {
                            RepositoryDocument doc = createDocument(entry);
                            sink.next(doc);
                        } catch (Exception e) {
                            // Log or handle individual file errors
                        }
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Error reading directory: " + dir, e);
            }
            
            scope.join();
        } catch (StructuredTaskScope.FailedException e) {
            throw new RuntimeException("Directory scan failed: " + dir, e.getCause());
        }
    }

    private RepositoryDocument createDocument(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        return new RepositoryDocument(
            file.toAbsolutePath().toString(),
            file.toUri().toString(),
            Files.newInputStream(file),
            Map.of("extension", List.of(getFileExtension(file.getFileName().toString()))),
            "public",
            attrs.lastModifiedTime().toInstant()
        );
    }
    
    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex == -1) ? "" : fileName.substring(dotIndex + 1);
    }
}
