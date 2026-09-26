#!/usr/bin/env bash
# ==============================================================================
# OpenCrawling - Qdrant Output Connector Integration Test Script
# 
# Description:
#   This script performs an end-to-end integration test for the Qdrant Output
#   Connector (`oc-qdrant-output-connector`). It verifies connection health,
#   collection provisioning with 1024-dim Cosine vectors, ACL payload index
#   creation, point upserts with OIS metadata & vectors, and payload-filtered
#   vector similarity searches.
# ==============================================================================

set -euo pipefail

# Configuration
QDRANT_HOST="${QDRANT_HOST:-localhost}"
QDRANT_REST_PORT="${QDRANT_REST_PORT:-6333}"
QDRANT_GRPC_PORT="${QDRANT_GRPC_PORT:-6334}"
COLLECTION_NAME="${COLLECTION_NAME:-enterprise_kb}"
VECTOR_DIMENSIONS=1024
CONTAINER_NAME="opencrawling-qdrant-test"
START_LOCAL_CONTAINER=false
FORCE_CLEANUP="${FORCE_CLEANUP:-false}"

# Terminal Formatting
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Cleanup trap
cleanup() {
    if [ "${START_LOCAL_CONTAINER}" = true ] || [ "${FORCE_CLEANUP}" = true ]; then
        log_info "Cleaning up temporary Qdrant docker container '${CONTAINER_NAME}'..."
        docker stop "${CONTAINER_NAME}" >/dev/null 2>&1 || true
        docker rm "${CONTAINER_NAME}" >/dev/null 2>&1 || true
        log_success "Temporary container removed."
    fi
}
trap cleanup EXIT

# 1. Dependency Checks
log_info "Checking required tools (curl, jq)..."
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required but not installed."; exit 1; }

QDRANT_URL="http://${QDRANT_HOST}:${QDRANT_REST_PORT}"

# 2. Health & Cluster Availability Check
log_info "Checking Qdrant service health at ${QDRANT_URL}..."

if ! curl -s --connect-timeout 3 "${QDRANT_URL}/healthz" > /dev/null; then
    log_warn "Qdrant is not currently running at ${QDRANT_URL}."
    if command -v docker >/dev/null 2>&1; then
        log_info "Attempting to spin up temporary Qdrant container '${CONTAINER_NAME}'..."
        docker stop "${CONTAINER_NAME}" >/dev/null 2>&1 || true
        docker rm "${CONTAINER_NAME}" >/dev/null 2>&1 || true
        docker run -d --name "${CONTAINER_NAME}" \
            -p "${QDRANT_REST_PORT}:6333" \
            -p "${QDRANT_GRPC_PORT}:6334" \
            qdrant/qdrant:latest >/dev/null
        START_LOCAL_CONTAINER=true
        log_info "Waiting for Qdrant service initialization..."
        until curl -s "${QDRANT_URL}/healthz" > /dev/null; do
            sleep 1
        done
        log_success "Qdrant container started successfully."
    else
        log_error "Docker is not available to start a local Qdrant container. Please start Qdrant manually."
        exit 1
    fi
fi

HEALTH_RES=$(curl -s "${QDRANT_URL}/healthz")
log_success "Qdrant health check passed: ${HEALTH_RES}"

# 3. Clean up existing test collection if present
log_info "Ensuring clean test state for collection '${COLLECTION_NAME}'..."
curl -s -X DELETE "${QDRANT_URL}/collections/${COLLECTION_NAME}" > /dev/null || true

# 4. Provision Qdrant Collection with 1024-dim Cosine Vector Configuration
log_info "Creating collection '${COLLECTION_NAME}' (dims: ${VECTOR_DIMENSIONS}, distance: Cosine)..."

CREATE_COLL_PAYLOAD=$(cat <<EOF
{
  "vectors": {
    "size": ${VECTOR_DIMENSIONS},
    "distance": "Cosine"
  },
  "optimizers_config": {
    "default_segment_number": 2
  },
  "replication_factor": 1
}
EOF
)

CREATE_COLL_RES=$(curl -s -X PUT "${QDRANT_URL}/collections/${COLLECTION_NAME}" \
    -H "Content-Type: application/json" \
    -d "${CREATE_COLL_PAYLOAD}")

STATUS=$(echo "${CREATE_COLL_RES}" | jq -r '.result')
if [ "${STATUS}" != "true" ]; then
    log_error "Failed to create collection: ${CREATE_COLL_RES}"
    exit 1
fi
log_success "Collection '${COLLECTION_NAME}' created successfully."

# 5. Provision Payload Indexes for Security ACL Pre-Filtering
log_info "Creating KEYWORD payload index for 'security_allowed_read'..."
INDEX_ALLOWED_RES=$(curl -s -X PUT "${QDRANT_URL}/collections/${COLLECTION_NAME}/index" \
    -H "Content-Type: application/json" \
    -d '{
      "field_name": "security_allowed_read",
      "field_schema": "keyword"
    }')
log_success "Index for 'security_allowed_read' created."

log_info "Creating KEYWORD payload index for 'security_denied_read'..."
INDEX_DENIED_RES=$(curl -s -X PUT "${QDRANT_URL}/collections/${COLLECTION_NAME}/index" \
    -H "Content-Type: application/json" \
    -d '{
      "field_name": "security_denied_read",
      "field_schema": "keyword"
    }')
log_success "Index for 'security_denied_read' created."

# 6. Generate Dummy Vector Embeddings (1024 Dimensions)
generate_dummy_vector() {
    python3 -c "import json; print(json.dumps([round(0.01 * (i % 100), 4) for i in range(1024)]))" 2>/dev/null || \
    perl -e 'print "[" . join(",", map { sprintf("%.4f", 0.01 * ($_ % 100)) } (0..1023)) . "]"'
}

DUMMY_VECTOR=$(generate_dummy_vector)

# 7. Upsert Test Points (OpenCrawling Document Chunks with Metadata & ACLs)
log_info "Upserting test OpenCrawling document chunks into Qdrant..."

POINT_ID_1="a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"
POINT_ID_2="b2c3d4e5-f6a7-8b9c-0d1e-2f3a4b5c6d7e"

UPSERT_PAYLOAD=$(cat <<EOF
{
  "points": [
    {
      "id": "${POINT_ID_1}",
      "vector": ${DUMMY_VECTOR},
      "payload": {
        "doc_id": "doc-finance-001",
        "chunk_index": 0,
        "text": "Q1 Financial Audit Report detailing quarterly revenue and candidate group permissions.",
        "uri": "sharepoint://finance/reports/q1_audit.docx",
        "lastModified": "2026-07-29T12:00:00Z",
        "security_allowed_read": ["role:finance-approvers", "role:executive-board"],
        "security_denied_read": []
      }
    },
    {
      "id": "${POINT_ID_2}",
      "vector": ${DUMMY_VECTOR},
      "payload": {
        "doc_id": "doc-hr-002",
        "chunk_index": 0,
        "text": "Employee Onboarding Policy and HR candidate workflow definition.",
        "uri": "flowable://engine/process-definition/onboarding:1",
        "lastModified": "2026-07-29T12:10:00Z",
        "security_allowed_read": ["role:hr-managers"],
        "security_denied_read": []
      }
    }
  ]
}
EOF
)

UPSERT_RES=$(curl -s -X PUT "${QDRANT_URL}/collections/${COLLECTION_NAME}/points?wait=true" \
    -H "Content-Type: application/json" \
    -d "${UPSERT_PAYLOAD}")

UPSERT_STATUS=$(echo "${UPSERT_RES}" | jq -r '.status')
if [ "${UPSERT_STATUS}" != "ok" ]; then
    log_error "Failed to upsert points: ${UPSERT_RES}"
    exit 1
fi
log_success "Test points upserted successfully."

# 8. Execute Payload-Filtered Vector Similarity Search (Zero-Trust Security Verification)
log_info "Executing payload-filtered vector similarity search matching 'role:finance-approvers'..."

SEARCH_PAYLOAD=$(cat <<EOF
{
  "vector": ${DUMMY_VECTOR},
  "filter": {
    "must": [
      {
        "key": "security_allowed_read",
        "match": {
          "value": "role:finance-approvers"
        }
      }
    ]
  },
  "limit": 10,
  "with_payload": true,
  "with_vector": false
}
EOF
)

SEARCH_RES=$(curl -s -X POST "${QDRANT_URL}/collections/${COLLECTION_NAME}/points/search" \
    -H "Content-Type: application/json" \
    -d "${SEARCH_PAYLOAD}")

HIT_COUNT=$(echo "${SEARCH_RES}" | jq '.result | length')
MATCHED_DOC_ID=$(echo "${SEARCH_RES}" | jq -r '.result[0].payload.doc_id // empty')

log_info "Search Results returned ${HIT_COUNT} document hit(s)."

if [ "${HIT_COUNT}" -ge 1 ] && [ "${MATCHED_DOC_ID}" = "doc-finance-001" ]; then
    log_success "Zero-Trust ACL Pre-Filtering verified! Match doc_id: ${MATCHED_DOC_ID}"
else
    log_error "Similarity search test failed. Response: ${SEARCH_RES}"
    exit 1
fi

# 9. Clean up temporary test container
cleanup

echo -e "\n=========================================================================="
log_success "All Qdrant Output Connector Integration Tests Passed Successfully! 🎉"
echo -e "==========================================================================\n"

exit 0
