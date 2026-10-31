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
package org.opencrawling.s3.client;

import org.junit.jupiter.api.Test;
import org.opencrawling.s3.config.S3OutputProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;

import static org.junit.jupiter.api.Assertions.*;

class S3ClientFactoryTest {

    @Test
    void testDefaultChainCredentialsProvider() {
        S3OutputProperties props = new S3OutputProperties();
        props.setCredentialsStrategy("DEFAULT_CHAIN");

        S3ClientFactory factory = new S3ClientFactory(props);
        AwsCredentialsProvider provider = factory.resolveCredentialsProvider();

        assertInstanceOf(DefaultCredentialsProvider.class, provider);
    }

    @Test
    void testStaticCredentialsProviderBasic() {
        S3OutputProperties props = new S3OutputProperties();
        props.setCredentialsStrategy("STATIC");
        props.setAccessKey("AKIAIOSFODNN7EXAMPLE");
        props.setSecretKey("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");

        S3ClientFactory factory = new S3ClientFactory(props);
        AwsCredentialsProvider provider = factory.resolveCredentialsProvider();

        assertInstanceOf(StaticCredentialsProvider.class, provider);
        AwsCredentials credentials = provider.resolveCredentials();
        assertInstanceOf(AwsBasicCredentials.class, credentials);
        assertEquals("AKIAIOSFODNN7EXAMPLE", credentials.accessKeyId());
        assertEquals("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", credentials.secretAccessKey());
    }

    @Test
    void testStaticCredentialsProviderWithSessionToken() {
        S3OutputProperties props = new S3OutputProperties();
        props.setCredentialsStrategy("STATIC");
        props.setAccessKey("ASIABASIC");
        props.setSecretKey("secret123");
        props.setSessionToken("sessionToken456");

        S3ClientFactory factory = new S3ClientFactory(props);
        AwsCredentialsProvider provider = factory.resolveCredentialsProvider();

        assertInstanceOf(StaticCredentialsProvider.class, provider);
        AwsCredentials credentials = provider.resolveCredentials();
        assertInstanceOf(AwsSessionCredentials.class, credentials);
        assertEquals("sessionToken456", ((AwsSessionCredentials) credentials).sessionToken());
    }

    @Test
    void testCreateS3ClientWithCustomEndpointAndPathStyle() {
        S3OutputProperties props = new S3OutputProperties();
        props.setCredentialsStrategy("STATIC");
        props.setAccessKey("test");
        props.setSecretKey("test");
        props.setEndpointOverride("http://localhost:4566");
        props.setPathStyleAccess(true);
        props.setRegion("us-east-1");
        props.setAutoCreateBucket(false);

        S3ClientFactory factory = new S3ClientFactory(props);
        try (S3Client client = factory.createS3Client()) {
            assertNotNull(client);
            assertEquals("s3", client.serviceName());
        }
    }
}
