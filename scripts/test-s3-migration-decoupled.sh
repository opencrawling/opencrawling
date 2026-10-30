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

# End-to-end decoupled integration test for Migration Mode with AWS S3 / MinIO as target storage.
# Preserves content binaries as-is directly from Claim Check store into target S3 and
# produces companion OIS JSON sidecars (<key>.ois.json) with Zero-Trust security.
# Exit immediately if a command exits with a non-zero status
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Set Migration Mode & Target AWS S3 Output Connector configuration
export OPENCRAWLING_PIPELINE_MODE=migration
export SPRING_OPENCRAWLING_OUTPUT_TYPE=s3
export SPRING_OPENCRAWLING_OUTPUT_S3_BUCKET="enterprise-migration-lake"
export SPRING_OPENCRAWLING_OUTPUT_S3_REGION="us-east-1"
export SPRING_OPENCRAWLING_OUTPUT_S3_ENDPOINT_OVERRIDE="http://localhost:9000"
export SPRING_OPENCRAWLING_OUTPUT_S3_PATH_STYLE_ACCESS="true"
export SPRING_OPENCRAWLING_OUTPUT_S3_CREDENTIALS_STRATEGY="STATIC"
export SPRING_OPENCRAWLING_OUTPUT_S3_ACCESS_KEY="minioadmin"
export SPRING_OPENCRAWLING_OUTPUT_S3_SECRET_KEY="minioadmin"
export SPRING_OPENCRAWLING_CLAIM_CHECK_STORE="${SPRING_OPENCRAWLING_CLAIM_CHECK_STORE:-s3}"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_CLIENT_TYPE="S3"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_S3_ENDPOINT="http://localhost:9000"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_BUCKET="claims"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_ACCESS_KEY="minioadmin"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_SECRET_KEY="minioadmin"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_PATH_STYLE_ACCESS="true"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_REGION="us-east-1"
# Low multipart thresholds (production default: 64MB/16MB) so Step 5b exercises parallel multipart uploads
export OC_MULTIPART_THRESHOLD="${OC_MULTIPART_THRESHOLD:-8MB}"
export OC_MULTIPART_PART_SIZE="${OC_MULTIPART_PART_SIZE:-5MB}"
export OC_MULTIPART_CONCURRENCY="${OC_MULTIPART_CONCURRENCY:-4}"

echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}=== OpenCrawling Decoupled Migration Mode & AWS S3 Integration Test          ===${NC}"
echo -e "${YELLOW}================================================================================${NC}"
echo -e "Pipeline Mode:          ${GREEN}${OPENCRAWLING_PIPELINE_MODE}${NC}"
echo -e "Output Connector:       ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_TYPE}${NC}"
echo -e "Target S3 Endpoint:     ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_S3_ENDPOINT_OVERRIDE}${NC}"
echo -e "Target S3 Bucket:       ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_S3_BUCKET}${NC}"
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
command -v aws >/dev/null 2>&1 || { echo -e "${RED}aws CLI is required but not installed. Aborting.${NC}" >&2; exit 1; }

COMPOSE_FILE="oc-s3-output-connector/docker/docker-compose-decoupled-with-s3.yml"

# Helper function for docker compose commands
compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

# AWS CLI wrapper pointing to target S3 / MinIO
aws_s3() {
  AWS_ACCESS_KEY_ID=minioadmin AWS_SECRET_ACCESS_KEY=minioadmin aws --endpoint-url http://localhost:9000 "$@"
}

cleanup() {
  echo -e "\n${YELLOW}Tearing down S3 migration decoupled test environment...${NC}"
  compose down --remove-orphans -v >/dev/null 2>&1 || true
  rm -rf "${TEST_DOC_DIR:-}"
}
trap cleanup EXIT

# Clean up any existing containers from previous test runs
echo -e "${YELLOW}Cleaning up previous decoupled S3 migration containers...${NC}"
compose down --remove-orphans -v || true

# Build microservices images sequentially to leverage BuildKit cache and prevent disk spikes
if [ "${SKIP_BUILD:-false}" != "true" ]; then
  echo -e "${YELLOW}Building OpenCrawling decoupled microservice images from source...${NC}"
  compose build oc-crawler
  compose build oc-ingestion-consumer
  compose build oc-writer-consumer
  compose build oc-mcp-server
else
  echo -e "${YELLOW}SKIP_BUILD=true: using existing microservice images.${NC}"
fi

# Dedicated crawl source folder: contains ONLY the documents to migrate, so runtime state in
# oc-runtime/data (settings, connectors, jobs, claims) never ends up in the target bucket.
TEST_DOC_DIR="./oc-runtime/crawl-data/s3-migration"
rm -rf "$TEST_DOC_DIR"
mkdir -p "$TEST_DOC_DIR"
export OC_CRAWL_SOURCE_DIR="$(cd "$TEST_DOC_DIR" && pwd)"

# Start services (except oc-crawler: it crawls on startup, so it is started only once S3 is writable)
echo -e "${YELLOW}Starting complete decoupled multi-service infrastructure with MinIO S3...${NC}"
compose up -d $(compose config --services | grep -vx 'oc-crawler')

# Define timeout (in seconds)
TIMEOUT=180
ELAPSED=0

echo -e "${YELLOW}Waiting for postgres-vector database to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' postgres-vector-decoupled-s3 2>/dev/null || echo 'starting')" == "healthy" ]; do
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
until [ "$(docker inspect -f '{{.State.Health.Status}}' redis-stack-decoupled-s3 2>/dev/null || echo 'starting')" == "healthy" ]; do
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
until docker exec kafka-decoupled-s3 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1 || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
if [ $ELAPSED -ge $TIMEOUT ]; then
  echo -e "${RED}Timeout waiting for Kafka.${NC}"
  compose logs kafka
  exit 1
fi
echo -e "${GREEN}Kafka is ready!${NC}"

# Wait for MinIO (port 9000)
ELAPSED=0
echo -e "${YELLOW}Waiting for MinIO S3 service to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' minio-decoupled-s3 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for MinIO.${NC}"
    compose logs minio
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}MinIO S3 service is healthy!${NC}"

# Pre-create target bucket and claims bucket if not present
echo -e "${YELLOW}Ensuring target and claim check buckets exist...${NC}"
aws_s3 s3 mb "s3://${SPRING_OPENCRAWLING_OUTPUT_S3_BUCKET}" 2>/dev/null || true
aws_s3 s3 mb "s3://${SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_BUCKET}" 2>/dev/null || true
echo -e "${GREEN}S3 Buckets verified (s3://${SPRING_OPENCRAWLING_OUTPUT_S3_BUCKET}, s3://${SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_BUCKET})!${NC}"

# Create a sample test document in the dedicated crawl folder (mounted as /crawl in oc-crawler)
TEST_FILENAME="s3-migration-contract.txt"
TEST_FILE="$TEST_DOC_DIR/$TEST_FILENAME"
CONTENT_TEXT="AWS S3 Migration Mode end-to-end integration test for OpenCrawling! Binary content preserved pristine as-it-is with companion OIS JSON sidecar and Zero-Trust ACLs."
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

# Start crawler service (first and only run) to trigger directory scan, Claim Check generation, and Kafka publication
echo -e "${YELLOW}Starting oc-crawler service in Migration Mode...${NC}"
compose up -d --no-deps oc-crawler

# Wait for crawler completion
ELAPSED=0
echo -e "${YELLOW}Waiting for oc-crawler service to finish directory scan...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-s3 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for oc-crawler-service-s3.${NC}"
    compose logs oc-crawler
    exit 1
  fi
  CRAWLER_LOG=$(docker logs --tail 1 oc-crawler-service-s3 2>&1 | tr '\r\n' ' ' || true)
  printf "  Crawler running (%ds): %s\r" "$ELAPSED" "${CRAWLER_LOG:0:80}"
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo ""
echo -e "${GREEN}oc-crawler-service finished directory scanning and published ingestion message in Migration Mode!${NC}"

# Wait for target S3 to receive the migrated binary and companion OIS JSON sidecar
echo -e "${YELLOW}Waiting for oc-writer-consumer to migrate binary and sidecar to target S3...${NC}"
ELAPSED=0
TIMEOUT=120
MIGRATED=false
S3_OBJECTS="[]"

until [ "$MIGRATED" == "true" ] || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 3
  ELAPSED=$((ELAPSED + 3))

  # Query keys in target S3 bucket using aws-cli
  S3_OBJECTS=$(aws_s3 s3api list-objects-v2 --bucket "$SPRING_OPENCRAWLING_OUTPUT_S3_BUCKET" --output json 2>/dev/null | jq '.Contents // []')
  KEY_COUNT=$(echo "$S3_OBJECTS" | jq '. | length' 2>/dev/null || echo "0")

  printf "  Elapsed: %ds, Target S3 keys count: %s\r" "$ELAPSED" "$KEY_COUNT"

  # Wait until both the binary and its .ois.json sidecar for the test document exist
  DOC_KEY_COUNT=$(echo "$S3_OBJECTS" | jq --arg fn "$TEST_FILENAME" '[.[].Key | select(endswith("/" + $fn) or endswith("/" + $fn + ".ois.json") or . == $fn or . == ($fn + ".ois.json"))] | length' 2>/dev/null || echo "0")
  if [ "$DOC_KEY_COUNT" -ge 2 ]; then
    MIGRATED=true
  fi
done
echo ""

if [ "$MIGRATED" != "true" ]; then
  echo -e "${RED}Target S3 migration timed out! Keys not found in $SPRING_OPENCRAWLING_OUTPUT_S3_BUCKET.${NC}"
  echo -e "${YELLOW}Crawler logs:${NC}"
  compose logs oc-crawler
  echo -e "${YELLOW}Writer consumer logs:${NC}"
  compose logs oc-writer-consumer
  echo -e "${YELLOW}Ingestion consumer logs:${NC}"
  compose logs oc-ingestion-consumer
  exit 1
fi

echo -e "${GREEN}Target S3 received migrated objects! Keys in target bucket:${NC}"
echo "$S3_OBJECTS" | jq -r '.[].Key'

# --- Clean target bucket check: only the crawled document + its sidecar (plus the connector's .opencrawling/ index) ---
CONTENT_KEYS=$(echo "$S3_OBJECTS" | jq -r '.[].Key | select((startswith(".opencrawling/") or endswith("/")) | not)' | sort)
EXPECTED_KEYS=$(echo "$CONTENT_KEYS" | grep -E "(^|/)${TEST_FILENAME}(\.ois\.json)?$" || true)
if [ "$(echo "$CONTENT_KEYS" | grep -c .)" -ne 2 ] || [ "$CONTENT_KEYS" != "$EXPECTED_KEYS" ]; then
  echo -e "${RED}FAILED: Target bucket is not clean. Expected only ${TEST_FILENAME} and its .ois.json sidecar, found:${NC}"
  echo "$CONTENT_KEYS"
  exit 1
fi
echo -e "${GREEN}PASSED: Target bucket contains only the migrated document and its OIS sidecar.${NC}"

# --- Verification Step 1: Migration Mode RAG Bypass Check (0 records in PgVector) ---
echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}Step 1: Verifying Migration Mode RAG Bypass (PgVector records must be 0)...    ${NC}"
echo -e "${YELLOW}================================================================================${NC}"
RECORD_COUNT=$(docker exec -i postgres-vector-decoupled-s3 psql -U opencrawling -d opencrawling -t -A -P pager=off -c \
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

# --- Verification Step 2: Binary Integrity in Target AWS S3 / MinIO ---
echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}Step 2: Verifying Pristine Binary Integrity in Target AWS S3...                ${NC}"
echo -e "${YELLOW}================================================================================${NC}"

# Find the binary key (the key not ending in .ois.json matching test filename)
BINARY_KEY=$(echo "$S3_OBJECTS" | jq -r --arg fn "$TEST_FILENAME" '.[] | select((.Key | endswith(".ois.json") | not) and (.Key | contains($fn))) | .Key' | head -n 1)
if [ -z "$BINARY_KEY" ]; then
  BINARY_KEY=$(echo "$S3_OBJECTS" | jq -r '.[] | select(.Key | endswith(".ois.json") | not) | .Key' | head -n 1)
fi
if [ -z "$BINARY_KEY" ]; then
  echo -e "${RED}FAIL: Target S3 binary object key not found!${NC}"
  exit 1
fi

echo -e "Identified Target S3 Binary Key: ${GREEN}$BINARY_KEY${NC}"

# Retrieve binary content directly from target S3
RETRIEVED_CONTENT=$(aws_s3 s3 cp "s3://$SPRING_OPENCRAWLING_OUTPUT_S3_BUCKET/$BINARY_KEY" - 2>/dev/null || true)

if [ "$RETRIEVED_CONTENT" != "$CONTENT_TEXT" ]; then
  echo -e "${RED}FAIL: Content mismatch between source document and target S3 object!${NC}"
  echo -e "Expected: $CONTENT_TEXT"
  echo -e "Actual:   $RETRIEVED_CONTENT"
  exit 1
fi
echo -e "${GREEN}PASSED: Pristine binary content in target S3 perfectly matches source!${NC}"

# --- Verification Step 3: Companion OIS JSON Sidecar Verification ---
echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}Step 3: Verifying Companion OIS JSON Sidecar & Zero-Trust Metadata...         ${NC}"
echo -e "${YELLOW}================================================================================${NC}"

SIDECAR_KEY=$(echo "$S3_OBJECTS" | jq -r --arg fn "$TEST_FILENAME" '.[] | select((.Key | endswith(".ois.json")) and (.Key | contains($fn))) | .Key' | head -n 1)
if [ -z "$SIDECAR_KEY" ]; then
  SIDECAR_KEY=$(echo "$S3_OBJECTS" | jq -r '.[] | select(.Key | endswith(".ois.json")) | .Key' | head -n 1)
fi
if [ -z "$SIDECAR_KEY" ]; then
  echo -e "${RED}FAIL: Companion OIS JSON sidecar key not found in target S3!${NC}"
  exit 1
fi

echo -e "Identified Companion OIS JSON Sidecar Key: ${GREEN}$SIDECAR_KEY${NC}"

# Retrieve OIS JSON payload
OIS_JSON=$(aws_s3 s3 cp "s3://$SPRING_OPENCRAWLING_OUTPUT_S3_BUCKET/$SIDECAR_KEY" - 2>/dev/null || echo "{}")

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

# Validate the stored URI matches the S3 scheme
OIS_URI=$(echo "$OIS_JSON" | jq -r '.uri // ""')
EXPECTED_URI="s3://${SPRING_OPENCRAWLING_OUTPUT_S3_BUCKET}/$BINARY_KEY"
if [ "$OIS_URI" != "$EXPECTED_URI" ]; then
  echo -e "${RED}FAIL: OIS uri '$OIS_URI' does not match target S3 (expected '$EXPECTED_URI')${NC}"
  exit 1
fi
echo -e "OIS URI:                ${GREEN}$OIS_URI${NC}"

# Validate SHA-256 checksum in ContentRef
OIS_SHA256=$(echo "$OIS_JSON" | jq -r '.contentRef.checksumSha256 // ""')
if [ "$OIS_SHA256" != "$SOURCE_SHA256" ]; then
  echo -e "${RED}FAIL: OIS SHA-256 checksum ($OIS_SHA256) does not match source ($SOURCE_SHA256)!${NC}"
  exit 1
fi
echo -e "OIS SHA-256:            ${GREEN}$OIS_SHA256${NC}"

# Validate Size in ContentRef
OIS_SIZE=$(echo "$OIS_JSON" | jq -r '.contentRef.contentLength // 0')
if [ "$OIS_SIZE" -ne "$SOURCE_SIZE" ]; then
  echo -e "${RED}FAIL: OIS contentRef.contentLength ($OIS_SIZE) does not match source size ($SOURCE_SIZE)!${NC}"
  exit 1
fi
echo -e "OIS Content Size:       ${GREEN}$OIS_SIZE bytes${NC}"

# Validate filename and binary locator in ContentRef
OIS_FILENAME=$(echo "$OIS_JSON" | jq -r '.contentRef.filename // ""')
OIS_KEY=$(echo "$OIS_JSON" | jq -r '.contentRef.key // ""')
if [ "$OIS_FILENAME" != "$TEST_FILENAME" ] || [ "$OIS_KEY" != "$BINARY_KEY" ]; then
  echo -e "${RED}FAIL: OIS contentRef filename/key mismatch (filename='$OIS_FILENAME', key='$OIS_KEY', expected '$TEST_FILENAME' / '$BINARY_KEY')${NC}"
  exit 1
fi
echo -e "OIS Filename / Key:     ${GREEN}$OIS_FILENAME / $OIS_KEY${NC}"

# Validate Zero-Trust Security structure (inheritanceEnabled boolean + permissions array)
if ! echo "$OIS_JSON" | jq -e '(.security.inheritanceEnabled | type == "boolean") and (.security.permissions | type == "array")' >/dev/null; then
  echo -e "${RED}FAIL: OIS security block must contain boolean 'inheritanceEnabled' and array 'permissions'${NC}"
  exit 1
fi
SECURITY_INHERITANCE=$(echo "$OIS_JSON" | jq -r '.security.inheritanceEnabled')
SECURITY_RULES=$(echo "$OIS_JSON" | jq -r '.security.permissions | length')
echo -e "Zero-Trust Inheritance: ${GREEN}$SECURITY_INHERITANCE${NC} (${SECURITY_RULES} permission rules)"

echo -e "${GREEN}PASSED: Companion OIS JSON metadata sidecar and Zero-Trust ACLs verified!${NC}"

# --- Verification Step 3b: Decoupled writer consumer actually handled the document ---
if ! compose logs oc-writer-consumer 2>/dev/null | grep -q "Successfully migrated document .* via decoupled consumer"; then
  echo -e "${RED}FAIL: oc-writer-consumer did not migrate the document from Kafka (check consumer group / topic wiring)${NC}"
  compose logs oc-writer-consumer | tail -50
  exit 1
fi
echo -e "${GREEN}PASSED: Decoupled oc-writer-consumer migrated the document from Kafka.${NC}"

# --- Verification Step 4: OIS Document Lifecycle Tombstone DELETE Validation (unit level) ---
echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}Step 4: Verifying OIS Document Lifecycle Tombstone DELETE Action (unit suite)...${NC}"
echo -e "${YELLOW}================================================================================${NC}"
mvn -q test -pl oc-s3-output-connector -Dtest=S3MigrationWriterConsumerTest,S3OutputConnectorTest -Dsurefire.failIfNoSpecifiedTests=false
echo -e "${GREEN}PASSED: OIS Tombstone DELETE lifecycle verified!${NC}"

# --- Verification Step 5: MCP Server Reachability ---
echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}Step 5: Verifying MCP Server Reachability in Migration Mode...                 ${NC}"
echo -e "${YELLOW}================================================================================${NC}"
HTTP_STATUS="000"
ELAPSED=0
TIMEOUT=60
until [ "$HTTP_STATUS" == "200" ] || [ "$HTTP_STATUS" == "400" ] || [ "$HTTP_STATUS" == "405" ] || [ "$HTTP_STATUS" == "404" ] || [ $ELAPSED -ge $TIMEOUT ]; do
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8080/mcp 2>/dev/null || echo "")
  if [ -z "$HTTP_STATUS" ] || [ "$HTTP_STATUS" == "000" ]; then
    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8080/ 2>/dev/null || echo "000")
  fi
  if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "400" ] && [ "$HTTP_STATUS" != "405" ] && [ "$HTTP_STATUS" != "404" ]; then
    sleep 2
    ELAPSED=$((ELAPSED + 2))
  fi
done
echo -e "MCP Server HTTP Status: ${GREEN}$HTTP_STATUS${NC}"

if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "400" ] && [ "$HTTP_STATUS" != "405" ] && [ "$HTTP_STATUS" != "404" ]; then
  echo -e "${RED}Decoupled integration test failed: MCP Server returned unexpected status $HTTP_STATUS${NC}"
  compose logs oc-mcp-server
  exit 1
fi
echo -e "${GREEN}MCP Server is reachable (HTTP $HTTP_STATUS)${NC}"

# SHA-256 helper
sha256_stdin() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 | awk '{print $1}'
  else openssl dgst -sha256 | awk '{print $NF}'; fi
}

# --- Step 5b: Large binary via parallel multipart upload (LARGE_FILE_MB=N, 0 = skip) ---
LARGE_FILE_MB="${LARGE_FILE_MB:-16}"
if [ "$LARGE_FILE_MB" -gt 0 ]; then
  echo -e "${YELLOW}================================================================================${NC}"
  echo -e "${YELLOW}Step 5b: Large binary (${LARGE_FILE_MB} MB) via multipart upload (threshold ${OC_MULTIPART_THRESHOLD}, parts ${OC_MULTIPART_PART_SIZE})...${NC}"
  echo -e "${YELLOW}================================================================================${NC}"
  LARGE_NAME="large-${LARGE_FILE_MB}mb.bin"
  LARGE_BYTES=$((LARGE_FILE_MB * 1024 * 1024))
  mkdir -p "$TEST_DOC_DIR/large"
  head -c "$LARGE_BYTES" /dev/urandom > "$TEST_DOC_DIR/large/$LARGE_NAME"
  LARGE_SHA256=$(sha256_stdin < "$TEST_DOC_DIR/large/$LARGE_NAME")
  echo -e "Source: ${CYAN}$LARGE_NAME${NC} ($LARGE_BYTES bytes, SHA-256 ${CYAN}$LARGE_SHA256${NC})"

  LARGE_SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  compose up -d --no-deps --force-recreate oc-crawler >/dev/null 2>&1
  ELAPSED=0
  until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-s3 2>/dev/null || echo 'false')" == "false" ]; do
    if [ $ELAPSED -ge 300 ]; then echo -e "${RED}Timeout waiting for crawler (large file).${NC}"; exit 1; fi
    sleep 3; ELAPSED=$((ELAPSED + 3))
  done
  CRAWLER_LOGS=$(docker logs oc-crawler-service-s3 2>&1)
  if ! echo "$CRAWLER_LOGS" | grep -q "Saved document content to Claim Check store: .*${LARGE_NAME}"; then
    echo -e "${RED}FAIL: crawler did not save ${LARGE_NAME} to the claim check${NC}"
    echo "$CRAWLER_LOGS" | grep -E "ERROR|${LARGE_NAME}" | head -10; exit 1
  fi

  ELAPSED=0
  until docker logs --since "$LARGE_SINCE" oc-writer-service-s3 2>&1 | grep -q "Successfully migrated document .*${LARGE_NAME}"; do
    if [ $ELAPSED -ge 300 ]; then
      echo -e "${RED}FAIL: writer did not migrate ${LARGE_NAME} within 300s${NC}"
      docker logs --since "$LARGE_SINCE" oc-writer-service-s3 2>&1 | grep -E "ERROR|Exception" | head -20; exit 1
    fi
    sleep 2; ELAPSED=$((ELAPSED + 2))
  done
  WRITER_LOGS=$(docker logs --since "$LARGE_SINCE" oc-writer-service-s3 2>&1)
  if ! echo "$WRITER_LOGS" | grep -q "Uploading large binary .* to S3 via S3MultipartUploader"; then
    echo -e "${RED}FAIL: S3 writer did not use S3MultipartUploader for ${LARGE_NAME}${NC}"; exit 1
  fi
  echo -e "${GREEN}PASSED: S3 writer uploaded the large binary with S3MultipartUploader.${NC}"

  TARGET_KEYS=$(aws_s3 s3api list-objects-v2 --bucket "$SPRING_OPENCRAWLING_OUTPUT_S3_BUCKET" --output json 2>/dev/null | jq '.Contents // []')
  LARGE_KEY=$(echo "$TARGET_KEYS" | jq -r --arg n "$LARGE_NAME" '.[] | select(.Key | endswith($n)) | .Key' | head -n 1)
  if [ -z "$LARGE_KEY" ]; then echo -e "${RED}FAIL: ${LARGE_NAME} not found in target S3 bucket${NC}"; exit 1; fi
  TARGET_SHA256=$(aws_s3 s3 cp "s3://$SPRING_OPENCRAWLING_OUTPUT_S3_BUCKET/$LARGE_KEY" - 2>/dev/null | sha256_stdin)
  SIDECAR_SHA256=$(aws_s3 s3 cp "s3://$SPRING_OPENCRAWLING_OUTPUT_S3_BUCKET/${LARGE_KEY}.ois.json" - 2>/dev/null \
    | jq -r '.contentRef.checksumSha256 // empty')
  if [ "$TARGET_SHA256" != "$LARGE_SHA256" ] || [ "$SIDECAR_SHA256" != "$LARGE_SHA256" ]; then
    echo -e "${RED}FAIL: large binary checksum mismatch${NC}"
    echo -e "  source:  $LARGE_SHA256\n  target:  $TARGET_SHA256\n  sidecar: $SIDECAR_SHA256"; exit 1
  fi
  echo -e "${GREEN}PASSED: ${LARGE_KEY} is bit-for-bit identical in target S3 (SHA-256 of object and sidecar match).${NC}"
  rm -rf "$TEST_DOC_DIR/large"
fi

echo -e "${GREEN}================================================================================${NC}"
echo -e "${GREEN}SUCCESS: All Decoupled S3 Migration Integration Tests Passed Successfully!     ${NC}"
echo -e "${GREEN}================================================================================${NC}"

# Clean up temporary test files
echo -e "${YELLOW}Cleaning up temporary test files...${NC}"
rm -rf "$TEST_DOC_DIR"

# Tear down the test environment
echo -e "${YELLOW}Tearing down test environment...${NC}"
compose down -v

exit 0
