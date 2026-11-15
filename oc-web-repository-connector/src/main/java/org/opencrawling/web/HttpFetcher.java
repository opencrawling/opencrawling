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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;

/**
 * Fetches one URL with Apache HttpClient 5 (spec §5.3).
 *
 * <p>Redirects are never followed automatically: the crawler checks scope on each hop and the
 * {@link SsrfGuard} re-checks every connection. Bodies are read up to a byte limit on the decoded content,
 * which also guards against decompression bombs. Each crawl gets its own client and cookie store.
 */
public final class HttpFetcher implements AutoCloseable {

    private static final long MAX_DRAIN_BYTES = 64 * 1024;

    private final CloseableHttpClient client;
    private final SsrfGuard ssrfGuard;

    /** Result of one HTTP request. Header names are lowercase. */
    public record Response(int status, Map<String, String> headers, byte[] body, boolean truncated) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** The MIME type without parameters, lowercase, or null when the server didn't send one. */
        public String mimeType() {
            String contentType = header("content-type");
            if (contentType == null || contentType.isBlank()) {
                return null;
            }
            return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        }

        public String charset() {
            String contentType = header("content-type");
            if (contentType == null) {
                return null;
            }
            for (String part : contentType.split(";")) {
                String trimmed = part.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    return trimmed.substring("charset=".length()).replace("\"", "").trim();
                }
            }
            return null;
        }

        public boolean isRedirect() {
            return status >= 300 && status < 400 && header("location") != null;
        }
    }

    public HttpFetcher(WebCrawlSettings settings) {
        this.ssrfGuard = new SsrfGuard(settings.allowPrivateNetworks(), settings.privateAllowlist());
        this.client = HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(ssrfGuard)
                .setMaxConnTotal(Math.max(settings.maxThreads(), 2))
                .setMaxConnPerRoute(2)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(settings.connectTimeoutMs()))
                    .setSocketTimeout(Timeout.ofMilliseconds(settings.readTimeoutMs()))
                    .build())
                .build())
            .setDefaultRequestConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(settings.readTimeoutMs()))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(settings.connectTimeoutMs()))
                .build())
            .setDefaultCookieStore(new BasicCookieStore())
            .setUserAgent(settings.userAgent())
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .build();
    }

    /**
     * Sends a GET request without following redirects.
     *
     * @param maxBytes maximum number of decoded body bytes to read; larger bodies are marked truncated
     */
    public Response get(String url, long maxBytes) throws IOException {
        URI uri = URI.create(url);
        return execute(new HttpGet(uri), uri.getHost(), maxBytes);
    }

    /**
     * Sends a HEAD request, falling back to GET when the server doesn't support HEAD (spec FR-18).
     */
    public Response head(String url) throws IOException {
        URI uri = URI.create(url);
        Response response = execute(new HttpHead(uri), uri.getHost(), 0);
        if (response.status() == 405 || response.status() == 501) {
            response = execute(new HttpGet(uri), uri.getHost(), 0);
        }
        return response;
    }

    private Response execute(HttpUriRequestBase request, String host, long maxBytes) throws IOException {
        ssrfGuard.check(host);
        ClassicHttpResponse response = client.executeOpen(null, request, null);
        // Aborting drops the connection instead of draining an unwanted or oversized body to reuse it
        boolean abort = false;
        try {
            Map<String, String> headers = new HashMap<>();
            for (Header header : response.getHeaders()) {
                headers.putIfAbsent(header.getName().toLowerCase(Locale.ROOT), header.getValue());
            }
            int status = response.getCode();
            HttpEntity entity = response.getEntity();
            byte[] body = new byte[0];
            boolean truncated = false;
            boolean readBody = status >= 200 && status < 300 && maxBytes > 0;
            if (entity != null && readBody) {
                InputStream in = entity.getContent();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        truncated = true;
                        abort = true;
                        break;
                    }
                    out.write(buffer, 0, read);
                }
                body = out.toByteArray();
            } else if (entity != null) {
                // Redirect and error bodies aren't needed. Small ones are drained so the connection can be reused.
                long length = entity.getContentLength();
                abort = length < 0 || length > MAX_DRAIN_BYTES;
            }
            return new Response(status, Map.copyOf(headers), body, truncated);
        } finally {
            if (abort) {
                request.abort();
            }
            try {
                response.close();
            } catch (IOException e) {
                // Closing an aborted response can fail; the result is already complete
                if (!abort) {
                    throw e;
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
