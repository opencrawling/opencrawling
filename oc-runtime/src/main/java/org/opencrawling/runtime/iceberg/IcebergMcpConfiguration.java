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
package org.opencrawling.runtime.iceberg;

import org.opencrawling.iceberg.output.config.IcebergOutputProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables property binding for {@link IcebergMcpProperties} ({@code opencrawling.mcp.iceberg.*}) and
 * {@link IcebergOutputProperties} ({@code spring.opencrawling.output.iceberg.*}) so Iceberg MCP tools
 * and Lakehouse sidecar inspection work alongside any active output backend.
 */
@Configuration
@EnableConfigurationProperties({IcebergMcpProperties.class, IcebergOutputProperties.class})
public class IcebergMcpConfiguration {
}
