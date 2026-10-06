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
# Claim check transport: NATIVE (default, Ozone RPC to the source OM) or S3 (source S3 Gateway)
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_CLIENT_TYPE="$(echo "${CLAIM_CHECK_OZONE_CLIENT_TYPE:-NATIVE}" | tr '[:lower:]' '[:upper:]')"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_S3_ENDPOINT="http://localhost:9878"
# Output transport: NATIVE (default, ofs/RPC to the target OM) or S3G (target S3 Gateway)
export SPRING_OPENCRAWLING_OUTPUT_OZONE_CLIENT_TYPE="$(echo "${OUTPUT_OZONE_CLIENT_TYPE:-NATIVE}" | tr '[:lower:]' '[:upper:]')"
export SPRING_OPENCRAWLING_OUTPUT_OZONE_VOLUME="s3v"
export SPRING_OPENCRAWLING_OUTPUT_OZONE_BUCKET="migration-target"
export SPRING_OPENCRAWLING_OUTPUT_OZONE_S3_ENDPOINT="http://localhost:9879"
# Low multipart thresholds (production default: 256MB/16MB) so Step 5b's large file exercises parallel multipart
# uploads on the S3 transports (crawler claim check with CLAIM_CHECK_OZONE_CLIENT_TYPE=S3, writer with S3G).
export OC_MULTIPART_THRESHOLD="${OC_MULTIPART_THRESHOLD:-8MB}"
export OC_MULTIPART_PART_SIZE="${OC_MULTIPART_PART_SIZE:-5MB}"
export OC_MULTIPART_CONCURRENCY="${OC_MULTIPART_CONCURRENCY:-4}"

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

# Dedicated crawl source folder: contains ONLY the documents to migrate, so runtime state in
# oc-runtime/data (settings, connectors, jobs, claims) never ends up in the target bucket.
TEST_DOC_DIR="./oc-runtime/crawl-data/ozone-migration"
rm -rf "$TEST_DOC_DIR"
mkdir -p "$TEST_DOC_DIR"
export OC_CRAWL_SOURCE_DIR="$(cd "$TEST_DOC_DIR" && pwd)"

# Start services (except oc-crawler: it crawls on startup, so it is started only once Ozone is writable)
echo -e "${YELLOW}Starting complete decoupled multi-service infrastructure with separated target Ozone...${NC}"
compose up -d $(compose config --services | grep -vx 'oc-crawler')

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

# Wait until each cluster has an OPEN write pipeline and accepts a real key write.
# Ozone answers S3/OM requests long before SCM creates a RATIS pipeline (~1-2 min after start);
# writes issued earlier hang until the client times out.
wait_for_writable_ozone() {
  local om_container="$1" bucket_path="$2"
  local elapsed=0 limit=300
  echo -e "${YELLOW}Waiting for writable pipeline on ${om_container} (${bucket_path})...${NC}"
  until docker exec "$om_container" ozone admin pipeline list 2>/dev/null | grep -q "State:OPEN" \
     && docker exec "$om_container" sh -c "echo probe > /tmp/oc-probe && ozone sh key put ${bucket_path}/.oc-write-probe /tmp/oc-probe" >/dev/null 2>&1; do
    if [ $elapsed -ge $limit ]; then
      echo -e "${RED}Timeout waiting for writable Ozone pipeline on ${om_container}.${NC}"
      docker exec "$om_container" ozone admin pipeline list 2>&1 | tail -5 || true
      exit 1
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  docker exec "$om_container" ozone sh key delete "${bucket_path}/.oc-write-probe" >/dev/null 2>&1 || true
  echo -e "${GREEN}${om_container} is writable (after ${elapsed}s).${NC}"
}

if [ "$SPRING_OPENCRAWLING_CLAIM_CHECK_STORE" == "ozone" ]; then
  wait_for_writable_ozone ozone-om-source /s3v/claims
fi
wait_for_writable_ozone target-ozone-om /s3v/migration-target

# Create a sample test document in the dedicated crawl folder (mounted as /crawl in oc-crawler)
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

# Start crawler service (first and only run) to trigger directory scan, Claim Check generation, and Kafka publication
echo -e "${YELLOW}Starting oc-crawler service in Migration Mode...${NC}"
compose up -d --no-deps oc-crawler

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

  # Wait until both the binary and its .ois.json sidecar for the test document exist
  DOC_KEY_COUNT=$(echo "$OZONE_KEYS" | jq --arg fn "$TEST_FILENAME" '[.[].name | select(endswith("/" + $fn) or endswith("/" + $fn + ".ois.json") or . == $fn or . == ($fn + ".ois.json"))] | length' 2>/dev/null || echo "0")
  if [ "$DOC_KEY_COUNT" -ge 2 ]; then
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

# --- Clean target bucket check: only the crawled document + its sidecar (plus the connector's .opencrawling/ index) ---
# Directory entries (keys ending in "/") are ignored: native RPC writes create real parent directories in Ozone's namespace.
CONTENT_KEYS=$(echo "$OZONE_KEYS" | jq -r '.[].name | select((startswith(".opencrawling/") or endswith("/")) | not)' | sort)
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

# Validate the stored URI matches the transport under test (proves which protocol wrote the object)
OIS_URI=$(echo "$OIS_JSON" | jq -r '.uri // ""')
if [ "$(echo "$SPRING_OPENCRAWLING_OUTPUT_OZONE_CLIENT_TYPE" | tr '[:lower:]' '[:upper:]')" == "S3G" ]; then
  EXPECTED_URI="s3://migration-target/$BINARY_KEY"
else
  EXPECTED_URI="ofs://s3v/migration-target/$BINARY_KEY"
fi
if [ "$OIS_URI" != "$EXPECTED_URI" ]; then
  echo -e "${RED}FAIL: OIS uri '$OIS_URI' does not match the ${SPRING_OPENCRAWLING_OUTPUT_OZONE_CLIENT_TYPE} transport (expected '$EXPECTED_URI')${NC}"
  exit 1
fi
echo -e "OIS URI (${SPRING_OPENCRAWLING_OUTPUT_OZONE_CLIENT_TYPE}):     ${GREEN}$OIS_URI${NC}"

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
# The standalone crawler only publishes to Kafka, so the target objects must come from the dedicated writer consumer group.
if ! compose logs oc-writer-consumer 2>/dev/null | grep -q "Successfully migrated document .* via decoupled consumer"; then
  echo -e "${RED}FAIL: oc-writer-consumer did not migrate the document from Kafka (check consumer group / topic wiring)${NC}"
  compose logs oc-writer-consumer | tail -50
  exit 1
fi
echo -e "${GREEN}PASSED: Decoupled oc-writer-consumer migrated the document from Kafka.${NC}"

# --- Verification Step 4: OIS Document Lifecycle Tombstone DELETE Validation (unit level) ---
# The filesystem repository connector does not emit live DELETE tombstones, so the lifecycle
# (metadata-less tombstone -> key index lookup -> purge / archive) is verified by the connector suite.
echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}Step 4: Verifying OIS Document Lifecycle Tombstone DELETE Action (unit suite)...${NC}"
echo -e "${YELLOW}================================================================================${NC}"
mvn -q test -pl oc-ozone-output-connector -Dtest=OzoneMigrationWriterConsumerTest,OzoneOutputConnectorTest -Dsurefire.failIfNoSpecifiedTests=false
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

# SHA-256 (hex) of stdin
sha256_stdin() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 | awk '{print $1}'
  else openssl dgst -sha256 | awk '{print $NF}'; fi
}

# Prints "<LABEL> [transport] docs= threads= duration= rate=" from the first/last ISO timestamp of the log lines on stdin.
rate_line() {
  python3 -c '
import sys
from datetime import datetime
label, total, docs, threads, transport = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], sys.argv[5]
ts = [datetime.fromisoformat(l[:24].strip().replace("Z", "+00:00")) for l in sys.stdin if l.strip()]
secs = max((max(ts) - min(ts)).total_seconds(), 0.001)
print(f"{label} [{transport}] docs={docs} threads={threads} duration={secs:.1f}s "
      f"rate={docs / secs:.1f} docs/s, {total / secs / 1048576:.2f} MB/s")
' "$@"
}

# --- Step 5b: Large binary via parallel multipart upload (LARGE_FILE_MB=N, 0 = skip) ---
# Crawls one large random file and verifies the target object is bit-for-bit identical (SHA-256). On the S3
# transports the crawler (claim check, CLAIM_CHECK_OZONE_CLIENT_TYPE=S3) and the writer (S3G) must also use a
# multipart upload; NATIVE streams blocks directly to the datanodes, so only integrity and timing are checked.
LARGE_FILE_MB="${LARGE_FILE_MB:-24}"
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
  until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-ozone 2>/dev/null || echo 'false')" == "false" ]; do
    if [ $ELAPSED -ge 300 ]; then echo -e "${RED}Timeout waiting for crawler (large file).${NC}"; exit 1; fi
    sleep 3; ELAPSED=$((ELAPSED + 3))
  done
  CRAWLER_LOGS=$(docker logs oc-crawler-service-ozone 2>&1)
  # OC_MULTIPART_THRESHOLD=0 disables multipart (single PUT baseline for comparisons)
  MULTIPART_ON=true; [ "$OC_MULTIPART_THRESHOLD" == "0" ] && MULTIPART_ON=false
  CC_MULTIPART=false
  [ "$MULTIPART_ON" == "true" ] && [ "$SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_CLIENT_TYPE" == "S3" ] && CC_MULTIPART=true
  if [ "$CC_MULTIPART" == "true" ] && ! echo "$CRAWLER_LOGS" | grep -q "Multipart upload of s3://claims/.*${LARGE_NAME}"; then
    echo -e "${RED}FAIL: crawler did not upload ${LARGE_NAME} to the claim check with a multipart upload${NC}"
    echo "$CRAWLER_LOGS" | grep -E "ERROR|${LARGE_NAME}" | head -10; exit 1
  fi
  [ "$CC_MULTIPART" == "true" ] && echo -e "${GREEN}PASSED: crawler uploaded the claim check with a multipart upload.${NC}"
  if ! echo "$CRAWLER_LOGS" | grep -q "Saved document content to Claim Check store: .*${LARGE_NAME}"; then
    echo -e "${RED}FAIL: crawler did not save ${LARGE_NAME} to the claim check${NC}"
    echo "$CRAWLER_LOGS" | grep -E "ERROR|${LARGE_NAME}" | head -10; exit 1
  fi
  # Start = crawler log line just before the upload (multipart start, or the scan job start for single PUT / NATIVE)
  echo "$CRAWLER_LOGS" | grep -E "Multipart upload of s3://claims/.*${LARGE_NAME}|Starting job|Saved document content to Claim Check store: .*${LARGE_NAME}" \
    | rate_line LARGE_CLAIM_CHECK_UPLOAD "$LARGE_BYTES" 1 "$OC_MULTIPART_CONCURRENCY" "$SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_CLIENT_TYPE"

  ELAPSED=0
  until docker logs --since "$LARGE_SINCE" oc-writer-service-ozone 2>&1 | grep -q "Successfully migrated document .*${LARGE_NAME}"; do
    if [ $ELAPSED -ge 300 ]; then
      echo -e "${RED}FAIL: writer did not migrate ${LARGE_NAME} within 300s${NC}"
      docker logs --since "$LARGE_SINCE" oc-writer-service-ozone 2>&1 | grep -E "ERROR|Exception" | head -20; exit 1
    fi
    sleep 2; ELAPSED=$((ELAPSED + 2))
  done
  WRITER_LOGS=$(docker logs --since "$LARGE_SINCE" oc-writer-service-ozone 2>&1)
  if [ "$SPRING_OPENCRAWLING_OUTPUT_OZONE_CLIENT_TYPE" == "S3G" ] && [ "$MULTIPART_ON" == "true" ]; then
    if ! echo "$WRITER_LOGS" | grep -q "Multipart upload of s3://migration-target/.*${LARGE_NAME}"; then
      echo -e "${RED}FAIL: S3G writer did not use a multipart upload for ${LARGE_NAME}${NC}"; exit 1
    fi
    echo -e "${GREEN}PASSED: S3G writer uploaded the binary with a multipart upload.${NC}"
  fi
  echo "$WRITER_LOGS" | grep -E "(processing migration document|Successfully migrated document).*${LARGE_NAME}" \
    | rate_line LARGE_FILE_MIGRATION "$LARGE_BYTES" 1 "$OC_MULTIPART_CONCURRENCY" "$SPRING_OPENCRAWLING_OUTPUT_OZONE_CLIENT_TYPE"

  TARGET_KEYS=$(docker exec target-ozone-om ozone sh key list -l 1000000 /s3v/migration-target 2>/dev/null || echo "[]")
  LARGE_KEY=$(echo "$TARGET_KEYS" | jq -r --arg n "$LARGE_NAME" '.[] | select(.name | endswith($n)) | .name' | head -n 1)
  if [ -z "$LARGE_KEY" ]; then echo -e "${RED}FAIL: ${LARGE_NAME} not found in the target bucket${NC}"; exit 1; fi
  TARGET_SHA256=$(docker exec target-ozone-om ozone sh key cat "/s3v/migration-target/$LARGE_KEY" 2>/dev/null | sha256_stdin)
  SIDECAR_SHA256=$(docker exec target-ozone-om ozone sh key cat "/s3v/migration-target/${LARGE_KEY}.ois.json" 2>/dev/null \
    | jq -r '.contentRef.checksumSha256 // empty')
  if [ "$TARGET_SHA256" != "$LARGE_SHA256" ] || [ "$SIDECAR_SHA256" != "$LARGE_SHA256" ]; then
    echo -e "${RED}FAIL: large binary checksum mismatch${NC}"
    echo -e "  source:  $LARGE_SHA256\n  target:  $TARGET_SHA256\n  sidecar: $SIDECAR_SHA256"; exit 1
  fi
  echo -e "${GREEN}PASSED: ${LARGE_KEY} is bit-for-bit identical in the target (SHA-256 of object and sidecar match).${NC}"
  # Keep later steps (load counts) independent of this file.
  rm -rf "$TEST_DOC_DIR/large"
fi

# --- Optional Step 6: Writer throughput (LOAD_DOCS=N) ---
# Measures the writer consumer in isolation: the writer is stopped while the crawler publishes N
# documents (building a Kafka backlog), then restarted and timed until the backlog is drained.
# Timing uses the writer's own log timestamps (first "processing" -> last "Successfully migrated").
LOAD_DOCS="${LOAD_DOCS:-0}"
if [ "$LOAD_DOCS" -gt 0 ]; then
  echo -e "${YELLOW}================================================================================${NC}"
  echo -e "${YELLOW}Step 6: Writer throughput with ${LOAD_DOCS} documents (${SPRING_OPENCRAWLING_OUTPUT_OZONE_CLIENT_TYPE})...${NC}"
  echo -e "${YELLOW}================================================================================${NC}"

  compose stop oc-writer-consumer >/dev/null 2>&1

  # Mixed sizes: 4 KB, 64 KB, 256 KB, 1 MB (round-robin)
  SIZES=(4096 65536 262144 1048576)
  TOTAL_BYTES=0
  mkdir -p "$TEST_DOC_DIR/load"
  for i in $(seq 1 "$LOAD_DOCS"); do
    SIZE=${SIZES[$(( (i - 1) % 4 ))]}
    head -c "$SIZE" /dev/urandom > "$TEST_DOC_DIR/load/doc-$(printf '%05d' "$i").bin"
    TOTAL_BYTES=$((TOTAL_BYTES + SIZE))
  done
  EXPECTED_DOCS=$((LOAD_DOCS + 1)) # generated files + the contract document crawled again
  echo -e "Generated ${CYAN}${LOAD_DOCS}${NC} files ($((TOTAL_BYTES / 1024 / 1024)) MB). Crawling with writer stopped..."

  compose up -d --no-deps --force-recreate oc-crawler >/dev/null 2>&1
  ELAPSED=0
  LOAD_TIMEOUT=900
  until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-ozone 2>/dev/null || echo 'false')" == "false" ]; do
    if [ $ELAPSED -ge $LOAD_TIMEOUT ]; then
      echo -e "${RED}Timeout waiting for crawler in load mode.${NC}"; exit 1
    fi
    sleep 3; ELAPSED=$((ELAPSED + 3))
  done
  PUBLISHED=$(docker logs oc-crawler-service-ozone 2>&1 | grep -c "Published document reference to Kafka" || true)
  echo -e "Crawler published ${CYAN}${PUBLISHED}${NC} messages in ${ELAPSED}s. Starting writer..."
  if [ "$PUBLISHED" -lt "$EXPECTED_DOCS" ]; then
    echo -e "${RED}FAIL: crawler published ${PUBLISHED}/${EXPECTED_DOCS} messages${NC}"; exit 1
  fi

  # Crawler side: claim-check upload + Kafka publish, timed from its own logs (excludes JVM startup).
  CRAWLER_LOGS=$(docker logs oc-crawler-service-ozone 2>&1)
  CRAWLER_LANES=$(echo "$CRAWLER_LOGS" | grep -oE 'with [0-9]+ parallel lanes' | grep -oE '[0-9]+' | tail -1)
  echo "$CRAWLER_LOGS" | grep -E "Saved document content to Claim Check store|Published document reference to Kafka" \
    | rate_line CRAWLER_THROUGHPUT "$TOTAL_BYTES" "$LOAD_DOCS" "${CRAWLER_LANES:-1}" "$SPRING_OPENCRAWLING_OUTPUT_OZONE_CLIENT_TYPE"

  WRITER_SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  compose start oc-writer-consumer >/dev/null 2>&1
  ELAPSED=0
  MIGRATED_COUNT=0
  until [ "$MIGRATED_COUNT" -ge "$EXPECTED_DOCS" ] || [ $ELAPSED -ge $LOAD_TIMEOUT ]; do
    sleep 2; ELAPSED=$((ELAPSED + 2))
    MIGRATED_COUNT=$(docker logs --since "$WRITER_SINCE" oc-writer-service-ozone 2>&1 | grep -c "Successfully migrated document" || true)
    printf "  Writer migrated: %s/%s (%ds)\r" "$MIGRATED_COUNT" "$EXPECTED_DOCS" "$ELAPSED"
  done
  echo ""
  if [ "$MIGRATED_COUNT" -lt "$EXPECTED_DOCS" ]; then
    echo -e "${RED}FAIL: writer migrated only ${MIGRATED_COUNT}/${EXPECTED_DOCS} documents within ${LOAD_TIMEOUT}s${NC}"
    docker logs --since "$WRITER_SINCE" oc-writer-service-ozone 2>&1 | grep -E "ERROR|Exception" | head -20
    exit 1
  fi

  # Every generated document must be in the target (binary + sidecar)
  LOAD_KEYS=$(docker exec target-ozone-om ozone sh key list -l 1000000 /s3v/migration-target 2>/dev/null || echo "[]")
  LOAD_BINARIES=$(echo "$LOAD_KEYS" | jq '[.[].name | select(test("/load/doc-[0-9]+\\.bin$"))] | length')
  LOAD_SIDECARS=$(echo "$LOAD_KEYS" | jq '[.[].name | select(test("/load/doc-[0-9]+\\.bin\\.ois\\.json$"))] | length')
  if [ "$LOAD_BINARIES" -ne "$LOAD_DOCS" ] || [ "$LOAD_SIDECARS" -ne "$LOAD_DOCS" ]; then
    echo -e "${RED}FAIL: target has ${LOAD_BINARIES} binaries / ${LOAD_SIDECARS} sidecars, expected ${LOAD_DOCS} each${NC}"; exit 1
  fi

  WRITER_LOGS=$(docker logs --since "$WRITER_SINCE" oc-writer-service-ozone 2>&1)
  CONCURRENCY_OBSERVED=$(echo "$WRITER_LOGS" | grep "Successfully migrated document" | sed -E 's/.*\[ *([^]]+)\] o\.o\.o\.m.*/\1/' | sort -u | wc -l | tr -d ' ')
  echo "$WRITER_LOGS" | grep -E "processing migration document|Successfully migrated document" \
    | rate_line THROUGHPUT "$TOTAL_BYTES" "$LOAD_DOCS" "$CONCURRENCY_OBSERVED" "$SPRING_OPENCRAWLING_OUTPUT_OZONE_CLIENT_TYPE"
  echo -e "${GREEN}PASSED: Writer migrated all ${LOAD_DOCS} load documents (binaries + sidecars verified in target).${NC}"
fi

echo -e "${GREEN}================================================================================${NC}"
echo -e "${GREEN}SUCCESS: All Decoupled Ozone Migration Integration Tests Passed Successfully!  ${NC}"
echo -e "${GREEN}================================================================================${NC}"

# Clean up temporary test files
echo -e "${YELLOW}Cleaning up temporary test files...${NC}"
rm -rf "$TEST_DOC_DIR"

# Tear down the test environment
echo -e "${YELLOW}Tearing down test environment...${NC}"
compose down -v

exit 0
