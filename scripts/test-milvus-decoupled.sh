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

# Integration test for docker-compose-decoupled-with-milvus.yml
# Exit immediately if a command exits with a non-zero status
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Starting OpenCrawling Decoupled Ingestion Pipeline with Milvus Integration Test ===${NC}"

# Get the directory where this script is located and switch to the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v docker-compose >/dev/null 2>&1 || docker compose version >/dev/null 2>&1 || { echo -e "${RED}Docker Compose is required but not installed. Aborting.${NC}" >&2; exit 1; }

# Helper function for docker compose commands
compose() {
  docker compose -f oc-milvus-output-connector/docker/docker-compose-decoupled-with-milvus.yml "$@"
}

# Clean up any existing containers
echo -e "${YELLOW}Cleaning up previous Milvus decoupled containers...${NC}"
compose down --remove-orphans || true

# Build microservices images
echo -e "${YELLOW}Building OpenCrawling decoupled microservice images from source...${NC}"
compose build

# Start services
echo -e "${YELLOW}Starting complete decoupled Milvus-based multi-service infrastructure...${NC}"
compose up -d

# Define timeout (in seconds)
TIMEOUT=180
ELAPSED=0

echo -e "${YELLOW}Waiting for Milvus standalone to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' milvus-standalone 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Milvus standalone.${NC}"
    compose logs milvus-standalone
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}Milvus standalone is healthy!${NC}"

# Reset elapsed timer
ELAPSED=0
echo -e "${YELLOW}Waiting for Ollama to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' ollama-decoupled-milvus 2>/dev/null || echo 'starting')" == "healthy" ]; do
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
echo -e "${YELLOW}Waiting for Ollama model puller to pull mxbai-embed-large and exit...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' ollama-model-puller-decoupled-milvus 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Ollama model puller.${NC}"
    compose logs ollama-model-puller
    exit 1
  fi
  # Output the latest log line from the model puller to show download progress status
  PROGRESS=$(docker logs --tail 1 ollama-model-puller-decoupled-milvus 2>&1 || true)
  if [ ! -z "$PROGRESS" ]; then
    printf "  Progress: %s\r" "$PROGRESS"
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo ""

# Check exit code of model puller
EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled-milvus 2>/dev/null || echo "1")
if [ "$EXIT_CODE" -ne 0 ]; then
  echo -e "${RED}Ollama model puller failed with exit code $EXIT_CODE.${NC}"
  compose logs ollama-model-puller
  exit 1
fi
echo -e "${GREEN}Ollama embedding model pulled successfully!${NC}"

# Create a sample test document in the mounted directory
TEST_DOC_DIR="./oc-runtime/data"
mkdir -p "$TEST_DOC_DIR"
TEST_FILE="$TEST_DOC_DIR/milvus-decoupled-integration-test.txt"
echo "OpenCrawling is an awesome open-source pipeline! Decoupled integration test with Milvus worked successfully." > "$TEST_FILE"
echo -e "${GREEN}Created test document: $TEST_FILE${NC}"

# Restart crawler to trigger directory scan and Kafka publication
echo -e "${YELLOW}Restarting crawler service to trigger directory scan...${NC}"
compose restart oc-crawler

# Wait for crawler completion
ELAPSED=0
echo -e "${YELLOW}Waiting for oc-crawler service to finish execution...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-milvus 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for oc-crawler-service-milvus.${NC}"
    compose logs oc-crawler
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}oc-crawler-service-milvus finished directory scanning!${NC}"

# Wait for messaging pipeline to process document vectors (with retries)
echo -e "${YELLOW}Waiting for Kafka consumers to process and store vectors in Milvus...${NC}"
RECORD_COUNT=0
ELAPSED=0
TIMEOUT=120  # Generous timeout: Ollama embedding is CPU-bound and slow on first run
until [ "$RECORD_COUNT" -gt 0 ] 2>/dev/null || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))
  
  # Count entities in Milvus collection via its REST API query endpoint
  RECORD_COUNT=$(curl -s --request POST \
    --url "http://localhost:19530/v2/vectordb/entities/query" \
    --header "Authorization: Bearer root:Milvus" \
    --header "Content-Type: application/json" \
    -d '{"collectionName": "enterprise_kb", "filter": "id != \"\"", "outputFields": ["id"]}' | grep -o '"id":' | wc -l || echo "0")
    
  if [ -z "$RECORD_COUNT" ]; then
    RECORD_COUNT=0
  fi
  printf "  Elapsed: %ds, Total vector records in Milvus enterprise_kb: %s\r" "$ELAPSED" "$RECORD_COUNT"
done
echo ""

# Verify Milvus collection content
echo -e "${YELLOW}Verifying Milvus collection records...${NC}"
echo -e "Milvus Records count: ${GREEN}$RECORD_COUNT${NC}"
if [ "$RECORD_COUNT" -eq 0 ] || [ "$RECORD_COUNT" == "failed" ]; then
  echo -e "${RED}Milvus decoupled integration test failed: 0 records found in Milvus collection!${NC}"
  echo -e "${YELLOW}Printing consumer service logs for diagnosis...${NC}"
  compose logs oc-writer-consumer
  exit 1
fi

# Verify MCP server endpoint
echo -e "${YELLOW}Verifying MCP Server health endpoint...${NC}"
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8080/mcp || \
              curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8080/ || true)
echo -e "MCP Server HTTP Status: ${GREEN}$HTTP_STATUS${NC}"

if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "405" ] && [ "$HTTP_STATUS" != "404" ]; then
  echo -e "${RED}Milvus decoupled integration test failed: MCP Server returned unexpected status $HTTP_STATUS${NC}"
  compose logs oc-mcp-server
  exit 1
fi
echo -e "${GREEN}MCP Server is reachable (HTTP $HTTP_STATUS)${NC}"

echo -e "${GREEN}================================================================================${NC}"
echo -e "${GREEN}SUCCESS: Milvus Decoupled Multi-Service Pipeline Integration Test Passed!${NC}"
echo -e "${GREEN}================================================================================${NC}"

# Clean up temporary test files
echo -e "${YELLOW}Cleaning up temporary test files...${NC}"
rm -f "$TEST_FILE"

# Tear down the test environment
echo -e "${YELLOW}Tearing down test environment...${NC}"
compose down

exit 0
