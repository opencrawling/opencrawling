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
package org.opencrawling.web;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes URLs so the same page is only crawled once (spec §5.2, step 1).
 *
 * <p>Lowercases scheme and host, converts IDN hosts to punycode, drops default ports, user info and fragments,
 * resolves dot segments, normalizes percent-encoding, removes session IDs from the path and strips tracking
 * query parameters.
 */
public final class UrlNormalizer {

    private static final Pattern PATH_SESSION_ID = Pattern.compile("(?i);(jsessionid|phpsessid|sid)=[^/?#]*");
    private static final Pattern AUTHORITY = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*://)([^/?#]*)(.*)$", Pattern.DOTALL);
    private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
    private static final String ALLOWED = UNRESERVED + "!$&'()*+,;=:@/?%";

    private final List<String> stripQueryParams;

    public UrlNormalizer(List<String> stripQueryParams) {
        this.stripQueryParams = stripQueryParams.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
    }

    /**
     * Resolves {@code url} against {@code base} (which may be null) and normalizes it.
     *
     * @return the normalized absolute URL, or empty if it isn't a valid http(s) URL
     */
    public Optional<String> normalize(String url, String base) {
        if (url == null) {
            return Optional.empty();
        }
        String candidate = url.trim();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(encodeIllegalCharacters(candidate));
            if (base != null && !uri.isAbsolute()) {
                uri = new URI(encodeIllegalCharacters(base)).resolve(uri);
            }
            return Optional.ofNullable(normalize(uri));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Optional<String> normalize(String url) {
        return normalize(url, null);
    }

    private String normalize(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return null;
        }
        host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        int port = uri.getPort();
        if ((port == 80 && scheme.equals("http")) || (port == 443 && scheme.equals("https"))) {
            port = -1;
        }

        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        path = PATH_SESSION_ID.matcher(path).replaceAll("");
        path = normalizePercentEncoding(removeDotSegments(path));

        String query = filterQuery(uri.getRawQuery());

        StringBuilder normalized = new StringBuilder(scheme).append("://").append(host);
        if (port != -1) {
            normalized.append(':').append(port);
        }
        normalized.append(path);
        if (query != null) {
            normalized.append('?').append(query);
        }
        return normalized.toString();
    }

    private String filterQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String param : rawQuery.split("&")) {
            if (param.isEmpty()) {
                continue;
            }
            String name = param.split("=", 2)[0].toLowerCase(Locale.ROOT);
            if (!isStripped(name)) {
                kept.add(normalizePercentEncoding(param));
            }
        }
        return kept.isEmpty() ? null : String.join("&", kept);
    }

    private boolean isStripped(String name) {
        for (String pattern : stripQueryParams) {
            if (pattern.endsWith("*") ? name.startsWith(pattern.substring(0, pattern.length() - 1)) : name.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    /** RFC 3986 §5.2.4. */
    private static String removeDotSegments(String path) {
        if (!path.contains(".")) {
            return path;
        }
        String[] segments = path.split("/", -1);
        List<String> output = new ArrayList<>();
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            boolean last = i == segments.length - 1;
            if (segment.equals(".")) {
                if (last) {
                    output.add("");
                }
            } else if (segment.equals("..")) {
                if (!output.isEmpty()) {
                    output.removeLast();
                }
                if (last) {
                    output.add("");
                }
            } else {
                output.add(segment);
            }
        }
        return "/" + String.join("/", output);
    }

    /** Uppercases percent-escapes and decodes escaped unreserved characters (RFC 3986 §6.2.2). */
    private static String normalizePercentEncoding(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length() && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                int decoded = Integer.parseInt(value.substring(i + 1, i + 3), 16);
                if (UNRESERVED.indexOf(decoded) >= 0) {
                    out.append((char) decoded);
                } else {
                    out.append('%').append(value.substring(i + 1, i + 3).toUpperCase(Locale.ROOT));
                }
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Percent-encodes characters that browsers accept in hrefs but {@link URI} rejects, such as spaces,
     * non-ASCII characters and stray percent signs.
     */
    static String encodeIllegalCharacters(String url) {
        String withAsciiHost = toAsciiHost(url);
        StringBuilder out = new StringBuilder(withAsciiHost.length());
        boolean inFragment = false;
        int i = 0;
        while (i < withAsciiHost.length()) {
            int cp = withAsciiHost.codePointAt(i);
            if (cp == '%') {
                boolean escape = i + 2 < withAsciiHost.length()
                    && isHex(withAsciiHost.charAt(i + 1)) && isHex(withAsciiHost.charAt(i + 2));
                out.append(escape ? "%" : "%25");
            } else if (cp == '#' && !inFragment) {
                inFragment = true;
                out.append('#');
            } else if (cp < 128 && (ALLOWED.indexOf(cp) >= 0 || cp == '[' || cp == ']')) {
                // '[' and ']' delimit IPv6 literal hosts
                out.append((char) cp);
            } else {
                for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(String.format("%02X", b & 0xFF));
                }
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /** Converts a non-ASCII host to punycode before encoding, since {@link URI} rejects percent-encoded hosts. */
    private static String toAsciiHost(String url) {
        Matcher matcher = AUTHORITY.matcher(url);
        if (!matcher.matches() || matcher.group(2).chars().allMatch(c -> c < 128)) {
            return url;
        }
        String authority = matcher.group(2);
        int at = authority.lastIndexOf('@');
        String userInfo = at >= 0 ? authority.substring(0, at + 1) : "";
        String hostPort = authority.substring(at + 1);
        int colon = hostPort.lastIndexOf(':');
        String host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        String port = colon >= 0 ? hostPort.substring(colon) : "";
        try {
            host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            return url;
        }
        return matcher.group(1) + userInfo + host + port + matcher.group(3);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
