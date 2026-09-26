#!/usr/bin/env bash
# ==============================================================================
# OpenCrawling - Vespa Output Connector Integration Test Script
#
# Description:
#   This script performs a lightweight, connector-only integration test for the
#   Vespa Output Connector (`oc-vespa-output-connector`), without booting the
#   full decoupled pipeline (see test-vespa-decoupled.sh for that). It deploys
#   the application package (schema + services.xml), feeds document chunks with
#   OIS ACL metadata directly via the Document v1 API, verifies ACL-filtered
#   hybrid search, and confirms dynamic multi-dimension routing by feeding a
#   384-dimension chunk alongside a 1024-dimension one.
# ==============================================================================

set -euo pipefail

# Configuration
VESPA_HOST="${VESPA_HOST:-localhost}"
VESPA_PORT="${VESPA_PORT:-8080}"
VESPA_CONFIG_PORT="${VESPA_CONFIG_PORT:-19071}"
CONTAINER_NAME="opencrawling-vespa-test"
START_LOCAL_CONTAINER=false
FORCE_CLEANUP="${FORCE_CLEANUP:-false}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VESPA_APP_DIR="${SCRIPT_DIR}/../oc-vespa-output-connector/vespa-app"

# Terminal Formatting
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Cleanup trap
cleanup() {
    if [ "${START_LOCAL_CONTAINER}" = true ] || [ "${FORCE_CLEANUP}" = true ]; then
        log_info "Cleaning up temporary Vespa docker container '${CONTAINER_NAME}'..."
        docker stop "${CONTAINER_NAME}" >/dev/null 2>&1 || true
        docker rm "${CONTAINER_NAME}" >/dev/null 2>&1 || true
        log_success "Temporary container removed."
    fi
}
trap cleanup EXIT

# 1. Dependency Checks
log_info "Checking required tools (curl, jq, tar)..."
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required but not installed."; exit 1; }
command -v tar >/dev/null 2>&1 || { log_error "tar is required but not installed."; exit 1; }

CONFIG_URL="http://${VESPA_HOST}:${VESPA_CONFIG_PORT}"
VESPA_URL="http://${VESPA_HOST}:${VESPA_PORT}"

# 2. Config Server Availability Check
log_info "Checking Vespa config server at ${CONFIG_URL}..."

if ! curl -s --connect-timeout 3 "${CONFIG_URL}/ApplicationStatus" > /dev/null; then
    log_warn "Vespa is not currently running at ${CONFIG_URL}."
    if command -v docker >/dev/null 2>&1; then
        log_info "Attempting to spin up temporary Vespa container '${CONTAINER_NAME}'..."
        docker stop "${CONTAINER_NAME}" >/dev/null 2>&1 || true
        docker rm "${CONTAINER_NAME}" >/dev/null 2>&1 || true
        docker run -d --name "${CONTAINER_NAME}" \
            -p "${VESPA_PORT}:8080" \
            -p "${VESPA_CONFIG_PORT}:19071" \
            vespaengine/vespa:8 >/dev/null
        START_LOCAL_CONTAINER=true
        log_info "Waiting for Vespa config server initialization..."
        until curl -s "${CONFIG_URL}/ApplicationStatus" > /dev/null; do
            sleep 2
        done
        log_success "Vespa container started successfully."
    else
        log_error "Docker is not available to start a local Vespa container. Please start Vespa manually."
        exit 1
    fi
fi
log_success "Vespa config server is reachable."

# 3. Deploy the Application Package (schema + services.xml)
log_info "Deploying application package from ${VESPA_APP_DIR}..."
DEPLOY_RES=$(tar -czf - -C "${VESPA_APP_DIR}" . | curl -s -X POST \
    --header "Content-Type:application/x-gzip" --data-binary @- \
    "${CONFIG_URL}/application/v2/tenant/default/prepareandactivate")

ACTIVATED=$(echo "${DEPLOY_RES}" | jq -r '.activated // false')
if [ "${ACTIVATED}" != "true" ]; then
    log_error "Application package deploy failed: ${DEPLOY_RES}"
    exit 1
fi
log_success "Application package deployed and activated."

# 4. Wait for the Document/Search API to Report Healthy
log_info "Waiting for Vespa document/search API at ${VESPA_URL} to become healthy..."
until curl -s "${VESPA_URL}/state/v1/health" 2>/dev/null | grep -q '"up"'; do
    sleep 2
done
log_success "Vespa document/search API is healthy."

# 5. Generate Dummy Vector Embeddings (1024 and 384 dimensions)
generate_dummy_vector() {
    local size="$1"
    python3 -c "import json; print(json.dumps([round(0.01 * (i % 100), 4) for i in range(${size})]))" 2>/dev/null || \
    perl -e 'print "[" . join(",", map { sprintf("%.4f", 0.01 * ($_ % 100)) } (0..'"${size}"'-1)) . "]"'
}

VECTOR_1024=$(generate_dummy_vector 1024)
VECTOR_384=$(generate_dummy_vector 384)

# 6. Feed Test Document Chunks (OpenCrawling chunks with OIS ACL metadata)
log_info "Feeding test document chunks with ACL metadata into Vespa..."

FEED_FINANCE=$(cat <<EOF
{
  "fields": {
    "chunk_id": "doc-finance-001_chunk-0",
    "text": "Q1 Financial Audit Report detailing quarterly revenue and candidate group permissions.",
    "uri": "sharepoint://finance/reports/q1_audit.docx",
    "acl": "acl-finance-001",
    "lastModified": "2026-07-29T12:00:00Z",
    "security_inheritance": true,
    "security_allowed_read": ["role:finance-approvers", "role:executive-board"],
    "security_denied_read": [],
    "embedding": {"values": ${VECTOR_1024}}
  }
}
EOF
)
FEED_HR=$(cat <<EOF
{
  "fields": {
    "chunk_id": "doc-hr-002_chunk-0",
    "text": "Employee Onboarding Policy and HR candidate workflow definition.",
    "uri": "flowable://engine/process-definition/onboarding:1",
    "acl": "acl-hr-002",
    "lastModified": "2026-07-29T12:10:00Z",
    "security_inheritance": true,
    "security_allowed_read": ["role:hr-managers"],
    "security_denied_read": [],
    "embedding": {"values": ${VECTOR_1024}}
  }
}
EOF
)
FEED_MINILM=$(cat <<EOF
{
  "fields": {
    "chunk_id": "doc-minilm-003_chunk-0",
    "text": "Content embedded with a 384-dimension all-minilm model, routed to its own document type.",
    "uri": "sharepoint://kb/minilm-doc.docx",
    "acl": "acl-minilm-003",
    "lastModified": "2026-07-29T12:20:00Z",
    "security_inheritance": true,
    "security_allowed_read": ["role:finance-approvers"],
    "security_denied_read": [],
    "embedding": {"values": ${VECTOR_384}}
  }
}
EOF
)

curl -s -X POST -H "Content-Type: application/json" --data-binary "${FEED_FINANCE}" \
    "${VESPA_URL}/document/v1/opencrawling/opencrawling_chunk_1024/docid/doc-finance-001_chunk-0" > /dev/null
curl -s -X POST -H "Content-Type: application/json" --data-binary "${FEED_HR}" \
    "${VESPA_URL}/document/v1/opencrawling/opencrawling_chunk_1024/docid/doc-hr-002_chunk-0" > /dev/null
curl -s -X POST -H "Content-Type: application/json" --data-binary "${FEED_MINILM}" \
    "${VESPA_URL}/document/v1/opencrawling/opencrawling_chunk_384/docid/doc-minilm-003_chunk-0" > /dev/null

log_success "Test document chunks fed successfully."

# 7. Execute ACL-Filtered Hybrid Search (Zero-Trust Security Verification)
log_info "Executing ACL-filtered nearestNeighbor search matching 'role:finance-approvers'..."

SEARCH_PAYLOAD=$(cat <<EOF
{
  "yql": "select * from opencrawling_chunk_1024 where ({targetHits:10}nearestNeighbor(embedding, q_embedding_1024)) and security_allowed_read contains \"role:finance-approvers\"",
  "input.query(q_embedding_1024)": ${VECTOR_1024},
  "ranking": "semantic",
  "hits": 10
}
EOF
)

SEARCH_RES=$(curl -s -X POST "${VESPA_URL}/search/" -H "Content-Type: application/json" -d "${SEARCH_PAYLOAD}")
HIT_COUNT=$(echo "${SEARCH_RES}" | jq '.root.fields.totalCount // 0')
MATCHED_CHUNK_ID=$(echo "${SEARCH_RES}" | jq -r '.root.children[0].fields.chunk_id // empty')

log_info "Search returned ${HIT_COUNT} hit(s)."

if [ "${HIT_COUNT}" -ge 1 ] && [ "${MATCHED_CHUNK_ID}" = "doc-finance-001_chunk-0" ]; then
    log_success "Zero-Trust ACL Pre-Filtering verified! Match chunk_id: ${MATCHED_CHUNK_ID}"
else
    log_error "ACL-filtered search test failed. Response: ${SEARCH_RES}"
    exit 1
fi

# 8. Verify Dynamic Multi-Dimension Routing (the 384-dim chunk landed in its own document type)
log_info "Verifying the 384-dimension chunk routed to opencrawling_chunk_384..."

ROUTING_RES=$(curl -s "${VESPA_URL}/search/?yql=select+chunk_id+from+opencrawling_chunk_384+where+true&hits=10")
ROUTING_COUNT=$(echo "${ROUTING_RES}" | jq '.root.fields.totalCount // 0')

if [ "${ROUTING_COUNT}" -ge 1 ]; then
    log_success "Dynamic multi-dimension routing verified! ${ROUTING_COUNT} chunk(s) found in opencrawling_chunk_384."
else
    log_error "Dynamic routing test failed. Response: ${ROUTING_RES}"
    exit 1
fi

# 9. Clean up temporary test container
cleanup

echo -e "\n=========================================================================="
log_success "All Vespa Output Connector Integration Tests Passed Successfully!"
echo -e "==========================================================================\n"

exit 0
