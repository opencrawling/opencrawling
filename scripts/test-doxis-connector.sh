#!/usr/bin/env bash
# ==============================================================================
# OpenCrawling - Doxis 4 Output Connector Integration Test Script
#
# Description:
#   Smoke-tests the Doxis CSB REST API contract used by `oc-doxis-output-connector`
#   against a running Doxis CSB (there is no local Doxis container):
#     1. CSB liveness and customer (tenant) discovery
#     2. JWT login (customer / user / password / role) and session user
#     3. Target repository and the CQL external-id lookup used for upserts
#     4. (opt-in, DOXIS_WRITE_TEST=true) multipart create with content,
#        read-back verification of the content object length, physical delete
#     5. Logout
#
#   Skips (exit 0) when DOXIS_BASE_URL / DOXIS_CUSTOMER / DOXIS_USER /
#   DOXIS_PASSWORD are not set, so it is safe in `run-integration-tests.sh`.
#
# Example:
#   DOXIS_BASE_URL=http://csb-host:8080/restws/publicws/rest/api/v1 \
#   DOXIS_CUSTOMER=faststarter DOXIS_USER=Supervisor2 DOXIS_PASSWORD=... \
#   DOXIS_ROLE=admins DOXIS_REPOSITORY=D_TEXTER DOXIS_EXTERNAL_ID_FIELD=OBJECTNUMBER2 \
#   DOXIS_WRITE_TEST=true DOXIS_DOCUMENT_TYPE_UUID=271228ee-0f1c-4169-878e-b9d7a1b12525 \
#   DOXIS_EXTERNAL_ID_ATTRIBUTE_UUID=5121f636-6090-41ff-bad8-8c05e6bb5fd6 DOXIS_MIME_TYPE=text/plain \
#   ./scripts/test-doxis-connector.sh
# ==============================================================================

set -euo pipefail

# Configuration
DOXIS_BASE_URL="${DOXIS_BASE_URL:-}"
DOXIS_CUSTOMER="${DOXIS_CUSTOMER:-}"
DOXIS_USER="${DOXIS_USER:-}"
DOXIS_PASSWORD="${DOXIS_PASSWORD:-}"
DOXIS_ROLE="${DOXIS_ROLE:-}"
DOXIS_REPOSITORY="${DOXIS_REPOSITORY:-D_TEXTER}"
DOXIS_EXTERNAL_ID_FIELD="${DOXIS_EXTERNAL_ID_FIELD:-OBJECTNUMBER}"
DOXIS_WRITE_TEST="${DOXIS_WRITE_TEST:-false}"
DOXIS_DOCUMENT_TYPE_UUID="${DOXIS_DOCUMENT_TYPE_UUID:-}"
DOXIS_EXTERNAL_ID_ATTRIBUTE_UUID="${DOXIS_EXTERNAL_ID_ATTRIBUTE_UUID:-}"
DOXIS_MIME_TYPE="${DOXIS_MIME_TYPE:-application/pdf}"

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

if [[ -z "$DOXIS_BASE_URL" || -z "$DOXIS_CUSTOMER" || -z "$DOXIS_USER" || -z "$DOXIS_PASSWORD" ]]; then
    log_warn "DOXIS_BASE_URL / DOXIS_CUSTOMER / DOXIS_USER / DOXIS_PASSWORD not set - skipping Doxis connector test."
    exit 0
fi
command -v jq >/dev/null 2>&1 || { log_error "jq is required."; exit 1; }

BASE="${DOXIS_BASE_URL%/}"
CSB_ROOT="${BASE%%/restws/*}"
JWT=""
CREATED_DOC=""

cleanup() {
    if [[ -n "$CREATED_DOC" && -n "$JWT" ]]; then
        curl -s -o /dev/null -X DELETE -H "Authorization: Bearer $JWT" \
            "$BASE/dmsRepositories/$DOXIS_REPOSITORY/documents/$CREATED_DOC" || true
    fi
    if [[ -n "$JWT" ]]; then
        curl -s -o /dev/null -X POST -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" -d '{}' "$BASE/logout" || true
        log_info "Logged out."
    fi
}
trap cleanup EXIT

api() { # method path [curl args...]
    local method=$1 path=$2; shift 2
    curl -sS -X "$method" -H "Accept: application/json" -H "Authorization: Bearer $JWT" "$@" "$BASE$path"
}

# 1. Liveness + customers
log_info "Checking CSB liveness at $CSB_ROOT ..."
status=$(curl -s -o /dev/null -w '%{http_code}' "$CSB_ROOT/sedna-transfer-service-xf/isAlive")
[[ "$status" == "200" ]] || { log_error "CSB isAlive returned HTTP $status"; exit 1; }
log_success "CSB is alive."

curl -sS -H "Accept: application/json" "$BASE/customers" | jq -e --arg c "$DOXIS_CUSTOMER" 'map(.name) | index($c) != null' >/dev/null \
    || { log_error "Customer '$DOXIS_CUSTOMER' not found in GET /customers"; exit 1; }
log_success "Customer '$DOXIS_CUSTOMER' exists."

# 2. Login
login_body=$(jq -n --arg c "$DOXIS_CUSTOMER" --arg u "$DOXIS_USER" --arg p "$DOXIS_PASSWORD" --arg r "$DOXIS_ROLE" \
    '{customerName:$c, userName:$u, password:$p, clientImplementationId:"OpenCrawling-IT"} + (if $r == "" then {} else {role:$r} end)')
JWT=$(curl -sS -X POST -H "Content-Type: application/json" -H "Accept: application/json" -d "$login_body" "$BASE/login" | jq -r '.')
[[ -n "$JWT" && "$JWT" != "null" && "$JWT" != *"Exception"* ]] || { log_error "Login failed"; JWT=""; exit 1; }
user=$(api GET /session/user | jq -r '.name')
log_success "Logged in as '$user' (role '${DOXIS_ROLE:--}')."

# 3. Repository + lookup
repo=$(api GET "/dmsRepositories/$DOXIS_REPOSITORY")
short=$(echo "$repo" | jq -r '.shortName // .name')
log_success "Repository '$(echo "$repo" | jq -r '.name')' (CQL name '$short') is accessible; allowed types: $(echo "$repo" | jq -c '.informationObjectTypeIds')."

lookup=$(jq -n --arg q "SELECT * FROM $short WHERE $DOXIS_EXTERNAL_ID_FIELD = 'opencrawling-it-probe'" \
    '{cqlStatement:$q, currentVersionOnly:true, fetchResultLimitation:10, logicallyDeletedFilter:"ANY_OBJECTS"}')
result=$(api POST /documents/search -H "Content-Type: application/json" -d "$lookup")
echo "$result" | jq -e '.searchHits' >/dev/null || { log_error "External-id lookup failed: $result"; exit 1; }
search_id=$(echo "$result" | jq -r '.searchId // empty')
[[ -n "$search_id" ]] && api DELETE "/documents/searchResults/$search_id" -o /dev/null
log_success "CQL external-id lookup works ($(echo "$result" | jq '.searchHits | length') hit(s))."

# 4. Optional write test
if [[ "$DOXIS_WRITE_TEST" == "true" ]]; then
    [[ -n "$DOXIS_DOCUMENT_TYPE_UUID" && -n "$DOXIS_EXTERNAL_ID_ATTRIBUTE_UUID" ]] \
        || { log_error "DOXIS_WRITE_TEST needs DOXIS_DOCUMENT_TYPE_UUID and DOXIS_EXTERNAL_ID_ATTRIBUTE_UUID"; exit 1; }
    tmp=$(mktemp)
    printf 'OpenCrawling Doxis integration test\n' > "$tmp"
    length=$(wc -c < "$tmp" | tr -d ' ')
    params=$(jq -n --arg t "$DOXIS_DOCUMENT_TYPE_UUID" --arg m "$DOXIS_MIME_TYPE" --arg a "$DOXIS_EXTERNAL_ID_ATTRIBUTE_UUID" --argjson l "$length" \
        '{documentTypeUUID:$t, mimeTypeName:$m, fullFileName:"opencrawling-it-probe", contentLength:$l,
          attributes:[{attributeDefinitionUUID:$a, attributeDataType:"STRING", values:["opencrawling-it-probe"]}]}')
    created=$(api POST "/dmsRepositories/$DOXIS_REPOSITORY/documents" \
        -F "documentParams=$params;type=application/json" -F "inputStream=@$tmp;type=$DOXIS_MIME_TYPE")
    rm -f "$tmp"
    CREATED_DOC=$(echo "$created" | jq -r '.documentWsTO.uuid // empty')
    [[ -n "$CREATED_DOC" ]] || { log_error "Create failed: $(echo "$created" | jq -r '.errorCode + " " + .message' 2>/dev/null || echo "$created")"; exit 1; }
    log_success "Created document $CREATED_DOC."

    stored=$(api GET "/dmsRepositories/$DOXIS_REPOSITORY/documents/$CREATED_DOC/versions?initializeRepresentations=true" \
        | jq '[.versions[] | select(.currentVersion) | .representations[].contentObjects[].length] | first')
    [[ "$stored" == "$length" ]] || { log_error "Content object length $stored != $length"; exit 1; }
    log_success "Content object verified ($stored bytes)."

    api DELETE "/dmsRepositories/$DOXIS_REPOSITORY/documents/$CREATED_DOC" -o /dev/null
    CREATED_DOC=""
    log_success "Document physically deleted."
else
    log_info "Write test skipped (set DOXIS_WRITE_TEST=true with a document type allowed in $DOXIS_REPOSITORY)."
fi

log_success "Doxis connector integration test passed."
