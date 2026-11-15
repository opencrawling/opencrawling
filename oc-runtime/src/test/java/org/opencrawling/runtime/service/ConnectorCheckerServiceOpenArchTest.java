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
package org.opencrawling.runtime.service;

import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.service.ConnectorCheckerService.ConnectionCheckResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ConnectorCheckerServiceOpenArchTest {

    @Test
    public void testCheckOpenArchUnreachable() {
        ConnectorCheckerService checkerService = new ConnectorCheckerService();
        ConnectorDTO connector = new ConnectorDTO(
                "OpenArch_Output",
                "Hyland OpenArch",
                "output",
                "org.opencrawling.openarch.output.OpenArchOutputConnector",
                10,
                Map.of(
                        "endpointUrl", "http://127.0.0.1:59999",
                        "repositoryId", "default"
                )
        );

        ConnectionCheckResult result = checkerService.check(connector);
        assertNotNull(result);
        assertFalse(result.success());
        assertTrue(result.message().contains("Failed to connect to Hyland OpenArch"));
    }
}
