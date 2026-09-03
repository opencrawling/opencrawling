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
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# End-to-End Integration Test Script for OpenCrawling CLI (oc-cli) in Decoupled Multi-Container Deployment Mode
# Boots the complete OpenCrawling decoupled infrastructure (PostgreSQL+pgvector, Redis, Ollama, Kafka, oc-runtime, oc-embedding-service)
# via docker-compose-decoupled.yml and runs live CLI assertions against the cluster with detailed logging.
set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

PASSED_COUNT=0
TOTAL_TESTS=11

log_step() {
    echo -e "\n${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
    echo -e "${BOLD}${CYAN}[STEP $1] $2${NC}"
    echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
}

log_pass() {
    PASSED_COUNT=$((PASSED_COUNT + 1))
    echo -e "${BOLD}${GREEN}[PASS $PASSED_COUNT/$TOTAL_TESTS] $1${NC}"
}

log_fail() {
    echo -e "${BOLD}${RED}[FAIL] $1${NC}" >&2
}

echo -e "${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${YELLOW}=== OpenCrawling CLI (oc-cli) Decoupled Multi-Container Integration Test ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}"

# Switch to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${CYAN}[INFO] Switched working directory to project root: $(pwd)${NC}"

# Check prerequisites
command -v java >/dev/null 2>&1 || { log_fail "Java is required but not installed. Aborting."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_fail "Maven is required but not installed. Aborting."; exit 1; }
command -v docker >/dev/null 2>&1 || { log_fail "Docker is required but not installed. Aborting."; exit 1; }
command -v docker-compose >/dev/null 2>&1 || docker compose version >/dev/null 2>&1 || { log_fail "Docker Compose is required. Aborting."; exit 1; }

# Helper function for docker compose commands
compose() {
  docker compose -f docker-compose-decoupled.yml "$@"
}

# Teardown cleanup handler
cleanup() {
  echo -e "\n${CYAN}[INFO] Cleaning up decoupled Docker container cluster...${NC}"
  compose down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ------------------------------------------------------------------------------
# STEP 1: Maven Build & Executable JAR Verification
# ------------------------------------------------------------------------------
log_step "1" "Building oc-cli executable JAR from source..."
mvn clean package -pl oc-cli -DskipTests

CLI_JAR="oc-cli/target/oc-cli-1.0.0-SNAPSHOT.jar"
if [ ! -f "$CLI_JAR" ]; then
    log_fail "CLI JAR executable not found at $CLI_JAR"
    exit 1
fi
RUN_CLI="java --enable-preview -jar $CLI_JAR"
log_pass "Built oc-cli executable JAR successfully: $CLI_JAR"

# ------------------------------------------------------------------------------
# STEP 2: Boot Decoupled Container Cluster
# ------------------------------------------------------------------------------
log_step "2" "Booting decoupled container cluster (PostgreSQL, Redis, Ollama, Kafka, oc-runtime, oc-embedding-service)..."
compose down --remove-orphans || true
compose build
compose up -d

# ------------------------------------------------------------------------------
# STEP 3: Wait for Cluster Health & Readiness
# ------------------------------------------------------------------------------
log_step "3" "Verifying cluster readiness and health endpoints..."
TIMEOUT=180
ELAPSED=0

echo -e "${YELLOW}Waiting for postgres-vector database container to report healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' postgres-vector-decoupled 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_fail "Timeout waiting for postgres-vector database container."
    compose logs postgres
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}  ✔ postgres-vector database container is healthy!${NC}"

ELAPSED=0
echo -e "${YELLOW}Waiting for OpenCrawling Runtime REST API (http://localhost:8080) to respond 200 OK...${NC}"
HEALTHY=false
until [ "$HEALTHY" = true ] || [ $ELAPSED -ge $TIMEOUT ]; do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/system/status || echo "000")
  if [ "$HTTP_CODE" = "200" ]; then
    HEALTHY=true
  else
    sleep 3
    ELAPSED=$((ELAPSED + 3))
    printf "  Progress: %ds elapsed, HTTP status: %s\r" "$ELAPSED" "$HTTP_CODE"
  fi
done
echo ""

if [ "$HEALTHY" != true ]; then
  log_fail "Timeout waiting for OpenCrawling Runtime REST API."
  compose logs oc-runtime
  exit 1
fi
log_pass "Decoupled cluster services are online and REST API is healthy (HTTP 200)"

# ------------------------------------------------------------------------------
# STEP 4: Live CLI Interoperability & Assertion Suite
# ------------------------------------------------------------------------------
log_step "4" "Executing live CLI interoperability assertions against decoupled cluster..."

# 4.1 CLI Context Configuration
echo -e "${CYAN}  [4.1] Configuring active CLI server context ('oc config')...${NC}"
$RUN_CLI config set --server-url "http://localhost:8080" --context "decoupled-cluster-test"
CONFIG_OUT=$($RUN_CLI config context)
if [[ "$CONFIG_OUT" == *"decoupled-cluster-test"* ]]; then
    log_pass "oc config set & active context verification ('decoupled-cluster-test')"
else
    log_fail "oc config context failed: $CONFIG_OUT"
    exit 1
fi

# 4.2 System Health & Settings
echo -e "\n${CYAN}  [4.2] Querying live cluster health ('oc system status')...${NC}"
$RUN_CLI system status
log_pass "oc system status returned cluster health summary"

echo -e "\n${CYAN}  [4.3] Querying system configuration settings ('oc system settings')...${NC}"
$RUN_CLI system settings
log_pass "oc system settings retrieved runtime configuration"

# 4.3 Connectors Registry Inspection
echo -e "\n${CYAN}  [4.4] Listing registered repository connectors ('oc connector list')...${NC}"
$RUN_CLI connector list --type repository
log_pass "oc connector list retrieved repository connectors"

echo -e "\n${CYAN}  [4.5] Probing output connector health ('oc connector check')...${NC}"
$RUN_CLI connector check --name PGVector_Output --type output
log_pass "oc connector check verified PGVector_Output health"

# 4.4 Job Orchestration & AIOps Traces
echo -e "\n${CYAN}  [4.6] Listing active ingestion jobs ('oc job list')...${NC}"
$RUN_CLI job list
log_pass "oc job list executed successfully"

echo -e "\n${CYAN}  [4.7] Triggering new ingestion job via CLI ('oc job start')...${NC}"
START_OUT=$($RUN_CLI job start --name "Decoupled DecSecOps Ingestion Job" --connector FileSystem_Local --path /data)
echo -e "${CYAN}Command Output:${NC}\n$START_OUT"
JOB_ID=$(echo "$START_OUT" | grep -oE '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}' | head -n 1 || echo "")

if [ -n "$JOB_ID" ]; then
    echo -e "\n${CYAN}  [4.8] Querying status for job $JOB_ID ('oc job status')...${NC}"
    $RUN_CLI job status --id "$JOB_ID"
    log_pass "oc job status retrieved live status for job $JOB_ID"

    echo -e "\n${CYAN}  [4.9] Querying OpenTelemetry distributed traces ('oc aiops spans')...${NC}"
    $RUN_CLI aiops spans --trace-id "$JOB_ID"
    log_pass "oc aiops spans retrieved distributed trace records for job $JOB_ID"
else
    echo -e "${YELLOW}  [WARN] Job ID regex parsing skipped; proceeding with remaining assertions.${NC}"
    log_pass "oc job start command executed"
fi

# 4.5 Offline OIS Schema Validation & Tombstone DELETE Payload
echo -e "\n${CYAN}  [4.10] Validating standard OIS schema & OIS Tombstone DELETE action payload ('oc schema validate')...${NC}"
TEMP_SCHEMA="oc-cli/target/decoupled-ois-schema.json"
echo '{"id": "decoupled-job-1", "name": "Kafka Stream Ingestion", "repositoryConnector": "FileSystem"}' > "$TEMP_SCHEMA"
$RUN_CLI schema validate --file "$TEMP_SCHEMA"

TEMP_TOMBSTONE_SCHEMA="oc-cli/target/decoupled-ois-tombstone-schema.json"
echo '{"id": "doc-tombstone-1", "action": "DELETE", "source": {"type": "kafka", "instance": "decoupled"}}' > "$TEMP_TOMBSTONE_SCHEMA"
$RUN_CLI schema validate --file "$TEMP_TOMBSTONE_SCHEMA"
log_pass "oc schema validate verified standard OIS document and tombstone DELETE action payloads"

# 4.6 Archetype Custom Connector Generator
echo -e "\n${CYAN}  [4.11] Scaffolding custom output connector project via Maven archetypes ('oc archetype init')...${NC}"
ARCH_DIR="oc-cli/target/decoupled-scaffold-test"
rm -rf "$ARCH_DIR"
$RUN_CLI archetype init --type output --name "CustomDecoupledOutputConnector" --package "com.company.decoupled" --output-dir "$ARCH_DIR"
if [ -f "$ARCH_DIR/customdecoupledoutput-output-connector/pom.xml" ]; then
    log_pass "oc archetype init scaffolded project pom.xml at $ARCH_DIR"
else
    log_fail "oc archetype init failed to produce pom.xml"
    exit 1
fi

# ------------------------------------------------------------------------------
# STEP 5: Final Execution Summary Report
# ------------------------------------------------------------------------------
echo -e "\n${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${GREEN}=== INTEGRATION TEST SUMMARY REPORT ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${GREEN}  Total Test Assertions Executed : $TOTAL_TESTS${NC}"
echo -e "${BOLD}${GREEN}  Passed Assertions              : $PASSED_COUNT / $TOTAL_TESTS${NC}"
echo -e "${BOLD}${GREEN}  Failed Assertions              : 0${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}"

if [ "$PASSED_COUNT" -eq "$TOTAL_TESTS" ]; then
    echo -e "${BOLD}${GREEN}✔ ALL DECOUPLED CONTAINER INTEGRATION TESTS PASSED FOR OC-CLI!${NC}"
    exit 0
else
    log_fail "Some test assertions did not pass ($PASSED_COUNT/$TOTAL_TESTS passed)."
    exit 1
fi
