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

# End-to-End Decoupled Integration Test Suite for Apache Iceberg MCP Component (#302):
#   1. Decoupled Lakehouse Stack (MinIO, Iceberg REST Catalog, Kafka, Redis, Consumers, oc-mcp-server)
#   2. MCP Streamable HTTP Transport Handshake & Tool Advertisement (tools/list)
#   3. Multi-Snapshot Ingestion + ACL Revocation + DELETE Tombstone via OIS API
#   4. listIcebergSnapshots & Time-Travel getIcebergDocumentAsOf (including negative ACL checks)
#   5. queryIcebergEntities & getIcebergIngestionStats (Zero-Trust ACL & tombstone verification)
#   6. Iceberg Sidecar Store Tools (listSidecarStores, readOisSidecars)
#   7. IcebergMcpSearchBackend Tools (getDocumentContent, listAccessibleSources)

set -e

SKIP_BUILD="${SKIP_BUILD:-false}"
LOCAL_BUILD="${LOCAL_BUILD:-false}"
KEEP_CONTAINERS="${KEEP_CONTAINERS:-false}"

show_help() {
  cat << EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  -s, --skip-build        Skip docker compose build and reuse existing images
  -l, --local, --local-build
                          Use local Dockerfiles with BuildKit ~/.m2 cache mounts
  -k, --keep, --keep-containers
                          Preserve running containers and volumes on exit/failure
  -h, --help              Display this help message and exit

Environment Variables:
  SKIP_BUILD=true         Equivalent to -s, --skip-build
  LOCAL_BUILD=true        Equivalent to -l, --local-build
  KEEP_CONTAINERS=true    Equivalent to -k, --keep-containers
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    -s|--skip-build)
      SKIP_BUILD=true
      shift
      ;;
    -l|--local|--local-build)
      LOCAL_BUILD=true
      shift
      ;;
    -k|--keep|--keep-containers)
      KEEP_CONTAINERS=true
      shift
      ;;
    -h|--help)
      show_help
      exit 0
      ;;
    *)
      shift
      ;;
  esac
done

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

PASSED_COUNT=0
TOTAL_TESTS=10

log_step() {
  echo -e "\n${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
  echo -e "${BOLD}${CYAN}[STEP $1] $2${NC}"
  echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
}

log_pass() {
  PASSED_COUNT=$((PASSED_COUNT + 1))
  echo -e "${BOLD}${GREEN}[PASS $PASSED_COUNT/$TOTAL_TESTS] $1${NC}"
}

log_fail() {
  echo -e "${BOLD}${RED}[FAIL] $1${NC}" >&2
}

log_info() {
  echo -e "${CYAN}[INFO] $1${NC}"
}

for cmd in docker curl jq openssl; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    log_fail "Required command '${cmd}' is not installed."
    exit 1
  fi
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory: $(pwd)"

COMPOSE_FILE="oc-iceberg-output-connector/docker/docker-compose-decoupled-with-iceberg.yml"
LOCAL_OVERLAY="${LOCAL_OVERLAY:-${COMPOSE_FILE%.yml}.local.yml}"

MCP_BASE_URL="${MCP_BASE_URL:-http://localhost:8080}"
MCP_ENDPOINT="${MCP_BASE_URL}/mcp"
OIS_JOB_ID="${OIS_JOB_ID:-1}"
INGEST_ENDPOINT="${MCP_BASE_URL}/api/v1/ingest/ois/${OIS_JOB_ID}"
export OPENCRAWLING_INGEST_OIS_TOKEN="${OPENCRAWLING_INGEST_OIS_TOKEN:-mcp-iceberg-token-$(date +%s)}"
export OPENCRAWLING_SIDECARS_ENABLED="true"
export OPENCRAWLING_SIDECARS_CONNECTORS="Iceberg_Lakehouse"
export OPENCRAWLING_SIDECARS_ALLOW_ASSERTED_IDENTITY="true"
export OPENCRAWLING_MCP_ICEBERG_ENABLED="true"

compose() {
  if [ "${LOCAL_BUILD}" = true ] && [ -f "${LOCAL_OVERLAY}" ]; then
    docker compose -f "${COMPOSE_FILE}" -f "${LOCAL_OVERLAY}" "$@"
  else
    docker compose -f "${COMPOSE_FILE}" "$@"
  fi
}

print_diagnostics() {
  echo -e "\n${YELLOW}--- Container Diagnostics (tail 80 lines) ---${NC}"
  compose logs --tail=80 oc-mcp-server oc-writer-consumer iceberg-rest minio 2>/dev/null || true
}

cleanup() {
  rm -f /tmp/mcp-iceberg-headers-*.txt /tmp/mcp-iceberg-body-*.txt
  if [ "${KEEP_CONTAINERS}" = true ]; then
    log_info "KEEP_CONTAINERS=true: Preserving running containers and volumes."
  else
    log_info "Tearing down decoupled Apache Iceberg MCP test environment..."
    compose down -v --remove-orphans >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
trap 'print_diagnostics' ERR

wait_until() {
  local description="$1"
  local timeout="$2"
  shift 2
  local elapsed=0
  until "$@"; do
    if [ "$elapsed" -ge "$timeout" ]; then
      log_fail "Timeout after ${timeout}s waiting for: ${description}"
      return 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  log_info "Ready: ${description} (${elapsed}s)"
}

ensure_minio_warehouse_bucket() {
  if docker exec minio-decoupled-iceberg sh -c "command -v mc >/dev/null 2>&1" 2>/dev/null; then
    docker exec minio-decoupled-iceberg sh -c \
      "mc alias set local http://127.0.0.1:9000 admin password >/dev/null && mc mb --ignore-existing local/warehouse >/dev/null"
    log_info "Created MinIO bucket 'warehouse' using bundled mc client."
    return 0
  fi

  # Signed S3 PUT /warehouse via AWS Signature V4 (no external mc image pull needed)
  local host="localhost:9000"
  local amz_date date_stamp payload_hash canonical_headers signed_headers canonical_request
  local scope string_to_sign k_date k_region k_service k_signing signature auth_header status
  amz_date=$(date -u +"%Y%m%dT%H%M%SZ")
  date_stamp=$(date -u +"%Y%m%d")
  payload_hash="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  canonical_headers="host:${host}\nx-amz-content-sha256:${payload_hash}\nx-amz-date:${amz_date}\n"
  signed_headers="host;x-amz-content-sha256;x-amz-date"
  canonical_request="PUT\n/warehouse\n\n${canonical_headers}\n${signed_headers}\n${payload_hash}"
  local req_hash
  req_hash=$(printf '%b' "$canonical_request" | openssl dgst -sha256 -hex | awk '{print $NF}')
  scope="${date_stamp}/us-east-1/s3/aws4_request"
  string_to_sign="AWS4-HMAC-SHA256\n${amz_date}\n${scope}\n${req_hash}"

  hmac_hex() {
    printf '%s' "$2" | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$1" | awk '{print $NF}'
  }
  local k_secret_hex
  k_secret_hex=$(printf 'AWS4%s' "password" | xxd -p -c 256)
  k_date=$(hmac_hex "$k_secret_hex" "$date_stamp")
  k_region=$(hmac_hex "$k_date" "us-east-1")
  k_service=$(hmac_hex "$k_region" "s3")
  k_signing=$(hmac_hex "$k_service" "aws4_request")
  signature=$(printf '%b' "$string_to_sign" | openssl dgst -sha256 -mac HMAC -macopt "hexkey:${k_signing}" | awk '{print $NF}')
  auth_header="AWS4-HMAC-SHA256 Credential=admin/${scope}, SignedHeaders=${signed_headers}, Signature=${signature}"

  status=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
    -H "Host: ${host}" \
    -H "x-amz-content-sha256: ${payload_hash}" \
    -H "x-amz-date: ${amz_date}" \
    -H "Authorization: ${auth_header}" \
    "http://${host}/warehouse")
  if [ "$status" != "200" ] && [ "$status" != "409" ]; then
    log_fail "Failed to create MinIO 'warehouse' bucket (HTTP ${status})"
    return 1
  fi
  log_info "Created/verified MinIO bucket 'warehouse' (HTTP ${status})."
}

extract_json_from_response() {
  local raw="$1"
  if printf '%s' "$raw" | grep -q '^data:'; then
    printf '%s\n' "$raw" | sed -n 's/^data: *//p' | grep -E '^\s*\{' | tail -n 1
  else
    printf '%s' "$raw"
  fi
}

mcp_post() {
  local payload="$1"
  local session_id="$2"
  local hdr_file="/tmp/mcp-iceberg-headers-$$.txt"
  local body_file="/tmp/mcp-iceberg-body-$$.txt"
  local http_code

  if [ -n "$session_id" ]; then
    http_code=$(curl -sS --max-time 30 -D "$hdr_file" -o "$body_file" -w "%{http_code}" -X POST \
      -H "Content-Type: application/json" \
      -H "Accept: application/json, text/event-stream" \
      -H "Mcp-Session-Id: ${session_id}" \
      -d "$payload" \
      "${MCP_ENDPOINT}")
  else
    http_code=$(curl -sS --max-time 30 -D "$hdr_file" -o "$body_file" -w "%{http_code}" -X POST \
      -H "Content-Type: application/json" \
      -H "Accept: application/json, text/event-stream" \
      -d "$payload" \
      "${MCP_ENDPOINT}")
  fi

  if [ "$http_code" -lt 200 ] || [ "$http_code" -ge 300 ]; then
    log_fail "MCP HTTP ${http_code} from ${MCP_ENDPOINT}: $(cat "$body_file")"
    return 1
  fi
  extract_json_from_response "$(cat "$body_file")"
}

mcp_call_tool() {
  local req_id="$1"
  local tool_name="$2"
  local args_json="$3"
  local payload
  payload=$(jq -n --argjson id "$req_id" --arg name "$tool_name" --argjson args "$args_json" \
    '{jsonrpc: "2.0", id: $id, method: "tools/call", params: {name: $name, arguments: $args}}')
  mcp_post "$payload" "$SESSION_ID"
}

mcp_tool_text() {
  local resp="$1"
  local is_error
  is_error=$(echo "$resp" | jq -r '.result.isError // false')
  if [ "$is_error" = "true" ] || echo "$resp" | jq -e '.error != null' >/dev/null 2>&1; then
    return 1
  fi
  local txt
  txt=$(echo "$resp" | jq -r '.result.content[0].text // empty')
  if [ -z "$txt" ]; then
    txt=$(echo "$resp" | jq -c '.result')
  fi
  printf '%s' "$txt"
}

ingest_ois_doc() {
  local doc_json="$1"
  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 20 -X POST \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${OPENCRAWLING_INGEST_OIS_TOKEN}" \
    -d "$doc_json" \
    "${INGEST_ENDPOINT}")
  if [ "$status" != "202" ] && [ "$status" != "200" ]; then
    log_fail "OIS ingest failed with HTTP ${status}"
    exit 1
  fi
}

echo -e "${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${YELLOW}=== OpenCrawling Apache Iceberg MCP Component Integration Test (#302)        ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}"

# ------------------------------------------------------------------------------
# STEP 1: Clean Stack, Build (Optional), and Boot Decoupled Iceberg Services
# ------------------------------------------------------------------------------
log_step "1" "Booting MinIO, Redis, Kafka, Iceberg REST, Consumers, and oc-mcp-server..."
compose down -v --remove-orphans >/dev/null 2>&1 || true

if [ "${SKIP_BUILD}" = true ]; then
  log_info "SKIP_BUILD=true: reusing existing container images."
else
  log_info "Building container images (LOCAL_BUILD=${LOCAL_BUILD})..."
  compose build oc-writer-consumer oc-ingestion-consumer oc-mcp-server
fi

compose up -d minio redis kafka
wait_until "MinIO live endpoint" 120 curl -sf "http://localhost:9000/minio/health/live"
ensure_minio_warehouse_bucket

compose up -d iceberg-rest oc-ingestion-consumer oc-writer-consumer oc-mcp-server
wait_until "Iceberg REST catalog" 120 curl -sf "http://localhost:8181/v1/config"
wait_until "oc-mcp-server OIS endpoint (expects 401 unauthenticated)" 240 \
  sh -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X POST -d '{}' '${INGEST_ENDPOINT}')\" = 401 ]"

log_pass "Decoupled Apache Iceberg + MCP stack started and healthy"

# ------------------------------------------------------------------------------
# STEP 2: Register Iceberg_Lakehouse Connector + MCP Handshake & Tool Discovery
# ------------------------------------------------------------------------------
log_step "2" "Registering Iceberg_Lakehouse connector and verifying MCP tools/list..."

CONNECTOR_PAYLOAD=$(cat <<'EOF'
{
  "name": "Iceberg_Lakehouse",
  "description": "Apache Iceberg Lakehouse Output Connector",
  "type": "output",
  "className": "org.opencrawling.iceberg.output.IcebergOutputConnector",
  "maxConnections": 4,
  "configuration": {
    "catalogType": "rest",
    "catalogUri": "http://iceberg-rest:8181",
    "catalogName": "opencrawling_lakehouse",
    "warehouse": "s3://warehouse",
    "namespace": "enterprise_data",
    "tableName": "documents",
    "tableStrategy": "UNIFIED",
    "partitionStrategy": "YEAR_MONTH",
    "ioImpl": "org.apache.iceberg.aws.s3.S3FileIO",
    "s3Endpoint": "http://minio:9000",
    "s3Region": "us-east-1",
    "s3AccessKeyId": "admin",
    "s3SecretAccessKey": "password",
    "s3PathStyleAccess": "true",
    "autoSchemaEvolution": "true"
  }
}
EOF
)

CONN_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 -X POST \
  -H "Content-Type: application/json" \
  -d "$CONNECTOR_PAYLOAD" \
  "${MCP_BASE_URL}/api/connectors")
if [ "$CONN_STATUS" != "201" ] && [ "$CONN_STATUS" != "200" ]; then
  log_fail "Failed to register Iceberg_Lakehouse connector (HTTP ${CONN_STATUS})"
  exit 1
fi

INIT_HDR="/tmp/mcp-iceberg-headers-init.txt"
INIT_BODY="/tmp/mcp-iceberg-body-init.txt"
INIT_CODE=$(curl -sS --max-time 20 -D "$INIT_HDR" -o "$INIT_BODY" -w "%{http_code}" -X POST \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"iceberg-mcp-test","version":"1.0.0"}}}' \
  "${MCP_ENDPOINT}")
if [ "$INIT_CODE" -lt 200 ] || [ "$INIT_CODE" -ge 300 ]; then
  log_fail "MCP initialize failed with HTTP ${INIT_CODE}: $(cat "$INIT_BODY")"
  exit 1
fi

SESSION_ID=$(grep -i '^mcp-session-id:' "$INIT_HDR" | tail -n 1 | awk '{print $2}' | tr -d '\r\n')
if [ -z "$SESSION_ID" ]; then
  log_fail "Missing Mcp-Session-Id header in initialize response."
  exit 1
fi
rm -f "$INIT_HDR" "$INIT_BODY"

mcp_post '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}' "$SESSION_ID" >/dev/null
PING_RESP=$(mcp_post '{"jsonrpc":"2.0","id":2,"method":"ping","params":{}}' "$SESSION_ID")
echo "$PING_RESP" | jq -e '.id == 2 and (.error == null)' >/dev/null || {
  log_fail "MCP ping failed: $PING_RESP"
  exit 1
}

TOOLS_RESP=$(mcp_post '{"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}' "$SESSION_ID")
TOOL_NAMES=$(echo "$TOOLS_RESP" | jq -r '.result.tools[].name')
for required_tool in \
  listIcebergSnapshots \
  getIcebergDocumentAsOf \
  queryIcebergEntities \
  getIcebergIngestionStats \
  listSidecarStores \
  readOisSidecars \
  getDocumentContent \
  listAccessibleSources; do
  if ! printf '%s\n' "$TOOL_NAMES" | grep -qx "$required_tool"; then
    log_fail "Expected MCP tool '${required_tool}' not advertised in tools/list!"
    exit 1
  fi
done
log_pass "MCP handshake succeeded and all 8 Iceberg/Sidecar/Knowledge tools are advertised"

# ------------------------------------------------------------------------------
# STEP 3: Ingest Multi-Snapshot Dataset + ACLTightening + DELETE Tombstone
# ------------------------------------------------------------------------------
log_step "3" "Ingesting multi-snapshot Iceberg dataset via OIS API and Kafka..."

RUN_TS=$(date +%s)
DOC_1="https://docs.acme.internal/contracts/ctr-ice-01-${RUN_TS}.pdf"
DOC_2="https://docs.acme.internal/board/exec-brief-01-${RUN_TS}.pdf"
DOC_3="https://docs.acme.internal/contracts/tmp-contract-01-${RUN_TS}.pdf"

MARKER_V1="MSA-V1-DRAFT-${RUN_TS}"
MARKER_V2="MSA-V2-FINAL-${RUN_TS}"
MARKER_EXEC="BOARD-EXEC-SECRET-${RUN_TS}"
MARKER_DEL="TEMP-CONTRACT-DEL-${RUN_TS}"

wait_for_snapshot_count() {
  local target_count="$1"
  wait_until "Iceberg snapshot count >= ${target_count}" 180 \
    sh -c "resp=\$(curl -sS --max-time 15 -X POST \
      -H 'Content-Type: application/json' \
      -H 'Accept: application/json, text/event-stream' \
      -H 'Mcp-Session-Id: ${SESSION_ID}' \
      -d '{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"tools/call\",\"params\":{\"name\":\"listIcebergSnapshots\",\"arguments\":{\"connector\":\"Iceberg_Lakehouse\",\"userPrincipal\":\"alice\",\"userRoles\":\"finance\"}}}' \
      '${MCP_ENDPOINT}' 2>/dev/null || true); \
      json=\$(printf '%s\n' \"\$resp\" | sed -n 's/^data: *//p' | tail -n 1); \
      [ -z \"\$json\" ] && json=\"\$resp\"; \
      cnt=\$(printf '%s' \"\$json\" | jq -r '(.result.content[0].text // \"\") | fromjson | .totalSnapshots // 0' 2>/dev/null || echo 0); \
      [ \"\$cnt\" -ge \"${target_count}\" ] 2>/dev/null"
}

# Snapshot 1: Doc 1 v1 readable by finance + legal
ingest_ois_doc "$(jq -n \
  --arg id "$DOC_1" \
  --arg text "Master Services Agreement v1 draft terms (${MARKER_V1})" \
  '{
    id: $id,
    action: "UPSERT",
    source: {type: "alfresco", instance: "alfresco://prod"},
    content: {mimeType: "application/pdf", text: $text},
    metadata: {
      title: "Acme MSA v1",
      source_type: "alfresco",
      mimeType: "application/pdf",
      extracted_text: $text,
      "entity.contract_number": "CTR-ICE-01",
      "entity.vendor": "Acme Corp"
    },
    security: {
      inheritanceEnabled: false,
      permissions: [
        {identity: "finance", identityType: "group", access: "read"},
        {identity: "legal", identityType: "group", access: "read"}
      ]
    }
  }')"

wait_for_snapshot_count 1
SNAPS_AFTER_1=$(mcp_tool_text "$(mcp_call_tool 10 listIcebergSnapshots '{"connector":"Iceberg_Lakehouse","userPrincipal":"alice","userRoles":"finance"}')")
SNAP_1_ID=$(echo "$SNAPS_AFTER_1" | jq -r '.currentSnapshotId')
log_info "Snapshot 1 committed: SNAP_1_ID=${SNAP_1_ID}"

# Snapshot 2..3: Doc 1 v2 (ACL tightened to finance ONLY; legal removed) + Doc 2 (executives ONLY)
ingest_ois_doc "$(jq -n \
  --arg id "$DOC_1" \
  --arg text "Master Services Agreement v2 confidential pricing (${MARKER_V2})" \
  '{
    id: $id,
    action: "UPSERT",
    source: {type: "alfresco", instance: "alfresco://prod"},
    content: {mimeType: "application/pdf", text: $text},
    metadata: {
      title: "Acme MSA v2",
      source_type: "alfresco",
      mimeType: "application/pdf",
      extracted_text: $text,
      "entity.contract_number": "CTR-ICE-01",
      "entity.vendor": "Acme Corp Updated"
    },
    security: {
      inheritanceEnabled: false,
      permissions: [
        {identity: "finance", identityType: "group", access: "read"}
      ]
    }
  }')"

ingest_ois_doc "$(jq -n \
  --arg id "$DOC_2" \
  --arg text "Board M&A Executive Brief (${MARKER_EXEC})" \
  '{
    id: $id,
    action: "UPSERT",
    source: {type: "sharepoint", instance: "sharepoint://exec"},
    content: {mimeType: "application/pdf", text: $text},
    metadata: {
      title: "Board M&A Brief",
      source_type: "sharepoint",
      mimeType: "application/pdf",
      extracted_text: $text,
      "entity.vendor": "Beta Corp"
    },
    security: {
      inheritanceEnabled: false,
      permissions: [
        {identity: "executives", identityType: "group", access: "read"}
      ]
    }
  }')"

wait_for_snapshot_count 3
SNAPS_AFTER_2=$(mcp_tool_text "$(mcp_call_tool 11 listIcebergSnapshots '{"connector":"Iceberg_Lakehouse","userPrincipal":"alice","userRoles":"finance"}')")
SNAP_2_ID=$(echo "$SNAPS_AFTER_2" | jq -r '.currentSnapshotId')
log_info "Snapshot 2 batch committed: SNAP_2_ID=${SNAP_2_ID}"

# Snapshot 4..5: Doc 3 UPSERT followed by DELETE tombstone
ingest_ois_doc "$(jq -n \
  --arg id "$DOC_3" \
  --arg text "Temporary Contract Draft (${MARKER_DEL})" \
  '{
    id: $id,
    action: "UPSERT",
    source: {type: "alfresco", instance: "alfresco://prod"},
    content: {mimeType: "application/pdf", text: $text},
    metadata: {
      title: "Temporary Contract",
      source_type: "alfresco",
      mimeType: "application/pdf",
      extracted_text: $text,
      "entity.contract_number": "CTR-DEL-03"
    },
    security: {
      inheritanceEnabled: false,
      permissions: [
        {identity: "finance", identityType: "group", access: "read"}
      ]
    }
  }')"
wait_for_snapshot_count 4

ingest_ois_doc "$(jq -n --arg id "$DOC_3" '{id: $id, action: "DELETE"}')"
wait_for_snapshot_count 5
SNAPS_AFTER_3=$(mcp_tool_text "$(mcp_call_tool 12 listIcebergSnapshots '{"userPrincipal":"alice","userRoles":"finance"}')")
log_info "All 5 Iceberg snapshots committed (latest currentSnapshotId=$(echo "$SNAPS_AFTER_3" | jq -r '.currentSnapshotId'))"
log_pass "Multi-snapshot dataset ingested and committed cleanly to Iceberg REST catalog"

# ------------------------------------------------------------------------------
# STEP 4: Verify listIcebergSnapshots Ordering & Metrics
# ------------------------------------------------------------------------------
log_step "4" "Verifying listIcebergSnapshots newest-first ordering and active fallback..."
echo "$SNAPS_AFTER_3" | jq -e '.totalSnapshots >= 5 and .snapshots[0].snapshotId == .currentSnapshotId and .snapshots[0].operation != null' >/dev/null || {
  log_fail "listIcebergSnapshots validation failed: $SNAPS_AFTER_3"
  exit 1
}
log_pass "listIcebergSnapshots returned ordered snapshot lineage with commit metrics"

# ------------------------------------------------------------------------------
# STEP 5: Verify getIcebergDocumentAsOf Time-Travel + Zero-Trust ACL Enforcement
# ------------------------------------------------------------------------------
log_step "5" "Verifying getIcebergDocumentAsOf time-travel and ACL revocation isolation..."

V1_LEGAL_RAW=$(mcp_call_tool 20 getIcebergDocumentAsOf "$(jq -n \
  --arg uri "$DOC_1" --argjson snap "$SNAP_1_ID" \
  '{connector:"Iceberg_Lakehouse",documentUri:$uri,snapshotId:$snap,userPrincipal:"linda",userRoles:"legal"}')")
V1_LEGAL=$(mcp_tool_text "$V1_LEGAL_RAW") || {
  log_fail "Legal user failed to read Doc 1 at Snapshot 1: $V1_LEGAL_RAW"
  exit 1
}
echo "$V1_LEGAL" | jq -e --arg m "$MARKER_V1" '.extractedText | contains($m)' >/dev/null || {
  log_fail "Snapshot 1 content mismatch for legal user: $V1_LEGAL"
  exit 1
}

V2_LEGAL_RAW=$(mcp_call_tool 21 getIcebergDocumentAsOf "$(jq -n \
  --arg uri "$DOC_1" --argjson snap "$SNAP_2_ID" \
  '{connector:"Iceberg_Lakehouse",documentUri:$uri,snapshotId:$snap,userPrincipal:"linda",userRoles:"legal"}')")
if echo "$V2_LEGAL_RAW" | grep -q "$MARKER_V2"; then
  log_fail "Zero-Trust violation: revoked legal user accessed Doc 1 v2 at Snapshot 2!"
  exit 1
fi

V2_FIN_RAW=$(mcp_call_tool 22 getIcebergDocumentAsOf "$(jq -n \
  --arg uri "$DOC_1" --argjson snap "$SNAP_2_ID" \
  '{connector:"Iceberg_Lakehouse",documentUri:$uri,snapshotId:$snap,userPrincipal:"alice",userRoles:"finance"}')")
V2_FIN=$(mcp_tool_text "$V2_FIN_RAW") || {
  log_fail "Finance user failed to read Doc 1 at Snapshot 2: $V2_FIN_RAW"
  exit 1
}
echo "$V2_FIN" | jq -e --arg m "$MARKER_V2" '.extractedText | contains($m)' >/dev/null || {
  log_fail "Snapshot 2 content mismatch for finance user: $V2_FIN"
  exit 1
}

DEL_CURR_RAW=$(mcp_call_tool 23 getIcebergDocumentAsOf "$(jq -n \
  --arg uri "$DOC_3" \
  '{documentUri:$uri,userPrincipal:"alice",userRoles:"finance"}')")
if echo "$DEL_CURR_RAW" | grep -q "$MARKER_DEL"; then
  log_fail "Tombstone failure: deleted Doc 3 still accessible in current snapshot!"
  exit 1
fi
log_pass "getIcebergDocumentAsOf enforced time-travel, ACL revocation, and tombstone suppression"

# ------------------------------------------------------------------------------
# STEP 6: Verify queryIcebergEntities & Tombstone/ACL Filtering
# ------------------------------------------------------------------------------
log_step "6" "Verifying queryIcebergEntities pushdown, tombstone hiding, and ACL scoping..."

ENT_FIN=$(mcp_tool_text "$(mcp_call_tool 30 queryIcebergEntities '{"connector":"Iceberg_Lakehouse","entityKey":"contract_number","entityValue":"CTR-ICE-01","userPrincipal":"alice","userRoles":"finance"}')")
echo "$ENT_FIN" | jq -e --arg d1 "$DOC_1" '(.matches | length) == 1 and .matches[0].documentId == $d1' >/dev/null || {
  log_fail "queryIcebergEntities for CTR-ICE-01 failed: $ENT_FIN"
  exit 1
}

ENT_DEL=$(mcp_tool_text "$(mcp_call_tool 31 queryIcebergEntities '{"connector":"Iceberg_Lakehouse","entityKey":"contract_number","entityValue":"CTR-DEL-03","userPrincipal":"alice","userRoles":"finance"}')")
echo "$ENT_DEL" | jq -e '(.matches | length) == 0' >/dev/null || {
  log_fail "Deleted Doc 3 resurfaced in queryIcebergEntities: $ENT_DEL"
  exit 1
}

ENT_BETA_FIN=$(mcp_tool_text "$(mcp_call_tool 32 queryIcebergEntities '{"connector":"Iceberg_Lakehouse","entityKey":"vendor","entityValue":"Beta Corp","userPrincipal":"alice","userRoles":"finance"}')")
echo "$ENT_BETA_FIN" | jq -e '(.matches | length) == 0' >/dev/null || {
  log_fail "Zero-Trust violation: executives-only Doc 2 leaked to finance in queryIcebergEntities: $ENT_BETA_FIN"
  exit 1
}

ENT_BETA_EXEC=$(mcp_tool_text "$(mcp_call_tool 33 queryIcebergEntities '{"connector":"Iceberg_Lakehouse","entityKey":"vendor","entityValue":"Beta Corp","userPrincipal":"ceo","userRoles":"executives"}')")
echo "$ENT_BETA_EXEC" | jq -e --arg d2 "$DOC_2" '(.matches | length) == 1 and .matches[0].documentId == $d2' >/dev/null || {
  log_fail "queryIcebergEntities for executives vendor Beta Corp failed: $ENT_BETA_EXEC"
  exit 1
}
log_pass "queryIcebergEntities verified entity lookups with Zero-Trust ACL and tombstone filtering"

# ------------------------------------------------------------------------------
# STEP 7: Verify getIcebergIngestionStats Zero-Trust Scoping
# ------------------------------------------------------------------------------
log_step "7" "Verifying getIcebergIngestionStats counts and ACL confidentiality..."

STATS_FIN=$(mcp_tool_text "$(mcp_call_tool 40 getIcebergIngestionStats '{"connector":"Iceberg_Lakehouse","userPrincipal":"alice","userRoles":"finance"}')")
echo "$STATS_FIN" | jq -e '.activeDocumentCount == 1 and .deleteTombstoneCount >= 1 and .bySourceType.alfresco == 1 and (.bySourceType.sharepoint // 0) == 0' >/dev/null || {
  log_fail "Finance ingestion stats leaked unauthorized or deleted records: $STATS_FIN"
  exit 1
}

STATS_EXEC=$(mcp_tool_text "$(mcp_call_tool 41 getIcebergIngestionStats '{"connector":"Iceberg_Lakehouse","userPrincipal":"ceo","userRoles":"executives"}')")
echo "$STATS_EXEC" | jq -e '.activeDocumentCount == 1 and .bySourceType.sharepoint == 1 and (.bySourceType.alfresco // 0) == 0' >/dev/null || {
  log_fail "Executives ingestion stats unexpected: $STATS_EXEC"
  exit 1
}
log_pass "getIcebergIngestionStats verified partition metrics and Zero-Trust document scoping"

# ------------------------------------------------------------------------------
# STEP 8: Verify OIS Sidecar Tools (listSidecarStores & readOisSidecars)
# ------------------------------------------------------------------------------
log_step "8" "Verifying Iceberg sidecar store discovery and readOisSidecars..."

STORES_TXT=$(mcp_tool_text "$(mcp_call_tool 50 listSidecarStores '{"userPrincipal":"alice","userRoles":"finance"}')")
if ! printf '%s' "$STORES_TXT" | grep -q "Iceberg_Lakehouse"; then
  log_fail "listSidecarStores did not advertise Iceberg_Lakehouse: $STORES_TXT"
  exit 1
fi

SIDECARS_FIN=$(mcp_tool_text "$(mcp_call_tool 51 readOisSidecars '{"connector":"Iceberg_Lakehouse","prefix":"alfresco/","limit":20,"userPrincipal":"alice","userRoles":"finance"}')")
if ! printf '%s' "$SIDECARS_FIN" | grep -q "$DOC_1"; then
  log_fail "readOisSidecars did not return permitted Doc 1: $SIDECARS_FIN"
  exit 1
fi
if printf '%s' "$SIDECARS_FIN" | grep -q "$DOC_2"; then
  log_fail "Zero-Trust violation: executives Doc 2 leaked via readOisSidecars!"
  exit 1
fi
if printf '%s' "$SIDECARS_FIN" | grep -q "$DOC_3"; then
  log_fail "Tombstone violation: deleted Doc 3 returned by readOisSidecars!"
  exit 1
fi
log_pass "listSidecarStores and readOisSidecars verified Iceberg Lakehouse sidecar serving"

# ------------------------------------------------------------------------------
# STEP 9: Verify IcebergMcpSearchBackend (getDocumentContent & listAccessibleSources)
# ------------------------------------------------------------------------------
log_step "9" "Verifying IcebergMcpSearchBackend getDocumentContent & listAccessibleSources..."

CONTENT_FIN=$(mcp_tool_text "$(mcp_call_tool 60 getDocumentContent "$(jq -n --arg uri "$DOC_1" '{documentUri:$uri,userPrincipal:"alice",userRoles:"finance"}')")")
if ! printf '%s' "$CONTENT_FIN" | grep -q "$MARKER_V2"; then
  log_fail "getDocumentContent did not return v2 text for authorized finance caller: $CONTENT_FIN"
  exit 1
fi

CONTENT_LEGAL=$(mcp_call_tool 61 getDocumentContent "$(jq -n --arg uri "$DOC_1" '{documentUri:$uri,userPrincipal:"linda",userRoles:"legal"}')")
if printf '%s' "$CONTENT_LEGAL" | grep -q "$MARKER_V2"; then
  log_fail "Zero-Trust violation: legal user read Doc 1 v2 via getDocumentContent!"
  exit 1
fi

SOURCES_FIN=$(mcp_tool_text "$(mcp_call_tool 62 listAccessibleSources '{"userPrincipal":"alice","userRoles":"finance"}')")
if ! printf '%s' "$SOURCES_FIN" | grep -q "$DOC_1" || printf '%s' "$SOURCES_FIN" | grep -q "$DOC_2" || printf '%s' "$SOURCES_FIN" | grep -q "$DOC_3"; then
  log_fail "listAccessibleSources scoping failure for finance: $SOURCES_FIN"
  exit 1
fi

SOURCES_EXEC=$(mcp_tool_text "$(mcp_call_tool 63 listAccessibleSources '{"userPrincipal":"ceo","userRoles":"executives"}')")
if ! printf '%s' "$SOURCES_EXEC" | grep -q "$DOC_2" || printf '%s' "$SOURCES_EXEC" | grep -q "$DOC_1"; then
  log_fail "listAccessibleSources scoping failure for executives: $SOURCES_EXEC"
  exit 1
fi
log_pass "IcebergMcpSearchBackend verified getDocumentContent and listAccessibleSources"

# ------------------------------------------------------------------------------
# STEP 10: Verify Negative Guard on secureVectorSearch against Iceberg Backend
# ------------------------------------------------------------------------------
log_step "10" "Verifying fail-fast diagnostic error on secureVectorSearch with Iceberg backend..."
VEC_RESP=$(mcp_call_tool 70 secureVectorSearch '{"query":"contract terms","userPrincipal":"alice","userRoles":"finance"}')
if ! printf '%s' "$VEC_RESP" | grep -qi "Iceberg\|listIcebergSnapshots\|vector"; then
  log_fail "secureVectorSearch did not return Iceberg diagnostic guidance: $VEC_RESP"
  exit 1
fi
log_pass "secureVectorSearch fail-fast Iceberg guidance verified"

echo -e "\n${BOLD}${GREEN}================================================================================${NC}"
echo -e "${BOLD}${GREEN}SUCCESS: All ${PASSED_COUNT}/${TOTAL_TESTS} Apache Iceberg MCP Integration Tests Passed!${NC}"
echo -e "${BOLD}${GREEN}================================================================================${NC}"
