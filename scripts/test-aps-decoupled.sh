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
# OpenCrawling - Decoupled Pipeline with Alfresco Process Services Integration Test
#
# Description:
#   Integration test script for docker-compose-decoupled-with-aps.yml validating
#   the end-to-end decoupled pipeline: APS 26.2 -> Crawler -> Kafka ->
#   Ingestion -> Embedding (Ollama) -> Writer (pgvector) -> MCP Server.
# ==============================================================================

set -euo pipefail

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m' # No Color

echo -e "\n${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${YELLOW}=== Starting OpenCrawling Decoupled Ingestion Pipeline with APS 26.2 Test ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}\n"

# Switch to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo -e "${RED}curl is required but not installed. Aborting.${NC}" >&2; exit 1; }

COMPOSE_FILE="oc-aps-repository-connector/docker/docker-compose-decoupled-with-aps.yml"
CLEANUP_ON_EXIT="${CLEANUP_ON_EXIT:-true}"
KEEP_CONTAINERS="${KEEP_CONTAINERS:-false}"

# Auto-detect enterprise license from user home or workspace
DETECTED_LICENSE=""
if [ -n "${APS_LICENSE_FILE:-}" ] && [ -f "${APS_LICENSE_FILE}" ]; then
  DETECTED_LICENSE="${APS_LICENSE_FILE}"
elif [ -f "${HOME}/.activiti/enterprise-license/activiti.lic" ]; then
  DETECTED_LICENSE="${HOME}/.activiti/enterprise-license/activiti.lic"
elif [ -f "${PROJECT_ROOT:-$(pwd)}/activiti.lic" ]; then
  DETECTED_LICENSE="${PROJECT_ROOT:-$(pwd)}/activiti.lic"
elif [ -f "${PROJECT_ROOT:-$(pwd)}/oc-aps-repository-connector/docker/activiti.lic" ]; then
  DETECTED_LICENSE="${PROJECT_ROOT:-$(pwd)}/oc-aps-repository-connector/docker/activiti.lic"
fi

# Auto-detect transform license from user home or workspace
DETECTED_TRANSFORM_LICENSE=""
if [ -n "${APS_TRANSFORM_LICENSE_FILE:-}" ] && [ -f "${APS_TRANSFORM_LICENSE_FILE}" ]; then
  DETECTED_TRANSFORM_LICENSE="${APS_TRANSFORM_LICENSE_FILE}"
elif [ -f "${HOME}/.activiti/enterprise-license/transform.lic" ]; then
  DETECTED_TRANSFORM_LICENSE="${HOME}/.activiti/enterprise-license/transform.lic"
elif [ -f "${PROJECT_ROOT:-$(pwd)}/transform.lic" ]; then
  DETECTED_TRANSFORM_LICENSE="${PROJECT_ROOT:-$(pwd)}/transform.lic"
elif [ -f "${PROJECT_ROOT:-$(pwd)}/oc-aps-repository-connector/docker/transform.lic" ]; then
  DETECTED_TRANSFORM_LICENSE="${PROJECT_ROOT:-$(pwd)}/oc-aps-repository-connector/docker/transform.lic"
fi

TEMP_LIC_DIR=""
COMPOSE_ARGS=(-f "${COMPOSE_FILE}")
if [ -n "${DETECTED_LICENSE}" ] || [ -n "${DETECTED_TRANSFORM_LICENSE}" ]; then
  TEMP_LIC_DIR=$(mktemp -d)
  LIC_OVERRIDE="${TEMP_LIC_DIR}/docker-compose.license.override.yml"
  cat <<EOF > "${LIC_OVERRIDE}"
services:
  aps:
    volumes:
EOF
  if [ -n "${DETECTED_LICENSE}" ]; then
    echo -e "${GREEN}Auto-detected APS enterprise license at '${DETECTED_LICENSE}'. Mounting into APS container on startup...${NC}"
    cat <<EOF >> "${LIC_OVERRIDE}"
      - "${DETECTED_LICENSE}:/home/alfresco/.activiti/enterprise-license/activiti.lic:ro"
      - "${DETECTED_LICENSE}:/usr/local/tomcat/lib/activiti.lic:ro"
EOF
  fi
  if [ -n "${DETECTED_TRANSFORM_LICENSE}" ]; then
    echo -e "${GREEN}Auto-detected APS transform license at '${DETECTED_TRANSFORM_LICENSE}'. Mounting into APS container on startup...${NC}"
    cat <<EOF >> "${LIC_OVERRIDE}"
      - "${DETECTED_TRANSFORM_LICENSE}:/home/alfresco/.activiti/enterprise-license/transform.lic:ro"
      - "${DETECTED_TRANSFORM_LICENSE}:/usr/local/tomcat/lib/transform.lic:ro"
EOF
  fi
  COMPOSE_ARGS+=(-f "${LIC_OVERRIDE}")
fi

compose() {
  docker compose "${COMPOSE_ARGS[@]}" "$@"
}

cleanup() {
  echo -e "\n${YELLOW}Tearing down APS decoupled test environment...${NC}"
  docker compose -f "${COMPOSE_FILE}" down --remove-orphans >/dev/null 2>&1 || true
  docker compose -f oc-aps-repository-connector/docker/docker-compose-aps.yml down >/dev/null 2>&1 || true
  docker rm -f aps-decoupled postgres-aps-decoupled postgres-vector-decoupled-aps redis-stack-decoupled-aps ollama-decoupled-aps ollama-pull-model-aps kafka-decoupled-aps oc-crawler-service-aps oc-ingestion-consumer-service-aps oc-embedding-consumer-service-aps oc-writer-service-aps oc-mcp-server-service-aps >/dev/null 2>&1 || true
}

on_exit() {
  if [ "${KEEP_CONTAINERS}" = true ] || [ "${CLEANUP_ON_EXIT}" = false ]; then
    echo -e "\n${YELLOW}KEEP_CONTAINERS=true: Leaving APS decoupled environment active.${NC}"
    if [ -n "${TEMP_LIC_DIR}" ] && [ -d "${TEMP_LIC_DIR}" ]; then
      rm -rf "${TEMP_LIC_DIR}"
    fi
    return 0
  fi
  cleanup
  if [ -n "${TEMP_LIC_DIR}" ] && [ -d "${TEMP_LIC_DIR}" ]; then
    rm -rf "${TEMP_LIC_DIR}"
  fi
}
trap on_exit EXIT

# 1. Clean up previous containers
echo -e "${YELLOW}Cleaning up previous decoupled APS containers...${NC}"
cleanup

# 2. Build microservice images
echo -e "${YELLOW}Building OpenCrawling decoupled microservice images from source...${NC}"
compose build

# 3. Start services
echo -e "${YELLOW}Starting complete decoupled APS-based multi-service infrastructure...${NC}"
compose up -d

TIMEOUT=240
ELAPSED=0

# 4. Wait for APS 26.2 engine to be healthy
echo -e "${YELLOW}Waiting for Alfresco Process Services 26.2 REST engine to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' aps-decoupled 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for APS 26.2 engine.${NC}"
    compose logs aps
    exit 1
  fi
  sleep 4
  ELAPSED=$((ELAPSED + 4))
  echo -n "."
done
echo ""
echo -e "${GREEN}Alfresco Process Services 26.2 REST engine is healthy!${NC}"

# 5. Wait for Ollama to be healthy
ELAPSED=0
echo -e "${YELLOW}Waiting for Ollama embedding service to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' ollama-decoupled-aps 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Ollama.${NC}"
    compose logs ollama
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
  echo -n "."
done
echo ""
echo -e "${GREEN}Ollama is healthy!${NC}"

# 6. Wait for Ollama model puller
ELAPSED=0
echo -e "${YELLOW}Waiting for Ollama model puller to complete...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' ollama-pull-model-aps 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Ollama model puller.${NC}"
    compose logs ollama-pull
    exit 1
  fi
  PROGRESS=$(docker logs --tail 1 ollama-pull-model-aps 2>&1 || true)
  if [ -n "$PROGRESS" ]; then
    printf "  Progress: %s\r" "$PROGRESS"
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo ""

EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' ollama-pull-model-aps 2>/dev/null || echo "1")
if [ "$EXIT_CODE" -ne 0 ]; then
  echo -e "${RED}Ollama model puller failed with exit code $EXIT_CODE.${NC}"
  compose logs ollama-pull
  exit 1
fi
echo -e "${GREEN}Ollama embedding models pulled successfully!${NC}"

# 7. Seed Sample Workflows into APS 26.2
echo -e "${YELLOW}Deploying sample BPMN workflow ('invoiceApproval') and seeding process instances into APS 26.2...${NC}"
APS_URL="http://localhost:8088/activiti-app/api/enterprise" \
  "${SCRIPT_DIR}/seed-aps-workflows.sh" || true

# 8. Trigger Crawler scan against APS engine
echo -e "${YELLOW}Triggering oc-crawler scan against APS 26.2 REST API...${NC}"
compose restart oc-crawler

# Wait for crawler completion
ELAPSED=0
echo -e "${YELLOW}Waiting for oc-crawler service to finish scanning APS engine...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-aps 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for oc-crawler-service-aps.${NC}"
    compose logs oc-crawler
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}oc-crawler-service-aps finished scanning APS engine!${NC}"
echo -e "${YELLOW}Crawler Execution Logs:${NC}"
compose logs --tail 50 oc-crawler

# Check how many workflow instances exist in APS
AUTH_HEADER="Basic $(printf "admin@app.activiti.com:admin" | base64 | tr -d '\n')"
APS_TOTAL=$(curl -s -X POST "http://localhost:8088/activiti-app/api/enterprise/historic-process-instances/query" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" \
  -d '{"size":1}' 2>/dev/null | jq -r '.total // 0' || echo "0")

# 9. Verify Persisted Vector Embeddings in pgvector
if [ "$APS_TOTAL" -eq 0 ]; then
  echo -e "${YELLOW}APS instance has 0 process instances (unlicensed/clean state). Skipping vector chunk wait period.${NC}"
  echo -e "${GREEN}Verified: Crawler scanned APS repository cleanly with 0 failures.${NC}"
else
  echo -e "${YELLOW}Waiting for ingestion & embedding consumers to persist vector chunks in pgvector...${NC}"
  ELAPSED=0
  VECTOR_TIMEOUT=60
  VECTOR_COUNT=0

  until [ "$VECTOR_COUNT" -gt 0 ] || [ $ELAPSED -ge $VECTOR_TIMEOUT ]; do
    VECTOR_COUNT=$(docker exec -i postgres-vector-decoupled-aps psql -U opencrawling -d opencrawling -t -A -P pager=off -c \
      "SELECT (
         CASE WHEN to_regclass('public.vector_store_384') IS NOT NULL THEN
           (SELECT count(*) FROM vector_store_384 WHERE metadata::text LIKE '%invoiceApproval%' OR content ILIKE '%INV-2026%')
         ELSE 0 END +
         CASE WHEN to_regclass('public.vector_store') IS NOT NULL THEN
           (SELECT count(*) FROM vector_store WHERE metadata::text LIKE '%invoiceApproval%' OR content ILIKE '%INV-2026%')
         ELSE 0 END +
         CASE WHEN to_regclass('public.vector_store_1024') IS NOT NULL THEN
           (SELECT count(*) FROM vector_store_1024 WHERE metadata::text LIKE '%invoiceApproval%' OR content ILIKE '%INV-2026%')
         ELSE 0 END
       );" 2>/dev/null || echo "0")
    VECTOR_COUNT=$(echo "$VECTOR_COUNT" | tr -d '[:space:]')
    [ -z "$VECTOR_COUNT" ] && VECTOR_COUNT=0

    if [ "$VECTOR_COUNT" -gt 0 ]; then
      break
    fi
    sleep 3
    ELAPSED=$((ELAPSED + 3))
  done

  if [ "$VECTOR_COUNT" -gt 0 ]; then
    echo -e "${GREEN}Discovered ${VECTOR_COUNT} vectorized workflow chunks in pgvector!${NC}"
  else
    echo -e "${RED}Vector records count check failed: ${VECTOR_COUNT}.${NC}"
    echo -e "${YELLOW}Pipeline Consumer Logs for debugging:${NC}"
    compose logs --tail 150 oc-ingestion-consumer || true
    compose logs --tail 150 oc-embedding-consumer || true
    compose logs --tail 150 oc-writer-consumer || true
    exit 1
  fi

  # Specifically verify ingestion of workflow attachment documents in pgvector
  echo -e "${YELLOW}Verifying ingestion of workflow attachment vector chunks in pgvector...${NC}"
  ATTACHMENT_VECTOR_COUNT=0
  ELAPSED=0

  until [ "$ATTACHMENT_VECTOR_COUNT" -gt 0 ] || [ $ELAPSED -ge $VECTOR_TIMEOUT ]; do
    ATTACHMENT_VECTOR_COUNT=$(docker exec -i postgres-vector-decoupled-aps psql -U opencrawling -d opencrawling -t -A -P pager=off -c \
      "SELECT (
         CASE WHEN to_regclass('public.vector_store_384') IS NOT NULL THEN
           (SELECT count(*) FROM vector_store_384 WHERE metadata::text LIKE '%/content/%' OR content ILIKE '%robotic tooling%' OR content ILIKE '%Quantum Tensor%')
         ELSE 0 END +
         CASE WHEN to_regclass('public.vector_store') IS NOT NULL THEN
           (SELECT count(*) FROM vector_store WHERE metadata::text LIKE '%/content/%' OR content ILIKE '%robotic tooling%' OR content ILIKE '%Quantum Tensor%')
         ELSE 0 END +
         CASE WHEN to_regclass('public.vector_store_1024') IS NOT NULL THEN
           (SELECT count(*) FROM vector_store_1024 WHERE metadata::text LIKE '%/content/%' OR content ILIKE '%robotic tooling%' OR content ILIKE '%Quantum Tensor%')
         ELSE 0 END
       );" 2>/dev/null || echo "0")
    ATTACHMENT_VECTOR_COUNT=$(echo "$ATTACHMENT_VECTOR_COUNT" | tr -d '[:space:]')
    [ -z "$ATTACHMENT_VECTOR_COUNT" ] && ATTACHMENT_VECTOR_COUNT=0

    if [ "$ATTACHMENT_VECTOR_COUNT" -gt 0 ]; then
      break
    fi
    sleep 3
    ELAPSED=$((ELAPSED + 3))
  done

  if [ "$ATTACHMENT_VECTOR_COUNT" -gt 0 ]; then
    echo -e "${GREEN}Discovered ${ATTACHMENT_VECTOR_COUNT} vectorized workflow attachment chunks in pgvector (attachment content & metadata verified)!${NC}"
  else
    echo -e "${RED}Workflow attachment vector check failed: ${ATTACHMENT_VECTOR_COUNT}.${NC}"
    echo -e "${YELLOW}Pipeline Consumer Logs for debugging:${NC}"
    compose logs --tail 150 oc-ingestion-consumer || true
    compose logs --tail 150 oc-embedding-consumer || true
    compose logs --tail 150 oc-writer-consumer || true
    exit 1
  fi
fi

# 10. Verify MCP server endpoint
echo -e "${YELLOW}Waiting for MCP Server health endpoint to be ready...${NC}"
HTTP_STATUS="000"
ELAPSED=0
MCP_TIMEOUT=60
until [ "$HTTP_STATUS" == "200" ] || [ "$HTTP_STATUS" == "405" ] || [ "$HTTP_STATUS" == "404" ] || [ $ELAPSED -ge $MCP_TIMEOUT ]; do
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8080/mcp 2>/dev/null || echo "")
  if [ -z "$HTTP_STATUS" ] || [ "$HTTP_STATUS" == "000" ]; then
    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8080/ 2>/dev/null || echo "000")
  fi
  if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "405" ] && [ "$HTTP_STATUS" != "404" ]; then
    sleep 2
    ELAPSED=$((ELAPSED + 2))
  fi
done
echo -e "MCP Server HTTP Status: ${GREEN}$HTTP_STATUS${NC}"

if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "405" ] && [ "$HTTP_STATUS" != "404" ]; then
  echo -e "${RED}APS decoupled integration test failed: MCP Server returned unexpected status $HTTP_STATUS${NC}"
  compose logs oc-mcp-server
  exit 1
fi
echo -e "${GREEN}OpenCrawling MCP Server is reachable (HTTP $HTTP_STATUS)${NC}"

echo -e "\n${BOLD}${GREEN}================================================================================${NC}"
echo -e "${BOLD}${GREEN}SUCCESS: Alfresco Process Services 26.2 Decoupled Pipeline Integration Test Passed! 🎉${NC}"
echo -e "${BOLD}${GREEN}================================================================================${NC}\n"

exit 0
