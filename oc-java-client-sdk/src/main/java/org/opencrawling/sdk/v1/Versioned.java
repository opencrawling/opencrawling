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
package org.opencrawling.sdk.v1;

/**
 * A resource with its version: the {@code ETag} to send as {@code If-Match} when changing it
 * ({@link RequestOptions#ifMatch(String)}), or to ask whether it changed.
 *
 * @param value    the resource
 * @param etag     its version, or null if the runtime sent none
 * @param location the resource's URL path, when the runtime sent one (after a create or a start)
 */
public record Versioned<T>(T value, String etag, String location) {}
