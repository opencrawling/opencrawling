#!/usr/bin/env bash
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
# Integration test for the gRPC Push Repository Connector (OisPushService on port 9096)
# and oc-cli `oc push` commands in docker-compose-decoupled.yml:
# 1. Ping health check (`oc push ping`) and Admin API (`oc connector check --name Grpc_Push_Connector`) succeed.
# 2. Unauthenticated `oc push upsert` call is rejected with UNAUTHENTICATED.
# 3. Authenticated `oc push upsert` is accepted by oc-mcp-server, offloaded via Claim-Check,
#    and written to pgvector by the decoupled consumer containers.
# 4. Authenticated `oc push delete` tombstone removes all vector chunks for the document ID.

set -e

# Configuration flags (CLI flags take precedence over environment variables)
SKIP_BUILD="${SKIP_BUILD:-false}"
LOCAL_BUILD="${LOCAL_BUILD:-false}"
KEEP_CONTAINERS="${KEEP_CONTAINERS:-false}"

show_help() {
  cat << EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  -s, --skip-build        Skip docker compose build and reuse existing images
  -l, --local, --local-build
                          Use local Dockerfiles with BuildKit ~/.m2 cache mounts
  -k, --keep, --keep-containers
                          Preserve running containers and volumes on exit/failure
  -h, --help              Display this help message and exit

Environment Variables:
  SKIP_BUILD=true         Equivalent to -s, --skip-build
  LOCAL_BUILD=true        Equivalent to -l, --local-build
  KEEP_CONTAINERS=true    Equivalent to -k, --keep-containers
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    -s|--skip-build)
      SKIP_BUILD=true
      shift
      ;;
    -l|--local|--local-build)
      LOCAL_BUILD=true
      shift
      ;;
    -k|--keep|--keep-containers)
      KEEP_CONTAINERS=true
      shift
      ;;
    -h|--help)
      show_help
      exit 0
      ;;
    *)
      shift
      ;;
  esac
done


GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

export OPENCRAWLING_GRPC_PUSH_ENABLED="${OPENCRAWLING_GRPC_PUSH_ENABLED:-true}"
export OPENCRAWLING_GRPC_PUSH_PORT="${OPENCRAWLING_GRPC_PUSH_PORT:-9096}"
export OPENCRAWLING_GRPC_PUSH_TOKEN="${OPENCRAWLING_GRPC_PUSH_TOKEN:-grpc-push-decoupled-test-token}"

JOB_ID="${GRPC_PUSH_JOB_ID:-1}"
GRPC_HOST="${GRPC_PUSH_HOST:-127.0.0.1}"
GRPC_PORT="${OPENCRAWLING_GRPC_PUSH_PORT}"
BASE_URL="${OPENCRAWLING_BASE_URL:-http://localhost:8080}"
DOC_ID="http://grpc-push-decoupled-test/$(date +%s)/page.txt"
MARKER="grpc-push-decoupled-marker-$(date +%s)"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

COMPOSE_FILE="docker-compose-decoupled.yml"
LOCAL_OVERLAY="${LOCAL_OVERLAY:-${COMPOSE_FILE%.yml}.local.yml}"

compose() {
  if [ "${LOCAL_BUILD}" = true ] && [ -f "${LOCAL_OVERLAY}" ]; then
    docker compose -f "${COMPOSE_FILE}" -f "${LOCAL_OVERLAY}" "$@"
  else
    docker compose -f "${COMPOSE_FILE}" "$@"
  fi
}

cleanup() {
  echo -e "\n${YELLOW}Tearing down decoupled test environment...${NC}"
  compose down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

print_logs() {
  compose logs oc-mcp-server oc-ingestion-consumer oc-embedding-consumer oc-writer-consumer | tail -200
}

# Rows of DOC_ID across the four pgvector tables
rows() {
  docker exec -i postgres-vector-decoupled psql -U opencrawling -d opencrawling -t -A -c \
    "SELECT (SELECT count(*) FROM vector_store WHERE metadata->>'documentId' = '${DOC_ID}')
          + (SELECT count(*) FROM vector_store_384 WHERE metadata->>'documentId' = '${DOC_ID}')
          + (SELECT count(*) FROM vector_store_768 WHERE metadata->>'documentId' = '${DOC_ID}')
          + (SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' = '${DOC_ID}');" 2>/dev/null | tr -d '[:space:]'
}

has_rows() { [ "$(rows)" -gt 0 ] 2>/dev/null; }
has_no_rows() { [ "$(rows)" = "0" ]; }

# wait_until <description> <timeout seconds> <command...>
wait_until() {
  local description="$1" timeout="$2" elapsed=0
  shift 2
  until "$@"; do
    if [ "$elapsed" -ge "$timeout" ]; then
      echo -e "${RED}Timeout after ${timeout}s waiting for ${description}.${NC}"
      print_logs
      exit 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo -e "${GREEN}${description}: ok${NC}"
}

echo -e "${YELLOW}=== gRPC Push Repository Connector, decoupled stack ===${NC}"
echo -e "${YELLOW}Building oc-cli executable JAR...${NC}"
mvn clean package -pl oc-cli -am -DskipTests -q
CLI_JAR=$(ls oc-cli/target/oc-cli-*.jar 2>/dev/null | grep -v 'original' | head -n 1)
RUN_CLI="java --enable-preview -jar ${CLI_JAR}"

compose down --remove-orphans || true
if [ "${SKIP_BUILD}" = true ]; then
  echo -e "${YELLOW:-}SKIP_BUILD=true: Skipping compose build and using existing images.${NC:-}"
else
  echo -e "${YELLOW:-}Building OpenCrawling decoupled microservice images from source (LOCAL_BUILD=${LOCAL_BUILD})...${NC:-}"
  compose build
fi
compose up -d

wait_until "postgres-vector healthy" 180 \
  sh -c '[ "$(docker inspect -f "{{.State.Health.Status}}" postgres-vector-decoupled 2>/dev/null)" = "healthy" ]'
wait_until "Ollama model pulled" 600 \
  sh -c '[ "$(docker inspect -f "{{.State.Status}} {{.State.ExitCode}}" ollama-model-puller-decoupled 2>/dev/null)" = "exited 0" ]'
wait_until "gRPC Push listener ready on ${GRPC_HOST}:${GRPC_PORT}" 180 \
  sh -c "$RUN_CLI push ping --host '${GRPC_HOST}' --port '${GRPC_PORT}' --format json 2>/dev/null | grep -q '\"status\":\"UP\"'"

# Verify Admin REST API connector health check via oc-cli
wait_until "Admin REST API connector check (oc connector check --name Grpc_Push_Connector)" 120 \
  sh -c "$RUN_CLI connector check --name Grpc_Push_Connector --type repository --url '${BASE_URL}' --config host=127.0.0.1 --config port='${GRPC_PORT}' --config token='${OPENCRAWLING_GRPC_PUSH_TOKEN}' >/dev/null 2>&1"

# 1. Verify unauthenticated PushDocument is rejected with UNAUTHENTICATED
set +e
UNAUTH_OUT=$(OPENCRAWLING_GRPC_PUSH_TOKEN="" $RUN_CLI push upsert --host "${GRPC_HOST}" --port "${GRPC_PORT}" --job-id "${JOB_ID}" --id "${DOC_ID}" --text "unauthenticated test" --format json 2>/dev/null)
UNAUTH_RC=$?
set -e
if [ "$UNAUTH_RC" -eq 0 ] || ! echo "$UNAUTH_OUT" | grep -q '"grpcStatus":"UNAUTHENTICATED"'; then
  echo -e "${RED}Expected UNAUTHENTICATED rejection without Bearer token, got rc=${UNAUTH_RC}: ${UNAUTH_OUT}${NC}"
  print_logs
  exit 1
fi
echo -e "${GREEN}Unauthenticated gRPC Push rejected (UNAUTHENTICATED): ok${NC}"

# 2. Push authenticated UPSERT document via oc push upsert
UPSERT_OUT=$($RUN_CLI push upsert \
  --host "${GRPC_HOST}" \
  --port "${GRPC_PORT}" \
  --token "${OPENCRAWLING_GRPC_PUSH_TOKEN}" \
  --job-id "${JOB_ID}" \
  --id "${DOC_ID}" \
  --mime-type "text/plain" \
  --text "OpenCrawling gRPC Push decoupled test document ${MARKER}" \
  --acl-allow "ROLE_USER" \
  --metadata "title=gRPC Push Decoupled Test" \
  --format json)

if ! echo "$UPSERT_OUT" | grep -q '"accepted":true'; then
  echo -e "${RED}gRPC UPSERT was not accepted: ${UPSERT_OUT}${NC}"
  print_logs
  exit 1
fi
echo -e "${GREEN}gRPC UPSERT accepted: ${UPSERT_OUT}${NC}"

# 3. Verify decoupled consumers ingest, chunk, embed, and write to pgvector
wait_until "rows of ${DOC_ID} in pgvector" 300 has_rows
CONTENT=$(docker exec -i postgres-vector-decoupled psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT content FROM vector_store_1024 WHERE metadata->>'documentId' = '${DOC_ID}' LIMIT 1;" 2>/dev/null)
if [[ "$CONTENT" != *"$MARKER"* ]]; then
  echo -e "${RED}The stored chunk does not hold the pushed text: ${CONTENT}${NC}"
  print_logs
  exit 1
fi
echo -e "${GREEN}Verified vector chunk content in pgvector contains marker ${MARKER}${NC}"

# 3b. Verify execution report was generated in /api/reports for the gRPC Push ingestion
wait_until "execution report generated in /api/reports for gRPC Push" 30 \
  sh -c "curl -sf '${BASE_URL}/api/reports' | grep -q 'exec-push-'"

# 4. Push authenticated DELETE tombstone via oc push delete
DELETE_OUT=$($RUN_CLI push delete \
  --host "${GRPC_HOST}" \
  --port "${GRPC_PORT}" \
  --token "${OPENCRAWLING_GRPC_PUSH_TOKEN}" \
  --job-id "${JOB_ID}" \
  --id "${DOC_ID}" \
  --format json)

if ! echo "$DELETE_OUT" | grep -q '"accepted":true'; then
  echo -e "${RED}gRPC DELETE was not accepted: ${DELETE_OUT}${NC}"
  print_logs
  exit 1
fi
echo -e "${GREEN}gRPC DELETE accepted: ${DELETE_OUT}${NC}"

# 5. Verify all vector chunks for DOC_ID are removed from pgvector
wait_until "no rows of ${DOC_ID} left in pgvector" 300 has_no_rows

echo -e "${GREEN}=== gRPC Push Repository Connector decoupled test passed ===${NC}"
