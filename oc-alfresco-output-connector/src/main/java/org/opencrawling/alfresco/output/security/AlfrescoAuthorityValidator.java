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
package org.opencrawling.alfresco.output.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.opencrawling.alfresco.output.config.AlfrescoOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.StructuredTaskScope;

/**
 * Validates existence of users and groups in target Alfresco Content Services instance.
 * Supports both Pre-Flight batch validation and In-Flight cached verification, maintaining
 * an audit ledger of all missing authority gaps.
 */
public class AlfrescoAuthorityValidator {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoAuthorityValidator.class);
    private static final String API_PREFIX = "/api/-default-/public/alfresco/versions/1";

    private static final Set<String> BUILTIN_AUTHORITIES = Set.of(
            "GROUP_EVERYONE", "EVERYONE", "GUEST", "ROLE_ANONYMOUS",
            "ROLE_AUTHENTICATED", "ROLE_ADMINISTRATOR", "ADMIN"
    );

    private final HttpClient httpClient;
    private final AlfrescoOutputProperties properties;
    private final String authHeader;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Boolean> authorityCache = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<AuthorityGapEntry> gapLedger = new CopyOnWriteArrayList<>();

    public record PreFlightAuditResult(
            int totalAuthoritiesChecked,
            int validAuthoritiesCount,
            int missingAuthoritiesCount,
            List<String> missingAuthorities,
            Instant timestamp
    ) {}

    public AlfrescoAuthorityValidator(
            HttpClient httpClient,
            AlfrescoOutputProperties properties,
            String authHeader,
            ObjectMapper objectMapper
    ) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.authHeader = authHeader;
        this.objectMapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT);

        // Pre-populate built-ins as existing
        for (String builtin : BUILTIN_AUTHORITIES) {
            authorityCache.put(builtin, true);
        }
    }

    /**
     * Checks if a user or group authority exists on the target Alfresco instance.
     * Uses in-memory cache to avoid duplicate REST calls.
     */
    public boolean isAuthorityPresent(String authorityId) {
        if (authorityId == null || authorityId.isBlank()) {
            return false;
        }

        String normalized = normalizeAuthorityId(authorityId);
        String upper = normalized.toUpperCase();

        if (BUILTIN_AUTHORITIES.contains(upper)) {
            return true;
        }

        // Cache lookup
        Boolean cached = authorityCache.get(normalized);
        if (cached != null) {
            return cached;
        }

        boolean exists = probeTargetAlfresco(normalized);
        authorityCache.put(normalized, exists);
        return exists;
    }

    /**
     * Executes Pre-Flight batch validation against target Alfresco for a set of authorities.
     * Concurrently verifies existence using Virtual Threads and populates cache & gap ledger.
     */
    public PreFlightAuditResult runPreFlightAudit(Collection<String> authorities) {
        if (authorities == null || authorities.isEmpty()) {
            return new PreFlightAuditResult(0, 0, 0, List.of(), Instant.now());
        }

        Set<String> distinctAuthorities = new HashSet<>(authorities);
        List<String> missing = Collections.synchronizedList(new ArrayList<>());

        try (var scope = StructuredTaskScope.open()) {
            for (String auth : distinctAuthorities) {
                scope.fork(() -> {
                    boolean present = isAuthorityPresent(auth);
                    if (!present) {
                        missing.add(auth);
                        recordGap(AuthorityGapEntry.skipped(
                                auth,
                                isGroup(auth) ? "group" : "user",
                                "PRE_FLIGHT_AUDIT",
                                properties.getDefaultPermissionRole(),
                                "Pre-flight audit: authority not found on target Alfresco instance"
                        ));
                    }
                    return null;
                });
            }

            scope.join();
        } catch (Exception e) {
            log.warn("Error during pre-flight authority audit: {}", e.getMessage());
        }

        int total = distinctAuthorities.size();
        int missingCount = missing.size();
        int validCount = total - missingCount;

        log.info("Alfresco Pre-Flight Authority Audit completed: {} checked, {} valid, {} missing.",
                total, validCount, missingCount);

        if (!missing.isEmpty()) {
            log.warn("Target Alfresco missing {} authorities: {}", missingCount, missing);
        }

        return new PreFlightAuditResult(total, validCount, missingCount, new ArrayList<>(missing), Instant.now());
    }

    /**
     * Records a gap entry into the audit ledger.
     */
    public void recordGap(AuthorityGapEntry entry) {
        if (entry != null) {
            gapLedger.add(entry);
        }
    }

    /**
     * Retrieves the unmodifiable audit ledger of all recorded authority gaps.
     */
    public List<AuthorityGapEntry> getGapLedger() {
        return Collections.unmodifiableList(gapLedger);
    }

    /**
     * Exports the accumulated authority gap ledger to the specified destination path.
     */
    public void writeGapReport(Path destinationPath) {
        if (destinationPath == null) return;

        try {
            if (destinationPath.getParent() != null) {
                Files.createDirectories(destinationPath.getParent());
            }

            Set<String> uniqueMissing = new TreeSet<>();
            for (AuthorityGapEntry entry : gapLedger) {
                uniqueMissing.add(entry.authorityId());
            }

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("reportType", "AlfrescoAuthorityGapReport");
            report.put("generatedAt", Instant.now().toString());
            report.put("targetAlfrescoUrl", properties.getUrl());
            report.put("validationMode", properties.getAuthorityValidationMode());
            report.put("missingAuthorityAction", properties.getMissingAuthorityAction());
            report.put("totalRecordedGaps", gapLedger.size());
            report.put("uniqueMissingAuthoritiesCount", uniqueMissing.size());
            report.put("uniqueMissingAuthorities", uniqueMissing);
            report.put("ledgerEntries", gapLedger);

            byte[] jsonBytes = objectMapper.writeValueAsBytes(report);
            Files.write(destinationPath, jsonBytes);
            log.info("Exported Alfresco Authority Gap Ledger report ({} entries) to: {}", gapLedger.size(), destinationPath);
        } catch (IOException e) {
            log.error("Failed to write Alfresco authority gap report to {}: {}", destinationPath, e.getMessage(), e);
        }
    }

    private boolean probeTargetAlfresco(String authorityId) {
        String path;
        if (isGroup(authorityId)) {
            String cleanGroup = authorityId.startsWith("GROUP_") ? authorityId : "GROUP_" + authorityId;
            path = API_PREFIX + "/groups/" + URLEncoder.encode(cleanGroup, StandardCharsets.UTF_8);
        } else {
            path = API_PREFIX + "/people/" + URLEncoder.encode(authorityId, StandardCharsets.UTF_8);
        }

        String targetUrl = properties.getUrl() + path;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Authorization", authHeader)
                    .timeout(Duration.ofSeconds(Math.min(properties.getTimeoutSeconds(), 10)))
                    .GET()
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();

            if (code == 200) {
                log.debug("Verified target authority '{}' exists (HTTP 200).", authorityId);
                return true;
            } else if (code == 404) {
                log.debug("Target authority '{}' not found in Alfresco (HTTP 404).", authorityId);
                return false;
            } else {
                log.warn("Checking authority '{}' returned unexpected HTTP status {}.", authorityId, code);
                return false;
            }
        } catch (Exception e) {
            log.warn("Failed to probe target Alfresco authority '{}': {}", authorityId, e.getMessage());
            return false;
        }
    }

    private boolean isGroup(String authorityId) {
        if (authorityId == null) return false;
        String lower = authorityId.toLowerCase();
        return authorityId.startsWith("GROUP_") || authorityId.startsWith("ROLE_")
                || lower.startsWith("group:") || lower.startsWith("role:");
    }

    private String normalizeAuthorityId(String identity) {
        if (identity == null || identity.isBlank()) return "";
        String raw = identity.trim();
        if (raw.startsWith("group:") || raw.startsWith("role:")) {
            String clean = raw.substring(raw.indexOf(":") + 1).trim();
            return clean.toUpperCase().startsWith("GROUP_") ? clean.toUpperCase() : "GROUP_" + clean.toUpperCase();
        }
        if (raw.startsWith("user:")) {
            return raw.substring(5).trim();
        }
        return raw;
    }
}
