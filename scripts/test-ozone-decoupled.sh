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

# Integration test for Apache Ozone Object Storage Claim Check store in docker-compose-decoupled.yml
# Exit immediately if a command exits with a non-zero status
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Set Ozone as active Claim Check store for decoupled containers
export SPRING_OPENCRAWLING_CLAIM_CHECK_STORE=ozone

echo -e "${YELLOW}=== Starting OpenCrawling Apache Ozone Claim Check Ingestion Pipeline Integration Test ===${NC}"

# Get the directory where this script is located and switch to the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v docker-compose >/dev/null 2>&1 || docker compose version >/dev/null 2>&1 || { echo -e "${RED}Docker Compose is required but not installed. Aborting.${NC}" >&2; exit 1; }

# Helper function for docker compose commands
compose() {
  docker compose -f docker-compose-decoupled.yml "$@"
}

# Clean up any existing containers
echo -e "${YELLOW}Cleaning up previous decoupled containers...${NC}"
compose down --remove-orphans || true

# Build microservices images
echo -e "${YELLOW}Building OpenCrawling decoupled microservice images from source...${NC}"
compose build

# Start services
echo -e "${YELLOW}Starting complete decoupled multi-service infrastructure with Apache Ozone...${NC}"
compose up -d

# Define timeout (in seconds)
TIMEOUT=180
ELAPSED=0

echo -e "${YELLOW}Waiting for postgres-vector database to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' postgres-vector-decoupled 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for postgres-vector database.${NC}"
    compose logs postgres
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}postgres-vector database is healthy!${NC}"

# Reset elapsed timer
ELAPSED=0
echo -e "${YELLOW}Waiting for Ollama to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' ollama-decoupled 2>/dev/null || echo 'starting')" == "healthy" ]; do
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
until [ "$(docker inspect -f '{{.State.Running}}' ollama-model-puller-decoupled 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Ollama model puller.${NC}"
    compose logs ollama-model-puller
    exit 1
  fi
  PROGRESS=$(docker logs --tail 1 ollama-model-puller-decoupled 2>&1 || true)
  if [ ! -z "$PROGRESS" ]; then
    printf "  Progress: %s\r" "$PROGRESS"
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo ""

# Check exit code of model puller
EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled 2>/dev/null || echo "1")
if [ "$EXIT_CODE" -ne 0 ]; then
  echo -e "${RED}Ollama model puller failed with exit code $EXIT_CODE.${NC}"
  compose logs ollama-model-puller
  exit 1
fi
echo -e "${GREEN}Ollama embedding model pulled successfully!${NC}"

# Reset elapsed timer
ELAPSED=0
echo -e "${YELLOW}Waiting for Apache Ozone 2.2.0 S3 Gateway (port 9878) to be ready...${NC}"
until curl -s http://localhost:9878/ >/dev/null 2>&1 || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
if [ $ELAPSED -ge $TIMEOUT ]; then
  echo -e "${RED}Timeout waiting for Apache Ozone S3 Gateway.${NC}"
  compose logs ozone-s3g
  exit 1
fi
echo -e "${GREEN}Apache Ozone 2.2.0 S3 Gateway is ready!${NC}"

# Create a sample test document in the mounted directory
TEST_DOC_DIR="./oc-runtime/data"
mkdir -p "$TEST_DOC_DIR"
TEST_FILE="$TEST_DOC_DIR/ozone-claim-check-test.txt"
echo "Apache Ozone S3 Gateway Claim Check pattern test for OpenCrawling! Decoupled object storage integration test worked successfully." > "$TEST_FILE"
echo -e "${GREEN}Created test document: $TEST_FILE${NC}"

# Restart crawler service to trigger directory scan and Ozone upload
echo -e "${YELLOW}Restarting crawler service with Apache Ozone Claim Check store...${NC}"
compose restart oc-crawler

# Wait for crawler completion
ELAPSED=0
echo -e "${YELLOW}Waiting for oc-crawler service to finish execution...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for oc-crawler-service.${NC}"
    compose logs oc-crawler
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}oc-crawler-service finished directory scanning and Ozone upload!${NC}"

# Wait for messaging pipeline to process document vectors (with retries)
echo -e "${YELLOW}Waiting for Kafka consumers to process and store vectors via Ozone claim check...${NC}"
RECORD_COUNT=0
ELAPSED=0
TIMEOUT=120
until [ "$RECORD_COUNT" -gt 0 ] 2>/dev/null || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))
  RECORD_COUNT=$(docker exec -i postgres-vector-decoupled psql -U opencrawling -d opencrawling -t -A -P pager=off -c \
    "SELECT (SELECT count(*) FROM vector_store) \
          + (SELECT count(*) FROM vector_store_384) \
          + (SELECT count(*) FROM vector_store_768) \
          + (SELECT count(*) FROM vector_store_1024);" 2>/dev/null || echo "0")
  RECORD_COUNT=$(echo "$RECORD_COUNT" | tr -d '[:space:]')
  if [ -z "$RECORD_COUNT" ]; then
    RECORD_COUNT=0
  fi
  printf "  Elapsed: %ds, Total vector records across all tables: %s\r" "$ELAPSED" "$RECORD_COUNT"
done
echo ""

# Verify pgvector database content
echo -e "${YELLOW}Verifying PgVector table records for Ozone claim check test...${NC}"
echo -e "PgVector Records count: ${GREEN}$RECORD_COUNT${NC}"
if [ "$RECORD_COUNT" -eq 0 ] || [ "$RECORD_COUNT" == "failed" ]; then
  echo -e "${RED}Apache Ozone integration test failed: 0 records found in pgvector database!${NC}"
  compose logs oc-ingestion-consumer
  compose logs oc-embedding-consumer
  compose logs oc-writer
  compose logs ozone-s3g
  exit 1
fi

# Verify MCP server endpoint
echo -e "${YELLOW}Verifying MCP Server health endpoint...${NC}"
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8080/mcp || \
              curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8080/ || true)
echo -e "MCP Server HTTP Status: ${GREEN}$HTTP_STATUS${NC}"

if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "405" ] && [ "$HTTP_STATUS" != "404" ]; then
  echo -e "${RED}Ozone decoupled integration test failed: MCP Server returned unexpected status $HTTP_STATUS${NC}"
  compose logs oc-mcp-server
  exit 1
fi
echo -e "${GREEN}MCP Server is reachable (HTTP $HTTP_STATUS)${NC}"

echo -e "${GREEN}================================================================================${NC}"
echo -e "${GREEN}SUCCESS: Apache Ozone Claim Check Decoupled Pipeline Integration Test Passed!${NC}"
echo -e "${GREEN}================================================================================${NC}"

# Clean up temporary test files
echo -e "${YELLOW}Cleaning up temporary test files...${NC}"
rm -f "$TEST_FILE"

# Tear down the test environment
echo -e "${YELLOW}Tearing down test environment...${NC}"
compose down

exit 0
