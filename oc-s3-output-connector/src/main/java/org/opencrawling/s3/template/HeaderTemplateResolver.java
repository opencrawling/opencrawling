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
package org.opencrawling.s3.template;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.opencrawling.core.document.RepositoryDocument;

/**
 * Resolves static and dynamic HTTP header values and templates for AWS S3 requests.
 *
 * <p>Supported placeholder patterns:
 * <ul>
 *   <li>{@code ${metadata.<key>}}: Injects attribute from {@code document.metadata()}. Multiple values are joined by comma.</li>
 *   <li>{@code ${metadata.<key> | fallback}}: Injects metadata with optional default fallback when missing or empty.</li>
 *   <li>{@code ${document.id}}: Injects unique document identifier.</li>
 *   <li>{@code ${document.uri}}: Injects source URI.</li>
 *   <li>{@code ${document.action}}: Injects document action name (e.g. UPSERT, DELETE).</li>
 *   <li>{@code ${job.id}}: Injects active job identifier when present in metadata.</li>
 * </ul>
 */
public final class HeaderTemplateResolver {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private HeaderTemplateResolver() {}

    /**
     * Resolves placeholders within a header template string using the supplied document context.
     *
     * @param template template string, e.g. {@code Department=${metadata.dept},Class=${metadata.cls | Public}}
     * @param document repository document context (may be {@code null})
     * @return resolved string, or original template if no placeholders are found
     */
    public static String resolve(String template, RepositoryDocument document) {
        if (template == null || template.isBlank()) {
            return template;
        }
        if (!template.contains("${")) {
            return template;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String replacement = evaluateExpression(expression, document);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement != null ? replacement : ""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolves all entries in the given headers map, returning a new map containing only non-empty resolved headers.
     *
     * @param customHeaders map of header names to templates
     * @param document      repository document context (may be {@code null})
     * @return unmodifiable map of resolved headers
     */
    public static Map<String, String> resolveHeaders(Map<String, String> customHeaders, RepositoryDocument document) {
        if (customHeaders == null || customHeaders.isEmpty()) {
            return Map.of();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        customHeaders.forEach((name, template) -> {
            if (name != null && !name.isBlank()) {
                String value = resolve(template, document);
                if (value != null && !value.isBlank()) {
                    resolved.put(name.trim(), value.trim());
                }
            }
        });
        return Collections.unmodifiableMap(resolved);
    }

    private static String evaluateExpression(String expression, RepositoryDocument document) {
        String key = expression;
        String fallback = "";
        int pipeIdx = expression.indexOf('|');
        if (pipeIdx >= 0) {
            key = expression.substring(0, pipeIdx).trim();
            fallback = expression.substring(pipeIdx + 1).trim();
        }

        if (document == null) {
            return fallback;
        }

        if ("document.id".equalsIgnoreCase(key) || "id".equalsIgnoreCase(key)) {
            return document.id() != null ? document.id() : fallback;
        }
        if ("document.uri".equalsIgnoreCase(key) || "uri".equalsIgnoreCase(key)) {
            return document.uri() != null ? document.uri() : fallback;
        }
        if ("document.action".equalsIgnoreCase(key) || "action".equalsIgnoreCase(key)) {
            return document.action() != null ? document.action().name() : fallback;
        }
        if ("job.id".equalsIgnoreCase(key) || "jobId".equalsIgnoreCase(key) || "job_id".equalsIgnoreCase(key)) {
            String jobId = resolveMetadata(document, "job_id", "jobId", "job.id");
            return jobId != null ? jobId : fallback;
        }

        if (key.startsWith("metadata.")) {
            String metaKey = key.substring("metadata.".length()).trim();
            String metaVal = resolveMetadata(document, metaKey);
            return metaVal != null ? metaVal : fallback;
        }

        // Direct metadata key fallback
        String directVal = resolveMetadata(document, key);
        return directVal != null ? directVal : fallback;
    }

    private static String resolveMetadata(RepositoryDocument document, String... keys) {
        if (document.metadata() == null || keys == null) {
            return null;
        }
        for (String k : keys) {
            List<String> values = document.metadata().get(k);
            if (values != null && !values.isEmpty()) {
                List<String> nonNull = values.stream()
                        .filter(v -> v != null && !v.isBlank())
                        .toList();
                if (!nonNull.isEmpty()) {
                    return String.join(",", nonNull);
                }
            }
        }
        return null;
    }
}
