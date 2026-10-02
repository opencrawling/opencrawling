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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.StructuredTaskScope;

import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

@Component
@Primary
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
        try (var scope = StructuredTaskScope.open()) {
            
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        scope.fork(org.opencrawling.observability.concurrency.ObservabilityTask.observed(() -> {
                            scanDirectory(entry, sink);
                            return null;
                        }));
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
        
        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("extension", List.of(getFileExtension(file.getFileName().toString())));
        metadata.put("name", List.of(file.getFileName().toString()));
        metadata.put("file_name", List.of(file.getFileName().toString()));
        metadata.put("file_path", List.of(file.toAbsolutePath().toString()));
        metadata.put("file_size", List.of(String.valueOf(attrs.size())));
        metadata.put("file_is_hidden", List.of(String.valueOf(Files.isHidden(file))));

        // Collocated POSIX / OS identity attributes
        String owner = null;
        try {
            owner = Files.getOwner(file).getName();
            if (owner != null && !owner.isBlank()) {
                metadata.put("file_owner", List.of(owner));
                metadata.put("file_identity_users", List.of(owner));
            }
        } catch (Exception ignored) {}

        List<PermissionRule> rules = new ArrayList<>();
        boolean isPublic = false;

        PosixFileAttributeView posixView = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posixView != null) {
            try {
                PosixFileAttributes posixAttrs = posixView.readAttributes();
                if (posixAttrs != null) {
                    Set<PosixFilePermission> perms = posixAttrs.permissions();
                    String permStr = PosixFilePermissions.toString(perms);
                    metadata.put("file_permissions", List.of(permStr));
                    
                    int octalMode = toOctalMode(perms);
                    metadata.put("file_mode_octal", List.of(String.format("%04o", octalMode)));

                    String group = posixAttrs.group().getName();
                    if (group != null && !group.isBlank()) {
                        metadata.put("file_group", List.of(group));
                        metadata.put("file_identity_groups", List.of(group));
                    }

                    if (perms.contains(PosixFilePermission.OWNER_READ)) {
                        rules.add(new PermissionRule(owner != null ? owner : "owner", "user", owner != null ? owner : "Owner", "read"));
                    }
                    if (group != null && perms.contains(PosixFilePermission.GROUP_READ)) {
                        rules.add(new PermissionRule(group, "group", group, "read"));
                    }
                    if (perms.contains(PosixFilePermission.OTHERS_READ)) {
                        rules.add(new PermissionRule("public", "public", "Everyone", "read"));
                        isPublic = true;
                    }
                }
            } catch (Exception ignored) {}
        }

        if (rules.isEmpty()) {
            if (owner != null && !owner.isBlank()) {
                rules.add(new PermissionRule(owner, "user", owner, "read"));
            }
            rules.add(new PermissionRule("public", "public", "Everyone", "read"));
            isPublic = true;
        }

        SecurityConfig securityConfig = new SecurityConfig(false, rules);

        return new RepositoryDocument(
            file.toAbsolutePath().toString(),
            file.toUri().toString(),
            Files.newInputStream(file),
            metadata,
            isPublic ? "public" : "secured",
            securityConfig,
            attrs.lastModifiedTime().toInstant(),
            DocumentAction.UPSERT
        );
    }

    private int toOctalMode(Set<PosixFilePermission> perms) {
        int mode = 0;
        if (perms.contains(PosixFilePermission.OWNER_READ)) mode |= 0400;
        if (perms.contains(PosixFilePermission.OWNER_WRITE)) mode |= 0200;
        if (perms.contains(PosixFilePermission.OWNER_EXECUTE)) mode |= 0100;
        if (perms.contains(PosixFilePermission.GROUP_READ)) mode |= 0040;
        if (perms.contains(PosixFilePermission.GROUP_WRITE)) mode |= 0020;
        if (perms.contains(PosixFilePermission.GROUP_EXECUTE)) mode |= 0010;
        if (perms.contains(PosixFilePermission.OTHERS_READ)) mode |= 0004;
        if (perms.contains(PosixFilePermission.OTHERS_WRITE)) mode |= 0002;
        if (perms.contains(PosixFilePermission.OTHERS_EXECUTE)) mode |= 0001;
        return mode;
    }

    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex == -1) ? "" : fileName.substring(dotIndex + 1);
    }
}
