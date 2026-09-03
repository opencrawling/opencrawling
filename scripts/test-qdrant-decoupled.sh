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

# Integration test for docker-compose-decoupled-with-qdrant.yml
# Exit immediately if a command exits with a non-zero status
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Starting OpenCrawling Decoupled Ingestion Pipeline with Qdrant Integration Test ===${NC}"

# Get the directory where this script is located and switch to the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v docker-compose >/dev/null 2>&1 || docker compose version >/dev/null 2>&1 || { echo -e "${RED}Docker Compose is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo -e "${RED}curl is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo -e "${RED}jq is required but not installed. Aborting.${NC}" >&2; exit 1; }

COMPOSE_FILE="oc-qdrant-output-connector/docker/docker-compose-decoupled-with-qdrant.yml"

# Helper function for docker compose commands
compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

# Clean up any existing containers
echo -e "${YELLOW}Cleaning up previous Qdrant decoupled containers...${NC}"
compose down --remove-orphans || true

# Build microservices images
echo -e "${YELLOW}Building OpenCrawling decoupled microservice images from source...${NC}"
compose build

# Start services
echo -e "${YELLOW}Starting complete decoupled Qdrant-based multi-service infrastructure...${NC}"
compose up -d

# Define timeout (in seconds)
TIMEOUT=180
ELAPSED=0

echo -e "${YELLOW}Waiting for Qdrant vector database to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' qdrant-standalone 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Qdrant standalone.${NC}"
    compose logs qdrant
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}Qdrant standalone is healthy!${NC}"

# Reset elapsed timer
ELAPSED=0
echo -e "${YELLOW}Waiting for Ollama to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' ollama-decoupled-qdrant 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Ollama.${NC}"
    compose logs ollama
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}Ollama is healthy!${NC}"

# Reset elapsed timer
ELAPSED=0
echo -e "${YELLOW}Waiting for Ollama model puller to pull embedding models and exit...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' ollama-model-puller-decoupled-qdrant 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Ollama model puller.${NC}"
    compose logs ollama-model-puller
    exit 1
  fi
  PROGRESS=$(docker logs --tail 1 ollama-model-puller-decoupled-qdrant 2>&1 || true)
  if [ ! -z "$PROGRESS" ]; then
    printf "  Progress: %s\r" "$PROGRESS"
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo ""

# Check exit code of model puller
EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled-qdrant 2>/dev/null || echo "1")
if [ "$EXIT_CODE" -ne 0 ]; then
  echo -e "${RED}Ollama model puller failed with exit code $EXIT_CODE.${NC}"
  compose logs ollama-model-puller
  exit 1
fi
echo -e "${GREEN}Ollama embedding models pulled successfully!${NC}"

# Create a sample test document in the mounted directory
TEST_DOC_DIR="./oc-runtime/data"
mkdir -p "$TEST_DOC_DIR"
TEST_FILE="$TEST_DOC_DIR/qdrant-decoupled-integration-test.txt"
echo "OpenCrawling is an awesome open-source pipeline! Decoupled integration test with Qdrant worked successfully." > "$TEST_FILE"
echo -e "${GREEN}Created test document: $TEST_FILE${NC}"

# Restart crawler to trigger directory scan and Kafka publication
echo -e "${YELLOW}Restarting crawler service to trigger directory scan...${NC}"
compose restart oc-crawler

# Wait for crawler completion
ELAPSED=0
echo -e "${YELLOW}Waiting for oc-crawler service to finish execution...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-qdrant 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for oc-crawler-service-qdrant.${NC}"
    compose logs oc-crawler
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}oc-crawler-service-qdrant finished directory scanning!${NC}"

# Wait for messaging pipeline to process document vectors
echo -e "${YELLOW}Waiting for Kafka consumers to process and store vectors in Qdrant...${NC}"
RECORD_COUNT=0
ELAPSED=0
TIMEOUT=120
until [ "$RECORD_COUNT" -gt 0 ] 2>/dev/null || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))

  # Count points in Qdrant collection via REST API
  COLL_INFO=$(curl -s http://localhost:6333/collections/enterprise_kb 2>/dev/null || echo "{}")
  RECORD_COUNT=$(echo "$COLL_INFO" | jq -r '.result.points_count // 0' 2>/dev/null || echo "0")

  if [ -z "$RECORD_COUNT" ] || [ "$RECORD_COUNT" == "null" ]; then
    RECORD_COUNT=0
  fi
  printf "  Elapsed: %ds, Total vector points in Qdrant enterprise_kb: %s\r" "$ELAPSED" "$RECORD_COUNT"
done
echo ""

# Verify Qdrant collection content
echo -e "${YELLOW}Verifying Qdrant collection records...${NC}"
echo -e "Qdrant Points count: ${GREEN}$RECORD_COUNT${NC}"
if [ "$RECORD_COUNT" -eq 0 ]; then
  echo -e "${RED}Qdrant decoupled integration test failed: 0 points found in Qdrant collection!${NC}"
  echo -e "${YELLOW}Printing consumer service logs for diagnosis...${NC}"
  compose logs oc-writer-consumer
  exit 1
fi

# Verify MCP server endpoint
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
  echo -e "${RED}Qdrant decoupled integration test failed: MCP Server returned unexpected status $HTTP_STATUS${NC}"
  compose logs oc-mcp-server
  exit 1
fi
echo -e "${GREEN}MCP Server is reachable (HTTP $HTTP_STATUS)${NC}"

# Verify OIS Document Lifecycle Tombstone DELETE action feature
echo -e "${YELLOW}Executing OIS Document Lifecycle Tombstone DELETE action test for Qdrant...${NC}"
mvn test -pl oc-qdrant-output-connector -Dtest=QdrantStoreWriterConsumerTest
echo -e "${GREEN}OIS Tombstone DELETE action integration step for Qdrant passed!${NC}"

echo -e "${GREEN}================================================================================${NC}"
echo -e "${GREEN}SUCCESS: Qdrant Decoupled Multi-Service Pipeline Integration Test Passed!${NC}"
echo -e "${GREEN}================================================================================${NC}"

# Clean up temporary test files
echo -e "${YELLOW}Cleaning up temporary test files...${NC}"
rm -f "$TEST_FILE"

# Tear down the test environment
echo -e "${YELLOW}Tearing down test environment...${NC}"
compose down

exit 0
