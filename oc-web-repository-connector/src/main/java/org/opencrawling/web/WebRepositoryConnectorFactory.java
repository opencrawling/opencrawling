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
package org.opencrawling.web;

import java.util.Map;

import org.opencrawling.core.connector.JobContext;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.connector.RepositoryConnectorFactory;

/**
 * Creates a {@link WebRepositoryConnector} from the connector configuration and the job's crawlSpec.
 */
public class WebRepositoryConnectorFactory implements RepositoryConnectorFactory {

    @Override
    public String className() {
        return WebRepositoryConnector.class.getName();
    }

    @Override
    public RepositoryConnector create(Map<String, String> configuration, JobContext job) {
        WebCrawlSettings settings = WebCrawlSettings.resolve(job.jobSettings(), configuration, job.propertyResolver(), job.path());
        return new WebRepositoryConnector(settings, job.jobId());
    }
}
