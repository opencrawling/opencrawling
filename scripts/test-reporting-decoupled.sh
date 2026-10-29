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

# End-to-End Integration Test for Lakehouse Audit Reporting & Document Ledger
# in Decoupled Multi-Container Deployment Mode (docker-compose-decoupled.yml).
#
# Validates:
#   1. End-to-end decoupled crawling & Kafka ingestion pipeline
#   2. Generation of Hive-partitioned Parquet, CSV compliance manifest, and JSON summaries
#   3. Persistence on the shared volume (opencrawling-reportsdata:/data/reports)
#   4. REST API endpoints (/api/reports, /api/reports/{jobId}/{executionId}, /documents, /export)
#   5. Embedded serverless DuckDB analytical SQL querying over Parquet files
#   6. CLI commands (oc report list, get, ledger, export, query)
set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

PASSED_COUNT=0
TOTAL_TESTS=13

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
echo -e "${BOLD}${YELLOW}=== OpenCrawling Lakehouse Audit Reporting Decoupled Integration Test ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}"

# Switch to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${CYAN}[INFO] Switched working directory to project root: $(pwd)${NC}"

# Configuration flags
KEEP_CONTAINERS="${KEEP_CONTAINERS:-false}"
CLEANUP_ON_EXIT="${CLEANUP_ON_EXIT:-true}"
REBUILD_IMAGES="${REBUILD_IMAGES:-false}"

compose() {
  docker compose -f docker-compose-decoupled.yml "$@"
}

cleanup() {
  echo -e "\n${CYAN}[INFO] Cleaning up test files and decoupled Docker containers...${NC}"
  rm -f oc-runtime/data/reporting-test-*.txt
  rm -f /tmp/reporting-test-export-*.csv /tmp/reporting-test-export-*.parquet /tmp/reporting-test-export-*.json
  if [ "${KEEP_CONTAINERS}" = true ] || [ "${CLEANUP_ON_EXIT}" = false ]; then
    echo -e "${YELLOW}[INFO] KEEP_CONTAINERS=true: Leaving decoupled environment active.${NC}"
    return 0
  fi
  compose down --remove-orphans >/dev/null 2>&1 || true
  docker volume rm opencrawling-reportsdata >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ------------------------------------------------------------------------------
# STEP 1: Prerequisites Check
# ------------------------------------------------------------------------------
log_step "1" "Checking prerequisites (docker, docker compose, curl, jq, java)..."
command -v docker >/dev/null 2>&1 || { log_fail "Docker is required but not installed."; exit 1; }
command -v docker-compose >/dev/null 2>&1 || docker compose version >/dev/null 2>&1 || { log_fail "Docker Compose is required."; exit 1; }
command -v curl >/dev/null 2>&1 || { log_fail "curl is required."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_fail "jq is required."; exit 1; }
command -v java >/dev/null 2>&1 || { log_fail "java is required."; exit 1; }
log_pass "All required tools are installed."

# ------------------------------------------------------------------------------
# STEP 2: Build CLI JAR (if not present)
# ------------------------------------------------------------------------------
log_step "2" "Ensuring CLI executable JAR is available..."
CLI_JAR=$(ls oc-cli/target/oc-cli-*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || echo "")
if [ -z "$CLI_JAR" ] || [ ! -f "$CLI_JAR" ]; then
    echo -e "${CYAN}Building oc-cli executable JAR...${NC}"
    mvn clean package -pl oc-cli -DskipTests
    CLI_JAR=$(ls oc-cli/target/oc-cli-*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || echo "")
fi
RUN_CLI="java --enable-preview -jar $CLI_JAR"
log_pass "Verified oc-cli executable JAR: $CLI_JAR"

# ------------------------------------------------------------------------------
# STEP 3: Boot Decoupled Container Infrastructure
# ------------------------------------------------------------------------------
log_step "3" "Booting decoupled container cluster via docker-compose-decoupled.yml..."
compose down --remove-orphans >/dev/null 2>&1 || true
docker volume rm opencrawling-reportsdata >/dev/null 2>&1 || true

if [ "$REBUILD_IMAGES" = true ]; then
  echo -e "${CYAN}Rebuilding microservices container images...${NC}"
  compose build
fi

compose up -d

# ------------------------------------------------------------------------------
# STEP 4: Wait for Cluster Readiness
# ------------------------------------------------------------------------------
log_step "4" "Waiting for cluster services to be ready and healthy..."
TIMEOUT=360
ELAPSED=0

echo -e "${YELLOW}Waiting for postgres-vector database container to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' postgres-vector-decoupled 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_fail "Timeout waiting for postgres-vector container."
    compose logs postgres
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}  ✔ postgres-vector database is healthy!${NC}"

ELAPSED=0
echo -e "${YELLOW}Waiting for Ollama to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' ollama-decoupled 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_fail "Timeout waiting for Ollama."
    compose logs ollama
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}  ✔ Ollama is healthy!${NC}"

ELAPSED=0
echo -e "${YELLOW}Waiting for Ollama model puller to pull mxbai-embed-large and exit...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' ollama-model-puller-decoupled 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_fail "Timeout waiting for Ollama model puller."
    compose logs ollama-model-puller
    exit 1
  fi
  PROGRESS=$(docker logs --tail 1 ollama-model-puller-decoupled 2>&1 || true)
  if [ -n "$PROGRESS" ]; then
    printf "  Progress: %s\r" "$PROGRESS"
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo ""

EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled 2>/dev/null || echo "1")
if [ "$EXIT_CODE" -ne 0 ]; then
  log_fail "Ollama model puller failed with exit code $EXIT_CODE."
  compose logs ollama-model-puller
  exit 1
fi
echo -e "${GREEN}  ✔ Ollama embedding model pulled successfully!${NC}"

ELAPSED=0
echo -e "${YELLOW}Waiting for OpenCrawling Runtime REST API (http://localhost:8080) to respond 200 OK...${NC}"
HEALTHY=false
until [ "$HEALTHY" = true ] || [ $ELAPSED -ge $TIMEOUT ]; do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/system/status 2>/dev/null || echo "000")
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
  compose logs oc-mcp-server
  exit 1
fi
log_pass "Decoupled cluster is online and REST API is healthy (HTTP 200)"

# ------------------------------------------------------------------------------
# STEP 5: Prepare Seed Documents for Crawling
# ------------------------------------------------------------------------------
log_step "5" "Preparing seed test documents in mounted /data directory..."
DATA_DIR="./oc-runtime/data"
mkdir -p "$DATA_DIR"

TEST_DOC_1="$DATA_DIR/reporting-test-alpha.txt"
TEST_DOC_2="$DATA_DIR/reporting-test-beta.txt"

cat <<EOF > "$TEST_DOC_1"
OpenCrawling Lakehouse Audit Reporting Test Alpha.
This document verifies high-throughput columnar audit tracking and token governance.
Decoupled Kafka microservices stream this text for text extraction and vector embedding.
EOF

cat <<EOF > "$TEST_DOC_2"
OpenCrawling Lakehouse Audit Reporting Test Beta.
Regulatory compliance manifest verification for SOC2, HIPAA, and GDPR data tracking.
Immutable Parquet ledger stores cryptographic checksums and execution latency.
EOF

chmod 644 "$TEST_DOC_1" "$TEST_DOC_2"
log_pass "Seeded test documents in $DATA_DIR"

# ------------------------------------------------------------------------------
# STEP 6: Execute Crawler and Wait for Completion
# ------------------------------------------------------------------------------
log_step "6" "Triggering directory scan via crawler service and waiting for completion..."
compose restart oc-crawler

ELAPSED=0
CRAWLER_DONE=false
until [ "$CRAWLER_DONE" = true ] || [ $ELAPSED -ge $TIMEOUT ]; do
  RUNNING=$(docker inspect -f '{{.State.Running}}' oc-crawler-service 2>/dev/null || echo "true")
  if [ "$RUNNING" == "false" ]; then
    CRAWLER_DONE=true
  else
    sleep 2
    ELAPSED=$((ELAPSED + 2))
    printf "  Waiting for crawler: %ds elapsed...\r" "$ELAPSED"
  fi
done
echo ""

if [ "$CRAWLER_DONE" != true ]; then
  log_fail "Timeout waiting for oc-crawler-service to finish execution."
  compose logs oc-crawler
  exit 1
fi

CRAWLER_EXIT=$(docker inspect -f '{{.State.ExitCode}}' oc-crawler-service 2>/dev/null || echo "1")
if [ "$CRAWLER_EXIT" -ne 0 ]; then
  log_fail "oc-crawler-service exited with error code: $CRAWLER_EXIT"
  compose logs oc-crawler
  exit 1
fi
log_pass "oc-crawler-service completed directory scanning and exited 0"

# ------------------------------------------------------------------------------
# STEP 7: Verify Vector Ingestion Pipeline
# ------------------------------------------------------------------------------
log_step "7" "Waiting for asynchronous Kafka consumers to process and store vectors..."
RECORD_COUNT=0
ELAPSED=0
VECTOR_TIMEOUT=120
until [ "$RECORD_COUNT" -gt 0 ] 2>/dev/null || [ $ELAPSED -ge $VECTOR_TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))
  RECORD_COUNT=$(docker exec -i postgres-vector-decoupled psql -U opencrawling -d opencrawling -t -A -P pager=off -c \
    "SELECT (SELECT count(*) FROM vector_store) \
          + (SELECT count(*) FROM vector_store_384) \
          + (SELECT count(*) FROM vector_store_768) \
          + (SELECT count(*) FROM vector_store_1024);" 2>/dev/null || echo "0")
  RECORD_COUNT=$(echo "$RECORD_COUNT" | tr -d '[:space:]')
  [ -z "$RECORD_COUNT" ] && RECORD_COUNT=0
  printf "  Elapsed: %ds, Total vector records: %s\r" "$ELAPSED" "$RECORD_COUNT"
done
echo ""

if [ "$RECORD_COUNT" -eq 0 ]; then
  log_fail "No vectors were stored in pgvector database within ${VECTOR_TIMEOUT}s."
  compose logs oc-writer-consumer
  exit 1
fi
log_pass "Verified vectors written to PostgreSQL pgvector (total records: $RECORD_COUNT)"

# ------------------------------------------------------------------------------
# STEP 8: Assert Lakehouse Artifacts on Shared Volume (/data/reports)
# ------------------------------------------------------------------------------
log_step "8" "Verifying Lakehouse audit artifacts generated on shared volume (/data/reports)..."
REPORTS_LIST=$(docker exec -i oc-mcp-server-service find /data/reports -type f 2>/dev/null || echo "")
echo -e "${CYAN}Discovered files in /data/reports on oc-mcp-server-service:${NC}\n$REPORTS_LIST"

PARQUET_FILE=$(echo "$REPORTS_LIST" | grep '\.parquet$' | head -n 1 || echo "")
CSV_FILE=$(echo "$REPORTS_LIST" | grep 'manifest_.*\.csv$' | head -n 1 || echo "")
JSON_FILE=$(echo "$REPORTS_LIST" | grep 'summary_.*\.json$' | head -n 1 || echo "")

if [ -z "$PARQUET_FILE" ]; then
  log_fail "No .parquet audit file found in /data/reports"
  exit 1
fi
if [ -z "$CSV_FILE" ]; then
  log_fail "No manifest_*.csv audit file found in /data/reports"
  exit 1
fi
if [ -z "$JSON_FILE" ]; then
  log_fail "No summary_*.json audit file found in /data/reports"
  exit 1
fi
log_pass "Discovered all Lakehouse artifacts (Parquet, CSV manifest, JSON summary) on shared volume"

# ------------------------------------------------------------------------------
# STEP 9: Verify REST API Summaries (GET /api/reports)
# ------------------------------------------------------------------------------
log_step "9" "Verifying REST API execution summaries endpoint (GET /api/reports)..."
REPORTS_JSON=$(curl -s http://localhost:8080/api/reports)
REPORTS_COUNT=$(echo "$REPORTS_JSON" | jq '. | length' 2>/dev/null || echo "0")

if [ "$REPORTS_COUNT" -lt 1 ]; then
  log_fail "GET /api/reports returned 0 summaries: $REPORTS_JSON"
  exit 1
fi

LATEST_SUMMARY=$(echo "$REPORTS_JSON" | jq '.[0]')
EXECUTION_ID=$(echo "$LATEST_SUMMARY" | jq -r '.executionId')
JOB_ID=$(echo "$LATEST_SUMMARY" | jq -r '.jobId')
PIPELINE_MODE=$(echo "$LATEST_SUMMARY" | jq -r '.pipelineMode')
STATUS=$(echo "$LATEST_SUMMARY" | jq -r '.status')
TOTAL_DOCS=$(echo "$LATEST_SUMMARY" | jq -r '.totalDocuments')
SUCCESS_DOCS=$(echo "$LATEST_SUMMARY" | jq -r '.successfulDocuments')

echo -e "  Latest Execution ID : ${BOLD}${EXECUTION_ID}${NC}"
echo -e "  Job ID              : ${JOB_ID}"
echo -e "  Pipeline Mode       : ${PIPELINE_MODE}"
echo -e "  Status              : ${STATUS}"
echo -e "  Total Documents     : ${TOTAL_DOCS}"
echo -e "  Successful Docs     : ${SUCCESS_DOCS}"

if [ -z "$EXECUTION_ID" ] || [ "$EXECUTION_ID" == "null" ]; then
  log_fail "Invalid executionId in summary response"
  exit 1
fi
if [ "$PIPELINE_MODE" != "RAG" ]; then
  log_fail "Expected pipelineMode 'RAG', got '$PIPELINE_MODE'"
  exit 1
fi
if [ "$TOTAL_DOCS" -lt 1 ]; then
  log_fail "Expected totalDocuments >= 1, got $TOTAL_DOCS"
  exit 1
fi
log_pass "GET /api/reports successfully retrieved valid job report summaries"

# ------------------------------------------------------------------------------
# STEP 10: Verify Single Execution Details (GET /api/reports/{jobId}/{executionId})
# ------------------------------------------------------------------------------
log_step "10" "Verifying specific execution summary (GET /api/reports/${JOB_ID}/${EXECUTION_ID})..."
DETAIL_JSON=$(curl -s "http://localhost:8080/api/reports/${JOB_ID}/${EXECUTION_ID}")
DETAIL_EXEC_ID=$(echo "$DETAIL_JSON" | jq -r '.executionId')
PARQUET_PATH=$(echo "$DETAIL_JSON" | jq -r '.reportPathParquet')
CSV_PATH=$(echo "$DETAIL_JSON" | jq -r '.reportPathCsv')
JSON_PATH=$(echo "$DETAIL_JSON" | jq -r '.reportPathJson')

if [ "$DETAIL_EXEC_ID" != "$EXECUTION_ID" ]; then
  log_fail "Mismatch in executionId from GET /api/reports/${JOB_ID}/${EXECUTION_ID}"
  exit 1
fi
if [ -z "$PARQUET_PATH" ] || [ "$PARQUET_PATH" == "null" ]; then
  log_fail "Missing reportPathParquet in execution detail"
  exit 1
fi
log_pass "GET /api/reports/${JOB_ID}/${EXECUTION_ID} returned matching execution details"

# ------------------------------------------------------------------------------
# STEP 11: Verify Document Ledger & Filters (GET /api/reports/{jobId}/{executionId}/documents)
# ------------------------------------------------------------------------------
log_step "11" "Verifying granular document ledger and status filters..."
LEDGER_JSON=$(curl -s "http://localhost:8080/api/reports/${JOB_ID}/${EXECUTION_ID}/documents?status=SUCCESS&size=50")
ITEM_COUNT=$(echo "$LEDGER_JSON" | jq '.items | length' 2>/dev/null || echo "0")
TOTAL_ITEMS=$(echo "$LEDGER_JSON" | jq '.total' 2>/dev/null || echo "0")

echo -e "  Ledger Items (SUCCESS filter): $ITEM_COUNT / Total: $TOTAL_ITEMS"
if [ "$ITEM_COUNT" -lt 1 ]; then
  log_fail "No documents returned in document ledger for execution $EXECUTION_ID"
  exit 1
fi

FIRST_DOC_ID=$(echo "$LEDGER_JSON" | jq -r '.items[0].documentId')
FIRST_STATUS=$(echo "$LEDGER_JSON" | jq -r '.items[0].status')
FIRST_LATENCY=$(echo "$LEDGER_JSON" | jq -r '.items[0].durationMs')

echo -e "  Sample Doc ID : $FIRST_DOC_ID"
echo -e "  Sample Status : $FIRST_STATUS"
echo -e "  Sample Latency: ${FIRST_LATENCY} ms"

if [ "$FIRST_STATUS" != "SUCCESS" ]; then
  log_fail "Expected status SUCCESS, got $FIRST_STATUS"
  exit 1
fi

# Query with text search
SEARCH_JSON=$(curl -s "http://localhost:8080/api/reports/${JOB_ID}/${EXECUTION_ID}/documents?query=reporting-test")
SEARCH_COUNT=$(echo "$SEARCH_JSON" | jq '.items | length' 2>/dev/null || echo "0")
if [ "$SEARCH_COUNT" -lt 1 ]; then
  log_fail "Text search for 'reporting-test' returned 0 matches in ledger"
  exit 1
fi
log_pass "Verified document ledger retrieval, status filtering, and query search"

# ------------------------------------------------------------------------------
# STEP 12: Verify File Export Endpoints (CSV, Parquet, JSON)
# ------------------------------------------------------------------------------
log_step "12" "Verifying one-click artifact export endpoints (CSV, Parquet, JSON)..."
EXPORT_CSV_PATH="/tmp/reporting-test-export-${EXECUTION_ID}.csv"
EXPORT_PARQUET_PATH="/tmp/reporting-test-export-${EXECUTION_ID}.parquet"
EXPORT_JSON_PATH="/tmp/reporting-test-export-${EXECUTION_ID}.json"

# 12.1 CSV Export
curl -s -f "http://localhost:8080/api/reports/${JOB_ID}/${EXECUTION_ID}/export?format=csv" -o "$EXPORT_CSV_PATH"
if [ ! -s "$EXPORT_CSV_PATH" ]; then
  log_fail "Exported CSV file is empty"
  exit 1
fi
CSV_HEADER=$(head -n 1 "$EXPORT_CSV_PATH")
if [[ "$CSV_HEADER" != *"job_id"* ]] || [[ "$CSV_HEADER" != *"document_id"* ]]; then
  log_fail "CSV export missing standard header fields: $CSV_HEADER"
  exit 1
fi
echo -e "${GREEN}  ✔ CSV manifest successfully exported (${EXPORT_CSV_PATH})${NC}"

# 12.2 Parquet Export & Magic Bytes Verification
curl -s -f "http://localhost:8080/api/reports/${JOB_ID}/${EXECUTION_ID}/export?format=parquet" -o "$EXPORT_PARQUET_PATH"
if [ ! -s "$EXPORT_PARQUET_PATH" ]; then
  log_fail "Exported Parquet file is empty"
  exit 1
fi
# Parquet files must start with magic bytes "PAR1"
PARQUET_MAGIC=$(head -c 4 "$EXPORT_PARQUET_PATH")
if [ "$PARQUET_MAGIC" != "PAR1" ]; then
  log_fail "Parquet file missing magic header 'PAR1', got: '$PARQUET_MAGIC'"
  exit 1
fi
echo -e "${GREEN}  ✔ Columnar Parquet successfully exported with valid PAR1 magic header (${EXPORT_PARQUET_PATH})${NC}"

# 12.3 JSON Export
curl -s -f "http://localhost:8080/api/reports/${JOB_ID}/${EXECUTION_ID}/export?format=json" -o "$EXPORT_JSON_PATH"
if [ ! -s "$EXPORT_JSON_PATH" ]; then
  log_fail "Exported JSON summary file is empty"
  exit 1
fi
jq . "$EXPORT_JSON_PATH" >/dev/null 2>&1 || { log_fail "Exported JSON file is not valid JSON"; exit 1; }
echo -e "${GREEN}  ✔ JSON summary successfully exported (${EXPORT_JSON_PATH})${NC}"

log_pass "All artifact export endpoints (CSV, Parquet, JSON) validated successfully"

# ------------------------------------------------------------------------------
# STEP 13: Verify Serverless DuckDB Analytical Queries (POST /api/reports/query)
# ------------------------------------------------------------------------------
log_step "13" "Executing serverless DuckDB analytical queries over Parquet files..."

# Query 1: Total document count and average duration
SQL_QUERY_1='{"sql": "SELECT count(*) AS doc_count, avg(duration_ms) AS avg_duration_ms FROM read_parquet('\''mode=RAG/**/*.parquet'\'')"}'
QUERY_RES_1=$(curl -s -X POST http://localhost:8080/api/reports/query \
  -H "Content-Type: application/json" \
  -d "$SQL_QUERY_1")

echo -e "  DuckDB Query 1 Response: $QUERY_RES_1"
if echo "$QUERY_RES_1" | grep -E -q '"status":500|"error":'; then
  log_fail "DuckDB query 1 failed with server error: $QUERY_RES_1"
  echo -e "${YELLOW}>>> Dumping oc-mcp-server logs for diagnosis:${NC}"
  compose logs oc-mcp-server | tail -n 60
  exit 1
fi

DOC_COUNT_RES=$(echo "$QUERY_RES_1" | jq -r '.rows[0][0] // empty' 2>/dev/null || echo "")
if [ -z "$DOC_COUNT_RES" ] || [ "$DOC_COUNT_RES" == "null" ] || [ "$DOC_COUNT_RES" -lt 1 ] 2>/dev/null; then
  log_fail "DuckDB query 1 returned document count < 1 (or invalid): $QUERY_RES_1"
  compose logs oc-mcp-server | tail -n 60
  exit 1
fi

# Query 2: Status aggregation query
SQL_QUERY_2='{"sql": "SELECT status, count(*) AS status_count FROM read_parquet('\''mode=RAG/**/*.parquet'\'') GROUP BY status ORDER BY status"}'
QUERY_RES_2=$(curl -s -X POST http://localhost:8080/api/reports/query \
  -H "Content-Type: application/json" \
  -d "$SQL_QUERY_2")

echo -e "  DuckDB Query 2 Response: $QUERY_RES_2"
if echo "$QUERY_RES_2" | grep -E -q '"status":500|"error":'; then
  log_fail "DuckDB query 2 failed with server error: $QUERY_RES_2"
  compose logs oc-mcp-server | tail -n 60
  exit 1
fi

ROW_COUNT_2=$(echo "$QUERY_RES_2" | jq '.rows | length' 2>/dev/null || echo "0")
if [ "$ROW_COUNT_2" -lt 1 ]; then
  log_fail "DuckDB query 2 returned 0 rows: $QUERY_RES_2"
  exit 1
fi
log_pass "Serverless DuckDB analytical queries executed and asserted successfully"

# ------------------------------------------------------------------------------
# STEP 14: Verify CLI Report Commands (`oc report`)
# ------------------------------------------------------------------------------
log_step "14" "Executing CLI reporting commands ('oc report')..."

echo -e "${CYAN}  [14.1] Testing 'oc report list'...${NC}"
$RUN_CLI report list --url http://localhost:8080

echo -e "\n${CYAN}  [14.2] Testing 'oc report get'...${NC}"
$RUN_CLI report get "$JOB_ID" "$EXECUTION_ID" --url http://localhost:8080

echo -e "\n${CYAN}  [14.3] Testing 'oc report ledger'...${NC}"
$RUN_CLI report ledger "$JOB_ID" "$EXECUTION_ID" --status SUCCESS --size 5 --url http://localhost:8080

echo -e "\n${CYAN}  [14.4] Testing 'oc report export'...${NC}"
CLI_EXPORT_OUT="/tmp/cli-exported-report-${EXECUTION_ID}.csv"
$RUN_CLI report export "$JOB_ID" "$EXECUTION_ID" --format csv --out "$CLI_EXPORT_OUT" --url http://localhost:8080
if [ ! -s "$CLI_EXPORT_OUT" ]; then
  log_fail "CLI report export failed to produce file at $CLI_EXPORT_OUT"
  exit 1
fi
rm -f "$CLI_EXPORT_OUT"

echo -e "\n${CYAN}  [14.5] Testing 'oc report query'...${NC}"
$RUN_CLI report query "SELECT count(*) AS total_audited_docs FROM read_parquet('mode=RAG/**/*.parquet')" --url http://localhost:8080

log_pass "All oc report CLI commands (list, get, ledger, export, query) passed"

# ------------------------------------------------------------------------------
# FINAL TEST REPORT
# ------------------------------------------------------------------------------
echo -e "\n${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${GREEN}=== LAKEHOUSE AUDIT REPORTING INTEGRATION TEST SUMMARY ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${GREEN}  Total Test Assertions Executed : $TOTAL_TESTS${NC}"
echo -e "${BOLD}${GREEN}  Passed Assertions              : $PASSED_COUNT / $TOTAL_TESTS${NC}"
echo -e "${BOLD}${GREEN}  Failed Assertions              : 0${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}"

if [ "$PASSED_COUNT" -eq "$TOTAL_TESTS" ]; then
    echo -e "${BOLD}${GREEN}✔ ALL DECOUPLED DOCKER INTEGRATION TESTS PASSED FOR REPORTING FEATURE!${NC}\n"
    exit 0
else
    log_fail "Some test assertions did not pass ($PASSED_COUNT/$TOTAL_TESTS passed)."
    exit 1
fi
