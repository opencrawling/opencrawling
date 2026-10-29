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

# ==============================================================================
# OpenCrawling - SharePoint connector through Microsoft Dev Proxy
#
# Description:
#   Runs the SharePoint connector scenarios against the real Microsoft host names
#   (login.microsoftonline.com, graph.microsoft.com, <tenant>.sharepoint.com),
#   routed by Microsoft Dev Proxy (https://aka.ms/devproxy) to the Graph mock,
#   with Microsoft's own Dev Proxy plugins in the way:
#
#   Proxy "graph" (the scenarios must all pass through it):
#     - Microsoft's Graph preset guidance plugins (paging, $select, request ids, SDK)
#     - GraphRandomErrorPlugin with the retryable errors 429, 503, 504
#     - RateLimitingPlugin with Microsoft's Graph rate-limiting preset
#       (budget 120, cost 2; RATE_LIMIT_WINDOW seconds, default 10, preset 60)
#     - RetryAfterPlugin: a request sent before Retry-After elapsed is throttled
#   Proxy "chaos": Microsoft's Graph error preset unchanged (50% of Graph requests
#     fail with 429, 500, 502, 503, 504 or 507), used by SharePointDevProxyChaosTest.
#
#   Dev Proxy runs contained: never as the system proxy, no first-run keychain
#   trust; its CA lives under target/devproxy and only the test JVM trusts it.
#
# Requirements: devproxy (brew install dotnet/dev-proxy/dev-proxy), python3,
#   mvn, java 25, openssl, keytool, curl, jq. The permissions the connector needs
#   are checked from Microsoft's documentation by test-sharepoint-repository-connector.sh.
# ==============================================================================

set -euo pipefail

GRAPH_MOCK_PORT="${GRAPH_MOCK_PORT:-18180}"
PROXY_PORT="${PROXY_PORT:-18190}"
PROXY_API_PORT="${PROXY_API_PORT:-18197}"
CHAOS_PORT="${CHAOS_PORT:-18191}"
CHAOS_API_PORT="${CHAOS_API_PORT:-18198}"
ERROR_RATE="${ERROR_RATE:-10}"
RATE_LIMIT_WINDOW="${RATE_LIMIT_WINDOW:-10}"
TIMEOUT="${TIMEOUT:-60}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

echo -e "${YELLOW}=== OpenCrawling SharePoint connector through Microsoft Dev Proxy ===${NC}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
MODULE="oc-sharepoint-repository-connector"
WORK="$(pwd)/${MODULE}/target/devproxy"

if ! command -v devproxy >/dev/null 2>&1; then
  log_warn "devproxy is required but not installed (install via 'brew install dotnet/dev-proxy/dev-proxy'). Skipping test."
  exit 0
fi
for tool in python3 mvn openssl keytool curl jq; do
  command -v "$tool" >/dev/null 2>&1 || { log_error "$tool is required."; exit 1; }
done
log_info "Dev Proxy $(devproxy --version 2>/dev/null | tail -1)"
for p in "$GRAPH_MOCK_PORT" "$PROXY_PORT" "$PROXY_API_PORT" "$CHAOS_PORT" "$CHAOS_API_PORT"; do
  if lsof -ti "tcp:$p" -sTCP:LISTEN >/dev/null 2>&1; then
    log_error "Port $p is in use."
    exit 1
  fi
done

rm -rf "$WORK"
mkdir -p "$WORK/graph" "$WORK/chaos" "$WORK/cert"

MOCK_PID=""
cleanup() {
  for api in "$PROXY_API_PORT" "$CHAOS_API_PORT"; do
    token=$(cat "$WORK/token-$api" 2>/dev/null || true)
    if [ -n "$token" ]; then
      curl -s -o /dev/null -X POST -H "Authorization: Bearer $token" "http://127.0.0.1:$api/proxy/stopProxy" || true
    fi
  done
  if [ -n "$MOCK_PID" ]; then
    kill "$MOCK_PID" 2>/dev/null || true
    wait "$MOCK_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# ------------------------------------------------------------------ configuration
cat > "$WORK/rewrites.json" <<EOF
{
  "rewrites": [
    { "in": { "url": "^https://(graph\\\\.microsoft\\\\.com|login\\\\.microsoftonline\\\\.com|contoso\\\\.sharepoint\\\\.com)/(.*)\$" },
      "out": { "url": "http://localhost:${GRAPH_MOCK_PORT}/\$2" } }
  ]
}
EOF
PLUGINS='~appFolder/plugins/DevProxy.Plugins.dll'
cat > "$WORK/graph/devproxyrc.json" <<EOF
{
  "\$schema": "https://raw.githubusercontent.com/dotnet/dev-proxy/main/schemas/v3.3.1/rc.schema.json",
  "plugins": [
    { "name": "GraphSelectGuidancePlugin", "enabled": true, "pluginPath": "$PLUGINS" },
    { "name": "GraphSdkGuidancePlugin", "enabled": true, "pluginPath": "$PLUGINS" },
    { "name": "ODataPagingGuidancePlugin", "enabled": true, "pluginPath": "$PLUGINS" },
    { "name": "GraphClientRequestIdGuidancePlugin", "enabled": true, "pluginPath": "$PLUGINS" },
    { "name": "RetryAfterPlugin", "enabled": true, "pluginPath": "$PLUGINS",
      "urlsToWatch": [ "https://graph.microsoft.com/v1.0/*" ] },
    { "name": "RateLimitingPlugin", "enabled": true, "pluginPath": "$PLUGINS", "configSection": "rateLimiting",
      "urlsToWatch": [ "https://graph.microsoft.com/*/drive*", "https://graph.microsoft.com/*/sites/*" ] },
    { "name": "GraphRandomErrorPlugin", "enabled": true, "pluginPath": "$PLUGINS", "configSection": "graphRandomErrors",
      "urlsToWatch": [ "https://graph.microsoft.com/v1.0/*" ] },
    { "name": "ExecutionSummaryPlugin", "enabled": true, "pluginPath": "$PLUGINS", "configSection": "executionSummary" },
    { "name": "JsonReporter", "enabled": true, "pluginPath": "$PLUGINS" },
    { "name": "RewritePlugin", "enabled": true, "pluginPath": "$PLUGINS", "configSection": "rewrite" }
  ],
  "urlsToWatch": [ "https://graph.microsoft.com/*", "https://login.microsoftonline.com/*", "https://contoso.sharepoint.com/*" ],
  "graphRandomErrors": { "allowedErrors": [ 429, 503, 504 ], "rate": ${ERROR_RATE}, "retryAfterInSeconds": 1 },
  "rateLimiting": { "costPerRequest": 2, "rateLimit": 120, "resetTimeWindowSeconds": ${RATE_LIMIT_WINDOW} },
  "executionSummary": { "groupBy": "messageType" },
  "rewrite": { "rewritesFile": "../rewrites.json" },
  "asSystemProxy": false,
  "port": ${PROXY_PORT},
  "apiPort": ${PROXY_API_PORT},
  "newVersionNotification": "none",
  "showTimestamps": false,
  "logLevel": "information"
}
EOF
cat > "$WORK/chaos/devproxyrc.json" <<EOF
{
  "\$schema": "https://raw.githubusercontent.com/dotnet/dev-proxy/main/schemas/v3.3.1/rc.schema.json",
  "plugins": [
    { "name": "GraphRandomErrorPlugin", "enabled": true, "pluginPath": "$PLUGINS", "configSection": "graphRandomErrors",
      "urlsToWatch": [ "https://graph.microsoft.com/v1.0/*" ] },
    { "name": "RewritePlugin", "enabled": true, "pluginPath": "$PLUGINS", "configSection": "rewrite" }
  ],
  "urlsToWatch": [ "https://graph.microsoft.com/*", "https://login.microsoftonline.com/*", "https://contoso.sharepoint.com/*" ],
  "graphRandomErrors": { "allowedErrors": [ 429, 500, 502, 503, 504, 507 ], "rate": 50 },
  "rewrite": { "rewritesFile": "../rewrites.json" },
  "asSystemProxy": false,
  "port": ${CHAOS_PORT},
  "apiPort": ${CHAOS_API_PORT},
  "newVersionNotification": "none",
  "showTimestamps": false,
  "logLevel": "information"
}
EOF

# ------------------------------------------------------------------ the Graph mock
log_info "Starting the Graph mock on port ${GRAPH_MOCK_PORT} (links on graph.microsoft.com, downloads on contoso.sharepoint.com)..."
GRAPH_PORT="$GRAPH_MOCK_PORT" GRAPH_QUIET=true GRAPH_PUBLIC_URL=https://graph.microsoft.com \
  GRAPH_DOWNLOAD_URL=https://contoso.sharepoint.com GRAPH_RECORD="$WORK/graph-traffic.jsonl" \
  python3 "${MODULE}/graph-mock/graph_mock.py" > "$WORK/graph-mock.log" 2>&1 &
MOCK_PID=$!
elapsed=0
until curl -sf -o /dev/null "http://localhost:${GRAPH_MOCK_PORT}/mock/stats"; do
  [ $elapsed -ge "$TIMEOUT" ] && { log_error "The Graph mock did not start."; cat "$WORK/graph-mock.log"; exit 1; }
  sleep 1
  elapsed=$((elapsed + 1))
done

# ------------------------------------------------------------------ Dev Proxy (contained)
start_proxy() { # <name> <port> <api port> [extra flags]
  local name="$1" port="$2" api="$3"
  shift 3
  # --as-system-proxy false and --no-first-run: Dev Proxy never changes the machine's proxy settings or keychain
  (cd "$WORK/$name" && DEV_PROXY_CERT_PATH="$WORK/cert/rootCert.pfx" nohup devproxy --config-file devproxyrc.json \
    --as-system-proxy false --no-first-run --no-watch --no-color "$@" < /dev/null > "$WORK/$name.log" 2>&1 &)
  local elapsed=0
  until grep -q "API token:" "$WORK/$name.log" 2>/dev/null && lsof -ti "tcp:$port" -sTCP:LISTEN >/dev/null 2>&1; do
    if [ $elapsed -ge "$TIMEOUT" ]; then
      log_error "Dev Proxy '$name' did not start."
      cat "$WORK/$name.log"
      exit 1
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  sed -n 's/^API token: *//p' "$WORK/$name.log" | tail -1 > "$WORK/token-$api"
  log_success "Dev Proxy '$name' listening on 127.0.0.1:$port."
}
start_proxy graph "$PROXY_PORT" "$PROXY_API_PORT" --record
start_proxy chaos "$CHAOS_PORT" "$CHAOS_API_PORT"

openssl pkcs12 -in "$WORK/cert/rootCert.pfx" -nokeys -passin pass: -out "$WORK/cert/root.pem" 2>/dev/null \
  || openssl pkcs12 -legacy -in "$WORK/cert/rootCert.pfx" -nokeys -passin pass: -out "$WORK/cert/root.pem"
keytool -importcert -noprompt -alias dev-proxy-ca -file "$WORK/cert/root.pem" -keystore "$WORK/cert/truststore.p12" \
  -storetype PKCS12 -storepass changeit >/dev/null
log_info "Only the test JVM trusts the Dev Proxy CA ($(openssl x509 -in "$WORK/cert/root.pem" -noout -subject))."
if [ "$(uname)" = "Darwin" ] && security find-certificate -c "Dev Proxy CA" >/dev/null 2>&1; then
  log_warn "A 'Dev Proxy CA' certificate is in a keychain (not added by this script)."
fi

# ------------------------------------------------------------------ the scenarios
log_info "Running the connector scenarios through Dev Proxy (${ERROR_RATE}% retryable Graph errors, rate limiting, Retry-After checks)..."
set +e
GRAPH_MOCK_URL="http://localhost:${GRAPH_MOCK_PORT}" \
GRAPH_MOCK_GRAPH_URL="https://graph.microsoft.com/v1.0" \
GRAPH_MOCK_AUTHORITY_URL="https://login.microsoftonline.com" \
GRAPH_MOCK_CHAOS_PROXY_PORT="$CHAOS_PORT" \
mvn -o -B test -pl "$MODULE" -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='SharePointMockServerTest,SharePointDevProxyChaosTest' \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort="$PROXY_PORT" \
  -Djavax.net.ssl.trustStore="$WORK/cert/truststore.p12" -Djavax.net.ssl.trustStorePassword=changeit \
  -Djavax.net.ssl.trustStoreType=PKCS12 ${MAVEN_ARGS:-} > "$WORK/maven.log" 2>&1
MVN_EXIT=$?
set -e
grep -E "Tests run:|FAIL|Crawls under" "$WORK/maven.log" | grep -v "^\[INFO\] Tests run: .*, Time" | tail -8 || true

# ------------------------------------------------------------------ Dev Proxy's reports
TOKEN=$(cat "$WORK/token-$PROXY_API_PORT")
curl -s -o /dev/null -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"recording": false}' "http://127.0.0.1:${PROXY_API_PORT}/proxy"
elapsed=0
until [ -f "$WORK/graph/ExecutionSummaryPlugin_JsonReporter.json" ]; do
  [ $elapsed -ge 60 ] && break
  sleep 1
  elapsed=$((elapsed + 1))
done
echo
SUMMARY="$WORK/graph/ExecutionSummaryPlugin_JsonReporter.json"
if [ -f "$SUMMARY" ]; then
  log_info "Dev Proxy execution summary (${SUMMARY#$(pwd)/}):"
  jq -r 'to_entries[] | select(.key != "Requests intercepted") | "  \(.key): \(.value | if type == "object" then (to_entries | map("\(.key) \(.value | if type == "object" then (to_entries | map(.value) | add) else . end)") | join(", ")) else . end)"' \
    "$SUMMARY" 2>/dev/null | cut -c1-240 || true
fi
log_info "Note: the paging warning above is an artifact of this setup: ODataPagingGuidancePlugin records nextLinks only"
log_info "      for watched URLs, and the RewritePlugin has already pointed the request at the mock when the response"
log_info "      arrives. test-sharepoint-repository-connector.sh checks that every paging URL is a link Graph returned."
log_info "Dev Proxy guidance on the connector's requests:"
grep -E "^ *(tip|warn|fail) " "$WORK/graph.log" | sed 's/^ */  /' | sort | uniq -c | sort -rn | head -15 || echo "  (none)"

if [ "$MVN_EXIT" -ne 0 ]; then
  log_error "The scenarios failed through Dev Proxy; see $WORK/maven.log and $WORK/graph.log."
  exit 1
fi
log_success "All SharePoint scenarios passed through Microsoft Dev Proxy."
