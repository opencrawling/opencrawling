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
package org.opencrawling.elasticsearch;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.opencrawling.core.security.ReadKeys;

import java.util.Collection;
import java.util.List;

/**
 * Query clauses for ACL pre-filtering inside Elasticsearch.
 *
 * <p>The filter matches a chunk when one of the caller's {@link ReadKeys} is among the chunk's stored read keys, the
 * same rule the pgvector backend applies. Explicit denies are not expressed here: the MCP access gate re-checks
 * every hit and an explicit deny always wins there.
 */
public final class ElasticsearchAclQueries {

    private ElasticsearchAclQueries() {
    }

    /** Chunks whose read grants include the caller (its principal, one of its groups, or {@code public}). */
    public static Query readableBy(String principal, Collection<String> groups) {
        List<FieldValue> keys = ReadKeys.forCaller(principal, groups).stream().map(FieldValue::of).toList();
        return Query.of(q -> q.terms(t -> t
                .field(ElasticsearchConstants.FIELD_SECURITY_READ_KEYS)
                .terms(v -> v.value(keys))));
    }
}
