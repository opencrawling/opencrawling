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
COPY oc-core/pom.xml oc-core/
COPY oc-filesystem-repository-connector/pom.xml oc-filesystem-repository-connector/
COPY oc-vector-output-connector/pom.xml oc-vector-output-connector/
COPY oc-admin-ui/pom.xml oc-admin-ui/
COPY oc-runtime/pom.xml oc-runtime/

# Download maven dependencies first (saves time on rebuilds if code changes but dependencies do not)
RUN mvn dependency:go-offline -B -DexcludeArtifactIds=oc-core,oc-filesystem-repository-connector,oc-vector-output-connector,oc-admin-ui,oc-runtime

# Copy all source files
COPY oc-core/src/ /app/oc-core/src/
COPY oc-filesystem-repository-connector/src/ /app/oc-filesystem-repository-connector/src/
COPY oc-vector-output-connector/src/ /app/oc-vector-output-connector/src/

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
