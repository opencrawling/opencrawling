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
package org.opencrawling.runtime.api.v1;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Strong entity tags over a resource's JSON, and the {@code If-Match} / {@code If-None-Match} checks (RFC 9110).
 * Unlike RFC 9110's strong comparison for {@code If-Match}, a weak tag ({@code W/"..."}) is accepted when its value
 * matches, because proxies that compress responses make the runtime's tags weak.
 */
public final class Etags {

    private Etags() {}

    public static String of(Object state, JsonMapper json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(state));
            return "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 27) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether a {@code If-Match}/{@code If-None-Match} header value names this tag ({@code *} names any). */
    static boolean matches(String header, String etag) {
        if (header == null) {
            return false;
        }
        for (String candidate : header.split(",")) {
            String tag = candidate.trim();
            if (tag.equals("*")) {
                return true;
            }
            if (tag.startsWith("W/")) {
                tag = tag.substring(2); // weak comparison is enough for If-None-Match
            }
            if (tag.equals(etag)) {
                return true;
            }
        }
        return false;
    }

    /** Refuses a change with {@code 412} when the caller sent {@code If-Match} for another version. */
    public static void requireMatch(String ifMatch, String currentEtag) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return;
        }
        for (String candidate : ifMatch.split(",")) {
            String tag = candidate.trim();
            // Our tags digest the JSON representation, so a weak copy of one names the same version: proxies that
            // compress responses (nginx with gzip on, many CDNs) turn the tag weak, and a strict strong comparison
            // would then refuse every change.
            if (tag.startsWith("W/")) {
                tag = tag.substring(2);
            }
            if (tag.equals("*") || tag.equals(currentEtag)) {
                return;
            }
        }
        throw ApiProblem.preconditionFailed().with("currentEtag", currentEtag);
    }

    /** Whether a conditional GET may be answered {@code 304 Not Modified}. */
    public static boolean notModified(String ifNoneMatch, String currentEtag) {
        return matches(ifNoneMatch, currentEtag);
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
