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
package org.opencrawling.sdk.v1;

/**
 * Optional headers of one Platform API request. Immutable; each {@code with...} returns a copy.
 *
 * @param idempotencyKey sent as {@code Idempotency-Key} with a {@code POST}, so a retry does not repeat it
 * @param correlationId  sent as {@code X-Correlation-Id}; recorded with the runs a request starts
 * @param requestId      sent as {@code X-Request-Id}; the runtime logs it and puts it in problem details
 * @param ifMatch        sent as {@code If-Match}: the change is refused with {@code 412} if the resource changed
 */
public record RequestOptions(String idempotencyKey, String correlationId, String requestId, String ifMatch) {

    private static final RequestOptions NONE = new RequestOptions(null, null, null, null);

    public static RequestOptions none() {
        return NONE;
    }

    public static RequestOptions idempotent(String idempotencyKey) {
        return NONE.withIdempotencyKey(idempotencyKey);
    }

    public static RequestOptions ifMatch(String etag) {
        return NONE.withIfMatch(etag);
    }

    public RequestOptions withIdempotencyKey(String key) {
        return new RequestOptions(key, correlationId, requestId, ifMatch);
    }

    public RequestOptions withCorrelationId(String id) {
        return new RequestOptions(idempotencyKey, id, requestId, ifMatch);
    }

    public RequestOptions withRequestId(String id) {
        return new RequestOptions(idempotencyKey, correlationId, id, ifMatch);
    }

    public RequestOptions withIfMatch(String etag) {
        return new RequestOptions(idempotencyKey, correlationId, requestId, etag);
    }
}
