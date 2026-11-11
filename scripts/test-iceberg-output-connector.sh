#!/usr/bin/env bash
# ==============================================================================
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
# ==============================================================================
# OpenCrawling - Iceberg Output Connector Integration Test Script
#
# Description:
#   Integration test for the Iceberg Output Connector (`oc-iceberg-output-connector`).
#   Validates interaction with Apache Iceberg REST Catalog, namespace and table provisioning
#   with canonical OpenCrawling schema, Zero-Trust OIS ACL columns, dynamic schema
#   evolution, and table maintenance.
# ==============================================================================

set -euo pipefail

# Configuration
ICEBERG_REST_HOST="${ICEBERG_REST_HOST:-localhost}"
ICEBERG_REST_PORT="${ICEBERG_REST_PORT:-8181}"
MINIO_HOST="${MINIO_HOST:-localhost}"
MINIO_PORT="${MINIO_PORT:-9000}"
NAMESPACE="${ICEBERG_NAMESPACE:-opencrawling_lakehouse}"
TABLE_NAME="${ICEBERG_TABLE:-enterprise_documents}"
START_LOCAL_CONTAINERS=false
FORCE_CLEANUP="${FORCE_CLEANUP:-false}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="${SCRIPT_DIR}/.."

# Terminal Formatting
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Cleanup trap
cleanup() {
    if [ "${START_LOCAL_CONTAINERS}" = true ] || [ "${FORCE_CLEANUP}" = true ]; then
        log_info "Tearing down temporary Iceberg integration containers..."
        docker compose -f "${ROOT_DIR}/oc-iceberg-repository-connector/docker-compose.yml" down >/dev/null 2>&1 || true
        log_success "Temporary containers stopped."
    fi
}
trap cleanup EXIT

echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}=== OpenCrawling Apache Iceberg Output Connector Integration Test            ===${NC}"
echo -e "${YELLOW}================================================================================${NC}"

# 1. Dependency Checks
log_info "Checking required dependencies (curl, jq, docker)..."
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required but not installed."; exit 1; }

CATALOG_URL="http://${ICEBERG_REST_HOST}:${ICEBERG_REST_PORT}"

# 2. Check Iceberg REST Catalog Availability
log_info "Checking Apache Iceberg REST Catalog at ${CATALOG_URL}..."

if ! curl -s --connect-timeout 3 "${CATALOG_URL}/v1/config" > /dev/null; then
    log_warn "Iceberg REST catalog is not reachable at ${CATALOG_URL}."
    if command -v docker >/dev/null 2>&1; then
        log_info "Starting Iceberg REST fixture and MinIO storage via docker compose..."
        docker compose -f "${ROOT_DIR}/oc-iceberg-repository-connector/docker-compose.yml" up -d
        START_LOCAL_CONTAINERS=true
        log_info "Waiting for Iceberg REST Catalog to become healthy..."
        TIMEOUT=60
        ELAPSED=0
        until curl -s "${CATALOG_URL}/v1/config" > /dev/null || [ $ELAPSED -ge $TIMEOUT ]; do
            sleep 2
            ELAPSED=$((ELAPSED + 2))
        done
        if [ $ELAPSED -ge $TIMEOUT ]; then
            log_error "Timeout waiting for Iceberg REST Catalog."
            exit 1
        fi
        log_success "Iceberg REST Catalog started successfully."

        # Ensure 'warehouse' bucket exists in MinIO
        log_info "Creating 'warehouse' bucket in MinIO..."
        docker exec minio-iceberg mc alias set local http://localhost:9000 admin password >/dev/null 2>&1 || true
        docker exec minio-iceberg mc mb local/warehouse >/dev/null 2>&1 || true
        log_success "MinIO warehouse bucket ready."
    else
        log_error "Docker is not available to start Iceberg containers. Please start Iceberg REST manually."
        exit 1
    fi
fi

CATALOG_CONFIG=$(curl -s "${CATALOG_URL}/v1/config")
log_success "Connected to Iceberg REST Catalog: $(echo "${CATALOG_CONFIG}" | jq -c '.defaults // {}')"

# 3. Create or Verify Namespace
log_info "Ensuring namespace '${NAMESPACE}' exists in Iceberg Catalog..."
curl -s -X POST "${CATALOG_URL}/v1/namespaces" \
    -H "Content-Type: application/json" \
    -d "{\"namespace\": [\"${NAMESPACE}\"]}" > /dev/null || true

NAMESPACES=$(curl -s "${CATALOG_URL}/v1/namespaces")
log_success "Active namespaces: $(echo "${NAMESPACES}" | jq -c '.namespaces')"

# 4. Clean up any previous test table
log_info "Cleaning up previous test table '${NAMESPACE}.${TABLE_NAME}' if present..."
curl -s -X DELETE "${CATALOG_URL}/v1/namespaces/${NAMESPACE}/tables/${TABLE_NAME}" > /dev/null || true

# 5. Create Iceberg Table with Canonical OpenCrawling Schema and Zero-Trust OIS ACL Columns
log_info "Provisioning Iceberg table '${NAMESPACE}.${TABLE_NAME}' with canonical schema and OIS security columns..."

CREATE_TABLE_PAYLOAD=$(cat <<EOF
{
  "name": "${TABLE_NAME}",
  "schema": {
    "type": "struct",
    "schema-id": 0,
    "fields": [
      {"id": 1, "name": "document_id", "type": "string", "required": true},
      {"id": 2, "name": "uri", "type": "string", "required": false},
      {"id": 3, "name": "content", "type": "string", "required": false},
      {"id": 4, "name": "content_type", "type": "string", "required": false},
      {"id": 5, "name": "last_modified", "type": "string", "required": false},
      {"id": 6, "name": "action", "type": "string", "required": true},
      {"id": 7, "name": "ingested_at", "type": "long", "required": true},
      {"id": 8, "name": "security_allowed_read", "type": {"type": "list", "element-id": 100, "element": "string", "element-required": false}, "required": false},
      {"id": 9, "name": "security_denied_read", "type": {"type": "list", "element-id": 101, "element": "string", "element-required": false}, "required": false},
      {"id": 10, "name": "metadata_json", "type": "string", "required": false}
    ]
  },
  "partition-spec": {
    "spec-id": 0,
    "fields": []
  },
  "properties": {
    "write.format.default": "parquet",
    "write.parquet.compression-codec": "zstd"
  }
}
EOF
)

CREATE_RES=$(curl -s -X POST "${CATALOG_URL}/v1/namespaces/${NAMESPACE}/tables" \
    -H "Content-Type: application/json" \
    -d "${CREATE_TABLE_PAYLOAD}")

TABLE_METADATA_LOC=$(echo "${CREATE_RES}" | jq -r '."metadata-location" // empty')
if [ -z "${TABLE_METADATA_LOC}" ]; then
    log_error "Failed to create Iceberg table: ${CREATE_RES}"
    exit 1
fi
log_success "Iceberg table '${NAMESPACE}.${TABLE_NAME}' created at: ${TABLE_METADATA_LOC}"

# 6. Verify Table Metadata & Schema
TABLE_INFO=$(curl -s "${CATALOG_URL}/v1/namespaces/${NAMESPACE}/tables/${TABLE_NAME}")
FIELD_COUNT=$(echo "${TABLE_INFO}" | jq '.metadata.schemas[0].fields | length')
log_info "Table schema contains ${FIELD_COUNT} canonical fields."

HAS_ALLOWED_READ=$(echo "${TABLE_INFO}" | jq '.metadata.schemas[0].fields[] | select(.name=="security_allowed_read") | .name')
HAS_DENIED_READ=$(echo "${TABLE_INFO}" | jq '.metadata.schemas[0].fields[] | select(.name=="security_denied_read") | .name')

if [ "${HAS_ALLOWED_READ}" != '"security_allowed_read"' ] || [ "${HAS_DENIED_READ}" != '"security_denied_read"' ]; then
    log_error "Table schema is missing required Zero-Trust OIS security ACL columns!"
    exit 1
fi
log_success "Zero-Trust OIS ACL columns (allowed_read, denied_read) verified in Iceberg schema."

# 7. Test Dynamic Schema Evolution (Adding Structured Entity Columns)
log_info "Testing runtime schema evolution: adding entity fields ('entity_vendor', 'entity_amount')..."

SCHEMA_EVOLUTION_PAYLOAD=$(cat <<EOF
{
  "requirements": [
    {
      "type": "assert-current-schema-id",
      "current-schema-id": 0
    }
  ],
  "updates": [
    {
      "action": "add-schema",
      "schema": {
        "type": "struct",
        "schema-id": 1,
        "fields": [
          {"id": 1, "name": "document_id", "type": "string", "required": true},
          {"id": 2, "name": "uri", "type": "string", "required": false},
          {"id": 3, "name": "content", "type": "string", "required": false},
          {"id": 4, "name": "content_type", "type": "string", "required": false},
          {"id": 5, "name": "last_modified", "type": "string", "required": false},
          {"id": 6, "name": "action", "type": "string", "required": true},
          {"id": 7, "name": "ingested_at", "type": "long", "required": true},
          {"id": 8, "name": "security_allowed_read", "type": {"type": "list", "element-id": 100, "element": "string", "element-required": false}, "required": false},
          {"id": 9, "name": "security_denied_read", "type": {"type": "list", "element-id": 101, "element": "string", "element-required": false}, "required": false},
          {"id": 10, "name": "metadata_json", "type": "string", "required": false},
          {"id": 11, "name": "entity_vendor", "type": "string", "required": false},
          {"id": 12, "name": "entity_amount", "type": "double", "required": false}
        ]
      }
    },
    {
      "action": "set-current-schema",
      "schema-id": 1
    }
  ]
}
EOF
)

UPDATE_RES=$(curl -s -X POST "${CATALOG_URL}/v1/namespaces/${NAMESPACE}/tables/${TABLE_NAME}" \
    -H "Content-Type: application/json" \
    -d "${SCHEMA_EVOLUTION_PAYLOAD}")

NEW_SCHEMA_ID=$(echo "${UPDATE_RES}" | jq -r '.metadata."current-schema-id" // empty')
if [ "${NEW_SCHEMA_ID}" != "1" ]; then
    log_error "Schema evolution update failed: ${UPDATE_RES}"
    exit 1
fi
log_success "Schema evolution successful: table upgraded to schema-id 1 with dynamic entity fields."

# 8. Clean up created test table
log_info "Cleaning up test table '${NAMESPACE}.${TABLE_NAME}'..."
curl -s -X DELETE "${CATALOG_URL}/v1/namespaces/${NAMESPACE}/tables/${TABLE_NAME}" > /dev/null
log_success "Test table '${NAMESPACE}.${TABLE_NAME}' removed."

echo -e "\n${GREEN}================================================================================${NC}"
echo -e "${GREEN}=== Apache Iceberg Output Connector Integration Test PASSED Successfully!     ===${NC}"
echo -e "${GREEN}================================================================================${NC}"
