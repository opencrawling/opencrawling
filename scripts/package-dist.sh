#!/usr/bin/env bash
#
# Copyright © 2026 the original author or authors (piergiorgio@apache.org)
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

# ==============================================================================
# OpenCrawling - VM & Bare-Metal Distribution ZIP Packager
#
# Builds and packages standalone ZIP distributions for deploying OpenCrawling
# on Virtual Machines (Linux / Windows / macOS) without Docker or Kubernetes.
#
# Output archives in dist/:
#   1. opencrawling-platform-dist-<version>.zip          (Full All-in-One & Decoupled Suite)
#   2. opencrawling-runtime-dist-<version>.zip           (Runtime / Crawler / Consumers / MCP)
#   3. opencrawling-embedding-service-dist-<version>.zip (Stateless Embedding Microservice)
#   4. opencrawling-admin-ui-dist-<version>.zip          (Static React UI + nginx.conf)
#   5. opencrawling-cli-dist-<version>.zip               (Standalone oc CLI + wrappers)
#   6. SHA256SUMS.txt & individual .sha256 files
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
fi
VERSION="${VERSION#v}"

SKIP_BUILD="${SKIP_BUILD:-false}"

echo "================================================================================"
echo "  OpenCrawling VM Distribution Packager — Version: ${VERSION}"
echo "================================================================================"

if [ "$SKIP_BUILD" != "true" ]; then
  echo "[1/6] Building OpenCrawling platform modules with Maven..."
  mvn clean package -pl oc-runtime,oc-embedding-service,oc-admin-ui,oc-cli,oc-mcf-migrator -am -DskipTests -DskipITs
else
  echo "[1/6] Skipping Maven build (SKIP_BUILD=true)..."
fi

DIST_ROOT="${PROJECT_ROOT}/dist"
STAGE_ROOT="${DIST_ROOT}/staging"
rm -rf "$DIST_ROOT"
mkdir -p "$STAGE_ROOT"

RUNTIME_JAR=$(ls oc-runtime/target/oc-runtime-*.jar 2>/dev/null | grep -v 'original' | head -n 1)
EMBEDDING_JAR=$(ls oc-embedding-service/target/oc-embedding-service-*.jar 2>/dev/null | grep -v 'original' | head -n 1)
CLI_JAR=$(ls oc-cli/target/oc-cli-*.jar 2>/dev/null | grep -v 'original' | head -n 1)
MCF_JAR=$(ls oc-mcf-migrator/target/oc-mcf-migrator-*-cli.jar 2>/dev/null | head -n 1 || ls oc-mcf-migrator/target/oc-mcf-migrator-*.jar 2>/dev/null | grep -v 'original' | head -n 1)

if [ -z "$RUNTIME_JAR" ] || [ ! -f "$RUNTIME_JAR" ]; then
  echo "[ERROR] Runtime JAR not found in oc-runtime/target/" >&2
  exit 1
fi
if [ -z "$EMBEDDING_JAR" ] || [ ! -f "$EMBEDDING_JAR" ]; then
  echo "[ERROR] Embedding Service JAR not found in oc-embedding-service/target/" >&2
  exit 1
fi
if [ -z "$CLI_JAR" ] || [ ! -f "$CLI_JAR" ]; then
  echo "[ERROR] CLI JAR not found in oc-cli/target/" >&2
  exit 1
fi

echo "[2/6] Extracting Spring Boot layered runtime (app.jar + lib/ for Apache Tika ForkParser)..."
EXTRACTED_RUNTIME="${STAGE_ROOT}/extracted-runtime"
java -Djarmode=tools -jar "$RUNTIME_JAR" extract --destination "$EXTRACTED_RUNTIME"
EXTRACTED_MAIN_JAR=$(ls "${EXTRACTED_RUNTIME}"/oc-runtime-*.jar | head -n 1)

# ------------------------------------------------------------------------------
# Helper: Generate shared config, launcher scripts, systemd units, and docs
# ------------------------------------------------------------------------------
generate_config_files() {
  local target_dir="$1"
  mkdir -p "${target_dir}/config" "${target_dir}/data/claims" "${target_dir}/data/reports" "${target_dir}/logs"

  cat > "${target_dir}/config/opencrawling.env.example" << 'EOF'
# ==============================================================================
# OpenCrawling VM Environment Configuration
# Copy this file to config/opencrawling.env and adjust for your VM environment.
# ==============================================================================

# Java 25 Runtime Options
JAVA_OPTS="-Xms512m -Xmx2g --enable-preview --enable-native-access=ALL-UNNAMED"

# PostgreSQL / pgvector Connection
SPRING_AI_VECTORSTORE_PGVECTOR_URL=jdbc:postgresql://127.0.0.1:5432/opencrawling
SPRING_AI_VECTORSTORE_PGVECTOR_USERNAME=opencrawling
SPRING_AI_VECTORSTORE_PGVECTOR_PASSWORD=opencrawling_password

# Apache Kafka Bootstrap Servers
SPRING_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092

# Redis Cache / State Store
SPRING_DATA_REDIS_HOST=127.0.0.1
SPRING_DATA_REDIS_PORT=6379
SPRING_DATA_REDIS_PASSWORD=redis_password

# Ollama / OpenAI Embedding Provider
SPRING_AI_OLLAMA_BASE_URL=http://127.0.0.1:11434
SPRING_AI_OPENAI_API_KEY=mock-key

# Local Claim-Check & Lakehouse Audit Report Directories
SPRING_OPENCRAWLING_CLAIM_CHECK_LOCAL_DIR=./data/claims
SPRING_OPENCRAWLING_REPORTING_LOCAL_DIR=./data/reports

# Apache Tika Out-of-Process ForkParser Settings
OPENCRAWLING_TIKA_FORK_ENABLED=true
OPENCRAWLING_TIKA_TIMEOUT_MS=30000
OPENCRAWLING_TIKA_JVM_ARGS=-Xmx512m
OPENCRAWLING_TIKA_MAX_FILES_PER_PROCESS=100000

# Observability (OpenTelemetry)
OPENCRAWLING_OBSERVABILITY_ENABLED=false
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://127.0.0.1:4318/v1/traces

# Server Ports
SERVER_PORT=8080
OPENCRAWLING_GRPC_PORT=9095
OPENCRAWLING_GRPC_PUSH_ENABLED=true
OPENCRAWLING_GRPC_PUSH_PORT=9096
EOF

  cp "${PROJECT_ROOT}/application-dev.yml" "${target_dir}/config/application-vm.yml" 2>/dev/null || true
}

generate_runtime_scripts() {
  local target_dir="$1"
  local runtime_rel_path="$2" # e.g. "." or "runtime"
  mkdir -p "${target_dir}/bin"

  # 1. All-in-One Runtime Launcher (Linux/macOS)
  cat > "${target_dir}/bin/start-runtime.sh" << EOF
#!/usr/bin/env bash
set -euo pipefail
BASE_DIR="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")/.." && pwd)"
cd "\$BASE_DIR"
[ -f "\$BASE_DIR/config/opencrawling.env" ] && set -a && source "\$BASE_DIR/config/opencrawling.env" && set +a
JAVA_CMD="\${JAVA_HOME:+\$JAVA_HOME/bin/}java"
JAVA_OPTS="\${JAVA_OPTS:--Xms512m -Xmx2g}"
export TIKA_EXTRAS_DIR="\$BASE_DIR/${runtime_rel_path}/lib"
exec "\$JAVA_CMD" \$JAVA_OPTS \\
  --enable-preview \\
  --enable-native-access=ALL-UNNAMED \\
  "-Dtika.extras.dir=\$BASE_DIR/${runtime_rel_path}/lib" \\
  -jar "\$BASE_DIR/${runtime_rel_path}/app.jar" "\$@"
EOF

  # 2. All-in-One Runtime Launcher (Windows .bat)
  cat > "${target_dir}/bin/start-runtime.bat" << EOF
@echo off
setlocal
set "BASE_DIR=%~dp0.."
cd /d "%BASE_DIR%"
if defined JAVA_HOME (set "JAVA_CMD=%JAVA_HOME%\bin\java.exe") else (set "JAVA_CMD=java")
if not defined JAVA_OPTS set "JAVA_OPTS=-Xms512m -Xmx2g"
set "TIKA_EXTRAS_DIR=%BASE_DIR%\\${runtime_rel_path}\lib"
"%JAVA_CMD%" %JAVA_OPTS% --enable-preview --enable-native-access=ALL-UNNAMED "-Dtika.extras.dir=%BASE_DIR%\\${runtime_rel_path}\lib" -jar "%BASE_DIR%\\${runtime_rel_path}\app.jar" %*
EOF

  # 3. Decoupled Role: Crawler Node
  cat > "${target_dir}/bin/start-crawler.sh" << 'EOF'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export SPRING_MAIN_WEB_APPLICATION_TYPE="${SPRING_MAIN_WEB_APPLICATION_TYPE:-none}"
export SPRING_OPENCRAWLING_CRAWL_ON_STARTUP="${SPRING_OPENCRAWLING_CRAWL_ON_STARTUP:-true}"
export OPENCRAWLING_CONSUMER_INGESTION_ENABLED=false
export OPENCRAWLING_CONSUMER_EMBEDDING_ENABLED=false
export OPENCRAWLING_CONSUMER_WRITER_ENABLED=false
export OPENCRAWLING_MCP_SERVER_ENABLED=false
export SPRING_AI_MCP_SERVER_ANNOTATION_SCANNER_ENABLED=false
exec "$SCRIPT_DIR/start-runtime.sh" "$@"
EOF

  # 4. Decoupled Role: Ingestion Consumer Node (Apache Tika)
  cat > "${target_dir}/bin/start-ingestion-consumer.sh" << 'EOF'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export SPRING_MAIN_WEB_APPLICATION_TYPE="${SPRING_MAIN_WEB_APPLICATION_TYPE:-none}"
export SPRING_OPENCRAWLING_CRAWL_ON_STARTUP=false
export OPENCRAWLING_CONSUMER_INGESTION_ENABLED=true
export OPENCRAWLING_CONSUMER_EMBEDDING_ENABLED=false
export OPENCRAWLING_CONSUMER_WRITER_ENABLED=false
export OPENCRAWLING_MCP_SERVER_ENABLED=false
export SPRING_AI_MCP_SERVER_ANNOTATION_SCANNER_ENABLED=false
exec "$SCRIPT_DIR/start-runtime.sh" "$@"
EOF

  # 5. Decoupled Role: Vector Store Writer Consumer Node
  cat > "${target_dir}/bin/start-writer-consumer.sh" << 'EOF'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export SPRING_MAIN_WEB_APPLICATION_TYPE="${SPRING_MAIN_WEB_APPLICATION_TYPE:-none}"
export SPRING_OPENCRAWLING_CRAWL_ON_STARTUP=false
export OPENCRAWLING_CONSUMER_INGESTION_ENABLED=false
export OPENCRAWLING_CONSUMER_EMBEDDING_ENABLED=false
export OPENCRAWLING_CONSUMER_WRITER_ENABLED=true
export OPENCRAWLING_MCP_SERVER_ENABLED=false
export SPRING_AI_MCP_SERVER_ANNOTATION_SCANNER_ENABLED=false
exec "$SCRIPT_DIR/start-runtime.sh" "$@"
EOF

  # 6. Decoupled Role: Secure MCP Server & REST Control Plane Node
  cat > "${target_dir}/bin/start-mcp-server.sh" << 'EOF'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export SPRING_OPENCRAWLING_CRAWL_ON_STARTUP=false
export OPENCRAWLING_CONSUMER_INGESTION_ENABLED=false
export OPENCRAWLING_CONSUMER_EMBEDDING_ENABLED=false
export OPENCRAWLING_CONSUMER_WRITER_ENABLED=false
export OPENCRAWLING_MCP_SERVER_ENABLED=true
export SPRING_AI_MCP_SERVER_PROTOCOL="${SPRING_AI_MCP_SERVER_PROTOCOL:-STREAMABLE}"
export SPRING_AI_MCP_SERVER_ANNOTATION_SCANNER_ENABLED=true
exec "$SCRIPT_DIR/start-runtime.sh" "$@"
EOF

  chmod +x "${target_dir}/bin/"*.sh
}

generate_embedding_scripts() {
  local target_dir="$1"
  local jar_rel_path="$2" # e.g. "oc-embedding-service.jar" or "embedding-service/oc-embedding-service.jar"
  mkdir -p "${target_dir}/bin"

  cat > "${target_dir}/bin/start-embedding-service.sh" << EOF
#!/usr/bin/env bash
set -euo pipefail
BASE_DIR="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")/.." && pwd)"
cd "\$BASE_DIR"
[ -f "\$BASE_DIR/config/opencrawling.env" ] && set -a && source "\$BASE_DIR/config/opencrawling.env" && set +a
JAVA_CMD="\${JAVA_HOME:+\$JAVA_HOME/bin/}java"
JAVA_OPTS="\${JAVA_OPTS:--Xms256m -Xmx1g}"
export SERVER_PORT="\${EMBEDDING_SERVER_PORT:-8082}"
exec "\$JAVA_CMD" \$JAVA_OPTS \\
  --enable-preview \\
  --enable-native-access=ALL-UNNAMED \\
  -jar "\$BASE_DIR/${jar_rel_path}" "\$@"
EOF

  cat > "${target_dir}/bin/start-embedding-service.bat" << EOF
@echo off
setlocal
set "BASE_DIR=%~dp0.."
cd /d "%BASE_DIR%"
if defined JAVA_HOME (set "JAVA_CMD=%JAVA_HOME%\bin\java.exe") else (set "JAVA_CMD=java")
if not defined JAVA_OPTS set "JAVA_OPTS=-Xms256m -Xmx1g"
if not defined SERVER_PORT set "SERVER_PORT=8082"
"%JAVA_CMD%" %JAVA_OPTS% --enable-preview --enable-native-access=ALL-UNNAMED -jar "%BASE_DIR%\\${jar_rel_path}" %*
EOF

  chmod +x "${target_dir}/bin/start-embedding-service.sh"
}

generate_cli_scripts() {
  local target_dir="$1"
  local jar_rel_path="$2" # e.g. "oc-cli.jar" or "cli/oc-cli.jar"
  mkdir -p "${target_dir}/bin"

  cat > "${target_dir}/bin/oc" << EOF
#!/usr/bin/env bash
set -euo pipefail
BASE_DIR="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_CMD="\${JAVA_HOME:+\$JAVA_HOME/bin/}java"
exec "\$JAVA_CMD" --enable-preview -jar "\$BASE_DIR/${jar_rel_path}" "\$@"
EOF

  cat > "${target_dir}/bin/oc.bat" << EOF
@echo off
setlocal
set "BASE_DIR=%~dp0.."
if defined JAVA_HOME (set "JAVA_CMD=%JAVA_HOME%\bin\java.exe") else (set "JAVA_CMD=java")
"%JAVA_CMD%" --enable-preview -jar "%BASE_DIR%\\${jar_rel_path}" %*
EOF

  chmod +x "${target_dir}/bin/oc"
}

generate_systemd_units() {
  local target_dir="$1"
  mkdir -p "${target_dir}/systemd"

  cat > "${target_dir}/systemd/opencrawling-runtime.service" << 'EOF'
[Unit]
Description=OpenCrawling Runtime Service (All-in-One / Control Plane)
After=network-online.target postgresql.service kafka.service redis.service
Wants=network-online.target

[Service]
Type=simple
User=opencrawling
Group=opencrawling
WorkingDirectory=/opt/opencrawling
EnvironmentFile=-/opt/opencrawling/config/opencrawling.env
ExecStart=/opt/opencrawling/bin/start-runtime.sh
Restart=on-failure
RestartSec=10
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF

  cat > "${target_dir}/systemd/opencrawling-embedding-service.service" << 'EOF'
[Unit]
Description=OpenCrawling Stateless Embedding Microservice
After=network-online.target kafka.service
Wants=network-online.target

[Service]
Type=simple
User=opencrawling
Group=opencrawling
WorkingDirectory=/opt/opencrawling
EnvironmentFile=-/opt/opencrawling/config/opencrawling.env
ExecStart=/opt/opencrawling/bin/start-embedding-service.sh
Restart=on-failure
RestartSec=10
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
}

generate_install_readme() {
  local target_dir="$1"
  cat > "${target_dir}/INSTALL.md" << EOF
# OpenCrawling v${VERSION} — VM & Bare-Metal Distribution Guide

This distribution package allows you to deploy **OpenCrawling v${VERSION}** directly on Linux, Windows Server, or macOS Virtual Machines **without Docker or Kubernetes**.

## Prerequisites
- **Java 25 (JDK / JRE 25)** installed and available on \`PATH\` or via \`JAVA_HOME\` (e.g., Eclipse Temurin 25).
- Target backing services reachable over the network (PostgreSQL 17+ with \`pgvector\` or alternative vector store, Apache Kafka, Redis, and Ollama/OpenAI if running embeddings).

## Directory Layout
- \`bin/\` — Startup scripts for Linux/macOS (\`.sh\`) and Windows (\`.bat\`), plus the \`oc\` CLI wrapper.
- \`config/\` — Environment template (\`opencrawling.env.example\`) and Spring Boot YAML configuration (\`application-vm.yml\`).
- \`runtime/\` (or root in runtime-dist) — Extracted Spring Boot runtime (\`app.jar\`) and \`lib/\` directory required by Apache Tika's out-of-process \`ForkParser\`.
- \`embedding-service/\` — Stateless Spring AI embedding microservice (\`oc-embedding-service.jar\`).
- \`admin-ui/\` — Pre-built static React Admin UI assets (\`dist/\`) and \`nginx.conf\` (note: the Admin UI is also embedded directly inside \`oc-runtime\` on port \`8080\`).
- \`cli/\` — Standalone OpenCrawling CLI (\`oc-cli.jar\`).
- \`systemd/\` — Ready-to-use Linux \`systemd\` unit files.

## Quick Start on a Linux VM

\`\`\`bash
# 1. Extract the archive to /opt/opencrawling
sudo mkdir -p /opt/opencrawling
sudo unzip opencrawling-platform-dist-${VERSION}.zip -d /opt/opencrawling
cd /opt/opencrawling/opencrawling-platform-dist-${VERSION}

# 2. Configure environment variables
cp config/opencrawling.env.example config/opencrawling.env
vi config/opencrawling.env

# 3. Start All-in-One Runtime (REST API + Embedded Admin UI on :8080 + MCP + gRPC :9095/:9096)
./bin/start-runtime.sh

# Or start individual decoupled roles on dedicated VMs:
./bin/start-crawler.sh
./bin/start-ingestion-consumer.sh
./bin/start-embedding-service.sh
./bin/start-writer-consumer.sh
./bin/start-mcp-server.sh

# 4. Use the bundled CLI
./bin/oc --version
./bin/oc system status
\`\`\`
EOF
}

# ------------------------------------------------------------------------------
# Package 1: Full Platform Bundle (opencrawling-platform-dist-<version>.zip)
# ------------------------------------------------------------------------------
echo "[3/6] Assembling Full Platform Distribution (opencrawling-platform-dist-${VERSION}.zip)..."
PLATFORM_DIR="${STAGE_ROOT}/opencrawling-platform-dist-${VERSION}"
mkdir -p "${PLATFORM_DIR}/runtime" "${PLATFORM_DIR}/embedding-service" "${PLATFORM_DIR}/admin-ui" "${PLATFORM_DIR}/cli" "${PLATFORM_DIR}/mcf-migrator"

cp "$EXTRACTED_MAIN_JAR" "${PLATFORM_DIR}/runtime/app.jar"
cp -R "${EXTRACTED_RUNTIME}/lib" "${PLATFORM_DIR}/runtime/lib"
cp "$EMBEDDING_JAR" "${PLATFORM_DIR}/embedding-service/oc-embedding-service.jar"
cp -R "${PROJECT_ROOT}/oc-admin-ui/dist" "${PLATFORM_DIR}/admin-ui/dist"
cp "${PROJECT_ROOT}/oc-admin-ui/nginx.conf" "${PLATFORM_DIR}/admin-ui/nginx.conf"
cp "$CLI_JAR" "${PLATFORM_DIR}/cli/oc-cli.jar"
if [ -n "$MCF_JAR" ] && [ -f "$MCF_JAR" ]; then
  cp "$MCF_JAR" "${PLATFORM_DIR}/mcf-migrator/oc-mcf-migrator.jar"
fi
cp "${PROJECT_ROOT}/LICENSE" "${PLATFORM_DIR}/LICENSE"

generate_config_files "$PLATFORM_DIR"
generate_runtime_scripts "$PLATFORM_DIR" "runtime"
generate_embedding_scripts "$PLATFORM_DIR" "embedding-service/oc-embedding-service.jar"
generate_cli_scripts "$PLATFORM_DIR" "cli/oc-cli.jar"
generate_systemd_units "$PLATFORM_DIR"
generate_install_readme "$PLATFORM_DIR"

(cd "$STAGE_ROOT" && zip -q -r "${DIST_ROOT}/opencrawling-platform-dist-${VERSION}.zip" "opencrawling-platform-dist-${VERSION}")

# ------------------------------------------------------------------------------
# Package 2: Standalone Runtime Distribution (opencrawling-runtime-dist-<version>.zip)
# ------------------------------------------------------------------------------
echo "[4/6] Assembling Standalone Runtime Distribution (opencrawling-runtime-dist-${VERSION}.zip)..."
RUNTIME_DIST_DIR="${STAGE_ROOT}/opencrawling-runtime-dist-${VERSION}"
mkdir -p "$RUNTIME_DIST_DIR"
cp "$EXTRACTED_MAIN_JAR" "${RUNTIME_DIST_DIR}/app.jar"
cp -R "${EXTRACTED_RUNTIME}/lib" "${RUNTIME_DIST_DIR}/lib"
cp "${PROJECT_ROOT}/LICENSE" "${RUNTIME_DIST_DIR}/LICENSE"

generate_config_files "$RUNTIME_DIST_DIR"
generate_runtime_scripts "$RUNTIME_DIST_DIR" "."
generate_systemd_units "$RUNTIME_DIST_DIR"
generate_install_readme "$RUNTIME_DIST_DIR"

(cd "$STAGE_ROOT" && zip -q -r "${DIST_ROOT}/opencrawling-runtime-dist-${VERSION}.zip" "opencrawling-runtime-dist-${VERSION}")

# ------------------------------------------------------------------------------
# Package 3: Standalone Embedding Service Distribution (opencrawling-embedding-service-dist-<version>.zip)
# ------------------------------------------------------------------------------
echo "[5/6] Assembling Embedding Service, Admin UI & CLI Distributions..."
EMBED_DIST_DIR="${STAGE_ROOT}/opencrawling-embedding-service-dist-${VERSION}"
mkdir -p "$EMBED_DIST_DIR"
cp "$EMBEDDING_JAR" "${EMBED_DIST_DIR}/oc-embedding-service.jar"
cp "${PROJECT_ROOT}/LICENSE" "${EMBED_DIST_DIR}/LICENSE"
generate_config_files "$EMBED_DIST_DIR"
generate_embedding_scripts "$EMBED_DIST_DIR" "oc-embedding-service.jar"
generate_systemd_units "$EMBED_DIST_DIR"

(cd "$STAGE_ROOT" && zip -q -r "${DIST_ROOT}/opencrawling-embedding-service-dist-${VERSION}.zip" "opencrawling-embedding-service-dist-${VERSION}")

# ------------------------------------------------------------------------------
# Package 4: Standalone Admin UI Static Bundle (opencrawling-admin-ui-dist-<version>.zip)
# ------------------------------------------------------------------------------
UI_DIST_DIR="${STAGE_ROOT}/opencrawling-admin-ui-dist-${VERSION}"
mkdir -p "$UI_DIST_DIR"
cp -R "${PROJECT_ROOT}/oc-admin-ui/dist" "${UI_DIST_DIR}/dist"
cp "${PROJECT_ROOT}/oc-admin-ui/nginx.conf" "${UI_DIST_DIR}/nginx.conf"
cp "${PROJECT_ROOT}/LICENSE" "${UI_DIST_DIR}/LICENSE"

(cd "$STAGE_ROOT" && zip -q -r "${DIST_ROOT}/opencrawling-admin-ui-dist-${VERSION}.zip" "opencrawling-admin-ui-dist-${VERSION}")

# ------------------------------------------------------------------------------
# Package 5: Standalone CLI Distribution (opencrawling-cli-dist-<version>.zip)
# ------------------------------------------------------------------------------
CLI_DIST_DIR="${STAGE_ROOT}/opencrawling-cli-dist-${VERSION}"
mkdir -p "$CLI_DIST_DIR"
cp "$CLI_JAR" "${CLI_DIST_DIR}/oc-cli.jar"
cp "${PROJECT_ROOT}/LICENSE" "${CLI_DIST_DIR}/LICENSE"
generate_cli_scripts "$CLI_DIST_DIR" "oc-cli.jar"

(cd "$STAGE_ROOT" && zip -q -r "${DIST_ROOT}/opencrawling-cli-dist-${VERSION}.zip" "opencrawling-cli-dist-${VERSION}")

# ------------------------------------------------------------------------------
# Step 6: Generate SHA-256 Checksums & Clean Staging
# ------------------------------------------------------------------------------
echo "[6/6] Computing SHA-256 checksums..."
rm -rf "$STAGE_ROOT"
cd "$DIST_ROOT"

for z in *.zip; do
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$z" > "${z}.sha256"
  else
    shasum -a 256 "$z" > "${z}.sha256"
  fi
done
cat *.zip.sha256 > SHA256SUMS.txt

echo "================================================================================"
echo "  Successfully generated VM Distribution ZIP packages in ${DIST_ROOT}:"
ls -lh "${DIST_ROOT}"/*.zip "${DIST_ROOT}/SHA256SUMS.txt"
echo "================================================================================"
