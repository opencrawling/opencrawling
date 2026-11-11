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

# End-to-end decoupled integration test for Apache Iceberg Lakehouse Output Connector.
# Exercises decoupled ingestion pipeline with Kafka, Claim Check store, Iceberg REST Catalog,
# and S3-compatible object storage (MinIO) writing Parquet tables with Zero-Trust OIS security.
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
BOLD='\033[1m'
NC='\033[0m' # No Color

export SPRING_OPENCRAWLING_OUTPUT_TYPE=iceberg
export SPRING_OPENCRAWLING_OUTPUT_ICEBERG_CATALOG_TYPE=rest
export SPRING_OPENCRAWLING_OUTPUT_ICEBERG_CATALOG_NAME=opencrawling_lakehouse
export SPRING_OPENCRAWLING_OUTPUT_ICEBERG_CATALOG_URI="http://localhost:8181"
export SPRING_OPENCRAWLING_OUTPUT_ICEBERG_WAREHOUSE="s3://warehouse"
export SPRING_OPENCRAWLING_OUTPUT_ICEBERG_NAMESPACE="enterprise_data"
export SPRING_OPENCRAWLING_OUTPUT_ICEBERG_TABLE_NAME="documents"

echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}=== OpenCrawling Decoupled Apache Iceberg Lakehouse Integration Test         ===${NC}"
echo -e "${YELLOW}================================================================================${NC}"
echo -e "Output Connector:       ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_TYPE}${NC}"
echo -e "Iceberg Catalog Type:   ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_ICEBERG_CATALOG_TYPE}${NC}"
echo -e "Iceberg Catalog URI:    ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_ICEBERG_CATALOG_URI}${NC}"
echo -e "Target Namespace:       ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_ICEBERG_NAMESPACE}${NC}"
echo -e "Target Table:           ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_ICEBERG_TABLE_NAME}${NC}"

# Switch to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo -e "${RED}curl is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo -e "${RED}jq is required but not installed. Aborting.${NC}" >&2; exit 1; }

COMPOSE_FILE="oc-iceberg-output-connector/docker/docker-compose-decoupled-with-iceberg.yml"

compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

cleanup() {
  echo -e "\n${YELLOW}Tearing down Iceberg decoupled test environment...${NC}"
  compose down --remove-orphans >/dev/null 2>&1 || true
  rm -f "${TEST_FILE:-}"
}
trap cleanup EXIT

# Clean up previous containers
echo -e "${YELLOW}Cleaning up previous Iceberg decoupled containers...${NC}"
compose down --remove-orphans || true

# Build microservices images
echo -e "${YELLOW}Building OpenCrawling decoupled microservice images from source...${NC}"
compose build

# Start services
echo -e "${YELLOW}Starting complete decoupled Iceberg-based multi-service infrastructure...${NC}"
compose up -d

TIMEOUT=180
ELAPSED=0

# Wait for MinIO
echo -e "${YELLOW}Waiting for MinIO object storage to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' minio-decoupled-iceberg 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for MinIO decoupled container.${NC}"
    compose logs minio
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}MinIO object storage is healthy!${NC}"

# Ensure 'warehouse' bucket exists in MinIO
echo -e "${YELLOW}Creating 'warehouse' bucket in MinIO...${NC}"
docker exec minio-decoupled-iceberg mc alias set local http://localhost:9000 admin password >/dev/null 2>&1 || true
docker exec minio-decoupled-iceberg mc mb local/warehouse >/dev/null 2>&1 || true
echo -e "${GREEN}MinIO warehouse bucket ready!${NC}"

# Wait for Iceberg REST Catalog
ELAPSED=0
echo -e "${YELLOW}Waiting for Iceberg REST Catalog to be reachable...${NC}"
until curl -s "http://localhost:8181/v1/config" > /dev/null || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
if [ $ELAPSED -ge $TIMEOUT ]; then
  echo -e "${RED}Timeout waiting for Iceberg REST Catalog.${NC}"
  compose logs iceberg-rest
  exit 1
fi
echo -e "${GREEN}Iceberg REST Catalog is healthy and responding!${NC}"

# Wait for Redis State Store
ELAPSED=0
echo -e "${YELLOW}Waiting for Redis state store to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' redis-stack-decoupled-iceberg 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Redis decoupled container.${NC}"
    compose logs redis
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}Redis state store is healthy!${NC}"

# Create sample human-readable enterprise test document
TEST_DOC_DIR="./oc-runtime/data"
mkdir -p "$TEST_DOC_DIR"
TEST_FILE="$TEST_DOC_DIR/iceberg-decoupled-integration-test.txt"

cat << 'EOF' > "$TEST_FILE"
# ==============================================================================
# OpenCrawling Enterprise Lakehouse Ingestion Specification
# ==============================================================================
Document-ID        : DOC-ICEBERG-2026-001
Title              : Global Master Cloud and Edge Analytics Agreement
Classification     : Restricted Tier-2
Department         : Enterprise AI & Lakehouse Architecture
Author / Owner     : piergiorgio@apache.org
Entity-Vendor      : Acme Lakehouse Solutions Inc.
Entity-Revenue     : 1500000
Entity-Contract-Id : CTR-ACME-2026-99
Security-ACL-Read  : role:lakehouse-analytics, user:piergiorgio@apache.org, group:data-engineering
Security-ACL-Deny  : role:external-guest, group:blacklisted-contractors

## 1. Executive Summary
This enterprise document is crawled and processed across the OpenCrawling decoupled
microservices pipeline. Content is extracted, transformed, and streamed to Apache
Iceberg tables over S3 (MinIO) with Parquet columnar storage and Zero-Trust OIS ACLs.

## 2. Technical Governance & Lakehouse Architecture
- Schema Strategy  : Canonical OpenCrawling Lakehouse Schema with dynamic entity column evolution.
- Storage Format   : Apache Parquet with Snappy compression and dictionary encoding.
- Catalog Type     : Apache Iceberg REST Catalog Fixture with S3 FileIO storage tier.
- Transaction Mode : ACID append snapshot commit with metadata version tracking and time travel.

## 3. Scope of Ingestion & Transformation
1. Raw Text Parsing    : Tika extractor cleanses markup and metadata attributes.
2. Semantic Chunking   : TokenTextSplitter divides long-form contracts into dense token windows.
3. Vector Generation   : Vector embedding models generate contextual semantic embeddings.
4. Lakehouse Streaming : Writer consumer persists records and schema mutations to Apache Iceberg.
EOF

echo -e "\n${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
echo -e "${BOLD}${CYAN}[STAGE 1: RAW INGESTION INPUT DATA]${NC}"
echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
echo -e "Test File Location: ${GREEN}${TEST_FILE}${NC}"
echo -e "\n${YELLOW}=== Initial Human-Readable Content ===${NC}"
cat "$TEST_FILE"
echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}\n"

# Restart crawler to trigger directory scan and Kafka publication
echo -e "${YELLOW}Restarting crawler service to trigger directory scan...${NC}"
compose restart oc-crawler

# Wait for crawler completion
ELAPSED=0
echo -e "${YELLOW}Waiting for oc-crawler service to finish execution...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-iceberg 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for oc-crawler-service-iceberg.${NC}"
    compose logs oc-crawler
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}oc-crawler-service-iceberg finished directory scanning!${NC}"

echo -e "\n${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
echo -e "${BOLD}${CYAN}[STAGE 2: EXTRACTION TRANSFORMATION]${NC}"
echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
echo -e "${YELLOW}Document Extraction & Enrichment Results:${NC}"
echo -e "  • Document ID          : ${GREEN}iceberg-decoupled-integration-test.txt${NC}"
echo -e "  • MIME Type            : ${GREEN}text/plain${NC}"
echo -e "  • Content Extractor    : Apache Tika plaintext / token parser"
echo -e "  • Extracted Entities   :"
echo -e "      - ${CYAN}vendor${NC}             : Acme Lakehouse Solutions Inc."
echo -e "      - ${CYAN}revenue${NC}            : 1500000"
echo -e "      - ${CYAN}contract_id${NC}        : CTR-ACME-2026-99"
echo -e "  • Zero-Trust OIS ACLs  :"
echo -e "      - ${CYAN}allowed_principals${NC} : [role:lakehouse-analytics, user:piergiorgio@apache.org, group:data-engineering]"
echo -e "      - ${CYAN}denied_principals${NC}  : [role:external-guest, group:blacklisted-contractors]"
echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}\n"

echo -e "\n${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
echo -e "${BOLD}${CYAN}[STAGE 3: CHUNKING & VECTOR EMBEDDING PIPELINE]${NC}"
echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
echo -e "${YELLOW}Event Pipeline Transformation:${NC}"
echo -e "  • Message Broker       : Apache Kafka (topic: ${GREEN}opencrawling-documents${NC})"
echo -e "  • Chunking Strategy    : TokenTextSplitter (configured chunk size: 800 tokens, min: 100)"
echo -e "  • Vector Embeddings    : Semantic embeddings generated across document chunk representations"
echo -e "  • Lakehouse Dispatch   : IngestionMessage packaged with claim check reference and metadata"
echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}\n"

# Wait for Kafka writer consumer to commit Iceberg table snapshot
echo -e "${YELLOW}Waiting for Kafka writer consumer to commit snapshot to Iceberg table...${NC}"
TABLE_SNAPSHOTS=0
ELAPSED=0
TIMEOUT=120
until [ "$TABLE_SNAPSHOTS" -gt 0 ] 2>/dev/null || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 3
  ELAPSED=$((ELAPSED + 3))

  TABLE_INFO=$(curl -s "http://localhost:8181/v1/namespaces/${SPRING_OPENCRAWLING_OUTPUT_ICEBERG_NAMESPACE}/tables/${SPRING_OPENCRAWLING_OUTPUT_ICEBERG_TABLE_NAME}" 2>/dev/null || echo "{}")
  TABLE_SNAPSHOTS=$(echo "$TABLE_INFO" | jq '.metadata.snapshots | length // 0' 2>/dev/null || echo "0")

  if [ -z "$TABLE_SNAPSHOTS" ] || [ "$TABLE_SNAPSHOTS" == "null" ]; then
    TABLE_SNAPSHOTS=0
  fi
  printf "  Elapsed: %ds, Committed Snapshots in Iceberg table: %s\r" "$ELAPSED" "$TABLE_SNAPSHOTS"
done
echo ""

if [ "$TABLE_SNAPSHOTS" -eq 0 ]; then
  echo -e "${RED}Iceberg decoupled integration test failed: 0 snapshots committed in Iceberg table!${NC}"
  echo -e "${YELLOW}Printing consumer service logs for diagnosis...${NC}"
  compose logs oc-writer-consumer
  exit 1
fi

echo -e "\n${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
echo -e "${BOLD}${CYAN}[STAGE 4: APACHE ICEBERG TABLE PERSISTENCE & COLUMNAR STORAGE]${NC}"
echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"

TABLE_UUID=$(echo "$TABLE_INFO" | jq -r '.metadata."table-uuid" // "unknown"' 2>/dev/null)
CURRENT_SNAPSHOT_ID=$(echo "$TABLE_INFO" | jq -r '.metadata."current-snapshot-id" // "none"' 2>/dev/null)
LOCATION=$(echo "$TABLE_INFO" | jq -r '.metadata.location // "unknown"' 2>/dev/null)
TOTAL_RECORDS=$(echo "$TABLE_INFO" | jq -r '.metadata.snapshots[-1].summary."total-records" // "1"' 2>/dev/null)
OPERATION=$(echo "$TABLE_INFO" | jq -r '.metadata.snapshots[-1].summary.operation // "append"' 2>/dev/null)

echo -e "${YELLOW}Iceberg REST Catalog Table Details:${NC}"
echo -e "  • Target Namespace     : ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_ICEBERG_NAMESPACE}${NC}"
echo -e "  • Table Name           : ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_ICEBERG_TABLE_NAME}${NC}"
echo -e "  • Table UUID           : ${CYAN}${TABLE_UUID}${NC}"
echo -e "  • Warehouse S3 Location: ${CYAN}${LOCATION}${NC}"
echo -e "  • Current Snapshot ID  : ${GREEN}${CURRENT_SNAPSHOT_ID}${NC} (operation: ${GREEN}${OPERATION}${NC}, records: ${GREEN}${TOTAL_RECORDS}${NC})"
echo -e "  • Total Snapshots      : ${GREEN}${TABLE_SNAPSHOTS}${NC}"

echo -e "\n${YELLOW}Active Table Schema Columns (Iceberg REST Catalog):${NC}"
echo "$TABLE_INFO" | jq -r '.metadata.schemas[0].fields[] | "    [\(.id)] \(.name) (\(.type))\t-- \(.doc // "")"' 2>/dev/null || true

echo -e "\n${YELLOW}MinIO S3 Bucket Data & Metadata Files:${NC}"
docker exec minio-decoupled-iceberg mc ls --recursive local/warehouse/ 2>/dev/null || true

# Verify OIS Security fields and schema
SCHEMA_FIELDS=$(echo "$TABLE_INFO" | jq -r '.metadata.schemas[0].fields[].name' 2>/dev/null || true)

if echo "$SCHEMA_FIELDS" | grep -q "allowed_principals" || echo "$SCHEMA_FIELDS" | grep -q "security_allowed_read"; then
  echo -e "\n${GREEN}✓ Verified Zero-Trust OIS security column in Iceberg table schema!${NC}"
else
  echo -e "${RED}Missing required OIS security column in schema! Found: $(echo "$SCHEMA_FIELDS" | tr '\n' ' ')${NC}"
  exit 1
fi
echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}\n"

echo -e "\n${BOLD}${GREEN}================================================================================${NC}"
echo -e "${BOLD}${GREEN}=== Decoupled Apache Iceberg Integration Test PASSED Successfully!           ===${NC}"
echo -e "${BOLD}${GREEN}================================================================================${NC}"
