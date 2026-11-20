/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.sdk.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.sdk.exceptions.OpenCrawlingApiException;
import org.opencrawling.sdk.exceptions.OpenCrawlingNetworkException;
import org.opencrawling.sdk.http.HttpTransport;
import org.opencrawling.sdk.v1.RetryPolicy;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Default implementation of {@link OisIngestClient} over {@link HttpTransport}.
 */
public class DefaultOisIngestClient implements OisIngestClient {

    private static final String BASE_PATH = "/api/v1/ingest/ois/";
    private static final String JSON_MEDIA_TYPE = "application/json";

    private final HttpTransport transport;
    private final ObjectMapper json;
    private final RetryPolicy retryPolicy;

    public DefaultOisIngestClient(HttpTransport transport, RetryPolicy retryPolicy) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.json = transport.getObjectMapper();
        this.retryPolicy = retryPolicy != null ? retryPolicy : RetryPolicy.defaults();
    }

    @Override
    public OisIngestClient withToken(String oisToken) {
        if (oisToken == null || oisToken.isBlank()) {
            throw new IllegalArgumentException("oisToken must not be blank");
        }
        return new DefaultOisIngestClient(
                transport.withTokenProvider(org.opencrawling.sdk.auth.TokenProvider.of(oisToken.trim())),
                retryPolicy);
    }

    @Override
    public OisIngestResponse push(String jobId, OisDocument document) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }
        Objects.requireNonNull(document, "document is required");

        String path = BASE_PATH + URLEncoder.encode(jobId.trim(), StandardCharsets.UTF_8).replace("+", "%20");
        byte[] body;
        try {
            body = json.writeValueAsBytes(document);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not serialize OIS document as JSON", e);
        }

        int maxAttempts = retryPolicy.maxAttempts();
        for (int attempt = 1; ; attempt++) {
            HttpTransport.RawResponse response;
            try {
                response = transport.exchange("POST", path, Map.of(), body, JSON_MEDIA_TYPE);
            } catch (OpenCrawlingNetworkException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                sleep(retryPolicy.baseDelay());
                continue;
            }
            if (response.status() < 400) {
                return new OisIngestResponse(jobId.trim(), document.id(), document.action(), response.status());
            }
            if (attempt < maxAttempts && (response.status() == 429 || response.status() == 502
                    || response.status() == 503 || response.status() == 504)) {
                sleep(retryPolicy.baseDelay());
                continue;
            }
            String errorBody = new String(response.body(), StandardCharsets.UTF_8);
            throw new OpenCrawlingApiException(response.status(), errorBody);
        }
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenCrawlingNetworkException("Interrupted while waiting to retry OIS ingestion", e);
        }
    }
}
