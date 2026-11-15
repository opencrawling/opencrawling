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
package org.opencrawling.cli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Sign-ins from {@code oc auth login}, one per server, in {@code credentials.json} next to the CLI config.
 *
 * <p>The file holds refresh tokens, so it is written atomically and, where the file system supports it, readable
 * and writable by the owner only ({@code 600}). A login is only ever used for the server it was made for.
 */
public class CredentialStore {

    /** One server's sign-in. Tokens are never printed. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Login(String serverUrl, String issuer, String clientId, String tokenEndpoint,
                        String revocationEndpoint, String accessToken, String refreshToken, Instant expiresAt) {

        public Login withTokens(String accessToken, String refreshToken, Instant expiresAt) {
            return new Login(serverUrl, issuer, clientId, tokenEndpoint, revocationEndpoint, accessToken, refreshToken,
                    expiresAt);
        }

        @Override
        public String toString() {
            return "Login[server=" + serverUrl + ", issuer=" + issuer + ", client=" + clientId + "]";
        }
    }

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path file;

    public CredentialStore(Path directory) {
        this.file = directory.resolve("credentials.json");
    }

    public Path file() {
        return file;
    }

    /** The sign-in for this server, if any. */
    public synchronized Login find(String serverUrl) {
        return readAll().get(key(serverUrl));
    }

    public synchronized void save(Login login) throws IOException {
        Map<String, Login> all = readAll();
        all.put(key(login.serverUrl()), login);
        write(all);
    }

    /** Removes this server's sign-in; deletes the file when no other is left. Returns whether one was there. */
    public synchronized boolean remove(String serverUrl) throws IOException {
        Map<String, Login> all = readAll();
        boolean removed = all.remove(key(serverUrl)) != null;
        if (all.isEmpty()) {
            Files.deleteIfExists(file);
        } else if (removed) {
            write(all);
        }
        return removed;
    }

    /** The same server however it was typed: no trailing slash, lower-case scheme and host. */
    static String key(String serverUrl) {
        String url = serverUrl == null ? "" : serverUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd > 0) {
            int hostEnd = url.indexOf('/', schemeEnd + 3);
            String origin = hostEnd < 0 ? url : url.substring(0, hostEnd);
            url = origin.toLowerCase(java.util.Locale.ROOT) + (hostEnd < 0 ? "" : url.substring(hostEnd));
        }
        return url;
    }

    private Map<String, Login> readAll() {
        if (!Files.exists(file)) {
            return new TreeMap<>();
        }
        try {
            return new TreeMap<>(JSON.readValue(file.toFile(), new TypeReference<Map<String, Login>>() {}));
        } catch (IOException e) {
            return new TreeMap<>();
        }
    }

    private void write(Map<String, Login> all) throws IOException {
        Path directory = file.getParent();
        Files.createDirectories(directory);
        boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
        Path temp = posix
                ? Files.createTempFile(directory, ".credentials", ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY))
                : Files.createTempFile(directory, ".credentials", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), all);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            if (posix) {
                Files.setPosixFilePermissions(file, OWNER_ONLY);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
