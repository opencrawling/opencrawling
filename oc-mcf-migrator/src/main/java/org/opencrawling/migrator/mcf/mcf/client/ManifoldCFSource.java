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
package org.opencrawling.migrator.mcf.mcf.client;

import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfJob;

import java.util.List;

/**
 * Anything {@link org.opencrawling.migrator.mcf.engine.MigrationEngine} can extract a
 * ManifoldCF configuration snapshot from — a live REST API ({@link ManifoldCFClient}) or a
 * directory of previously-saved JSON responses ({@link ManifoldCFFileSource}). Both read the
 * identical {@code ConfigurationNode}-shaped JSON (see {@code McfJsonNodes}); only how the raw
 * JSON is obtained differs.
 */
public interface ManifoldCFSource {
    List<McfConnection> listRepositoryConnections();

    List<McfConnection> listOutputConnections();

    List<McfConnection> listTransformationConnections();

    List<McfConnection> listAuthorityConnections();

    List<McfJob> listJobs();
}
