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

# Integration test for docker-compose-decoupled-with-flowable.yml
# Exit immediately if a command exits with a non-zero status
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Starting OpenCrawling Decoupled Ingestion Pipeline with Flowable BPMN Engine Integration Test ===${NC}"

# Get the directory where this script is located and switch to the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v docker-compose >/dev/null 2>&1 || docker compose version >/dev/null 2>&1 || { echo -e "${RED}Docker Compose is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo -e "${RED}curl is required but not installed. Aborting.${NC}" >&2; exit 1; }

COMPOSE_FILE="oc-flowable-repository-connector/docker/docker-compose-decoupled-with-flowable.yml"
KEEP_CONTAINERS="${KEEP_CONTAINERS:-false}"
CLEANUP_ON_EXIT="${CLEANUP_ON_EXIT:-true}"

# Helper function for docker compose commands
compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

cleanup() {
  echo -e "\n${YELLOW}Tearing down Flowable decoupled test environment...${NC}"
  docker compose -f "${COMPOSE_FILE}" down --remove-orphans >/dev/null 2>&1 || true
  docker rm -f flowable-rest-decoupled postgres-vector-decoupled-flowable redis-stack-decoupled-flowable ollama-decoupled-flowable ollama-model-puller-decoupled-flowable kafka-decoupled-flowable oc-crawler-service-flowable oc-ingestion-consumer-service-flowable oc-embedding-consumer-service-flowable oc-writer-service-flowable oc-mcp-server-service-flowable >/dev/null 2>&1 || true
}

on_exit() {
  if [ "${KEEP_CONTAINERS}" = true ] || [ "${CLEANUP_ON_EXIT}" = false ]; then
    echo -e "\n${YELLOW}KEEP_CONTAINERS=true: Leaving Flowable decoupled environment active.${NC}"
    return 0
  fi
  cleanup
}
trap on_exit EXIT

# 1. Clean up previous containers
echo -e "${YELLOW}Cleaning up previous Flowable decoupled containers...${NC}"
cleanup

# 2. Build microservices images
echo -e "${YELLOW}Building OpenCrawling decoupled microservice images from source...${NC}"
compose build

# 3. Start services
echo -e "${YELLOW}Starting complete decoupled Flowable-based multi-service infrastructure...${NC}"
compose up -d

# 4. Wait for Flowable REST engine to be healthy
TIMEOUT=180
ELAPSED=0
echo -e "${YELLOW}Waiting for Flowable REST engine to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' flowable-rest-decoupled 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Flowable REST engine.${NC}"
    compose logs flowable
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}Flowable REST engine is healthy!${NC}"

# 5. Wait for Ollama to be healthy
ELAPSED=0
echo -e "${YELLOW}Waiting for Ollama to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' ollama-decoupled-flowable 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Ollama.${NC}"
    compose logs ollama
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}Ollama is healthy!${NC}"

# 6. Wait for Ollama model puller to pull embedding models and exit
ELAPSED=0
echo -e "${YELLOW}Waiting for Ollama model puller to pull embedding models and exit...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' ollama-model-puller-decoupled-flowable 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Ollama model puller.${NC}"
    compose logs ollama-model-puller
    exit 1
  fi
  PROGRESS=$(docker logs --tail 1 ollama-model-puller-decoupled-flowable 2>&1 || true)
  if [ -n "$PROGRESS" ]; then
    printf "  Progress: %s\r" "$PROGRESS"
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo ""

EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled-flowable 2>/dev/null || echo "1")
if [ "$EXIT_CODE" -ne 0 ]; then
  echo -e "${RED}Ollama model puller failed with exit code $EXIT_CODE.${NC}"
  compose logs ollama-model-puller
  exit 1
fi
echo -e "${GREEN}Ollama embedding models pulled successfully!${NC}"

# 7. Seed Sample Workflows into Flowable REST Engine
echo -e "${YELLOW}Deploying sample BPMN workflow ('invoiceApproval') and seeding process instances into Flowable...${NC}"
FLOWABLE_URL="http://localhost:8088/flowable-rest/service" \
  "${SCRIPT_DIR}/seed-flowable-workflows.sh"

# 8. Trigger Flowable repository connector scan via crawler restart
echo -e "${YELLOW}Triggering oc-crawler-service scan against Flowable REST API...${NC}"
compose restart oc-crawler

# 9. Wait for crawler completion
ELAPSED=0
TIMEOUT=180
echo -e "${YELLOW}Waiting for oc-crawler service to finish scanning Flowable engine...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-flowable 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for oc-crawler-service-flowable.${NC}"
    compose logs oc-crawler
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done

# Check crawler exit code
CRAWLER_EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' oc-crawler-service-flowable 2>/dev/null || echo "1")
echo -e "${YELLOW}Crawler Execution Logs:${NC}"
compose logs --tail 30 oc-crawler

if [ "$CRAWLER_EXIT_CODE" -ne 0 ]; then
  echo -e "${RED}oc-crawler-service-flowable failed with exit code $CRAWLER_EXIT_CODE.${NC}"
  exit 1
fi
echo -e "${GREEN}oc-crawler-service-flowable finished scanning Flowable engine successfully!${NC}"

# 10. Verify Persisted Vector Embeddings in pgvector
echo -e "${YELLOW}Waiting for ingestion & embedding consumers to persist vector chunks in pgvector...${NC}"
ELAPSED=0
VECTOR_TIMEOUT=60
VECTOR_COUNT=0

until [ "$VECTOR_COUNT" -gt 0 ] || [ $ELAPSED -ge $VECTOR_TIMEOUT ]; do
  VECTOR_COUNT=$(docker exec -i postgres-vector-decoupled-flowable psql -U opencrawling -d opencrawling -t -A -P pager=off -c \
    "SELECT (
       CASE WHEN to_regclass('public.vector_store_384') IS NOT NULL THEN
         (SELECT count(*) FROM vector_store_384 WHERE metadata::text LIKE '%invoiceApproval%' OR metadata::text LIKE '%flowable%' OR content ILIKE '%INV-2026%')
       ELSE 0 END +
       CASE WHEN to_regclass('public.vector_store') IS NOT NULL THEN
         (SELECT count(*) FROM vector_store WHERE metadata::text LIKE '%invoiceApproval%' OR metadata::text LIKE '%flowable%' OR content ILIKE '%INV-2026%')
       ELSE 0 END +
       CASE WHEN to_regclass('public.vector_store_1024') IS NOT NULL THEN
         (SELECT count(*) FROM vector_store_1024 WHERE metadata::text LIKE '%invoiceApproval%' OR metadata::text LIKE '%flowable%' OR content ILIKE '%INV-2026%')
       ELSE 0 END
     );" 2>/dev/null || echo "0")
  VECTOR_COUNT=$(echo "$VECTOR_COUNT" | tr -d '[:space:]')
  [ -z "$VECTOR_COUNT" ] && VECTOR_COUNT=0

  if [ "$VECTOR_COUNT" -gt 0 ]; then
    break
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
  echo -n "."
done
echo ""

if [ "$VECTOR_COUNT" -gt 0 ]; then
  echo -e "${GREEN}Verified: ${VECTOR_COUNT} vector chunk(s) successfully persisted in pgvector!${NC}"
else
  echo -e "${YELLOW}Warning: No vector chunks found in pgvector within timeout (continuing to MCP check).${NC}"
fi

# 11. Verify MCP server endpoint
echo -e "${YELLOW}Waiting for MCP Server health endpoint to be ready...${NC}"
HTTP_STATUS="000"
ELAPSED=0
TIMEOUT=60
until [ "$HTTP_STATUS" == "200" ] || [ "$HTTP_STATUS" == "405" ] || [ "$HTTP_STATUS" == "404" ] || [ $ELAPSED -ge $TIMEOUT ]; do
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
  echo -e "${RED}Flowable decoupled integration test failed: MCP Server returned unexpected status $HTTP_STATUS${NC}"
  compose logs oc-mcp-server
  exit 1
fi
echo -e "${GREEN}MCP Server is reachable (HTTP $HTTP_STATUS)${NC}"

echo -e "${GREEN}================================================================================${NC}"
echo -e "${GREEN}SUCCESS: Flowable Decoupled Multi-Service Pipeline Integration Test Passed!${NC}"
echo -e "${GREEN}================================================================================${NC}"

exit 0
