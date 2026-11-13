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

# Starts the self-contained tutorial Docker Compose stack (Alfresco 26.1.0,
# PGVector, Redis, Ollama, Kafka, OpenCrawling pipeline consumers, MCP Server,
# and Admin UI) and waits until every service is ready.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TUTORIAL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${TUTORIAL_DIR}/docker/docker-compose-alfresco-pgvector-mcp.yml"
TIMEOUT="${TIMEOUT:-360}"
BUILD_IMAGES="${BUILD_IMAGES:-false}"

BLUE='\033[0;34m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info()  { echo -e "${BLUE}[INFO]${NC} $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_err()   { echo -e "${RED}[ERROR]${NC} $*"; }

for cmd in docker curl jq; do
  command -v "$cmd" >/dev/null 2>&1 || { log_err "Required command '$cmd' not found."; exit 1; }
done

compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

wait_until() {
  local desc="$1"
  local max_wait="$2"
  shift 2
  local elapsed=0
  log_info "Waiting for ${desc} (timeout: ${max_wait}s)..."
  until "$@" >/dev/null 2>&1; do
    if [ "$elapsed" -ge "$max_wait" ]; then
      log_err "Timed out waiting for ${desc} after ${max_wait}s."
      return 1
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  log_ok "${desc} is ready (${elapsed}s)."
}

if [ "${BUILD_IMAGES}" = "true" ]; then
  log_info "Building OpenCrawling Docker images from local workspace..."
  compose build
fi

log_info "Starting OpenCrawling Alfresco + PGVector + MCP tutorial stack..."
compose up -d

wait_until "PGVector (postgres-vector-tutorial)" 90 \
  docker exec postgres-vector-tutorial pg_isready -U opencrawling -d opencrawling

wait_until "Redis Stack (redis-stack-tutorial)" 90 \
  docker exec redis-stack-tutorial redis-cli -a redis_password ping

wait_until "Ollama model puller (mxbai-embed-large)" 600 \
  sh -c '[ "$(docker inspect -f "{{.State.Status}} {{.State.ExitCode}}" ollama-model-puller-tutorial 2>/dev/null)" = "exited 0" ]'

wait_until "Alfresco Content Services (http://localhost:8081)" "${TIMEOUT}" \
  curl -sf http://localhost:8081/alfresco/api/-default-/public/alfresco/versions/1/probes/-ready-

wait_until "OpenCrawling Backend & MCP Server (http://localhost:8080/api/system/status)" 180 \
  curl -sf http://localhost:8080/api/system/status

wait_until "OpenCrawling Admin UI (http://localhost:3000)" 60 \
  curl -sf http://localhost:3000/

echo ""
log_ok "All tutorial services are running and healthy!"
echo "  - Alfresco REST API : http://localhost:8081/alfresco/api/-default-/public/alfresco/versions/1 (admin / admin)"
echo "  - OpenCrawling UI   : http://localhost:3000"
echo "  - OpenCrawling API  : http://localhost:8080/swagger-ui.html"
echo "  - MCP Endpoint      : http://localhost:8080/mcp (Streamable HTTP)"
echo "  - PGVector DB       : localhost:5432 (db: opencrawling, user: opencrawling, password: opencrawling_password)"
echo ""
log_info "Next step: run ${SCRIPT_DIR}/provision-alfresco-data.sh to create test users, groups, documents, and ACLs."
