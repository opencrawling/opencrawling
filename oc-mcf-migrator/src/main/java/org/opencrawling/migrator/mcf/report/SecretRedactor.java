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
package org.opencrawling.migrator.mcf.report;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The only code path allowed to look at a raw ManifoldCF connection-parameter value on its way
 * into anything printed or written to disk (console output, the migration report, log lines).
 * Connection configs routinely carry passwords/tokens (ManifoldCF's {@code password},
 * {@code vespaPassword}, {@code vespaBearerToken}, M-Files {@code password}, ...) — every {@code
 * ConnectorMapper} must redact a value before it ever reaches a {@code FieldNote} message.
 */
public final class SecretRedactor {

    private static final Pattern SECRET_KEY_PATTERN =
        Pattern.compile("(?i).*(password|secret|token|credential|apikey|api_key|accesskey|access_key|privatekey|passphrase).*");

    private static final String REDACTED = "***REDACTED***";

    private SecretRedactor() {
    }

    public static boolean isSecretKey(String key) {
        return key != null && SECRET_KEY_PATTERN.matcher(key).matches();
    }

    /** Returns {@code value} unchanged unless {@code key} looks like a secret, in which case it returns a fixed placeholder. */
    public static String redact(String key, String value) {
        if (value == null) {
            return null;
        }
        return isSecretKey(key) ? REDACTED : value;
    }

    /** Redacts every secret-looking entry in {@code configuration}, preserving order and all other values. */
    public static Map<String, String> redact(Map<String, String> configuration) {
        Map<String, String> result = new LinkedHashMap<>();
        configuration.forEach((key, value) -> result.put(key, redact(key, value)));
        return result;
    }
}
