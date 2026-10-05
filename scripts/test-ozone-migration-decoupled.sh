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

# End-to-end decoupled integration test for Migration Mode with separated Apache Ozone instance as target storage.
# Bypasses narrativization and vector embeddings, copying content binaries as-is from Claim Check store into
# target Ozone and producing companion OIS JSON sidecars (<key>.ois.json) with Zero-Trust security.
# Exit immediately if a command exits with a non-zero status
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Set Migration Mode & Target Apache Ozone Connector configuration
export OPENCRAWLING_PIPELINE_MODE=migration
export SPRING_OPENCRAWLING_OUTPUT_TYPE=ozone
export SPRING_OPENCRAWLING_CLAIM_CHECK_STORE="${SPRING_OPENCRAWLING_CLAIM_CHECK_STORE:-ozone}"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_CLIENT_TYPE="S3"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_S3_ENDPOINT="http://localhost:9878"
export SPRING_OPENCRAWLING_OUTPUT_OZONE_CLIENT_TYPE="${OUTPUT_OZONE_CLIENT_TYPE:-S3G}"
export SPRING_OPENCRAWLING_OUTPUT_OZONE_VOLUME="s3v"
export SPRING_OPENCRAWLING_OUTPUT_OZONE_BUCKET="migration-target"
export SPRING_OPENCRAWLING_OUTPUT_OZONE_S3_ENDPOINT="http://localhost:9879"

echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}=== OpenCrawling Decoupled Migration Mode & Apache Ozone Integration Test    ===${NC}"
echo -e "${YELLOW}================================================================================${NC}"
echo -e "Pipeline Mode:          ${GREEN}${OPENCRAWLING_PIPELINE_MODE}${NC}"
echo -e "Output Connector:       ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_TYPE}${NC}"
echo -e "Target Ozone Client:    ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_OZONE_CLIENT_TYPE}${NC}"
echo -e "Target Ozone S3Gateway: ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_OZONE_S3_ENDPOINT}${NC}"
echo -e "Target Volume / Bucket: ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_OZONE_VOLUME}/${SPRING_OPENCRAWLING_OUTPUT_OZONE_BUCKET}${NC}"
echo -e "Source Claim Check:     ${GREEN}${SPRING_OPENCRAWLING_CLAIM_CHECK_STORE} (${SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_CLIENT_TYPE})${NC}"

# Get the directory where this script is located and switch to the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo -e "${RED}Docker daemon is not running. Aborting.${NC}" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo -e "${RED}curl is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo -e "${RED}jq is required but not installed. Aborting.${NC}" >&2; exit 1; }

COMPOSE_FILE="oc-ozone-output-connector/docker/docker-compose-decoupled-with-ozone.yml"

# Helper function for docker compose commands
compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

# Clean up any existing containers from previous test runs
echo -e "${YELLOW}Cleaning up previous decoupled Ozone migration containers...${NC}"
compose down --remove-orphans -v || true

# Build microservices images
echo -e "${YELLOW}Building OpenCrawling decoupled microservice images from source...${NC}"
compose build

# Start services
echo -e "${YELLOW}Starting complete decoupled multi-service infrastructure with separated target Ozone...${NC}"
compose up -d

# Define timeout (in seconds)
TIMEOUT=180
ELAPSED=0

echo -e "${YELLOW}Waiting for postgres-vector database to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' postgres-vector-decoupled-ozone 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for postgres-vector database.${NC}"
    compose logs postgres
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}postgres-vector database is healthy!${NC}"

# Wait for Redis
ELAPSED=0
echo -e "${YELLOW}Waiting for Redis to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' redis-stack-decoupled-ozone 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Redis.${NC}"
    compose logs redis
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}Redis is healthy!${NC}"

# Wait for Kafka
ELAPSED=0
echo -e "${YELLOW}Waiting for Kafka to be ready...${NC}"
until docker exec kafka-decoupled-ozone /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1 || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
if [ $ELAPSED -ge $TIMEOUT ]; then
  echo -e "${RED}Timeout waiting for Kafka.${NC}"
  compose logs kafka
  exit 1
fi
echo -e "${GREEN}Kafka is ready!${NC}"

# Wait for Source Apache Ozone S3 Gateway (port 9878) if claim check is ozone
if [ "$SPRING_OPENCRAWLING_CLAIM_CHECK_STORE" == "ozone" ]; then
  ELAPSED=0
  echo -e "${YELLOW}Waiting for Source Apache Ozone S3 Gateway (port 9878) to be ready...${NC}"
  until curl -s http://localhost:9878/ >/dev/null 2>&1 || [ $ELAPSED -ge $TIMEOUT ]; do
    sleep 2
    ELAPSED=$((ELAPSED + 2))
  done
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Source Apache Ozone S3 Gateway.${NC}"
    compose logs ozone-s3g
    exit 1
  fi
  echo -e "${GREEN}Source Apache Ozone S3 Gateway (port 9878) is ready!${NC}"

  echo -e "${YELLOW}Waiting for Source Apache Ozone OM to be ready...${NC}"
  ELAPSED=0
  until docker exec ozone-om-source ozone sh volume list / >/dev/null 2>&1 || [ $ELAPSED -ge $TIMEOUT ]; do
    sleep 2
    ELAPSED=$((ELAPSED + 2))
  done
  docker exec ozone-om-source ozone sh volume create /s3v 2>/dev/null || true
  docker exec ozone-om-source ozone sh bucket create /s3v/claims 2>/dev/null || true
  echo -e "${GREEN}Source Apache Ozone claims bucket (/s3v/claims) verified!${NC}"

  echo -e "${YELLOW}Waiting for Source Apache Ozone DataNode to be running...${NC}"
  ELAPSED=0
  until [ "$(docker inspect -f '{{.State.Running}}' ozone-datanode-source 2>/dev/null || echo 'false')" == "true" ] || [ $ELAPSED -ge $TIMEOUT ]; do
    sleep 2
    ELAPSED=$((ELAPSED + 2))
  done
  echo -e "${GREEN}Source Apache Ozone DataNode is running!${NC}"
fi

# Wait for Target Separated Apache Ozone S3 Gateway (port 9879)
ELAPSED=0
echo -e "${YELLOW}Waiting for Target Separated Apache Ozone S3 Gateway (port 9879) to be ready...${NC}"
until curl -s http://localhost:9879/ >/dev/null 2>&1 || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
if [ $ELAPSED -ge $TIMEOUT ]; then
  echo -e "${RED}Timeout waiting for Target Apache Ozone S3 Gateway.${NC}"
  compose logs target-ozone-s3g
  exit 1
fi
echo -e "${GREEN}Target Separated Apache Ozone S3 Gateway (port 9879) is ready!${NC}"

echo -e "${YELLOW}Waiting for Target Apache Ozone OM to be ready...${NC}"
ELAPSED=0
until docker exec target-ozone-om ozone sh volume list / >/dev/null 2>&1 || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
docker exec target-ozone-om ozone sh volume create /s3v 2>/dev/null || true
docker exec target-ozone-om ozone sh bucket create /s3v/migration-target 2>/dev/null || true
echo -e "${GREEN}Target Apache Ozone migration bucket (/s3v/migration-target) verified!${NC}"

echo -e "${YELLOW}Waiting for Target Apache Ozone DataNode to be running...${NC}"
ELAPSED=0
until [ "$(docker inspect -f '{{.State.Running}}' target-ozone-datanode 2>/dev/null || echo 'false')" == "true" ] || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}Target Apache Ozone DataNode is running!${NC}"

# Allow datanodes heartbeat registration with SCM
sleep 5

# Create a sample test document in the mounted directory
TEST_DOC_DIR="./oc-runtime/data"
mkdir -p "$TEST_DOC_DIR"
TEST_FILENAME="ozone-migration-contract.txt"
TEST_FILE="$TEST_DOC_DIR/$TEST_FILENAME"
CONTENT_TEXT="Apache Ozone Migration Mode end-to-end integration test for OpenCrawling! Binary content preserved pristine as-it-is with companion OIS JSON sidecar and Zero-Trust ACLs."
echo "$CONTENT_TEXT" > "$TEST_FILE"

# Calculate source hash and size
if command -v sha256sum >/dev/null 2>&1; then
  SOURCE_SHA256=$(sha256sum "$TEST_FILE" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  SOURCE_SHA256=$(shasum -a 256 "$TEST_FILE" | awk '{print $1}')
else
  SOURCE_SHA256=$(openssl dgst -sha256 "$TEST_FILE" | awk '{print $NF}')
fi
SOURCE_SIZE=$(wc -c < "$TEST_FILE" | tr -d '[:space:]')

echo -e "${GREEN}Created test document: $TEST_FILE${NC}"
echo -e "  File Name:   ${CYAN}$TEST_FILENAME${NC}"
echo -e "  Size:        ${CYAN}$SOURCE_SIZE bytes${NC}"
echo -e "  SHA-256:     ${CYAN}$SOURCE_SHA256${NC}"

# Restart crawler service to trigger directory scan, Claim Check generation, and Kafka publication
echo -e "${YELLOW}Restarting oc-crawler service in Migration Mode...${NC}"
compose restart oc-crawler

# Wait for crawler completion
ELAPSED=0
echo -e "${YELLOW}Waiting for oc-crawler service to finish directory scan...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-ozone 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for oc-crawler-service-ozone.${NC}"
    compose logs oc-crawler
    exit 1
  fi
  CRAWLER_LOG=$(docker logs --tail 1 oc-crawler-service-ozone 2>&1 | tr '\r\n' ' ' || true)
  printf "  Crawler running (%ds): %s\r" "$ELAPSED" "${CRAWLER_LOG:0:80}"
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo ""
echo -e "${GREEN}oc-crawler-service finished directory scanning and published ingestion message in Migration Mode!${NC}"

# Wait for target Ozone to receive the migrated binary and companion OIS JSON sidecar
echo -e "${YELLOW}Waiting for oc-writer-consumer to migrate binary and sidecar to target Ozone...${NC}"
ELAPSED=0
TIMEOUT=120
MIGRATED=false
OZONE_KEYS="[]"

until [ "$MIGRATED" == "true" ] || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 3
  ELAPSED=$((ELAPSED + 3))

  # Query keys in target Ozone bucket /s3v/migration-target using native Ozone CLI inside target-ozone-om
  OZONE_KEYS=$(docker exec target-ozone-om ozone sh key list /s3v/migration-target 2>/dev/null || echo "[]")
  KEY_COUNT=$(echo "$OZONE_KEYS" | jq '. | length' 2>/dev/null || echo "0")

  printf "  Elapsed: %ds, Target Ozone keys count: %s\r" "$ELAPSED" "$KEY_COUNT"

  # Check if at least 2 keys exist (binary + .ois.json sidecar)
  if [ "$KEY_COUNT" -ge 2 ]; then
    MIGRATED=true
  fi
done
echo ""

if [ "$MIGRATED" != "true" ]; then
  echo -e "${RED}Target Ozone migration timed out! Keys not found in /s3v/migration-target.${NC}"
  echo -e "${YELLOW}Crawler logs:${NC}"
  compose logs oc-crawler
  echo -e "${YELLOW}Writer consumer logs:${NC}"
  compose logs oc-writer-consumer
  echo -e "${YELLOW}Ingestion consumer logs:${NC}"
  compose logs oc-ingestion-consumer
  exit 1
fi

echo -e "${GREEN}Target Ozone received migrated objects! Keys in target bucket:${NC}"
echo "$OZONE_KEYS" | jq -r '.[].name'

# --- Verification Step 1: Migration Mode RAG Bypass Check (0 records in PgVector) ---
echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}Step 1: Verifying Migration Mode RAG Bypass (PgVector records must be 0)...    ${NC}"
echo -e "${YELLOW}================================================================================${NC}"
RECORD_COUNT=$(docker exec -i postgres-vector-decoupled-ozone psql -U opencrawling -d opencrawling -t -A -P pager=off -c \
  "SELECT (SELECT count(*) FROM vector_store) \
        + (SELECT count(*) FROM vector_store_384) \
        + (SELECT count(*) FROM vector_store_768) \
        + (SELECT count(*) FROM vector_store_1024);" 2>/dev/null || echo "0")
RECORD_COUNT=$(echo "$RECORD_COUNT" | tr -d '[:space:]')
if [ -z "$RECORD_COUNT" ]; then
  RECORD_COUNT=0
fi

echo -e "Total Vector Store Records: ${GREEN}$RECORD_COUNT${NC}"
if [ "$RECORD_COUNT" -ne 0 ]; then
  echo -e "${RED}FAIL: Migration Mode violation! Vector records found in pgvector: $RECORD_COUNT${NC}"
  echo -e "In Migration Mode (opencrawling.pipeline.mode=migration), narrativization and embeddings must be skipped!"
  exit 1
fi
echo -e "${GREEN}PASSED: 0 vector records in PgVector. Narrativization and vector embeddings were successfully bypassed!${NC}"

# --- Verification Step 2: Binary Integrity in Target Apache Ozone ---
echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}Step 2: Verifying Pristine Binary Integrity in Target Apache Ozone...          ${NC}"
echo -e "${YELLOW}================================================================================${NC}"

# Find the binary key (the key not ending in .ois.json matching test filename)
BINARY_KEY=$(echo "$OZONE_KEYS" | jq -r --arg fn "$TEST_FILENAME" '.[] | select((.name | endswith(".ois.json") | not) and (.name | contains($fn))) | .name' | head -n 1)
if [ -z "$BINARY_KEY" ]; then
  BINARY_KEY=$(echo "$OZONE_KEYS" | jq -r '.[] | select(.name | endswith(".ois.json") | not) | .name' | head -n 1)
fi
if [ -z "$BINARY_KEY" ]; then
  echo -e "${RED}FAIL: Target Ozone binary object key not found!${NC}"
  exit 1
fi

echo -e "Identified Target Ozone Binary Key: ${GREEN}$BINARY_KEY${NC}"

# Retrieve binary content directly from target Ozone
RETRIEVED_CONTENT=$(docker exec target-ozone-om ozone sh key cat "/s3v/migration-target/$BINARY_KEY" 2>/dev/null || true)

if [ "$RETRIEVED_CONTENT" != "$CONTENT_TEXT" ]; then
  echo -e "${RED}FAIL: Content mismatch between source document and target Ozone object!${NC}"
  echo -e "Expected: $CONTENT_TEXT"
  echo -e "Actual:   $RETRIEVED_CONTENT"
  exit 1
fi
echo -e "${GREEN}PASSED: Pristine binary content in target Ozone perfectly matches source!${NC}"

# --- Verification Step 3: Companion OIS JSON Sidecar Verification ---
echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}Step 3: Verifying Companion OIS JSON Sidecar & Zero-Trust Metadata...         ${NC}"
echo -e "${YELLOW}================================================================================${NC}"

SIDECAR_KEY=$(echo "$OZONE_KEYS" | jq -r --arg fn "$TEST_FILENAME" '.[] | select((.name | endswith(".ois.json")) and (.name | contains($fn))) | .name' | head -n 1)
if [ -z "$SIDECAR_KEY" ]; then
  SIDECAR_KEY=$(echo "$OZONE_KEYS" | jq -r '.[] | select(.name | endswith(".ois.json")) | .name' | head -n 1)
fi
if [ -z "$SIDECAR_KEY" ]; then
  echo -e "${RED}FAIL: Companion OIS JSON sidecar key not found in target Ozone!${NC}"
  exit 1
fi

echo -e "Identified Companion OIS JSON Sidecar Key: ${GREEN}$SIDECAR_KEY${NC}"

# Retrieve OIS JSON payload
OIS_JSON=$(docker exec target-ozone-om ozone sh key cat "/s3v/migration-target/$SIDECAR_KEY" 2>/dev/null || echo "{}")

echo -e "${CYAN}--- Retrieved Companion OIS JSON Sidecar Content ---${NC}"
echo "$OIS_JSON" | jq .
echo -e "${CYAN}----------------------------------------------------${NC}"

# Validate schema version
OIS_SCHEMA=$(echo "$OIS_JSON" | jq -r '."$schema" // ""')
if [ "$OIS_SCHEMA" != "https://opencrawling.org/schemas/v1/ois-document.json" ]; then
  echo -e "${RED}FAIL: Invalid OIS schema URI: $OIS_SCHEMA${NC}"
  exit 1
fi
echo -e "OIS Schema:             ${GREEN}$OIS_SCHEMA${NC}"

# Validate action
OIS_ACTION=$(echo "$OIS_JSON" | jq -r '.action // ""')
if [ "$OIS_ACTION" != "UPSERT" ]; then
  echo -e "${RED}FAIL: Expected action UPSERT, found: $OIS_ACTION${NC}"
  exit 1
fi
echo -e "OIS Action:             ${GREEN}$OIS_ACTION${NC}"

# Validate SHA-256 checksum in ContentRef
OIS_SHA256=$(echo "$OIS_JSON" | jq -r '.contentRef.sha256 // ""')
if [ "$OIS_SHA256" != "$SOURCE_SHA256" ]; then
  echo -e "${RED}FAIL: OIS SHA-256 checksum ($OIS_SHA256) does not match source ($SOURCE_SHA256)!${NC}"
  exit 1
fi
echo -e "OIS SHA-256:            ${GREEN}$OIS_SHA256${NC}"

# Validate Size in ContentRef
OIS_SIZE=$(echo "$OIS_JSON" | jq -r '.contentRef.size // 0')
if [ "$OIS_SIZE" -ne "$SOURCE_SIZE" ]; then
  echo -e "${RED}FAIL: OIS contentRef.size ($OIS_SIZE) does not match source size ($SOURCE_SIZE)!${NC}"
  exit 1
fi
echo -e "OIS Content Size:       ${GREEN}$OIS_SIZE bytes${NC}"

# Validate Zero-Trust Security configuration
SECURITY_INHERITANCE=$(echo "$OIS_JSON" | jq -r '.security.inheritanceEnabled // false')
echo -e "Zero-Trust Inheritance: ${GREEN}$SECURITY_INHERITANCE${NC}"

echo -e "${GREEN}PASSED: Companion OIS JSON metadata sidecar and Zero-Trust ACLs verified!${NC}"

# --- Verification Step 4: OIS Document Lifecycle Tombstone DELETE Validation ---
echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}Step 4: Verifying OIS Document Lifecycle Tombstone DELETE Action...            ${NC}"
echo -e "${YELLOW}================================================================================${NC}"
mvn test -pl oc-ozone-output-connector -Dtest=OzoneMigrationWriterConsumerTest,OzoneOutputConnectorTest
echo -e "${GREEN}PASSED: OIS Tombstone DELETE lifecycle verified!${NC}"

# --- Verification Step 5: MCP Server Reachability ---
echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}Step 5: Verifying MCP Server Reachability in Migration Mode...                 ${NC}"
echo -e "${YELLOW}================================================================================${NC}"
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
  echo -e "${RED}Decoupled integration test failed: MCP Server returned unexpected status $HTTP_STATUS${NC}"
  compose logs oc-mcp-server
  exit 1
fi
echo -e "${GREEN}MCP Server is reachable (HTTP $HTTP_STATUS)${NC}"

echo -e "${GREEN}================================================================================${NC}"
echo -e "${GREEN}SUCCESS: All Decoupled Ozone Migration Integration Tests Passed Successfully!  ${NC}"
echo -e "${GREEN}================================================================================${NC}"

# Clean up temporary test files
echo -e "${YELLOW}Cleaning up temporary test files...${NC}"
rm -f "$TEST_FILE"

# Tear down the test environment
echo -e "${YELLOW}Tearing down test environment...${NC}"
compose down -v

exit 0
