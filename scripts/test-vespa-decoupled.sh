#!/usr/bin/env bash
#
# Copyright © 2026 the original author or authors (michael@michaelcizmar.com)
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

# Integration test for docker-compose-decoupled-with-vespa.yml: boots the full decoupled pipeline,
# crawls a real file through to Vespa, then verifies the raw Vespa document count, the oc-runtime
# Model Insights REST endpoints (/api/vespa/health, /document-counts, /query), and MCP reachability.
# Exit immediately if a command exits with a non-zero status
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Starting OpenCrawling Decoupled Ingestion Pipeline with Vespa Integration Test ===${NC}"

# Get the directory where this script is located and switch to the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v docker-compose >/dev/null 2>&1 || docker compose version >/dev/null 2>&1 || { echo -e "${RED}Docker Compose is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo -e "${RED}curl is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo -e "${RED}jq is required but not installed. Aborting.${NC}" >&2; exit 1; }

COMPOSE_FILE="oc-vespa-output-connector/docker/docker-compose-decoupled-with-vespa.yml"

# Helper function for docker compose commands
compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

# Clean up any existing containers
echo -e "${YELLOW}Cleaning up previous Vespa decoupled containers...${NC}"
compose down --remove-orphans || true

# Build microservices images
echo -e "${YELLOW}Building OpenCrawling decoupled microservice images from source...${NC}"
compose build

# Start services
echo -e "${YELLOW}Starting complete decoupled Vespa-based multi-service infrastructure...${NC}"
compose up -d

# Define timeout (in seconds)
TIMEOUT=180
ELAPSED=0

echo -e "${YELLOW}Waiting for Vespa config server to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' vespa-standalone 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Vespa standalone.${NC}"
    compose logs vespa
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}Vespa config server is healthy!${NC}"

# Wait for the one-shot schema deploy container to finish
ELAPSED=0
echo -e "${YELLOW}Waiting for the Vespa schema application package to deploy...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' vespa-schema-deploy 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for vespa-schema-deploy.${NC}"
    compose logs vespa-deploy
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
DEPLOY_EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' vespa-schema-deploy 2>/dev/null || echo "1")
if [ "$DEPLOY_EXIT_CODE" -ne 0 ]; then
  echo -e "${RED}Vespa schema deploy failed with exit code $DEPLOY_EXIT_CODE.${NC}"
  compose logs vespa-deploy
  exit 1
fi
echo -e "${GREEN}Vespa schema application package deployed!${NC}"

# Wait for the document/search API to start serving after activation
ELAPSED=0
TIMEOUT=120
echo -e "${YELLOW}Waiting for Vespa document/search API to become healthy...${NC}"
until curl -sf http://localhost:8090/state/v1/health 2>/dev/null | grep -q '"up"'; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Vespa document/search API.${NC}"
    compose logs vespa
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}Vespa document/search API is healthy!${NC}"

# Reset elapsed timer
ELAPSED=0
TIMEOUT=180
echo -e "${YELLOW}Waiting for Ollama to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' ollama-decoupled-vespa 2>/dev/null || echo 'starting')" == "healthy" ]; do
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
until [ "$(docker inspect -f '{{.State.Running}}' ollama-model-puller-decoupled-vespa 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Ollama model puller.${NC}"
    compose logs ollama-model-puller
    exit 1
  fi
  PROGRESS=$(docker logs --tail 1 ollama-model-puller-decoupled-vespa 2>&1 || true)
  if [ ! -z "$PROGRESS" ]; then
    printf "  Progress: %s\r" "$PROGRESS"
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo ""

# Check exit code of model puller
EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled-vespa 2>/dev/null || echo "1")
if [ "$EXIT_CODE" -ne 0 ]; then
  echo -e "${RED}Ollama model puller failed with exit code $EXIT_CODE.${NC}"
  compose logs ollama-model-puller
  exit 1
fi
echo -e "${GREEN}Ollama embedding models pulled successfully!${NC}"

# Create a sample test document in the mounted directory
TEST_DOC_DIR="./oc-runtime/data"
mkdir -p "$TEST_DOC_DIR"
TEST_FILE="$TEST_DOC_DIR/vespa-decoupled-integration-test.txt"
echo "OpenCrawling is an awesome open-source pipeline! Decoupled integration test with Vespa worked successfully." > "$TEST_FILE"
echo -e "${GREEN}Created test document: $TEST_FILE${NC}"

# Restart crawler to trigger directory scan and Kafka publication
echo -e "${YELLOW}Restarting crawler service to trigger directory scan...${NC}"
compose restart oc-crawler

# Wait for crawler completion
ELAPSED=0
TIMEOUT=180
echo -e "${YELLOW}Waiting for oc-crawler service to finish execution...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-vespa 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for oc-crawler-service-vespa.${NC}"
    compose logs oc-crawler
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}oc-crawler-service-vespa finished directory scanning!${NC}"

# Wait for messaging pipeline to process document vectors
echo -e "${YELLOW}Waiting for Kafka consumers to process and feed chunks into Vespa...${NC}"
RECORD_COUNT=0
ELAPSED=0
TIMEOUT=120
until [ "$RECORD_COUNT" -gt 0 ] 2>/dev/null || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))

  # Count documents in Vespa via the search API. mxbai-embed-large (this stack's default embedding
  # model) produces 1024-dim vectors, so VespaDocumentMapper.resolveDocumentType() always routes fed
  # chunks to opencrawling_chunk_1024, never the generic opencrawling_chunk fallback - that fallback
  # only ever receives chunks from an embedding model whose dimension isn't 384, 768, or 1024.
  SEARCH_RESULT=$(curl -s -X POST http://localhost:8090/search/ \
    -H "Content-Type: application/json" \
    -d '{"yql":"select * from opencrawling_chunk_1024 where true","hits":0}' 2>/dev/null || echo "{}")
  RECORD_COUNT=$(echo "$SEARCH_RESULT" | jq -r '.root.fields.totalCount // 0' 2>/dev/null || echo "0")

  if [ -z "$RECORD_COUNT" ] || [ "$RECORD_COUNT" == "null" ]; then
    RECORD_COUNT=0
  fi
  printf "  Elapsed: %ds, Total chunks in Vespa opencrawling_chunk_1024: %s\r" "$ELAPSED" "$RECORD_COUNT"
done
echo ""

# Verify Vespa document content
echo -e "${YELLOW}Verifying Vespa document count...${NC}"
echo -e "Vespa document count: ${GREEN}$RECORD_COUNT${NC}"
if [ "$RECORD_COUNT" -eq 0 ]; then
  echo -e "${RED}Vespa decoupled integration test failed: 0 documents found in Vespa!${NC}"
  echo -e "${YELLOW}Printing consumer service logs for diagnosis...${NC}"
  compose logs oc-writer-consumer
  exit 1
fi

# Verify the Model Insights REST endpoints (/api/vespa/*), served by oc-runtime itself rather than
# talking to Vespa directly - the connector-only smoke test (test-vespa-connector.sh) never boots
# oc-runtime, so this decoupled test is the only place these get real coverage.
echo -e "${YELLOW}Verifying the Vespa Model Insights REST endpoints...${NC}"

INSIGHTS_HEALTH=$(curl -s "http://localhost:8080/api/vespa/health?endpoint=http://vespa:8080")
INSIGHTS_UP=$(echo "$INSIGHTS_HEALTH" | jq -r '.up // false')
if [ "$INSIGHTS_UP" != "true" ]; then
  echo -e "${RED}Vespa decoupled integration test failed: /api/vespa/health reported down: $INSIGHTS_HEALTH${NC}"
  compose logs oc-mcp-server
  exit 1
fi
echo -e "${GREEN}/api/vespa/health reports up.${NC}"

# mxbai-embed-large (1024-dim) is this stack's default embedding model, so fed chunks always route
# to opencrawling_chunk_1024 - see the comment above the raw Vespa document-count check.
INSIGHTS_COUNTS=$(curl -s "http://localhost:8080/api/vespa/document-counts?endpoint=http://vespa:8080")
TYPE_1024_COUNT=$(echo "$INSIGHTS_COUNTS" | jq -r '.[] | select(.documentType=="opencrawling_chunk_1024") | .count // 0')
if [ -z "$TYPE_1024_COUNT" ] || [ "$TYPE_1024_COUNT" == "null" ]; then
  TYPE_1024_COUNT=0
fi
if [ "$TYPE_1024_COUNT" -lt 1 ]; then
  echo -e "${RED}Vespa decoupled integration test failed: /api/vespa/document-counts shows no chunks for opencrawling_chunk_1024: $INSIGHTS_COUNTS${NC}"
  exit 1
fi
echo -e "${GREEN}/api/vespa/document-counts confirms $TYPE_1024_COUNT chunk(s) in opencrawling_chunk_1024.${NC}"

INSIGHTS_QUERY=$(curl -s -X POST http://localhost:8080/api/vespa/query \
  -H "Content-Type: application/json" \
  -d '{"endpoint":"http://vespa:8080","documentType":"opencrawling_chunk_1024","queryText":"decoupled integration test","rankProfile":"default"}')
QUERY_HITS=$(echo "$INSIGHTS_QUERY" | jq -r '.totalCount // 0')
if [ -z "$QUERY_HITS" ] || [ "$QUERY_HITS" == "null" ]; then
  QUERY_HITS=0
fi
if [ "$QUERY_HITS" -lt 1 ]; then
  echo -e "${RED}Vespa decoupled integration test failed: /api/vespa/query (BM25) returned 0 hits: $INSIGHTS_QUERY${NC}"
  exit 1
fi
echo -e "${GREEN}/api/vespa/query (BM25) confirms $QUERY_HITS hit(s).${NC}"

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
  echo -e "${RED}Vespa decoupled integration test failed: MCP Server returned unexpected status $HTTP_STATUS${NC}"
  compose logs oc-mcp-server
  exit 1
fi
echo -e "${GREEN}MCP Server is reachable (HTTP $HTTP_STATUS)${NC}"

# Verify OIS Document Lifecycle Tombstone DELETE action feature
echo -e "${YELLOW}Executing OIS Document Lifecycle Tombstone DELETE action test for Vespa...${NC}"
mvn test -pl oc-vespa-output-connector -Dtest=VespaStoreWriterConsumerTest
echo -e "${GREEN}OIS Tombstone DELETE action integration step for Vespa passed!${NC}"

echo -e "${GREEN}================================================================================${NC}"
echo -e "${GREEN}SUCCESS: Vespa Decoupled Multi-Service Pipeline Integration Test Passed!${NC}"
echo -e "${GREEN}================================================================================${NC}"

# Clean up temporary test files
echo -e "${YELLOW}Cleaning up temporary test files...${NC}"
rm -f "$TEST_FILE"

# Tear down the test environment
echo -e "${YELLOW}Tearing down test environment...${NC}"
compose down

exit 0
