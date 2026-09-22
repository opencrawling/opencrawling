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

# Integration test for docker-compose-decoupled-with-luxir.yml
# Exit immediately if a command exits with a non-zero status
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Starting OpenCrawling Decoupled Ingestion Pipeline with Luxir Integration Test ===${NC}"

# Get the directory where this script is located and switch to the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v docker-compose >/dev/null 2>&1 || docker compose version >/dev/null 2>&1 || { echo -e "${RED}Docker Compose is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo -e "${RED}curl is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo -e "${RED}jq is required but not installed. Aborting.${NC}" >&2; exit 1; }

COMPOSE_FILE="oc-luxir-output-connector/docker/docker-compose-decoupled-with-luxir.yml"

# Helper function for docker compose commands
compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

# Clean up any existing containers
echo -e "${YELLOW}Cleaning up previous Luxir decoupled containers...${NC}"
compose down --remove-orphans || true

# Build microservices images
echo -e "${YELLOW}Building OpenCrawling decoupled microservice images from source...${NC}"
compose build

# Start services
echo -e "${YELLOW}Starting complete decoupled Luxir-based multi-service infrastructure...${NC}"
compose up -d
echo -e "${GREEN}Docker Compose stack launched. Current service status:${NC}"
compose ps

# Define timeout (in seconds)
TIMEOUT=180
ELAPSED=0

echo -e "${YELLOW}Waiting for Luxir search engine to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' luxir-decoupled 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Luxir decoupled container.${NC}"
    compose logs luxir
    exit 1
  fi
  printf "  Waiting for Luxir health check... (elapsed: %ds)\r" "$ELAPSED"
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "\n${GREEN}Luxir is healthy! Endpoint available at http://localhost:9400${NC}"

# Reset elapsed timer
ELAPSED=0
echo -e "${YELLOW}Waiting for Ollama to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' ollama-decoupled-luxir 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Ollama.${NC}"
    compose logs ollama
    exit 1
  fi
  printf "  Waiting for Ollama health check... (elapsed: %ds)\r" "$ELAPSED"
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "\n${GREEN}Ollama is healthy! Endpoint available at http://localhost:11434${NC}"

# Reset elapsed timer
ELAPSED=0
echo -e "${YELLOW}Waiting for Ollama model puller to pull embedding models and exit...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' ollama-model-puller-decoupled-luxir 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Ollama model puller.${NC}"
    compose logs ollama-model-puller
    exit 1
  fi
  PROGRESS=$(docker logs --tail 1 ollama-model-puller-decoupled-luxir 2>&1 || true)
  if [ ! -z "$PROGRESS" ]; then
    printf "  Progress: %s\r" "$PROGRESS"
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo ""

# Check exit code of model puller
EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled-luxir 2>/dev/null || echo "1")
if [ "$EXIT_CODE" -ne 0 ]; then
  echo -e "${RED}Ollama model puller failed with exit code $EXIT_CODE.${NC}"
  compose logs ollama-model-puller
  exit 1
fi
echo -e "${GREEN}Ollama embedding models (mxbai-embed-large) pulled successfully!${NC}"

# Create a sample test document in the mounted directory
TEST_DOC_DIR="./oc-runtime/data"
mkdir -p "$TEST_DOC_DIR"
TEST_FILE="$TEST_DOC_DIR/luxir-decoupled-integration-test.txt"
echo "OpenCrawling is an awesome open-source pipeline! Decoupled integration test with Luxir worked successfully." > "$TEST_FILE"
echo -e "${GREEN}Created test document: $TEST_FILE${NC}"
echo -e "${YELLOW}Ingested Data Content:${NC}"
echo "--------------------------------------------------------------------------------"
cat "$TEST_FILE"
echo ""
echo "--------------------------------------------------------------------------------"

# Restart crawler to trigger directory scan and Kafka publication
echo -e "${YELLOW}Restarting crawler service (oc-crawler) to trigger directory scan and Kafka publication...${NC}"
compose restart oc-crawler

# Wait for crawler completion
ELAPSED=0
echo -e "${YELLOW}Waiting for oc-crawler service to finish execution...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-luxir 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for oc-crawler-service-luxir.${NC}"
    compose logs oc-crawler
    exit 1
  fi
  printf "  Waiting for oc-crawler directory scan to complete... (elapsed: %ds)\r" "$ELAPSED"
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done

CRAWLER_EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' oc-crawler-service-luxir 2>/dev/null || echo "unknown")
echo -e "\n${GREEN}oc-crawler-service-luxir finished directory scanning with exit code: $CRAWLER_EXIT_CODE${NC}"
if [ "$CRAWLER_EXIT_CODE" -ne 0 ]; then
  echo -e "${RED}Crawler failed with non-zero exit code $CRAWLER_EXIT_CODE.${NC}"
  compose logs oc-crawler
  exit 1
fi

echo -e "${YELLOW}Displaying oc-crawler execution logs:${NC}"
echo "--------------------------------------------------------------------------------"
compose logs oc-crawler
echo "--------------------------------------------------------------------------------"

# Wait for messaging pipeline to process document vectors
echo -e "${YELLOW}Waiting for Kafka consumers (Ingestion -> Embedding -> Luxir Writer) to process and store vectors in Luxir...${NC}"
RECORD_COUNT=0
ELAPSED=0
TIMEOUT=120
until [ "$RECORD_COUNT" -gt 0 ] 2>/dev/null || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))

  # Count documents in Luxir collection via search API
  SEARCH_RESP=$(curl -s -X POST "http://localhost:9400/collections/opencrawling/_search" -H "Content-Type: application/json" -d '{"query":"*:*","get_number":true}' 2>/dev/null || echo "{}")
  RECORD_COUNT=$(echo "$SEARCH_RESP" | jq -r '.found // (.docs | length) // 0' 2>/dev/null || echo "0")

  if [ -z "$RECORD_COUNT" ] || [ "$RECORD_COUNT" == "null" ]; then
    RECORD_COUNT=0
  fi
  printf "  Elapsed: %ds, Total document chunks in Luxir opencrawling: %s\r" "$ELAPSED" "$RECORD_COUNT"
done
echo ""

# Verify Luxir collection content
echo -e "${YELLOW}Verifying Luxir collection records...${NC}"
echo -e "Luxir document chunk count: ${GREEN}$RECORD_COUNT${NC}"
if [ "$RECORD_COUNT" -eq 0 ]; then
  echo -e "${RED}Luxir decoupled integration test failed: 0 document chunks found in Luxir collection!${NC}"
  echo -e "${YELLOW}Printing consumer service logs for diagnosis...${NC}"
  echo "--- oc-ingestion-consumer ---"
  compose logs --tail 50 oc-ingestion-consumer
  echo "--- oc-embedding-consumer ---"
  compose logs --tail 50 oc-embedding-consumer
  echo "--- oc-writer-consumer ---"
  compose logs --tail 50 oc-writer-consumer
  exit 1
fi

echo -e "${GREEN}Kafka pipeline completed processing! Detected $RECORD_COUNT document chunk(s) in Luxir.${NC}"
echo -e "${YELLOW}Displaying oc-writer-consumer execution logs:${NC}"
echo "--------------------------------------------------------------------------------"
compose logs --tail 25 oc-writer-consumer
echo "--------------------------------------------------------------------------------"

# Search and log retrieved documents from Luxir (Full-Text Query)
echo -e "${YELLOW}Executing full-text search query against Luxir collection 'opencrawling' (query: 'text_t:OpenCrawling')...${NC}"
SEARCH_RESPONSE=$(curl -s -X POST "http://localhost:9400/collections/opencrawling/_search" -H "Content-Type: application/json" -d '{"query":"text_t:OpenCrawling"}' 2>/dev/null || echo "{}")
FOUND_DOCS=$(echo "$SEARCH_RESPONSE" | jq -r '(.docs // []) | length' 2>/dev/null || echo "0")
echo -e "Full-text search query returned ${GREEN}$FOUND_DOCS${NC} document(s)."
echo -e "${GREEN}Retrieved Luxir Full-Text Search Results:${NC}"
echo "--------------------------------------------------------------------------------"
echo "$SEARCH_RESPONSE" | jq '.docs // []'
echo "--------------------------------------------------------------------------------"

# Generate a 1024-dimensional vector for Luxir Dense Vector Search
DENSE_VECTOR="["$(python3 -c "print(','.join(['0.01']*1024))" 2>/dev/null || seq -s, 1 1024 | sed 's/[0-9]*/0.01/g')"]"

# Search and log retrieved documents from Luxir (Dense Vector KNN Query)
echo -e "${YELLOW}Executing Luxir Dense Vector Search query (k=3, 1024 dimensions on 'embedding_v')...${NC}"
KNN_RESPONSE=$(curl -s -X POST "http://localhost:9400/collections/opencrawling/_search" -H "Content-Type: application/json" -d '{"query":{"knn":{"field":"embedding_v","query":'$DENSE_VECTOR',"k":3}}}' 2>/dev/null || echo "{}")
KNN_FOUND=$(echo "$KNN_RESPONSE" | jq -r '(.docs // []) | length' 2>/dev/null || echo "0")
echo -e "KNN vector search returned ${GREEN}$KNN_FOUND${NC} document(s)."
echo -e "${GREEN}Retrieved Luxir KNN Dense Vector Search Results:${NC}"
echo "--------------------------------------------------------------------------------"
echo "$KNN_RESPONSE" | jq '.docs // []'
echo "--------------------------------------------------------------------------------"

# Verify MCP server endpoint
echo -e "${YELLOW}Waiting for MCP Server health endpoint to be ready on http://localhost:8080/mcp...${NC}"
HTTP_STATUS="000"
ELAPSED=0
TIMEOUT=60
until [ "$HTTP_STATUS" == "200" ] || [ "$HTTP_STATUS" == "405" ] || [ "$HTTP_STATUS" == "404" ] || [ $ELAPSED -ge $TIMEOUT ]; do
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8080/mcp 2>/dev/null || echo "")
  if [ -z "$HTTP_STATUS" ] || [ "$HTTP_STATUS" == "000" ]; then
    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8080/ 2>/dev/null || echo "000")
  fi
  if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "405" ] && [ "$HTTP_STATUS" != "404" ]; then
    printf "  Checking MCP Server status... (elapsed: %ds, current status: %s)\r" "$ELAPSED" "$HTTP_STATUS"
    sleep 2
    ELAPSED=$((ELAPSED + 2))
  fi
done
echo -e "\nMCP Server HTTP Status: ${GREEN}$HTTP_STATUS${NC}"

if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "405" ] && [ "$HTTP_STATUS" != "404" ]; then
  echo -e "${RED}Luxir decoupled integration test failed: MCP Server returned unexpected status $HTTP_STATUS${NC}"
  compose logs oc-mcp-server
  exit 1
fi
echo -e "${GREEN}MCP Server is reachable (HTTP $HTTP_STATUS)${NC}"

# Verify OIS Document Lifecycle Tombstone DELETE action feature
echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}Executing OIS Document Lifecycle Tombstone DELETE action test for Luxir...${NC}"
echo -e "${YELLOW}================================================================================${NC}"
mvn test -pl oc-luxir-output-connector -Dtest=LuxirStoreWriterConsumerTest
echo -e "${GREEN}OIS Tombstone DELETE action integration step for Luxir passed!${NC}"

echo -e "${GREEN}================================================================================${NC}"
echo -e "${GREEN}SUCCESS: Luxir Decoupled Multi-Service Pipeline Integration Test Passed!${NC}"
echo -e "${GREEN}================================================================================${NC}"

# Clean up temporary test files
echo -e "${YELLOW}Cleaning up temporary test files: $TEST_FILE...${NC}"
rm -f "$TEST_FILE"

# Tear down the test environment
echo -e "${YELLOW}Tearing down Docker Compose test environment...${NC}"
compose down
echo -e "${GREEN}Test environment cleanly destroyed.${NC}"

exit 0
