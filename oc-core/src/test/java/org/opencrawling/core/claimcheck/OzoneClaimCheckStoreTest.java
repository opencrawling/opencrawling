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
package org.opencrawling.core.claimcheck;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class OzoneClaimCheckStoreTest {

    @Test
    void testSupportsUriSchemes() {
        ClaimCheckProperties.Ozone ozoneProps = new ClaimCheckProperties.Ozone();
        ozoneProps.setAutoCreateBucket(false);

        // Verify supported URI schemes without calling remote service
        OzoneClaimCheckStore ozoneStore = new OzoneClaimCheckStore(
                null,
                ozoneProps.getBucket(),
                false
        );

        assertThat(ozoneStore.supports(URI.create("s3://claims/doc-123.pdf"))).isTrue();
        assertThat(ozoneStore.supports(URI.create("ofs://s3v/claims/doc-123.pdf"))).isTrue();
        assertThat(ozoneStore.supports(URI.create("file:///data/claims/doc-123.pdf"))).isFalse();
        assertThat(ozoneStore.supports(null)).isFalse();
    }
}
