/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file me except in compliance with the License.
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
package org.opencrawling.milvus.messaging;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.mockito.Mockito.*;

class MilvusStoreWriterConsumerTest {

    @Test
    void testConsumeDeleteTombstoneDeletesFromMilvus() throws Exception {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        MilvusStoreWriterConsumer consumer = new MilvusStoreWriterConsumer(client);
        ReflectionTestUtils.setField(consumer, "collectionName", "enterprise_kb");

        DocumentEmbeddedMessage tombstone = new DocumentEmbeddedMessage(
                "doc-milvus-1",
                "chunk-milvus-1",
                "",
                Map.of(),
                new float[0],
                DocumentAction.DELETE
        );

        consumer.consume(tombstone);

        verify(client).delete(any(DeleteReq.class));
    }
}
