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
# Integration test for POST /api/v1/ingest/ois/{jobId} in docker-compose-decoupled.yml:
# the document is accepted by oc-mcp-server and written to pgvector by the separate
# consumer containers, then removed by a DELETE for the same id.

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

export OPENCRAWLING_INGEST_OIS_TOKEN="${OPENCRAWLING_INGEST_OIS_TOKEN:-ois-decoupled-test-token}"
JOB_ID="${OIS_JOB_ID:-1}"
ENDPOINT="http://localhost:8080/api/v1/ingest/ois/${JOB_ID}"
DOC_ID="http://ois-decoupled-test/$(date +%s)/page.html"
MARKER="ois-decoupled-marker-$(date +%s)"

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

# Rows of a document ID across the four pgvector tables
rows() {
  local id="${1:-$DOC_ID}"
  docker exec -i postgres-vector-decoupled psql -U opencrawling -d opencrawling -t -A -c \
    "SELECT (SELECT count(*) FROM vector_store WHERE metadata->>'documentId' = '${id}')
          + (SELECT count(*) FROM vector_store_384 WHERE metadata->>'documentId' = '${id}')
          + (SELECT count(*) FROM vector_store_768 WHERE metadata->>'documentId' = '${id}')
          + (SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' = '${id}');" 2>/dev/null | tr -d '[:space:]'
}

has_rows() { [ "$(rows)" -gt 0 ] 2>/dev/null; }
has_no_rows() { [ "$(rows)" = "0" ]; }
has_rows_for() { [ "$(rows "$1")" -gt 0 ] 2>/dev/null; }
has_no_rows_for() { [ "$(rows "$1")" = "0" ]; }

# post <body> [authorization]: prints the HTTP status
post() {
  if [ -n "$2" ]; then
    curl -s -o /dev/null -w "%{http_code}" --max-time 10 -H "Content-Type: application/json" -H "Authorization: $2" -d "$1" "$ENDPOINT"
  else
    curl -s -o /dev/null -w "%{http_code}" --max-time 10 -H "Content-Type: application/json" -d "$1" "$ENDPOINT"
  fi
}

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

echo -e "${YELLOW}=== OIS ingest endpoint, decoupled stack ===${NC}"
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
# A request without credentials answers 401 once the API is up and the endpoint is enabled
wait_until "endpoint enabled on oc-mcp-server" 180 \
  sh -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X POST -d '{}' '$ENDPOINT')\" = 401 ]"

UPSERT=$(cat <<EOF
{"id": "${DOC_ID}", "action": "UPSERT",
 "content": {"mimeType": "text/html", "text": "OpenCrawling OIS decoupled test page ${MARKER}"},
 "metadata": {"title": "OIS decoupled test"},
 "security": {"inheritanceEnabled": false,
              "permissions": [{"identity": "ROLE_USER", "identityType": "role", "access": "read"}]}}
EOF
)
STATUS=$(post "$UPSERT" "Bearer ${OPENCRAWLING_INGEST_OIS_TOKEN}")
if [ "$STATUS" != "202" ]; then
  echo -e "${RED}UPSERT answered ${STATUS}, expected 202.${NC}"
  print_logs
  exit 1
fi
echo -e "${GREEN}UPSERT accepted (202)${NC}"

wait_until "rows of ${DOC_ID} in pgvector" 300 has_rows
CONTENT=$(docker exec -i postgres-vector-decoupled psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT content FROM vector_store_1024 WHERE metadata->>'documentId' = '${DOC_ID}' LIMIT 1;" 2>/dev/null)
if [[ "$CONTENT" != *"$MARKER"* ]]; then
  echo -e "${RED}The stored chunk does not hold the posted text: ${CONTENT}${NC}"
  exit 1
fi

STATUS=$(post "{\"id\": \"${DOC_ID}\", \"action\": \"DELETE\"}" "Bearer ${OPENCRAWLING_INGEST_OIS_TOKEN}")
if [ "$STATUS" != "202" ]; then
  echo -e "${RED}DELETE answered ${STATUS}, expected 202.${NC}"
  print_logs
  exit 1
fi
echo -e "${GREEN}DELETE accepted (202)${NC}"

wait_until "no rows of ${DOC_ID} left in pgvector" 300 has_no_rows

# Ingest and verify document with empty permissions (Zero-Trust default deny: security_allowed_read="[]")
EMPTY_DOC_ID="http://ois-decoupled-test/$(date +%s)/empty-permissions.html"
EMPTY_MARKER="ois-decoupled-empty-marker-$(date +%s)"

EMPTY_UPSERT=$(cat <<EOF
{"id": "${EMPTY_DOC_ID}", "action": "UPSERT",
 "content": {"mimeType": "text/html", "text": "OpenCrawling OIS empty permissions test page ${EMPTY_MARKER}"},
 "metadata": {"title": "OIS empty permissions test"},
 "security": {"inheritanceEnabled": false,
              "permissions": []}}
EOF
)
STATUS=$(post "$EMPTY_UPSERT" "Bearer ${OPENCRAWLING_INGEST_OIS_TOKEN}")
if [ "$STATUS" != "202" ]; then
  echo -e "${RED}Empty permissions UPSERT answered ${STATUS}, expected 202.${NC}"
  print_logs
  exit 1
fi
echo -e "${GREEN}Empty permissions UPSERT accepted (202)${NC}"

wait_until "rows of ${EMPTY_DOC_ID} in pgvector" 300 has_rows_for "$EMPTY_DOC_ID"

ALLOWED_READ=$(docker exec -i postgres-vector-decoupled psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT metadata->>'security_allowed_read' FROM vector_store_1024 WHERE metadata->>'documentId' = '${EMPTY_DOC_ID}' LIMIT 1;" 2>/dev/null | tr -d '[:space:]')
if [ "$ALLOWED_READ" != "[]" ]; then
  echo -e "${RED}Empty permissions document stored security_allowed_read='${ALLOWED_READ}', expected '[]'${NC}"
  exit 1
fi
echo -e "${GREEN}Empty permissions document stored with empty security_allowed_read: '[]' (not defaulted to public)${NC}"

STATUS=$(post "{\"id\": \"${EMPTY_DOC_ID}\", \"action\": \"DELETE\"}" "Bearer ${OPENCRAWLING_INGEST_OIS_TOKEN}")
if [ "$STATUS" != "202" ]; then
  echo -e "${RED}Empty permissions DELETE answered ${STATUS}, expected 202.${NC}"
  print_logs
  exit 1
fi
echo -e "${GREEN}Empty permissions DELETE accepted (202)${NC}"

wait_until "no rows of ${EMPTY_DOC_ID} left in pgvector" 300 has_no_rows_for "$EMPTY_DOC_ID"

echo -e "${GREEN}=== OIS ingest endpoint decoupled test passed ===${NC}"
