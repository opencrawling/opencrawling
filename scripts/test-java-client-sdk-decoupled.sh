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

# End-to-End Integration Test Script for OpenCrawling Java Client SDK (oc-java-client-sdk)
# in Decoupled Multi-Container Deployment Mode.
# Boots the complete OpenCrawling decoupled infrastructure (PostgreSQL+pgvector, Redis, Ollama, Kafka, oc-runtime, oc-embedding-service)
# via docker-compose-decoupled.yml and runs live Java Client SDK integration assertions against the cluster.
set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

PASSED_COUNT=0
TOTAL_TESTS=4

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
echo -e "${BOLD}${YELLOW}=== OpenCrawling Java Client SDK Decoupled Multi-Container Integration Test ===${NC}"
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
# STEP 1: Maven Build & Unit Tests for SDK
# ------------------------------------------------------------------------------
log_step "1" "Compiling and running offline unit tests for oc-java-client-sdk..."
mvn clean test -pl oc-java-client-sdk -Dtest='!LiveSystemIntegrationTest'
log_pass "Compiled and passed offline SDK unit tests (JobClient, ConnectorClient, DocumentPayloadTest)"

# ------------------------------------------------------------------------------
# STEP 2: Boot Decoupled Container Cluster
# ------------------------------------------------------------------------------
log_step "2" "Booting decoupled container cluster (PostgreSQL, Redis, Ollama, Kafka, oc-runtime, oc-embedding-service)..."
compose down --remove-orphans || true
compose build
compose up -d
log_pass "Built and started decoupled container cluster"

# ------------------------------------------------------------------------------
# STEP 3: Wait for Services Health & Readiness
# ------------------------------------------------------------------------------
log_step "3" "Verifying cluster readiness and REST API health endpoint..."
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
log_pass "Decoupled cluster REST API is live and healthy (HTTP 200)"

# ------------------------------------------------------------------------------
# STEP 4: Live Java SDK Integration Test Suite Execution
# ------------------------------------------------------------------------------
log_step "4" "Executing LiveSystemIntegrationTest against live decoupled cluster..."
export OPENCRAWLING_LIVE_TEST=true
export OPENCRAWLING_BASE_URL="http://localhost:8080"

mvn test -pl oc-java-client-sdk -Dtest=LiveSystemIntegrationTest
log_pass "LiveSystemIntegrationTest completed successfully against decoupled cluster"

# ------------------------------------------------------------------------------
# STEP 5: Final Summary Report
# ------------------------------------------------------------------------------
echo -e "\n${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${GREEN}=== JAVA CLIENT SDK DECOUPLED INTEGRATION TEST SUMMARY REPORT ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${GREEN}  Total Test Assertions Executed : $TOTAL_TESTS${NC}"
echo -e "${BOLD}${GREEN}  Passed Assertions              : $PASSED_COUNT / $TOTAL_TESTS${NC}"
echo -e "${BOLD}${GREEN}  Failed Assertions              : 0${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}"

if [ "$PASSED_COUNT" -eq "$TOTAL_TESTS" ]; then
    echo -e "${BOLD}${GREEN}✔ ALL DECOUPLED CONTAINER INTEGRATION TESTS PASSED FOR JAVA CLIENT SDK!${NC}"
    exit 0
else
    log_fail "Some test assertions did not pass ($PASSED_COUNT/$TOTAL_TESTS passed)."
    exit 1
fi
