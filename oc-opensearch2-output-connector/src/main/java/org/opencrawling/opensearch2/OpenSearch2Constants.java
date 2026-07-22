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
package org.opencrawling.opensearch2;

public final class OpenSearch2Constants {

    private OpenSearch2Constants() {
        // Prevent instantiation
    }

    public static final String FIELD_ID = "id";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_URI = "uri";
    public static final String FIELD_ACL = "acl";
    public static final String FIELD_LAST_MODIFIED = "lastModified";
    public static final String FIELD_SECURITY_INHERITANCE = "security_inheritance";
    public static final String FIELD_SECURITY_ALLOWED_READ = "security_allowed_read";
    public static final String FIELD_SECURITY_DENIED_READ = "security_denied_read";
    public static final String FIELD_EMBEDDINGS = "embeddings";
}
