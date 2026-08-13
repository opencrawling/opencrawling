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

# End-to-End Integration Test Script for OpenCrawling CLI (oc-cli) in Decoupled Multi-Container Deployment Mode
# Boots the complete OpenCrawling decoupled infrastructure (PostgreSQL+pgvector, Redis, Ollama, Kafka, oc-runtime, oc-embedding-service)
# via docker-compose-decoupled.yml and runs live CLI assertions against the cluster.
set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}=== OpenCrawling CLI (oc-cli) Decoupled Multi-Container Integration Test ===${NC}"
echo -e "${YELLOW}================================================================================${NC}"

# Switch to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${CYAN}[INFO] Switched to project root: $(pwd)${NC}"

# Check prerequisites
command -v java >/dev/null 2>&1 || { echo -e "${RED}[ERROR] Java is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo -e "${RED}[ERROR] Maven is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo -e "${RED}[ERROR] Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v docker-compose >/dev/null 2>&1 || docker compose version >/dev/null 2>&1 || { echo -e "${RED}[ERROR] Docker Compose is required. Aborting.${NC}" >&2; exit 1; }

# Helper function for docker compose commands
compose() {
  docker compose -f docker-compose-decoupled.yml "$@"
}

# Teardown cleanup handler
cleanup() {
  echo -e "${CYAN}[INFO] Cleaning up decoupled Docker container cluster...${NC}"
  compose down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Step 1: Build oc-cli executable JAR
echo -e "${CYAN}[INFO] Step 1: Building oc-cli module and executable JAR...${NC}"
mvn clean package -pl oc-cli -DskipTests

CLI_JAR="oc-cli/target/oc-cli-1.0.0-SNAPSHOT.jar"
if [ ! -f "$CLI_JAR" ]; then
    echo -e "${RED}[ERROR] CLI JAR executable not found at $CLI_JAR${NC}"
    exit 1
fi
RUN_CLI="java --enable-preview -jar $CLI_JAR"

# Step 2: Spin up Decoupled Infrastructure
echo -e "${CYAN}[INFO] Step 2: Tearing down previous containers and building decoupled cluster...${NC}"
compose down --remove-orphans || true
compose build

echo -e "${CYAN}[INFO] Starting decoupled services (PostgreSQL, Redis, Ollama, Kafka, oc-runtime, oc-embedding-service)...${NC}"
compose up -d

# Step 3: Wait for Services Health & Readiness
TIMEOUT=180
ELAPSED=0

echo -e "${YELLOW}Waiting for postgres-vector database to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' postgres-vector-decoupled 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}[ERROR] Timeout waiting for postgres-vector database.${NC}"
    compose logs postgres
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}  ✔ postgres-vector database is healthy!${NC}"

ELAPSED=0
echo -e "${YELLOW}Waiting for OpenCrawling Runtime REST API (http://localhost:8080) to be healthy...${NC}"
HEALTHY=false
until [ "$HEALTHY" = true ] || [ $ELAPSED -ge $TIMEOUT ]; do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/system/status || echo "000")
  if [ "$HTTP_CODE" = "200" ]; then
    HEALTHY=true
  else
    sleep 3
    ELAPSED=$((ELAPSED + 3))
    printf "  Waiting for REST API... (%ds elapsed, HTTP status: %s)\r" "$ELAPSED" "$HTTP_CODE"
  fi
done
echo ""

if [ "$HEALTHY" != true ]; then
  echo -e "${RED}[ERROR] Timeout waiting for OpenCrawling Runtime REST API.${NC}"
  compose logs oc-runtime
  exit 1
fi
echo -e "${GREEN}  ✔ OpenCrawling Runtime REST API is live!${NC}"

# Step 4: Live CLI Interoperability Integration Tests
echo -e "${CYAN}[INFO] Step 4: Executing live CLI integration commands against decoupled cluster...${NC}"

# 4.1 Config Command
echo -e "${CYAN}  -> Configuring CLI active context to decoupled cluster...${NC}"
$RUN_CLI config set --server-url "http://localhost:8080" --context "decoupled-cluster-test" > /dev/null
CONFIG_OUT=$($RUN_CLI config context)
if [[ "$CONFIG_OUT" == *"decoupled-cluster-test"* ]]; then
    echo -e "${GREEN}  ✔ oc config set & context verified.${NC}"
else
    echo -e "${RED}[ERROR] oc config context failed: $CONFIG_OUT${NC}"
    exit 1
fi

# 4.2 System Command
echo -e "${CYAN}  -> Querying live cluster health via 'oc system status'...${NC}"
$RUN_CLI system status
echo -e "${GREEN}  ✔ oc system status passed.${NC}"

echo -e "${CYAN}  -> Querying live system settings via 'oc system settings'...${NC}"
$RUN_CLI system settings > /dev/null
echo -e "${GREEN}  ✔ oc system settings passed.${NC}"

# 4.3 Connector Command
echo -e "${CYAN}  -> Listing repository connectors via 'oc connector list'...${NC}"
$RUN_CLI connector list --type repository
echo -e "${GREEN}  ✔ oc connector list passed.${NC}"

echo -e "${CYAN}  -> Checking output connector health via 'oc connector check'...${NC}"
$RUN_CLI connector check --name PGVector_Output --type output
echo -e "${GREEN}  ✔ oc connector check passed.${NC}"

# 4.4 Job Command
echo -e "${CYAN}  -> Listing jobs via 'oc job list'...${NC}"
$RUN_CLI job list
echo -e "${GREEN}  ✔ oc job list passed.${NC}"

echo -e "${CYAN}  -> Triggering new ingestion job via 'oc job start'...${NC}"
START_OUT=$($RUN_CLI job start --name "Decoupled DecSecOps Ingestion Job" --connector FileSystem_Local --path /data)
echo "$START_OUT"
JOB_ID=$(echo "$START_OUT" | grep -oE '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}' | head -n 1 || echo "")

if [ -n "$JOB_ID" ]; then
    echo -e "${GREEN}  ✔ Job triggered successfully. Captured Job ID: $JOB_ID${NC}"
    
    echo -e "${CYAN}  -> Inspecting job status via 'oc job status --id $JOB_ID'...${NC}"
    $RUN_CLI job status --id "$JOB_ID"
    echo -e "${GREEN}  ✔ oc job status passed.${NC}"

    echo -e "${CYAN}  -> Fetching OpenTelemetry trace spans via 'oc aiops spans --trace-id $JOB_ID'...${NC}"
    $RUN_CLI aiops spans --trace-id "$JOB_ID" > /dev/null
    echo -e "${GREEN}  ✔ oc aiops spans passed.${NC}"
else
    echo -e "${YELLOW}  [WARN] Job ID regex parsing skipped, proceeding with listing assertions.${NC}"
fi

# 4.5 Schema Command
echo -e "${CYAN}  -> Testing offline schema validation via 'oc schema validate'...${NC}"
TEMP_SCHEMA="oc-cli/target/decoupled-ois-schema.json"
echo '{"id": "decoupled-job-1", "name": "Kafka Stream Ingestion", "repositoryConnector": "FileSystem"}' > "$TEMP_SCHEMA"
$RUN_CLI schema validate --file "$TEMP_SCHEMA" > /dev/null
echo -e "${GREEN}  ✔ oc schema validate passed.${NC}"

# 4.6 Archetype Command
echo -e "${CYAN}  -> Testing archetype project generator via 'oc archetype init'...${NC}"
ARCH_DIR="oc-cli/target/decoupled-scaffold-test"
rm -rf "$ARCH_DIR"
$RUN_CLI archetype init --type output --name "CustomDecoupledOutputConnector" --package "com.company.decoupled" --output-dir "$ARCH_DIR" > /dev/null
if [ -f "$ARCH_DIR/customdecoupledoutput-output-connector/pom.xml" ]; then
    echo -e "${GREEN}  ✔ oc archetype init passed.${NC}"
else
    echo -e "${RED}[ERROR] oc archetype init failed.${NC}"
    exit 1
fi

echo -e "${YELLOW}================================================================================${NC}"
echo -e "${GREEN}=== ALL DECOUPLED CONTAINER INTEGRATION TESTS PASSED FOR OC-CLI! ===${NC}"
echo -e "${YELLOW}================================================================================${NC}"
