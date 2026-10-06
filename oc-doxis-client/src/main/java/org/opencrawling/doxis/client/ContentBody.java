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
package org.opencrawling.doxis.client;

import java.io.InputStream;
import java.util.function.Supplier;

/**
 * A binary streamed as the {@code inputStream} part of a multipart request. The stream is consumed lazily while the request
 * is sent, so content is never buffered in memory. {@code reopenable} tells the client whether the supplier can produce a
 * fresh stream, which is required to retry a request after a throttling or session-expiry response.
 */
public record ContentBody(String fileName, String mimeType, Supplier<InputStream> stream, boolean reopenable) {
}
