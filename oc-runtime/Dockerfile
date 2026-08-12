#
# Copyright © ${year} the original author or authors (piergiorgio@apache.org)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Build stage
FROM maven:3-eclipse-temurin-25 AS builder
WORKDIR /app

# Copy the parent pom and module definitions to cache dependencies
COPY pom.xml .
COPY oc-grpc-api/pom.xml oc-grpc-api/
COPY oc-core/pom.xml oc-core/
COPY oc-observability/pom.xml oc-observability/
COPY oc-filesystem-repository-connector/pom.xml oc-filesystem-repository-connector/
COPY oc-alfresco-repository-connector/pom.xml oc-alfresco-repository-connector/
COPY oc-iceberg-repository-connector/pom.xml oc-iceberg-repository-connector/
COPY oc-flowable-repository-connector/pom.xml oc-flowable-repository-connector/
COPY oc-camunda-repository-connector/pom.xml oc-camunda-repository-connector/
COPY oc-vector-output-connector/pom.xml oc-vector-output-connector/
COPY oc-milvus-output-connector/pom.xml oc-milvus-output-connector/
COPY oc-opensearch2-output-connector/pom.xml oc-opensearch2-output-connector/
COPY oc-opensearch3-output-connector/pom.xml oc-opensearch3-output-connector/
COPY oc-qdrant-output-connector/pom.xml oc-qdrant-output-connector/
COPY oc-vespa-output-connector/pom.xml oc-vespa-output-connector/
COPY oc-admin-ui/pom.xml oc-admin-ui/
COPY oc-runtime/pom.xml oc-runtime/
COPY oc-embedding-service/pom.xml oc-embedding-service/
COPY oc-connector-archetypes/pom.xml oc-connector-archetypes/
COPY oc-connector-archetypes/opencrawling-archetype-repository-connector/pom.xml oc-connector-archetypes/opencrawling-archetype-repository-connector/
COPY oc-connector-archetypes/opencrawling-archetype-output-connector/pom.xml oc-connector-archetypes/opencrawling-archetype-output-connector/
COPY oc-connector-archetypes/opencrawling-archetype-transformation-connector/pom.xml oc-connector-archetypes/opencrawling-archetype-transformation-connector/
COPY oc-java-client-sdk/pom.xml oc-java-client-sdk/

# Download maven dependencies first (saves time on rebuilds if code changes but dependencies do not)
RUN mvn dependency:go-offline -B -DexcludeArtifactIds=oc-grpc-api,oc-core,oc-observability,oc-filesystem-repository-connector,oc-alfresco-repository-connector,oc-iceberg-repository-connector,oc-flowable-repository-connector,oc-camunda-repository-connector,oc-vector-output-connector,oc-milvus-output-connector,oc-admin-ui,oc-runtime,oc-embedding-service,oc-opensearch2-output-connector,oc-opensearch3-output-connector,oc-qdrant-output-connector,oc-vespa-output-connector,opencrawling-connector-archetypes,opencrawling-archetype-repository-connector,opencrawling-archetype-output-connector,opencrawling-archetype-transformation-connector,oc-java-client-sdk

# Copy all source files
COPY oc-grpc-api/src/ /app/oc-grpc-api/src/
COPY oc-core/src/ /app/oc-core/src/
COPY oc-observability/src/ /app/oc-observability/src/
COPY oc-filesystem-repository-connector/src/ /app/oc-filesystem-repository-connector/src/
COPY oc-alfresco-repository-connector/src/ /app/oc-alfresco-repository-connector/src/
COPY oc-iceberg-repository-connector/src/ /app/oc-iceberg-repository-connector/src/
COPY oc-flowable-repository-connector/src/ /app/oc-flowable-repository-connector/src/
COPY oc-camunda-repository-connector/src/ /app/oc-camunda-repository-connector/src/
COPY oc-vector-output-connector/src/ /app/oc-vector-output-connector/src/
COPY oc-milvus-output-connector/src/ /app/oc-milvus-output-connector/src/
COPY oc-opensearch2-output-connector/src/ /app/oc-opensearch2-output-connector/src/
COPY oc-opensearch3-output-connector/src/ /app/oc-opensearch3-output-connector/src/
COPY oc-qdrant-output-connector/src/ /app/oc-qdrant-output-connector/src/
COPY oc-vespa-output-connector/src/ /app/oc-vespa-output-connector/src/
COPY oc-vespa-output-connector/vespa-app/ /app/oc-vespa-output-connector/vespa-app/
COPY oc-embedding-service/src/ /app/oc-embedding-service/src/
COPY oc-connector-archetypes/ /app/oc-connector-archetypes/
COPY oc-java-client-sdk/src/ /app/oc-java-client-sdk/src/

# Copy frontend source files (needed by frontend-maven-plugin in oc-admin-ui)
COPY oc-admin-ui/src/ /app/oc-admin-ui/src/
COPY oc-admin-ui/package.json oc-admin-ui/package-lock.json oc-admin-ui/postcss.config.js oc-admin-ui/tailwind.config.js oc-admin-ui/tsconfig.json oc-admin-ui/tsconfig.node.json oc-admin-ui/vite.config.ts oc-admin-ui/index.html /app/oc-admin-ui/

# Copy backend source files
COPY oc-runtime/src/ /app/oc-runtime/src/

# Build and package the application
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Copy the fat jar from the builder stage
COPY --from=builder /app/oc-runtime/target/oc-runtime-1.0.0-SNAPSHOT.jar app.jar

# Expose the port the backend runs on
EXPOSE 8080

# Run the Spring Boot application enabling Java 25 preview features
ENTRYPOINT ["java", "--enable-preview", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
