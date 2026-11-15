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
# OpenCrawling Root CLI Runner Scripts (run.sh & run.bat) Test Suite
# ==============================================================================
# Validates syntax, help banners, NO_COLOR compliance, pre-flight error handling,
# command dispatching for all build/stack/developer commands, and Windows
# run.bat feature parity (#217).
# ==============================================================================

set -euo pipefail

# ANSI Color Codes
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

PASSED_COUNT=0
TOTAL_TESTS=7

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
    exit 1
}

log_info() {
    echo -e "${CYAN}[INFO] $1${NC}"
}

echo -e "${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${YELLOW}=== OpenCrawling Root CLI Helpers (run.sh & run.bat) Test Suite ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"
log_info "Working directory: $PROJECT_ROOT"

TMP_DIR="$(mktemp -d)"
cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

# ------------------------------------------------------------------------------
# STEP 1: Presence, Executable Permissions, and Syntax Validation
# ------------------------------------------------------------------------------
log_step "1" "Verifying presence, executable permissions, and syntax of run.sh and run.bat..."

[ -f "$PROJECT_ROOT/run.sh" ] || log_fail "run.sh not found in repository root"
[ -x "$PROJECT_ROOT/run.sh" ] || log_fail "run.sh is not executable (chmod +x missing)"
[ -f "$PROJECT_ROOT/run.bat" ] || log_fail "run.bat not found in repository root"
[ -s "$PROJECT_ROOT/run.bat" ] || log_fail "run.bat is empty"

bash -n "$PROJECT_ROOT/run.sh" || log_fail "run.sh failed bash -n syntax validation"

grep -q "Licensed under the Apache License, Version 2.0" "$PROJECT_ROOT/run.sh" || log_fail "run.sh missing Apache 2.0 license header"
grep -q "Licensed under the Apache License, Version 2.0" "$PROJECT_ROOT/run.bat" || log_fail "run.bat missing Apache 2.0 license header"

log_pass "Presence, permissions, license headers, and syntax verified"

# ------------------------------------------------------------------------------
# STEP 2: Help Banner, Usage Flags, and NO_COLOR Compliance
# ------------------------------------------------------------------------------
log_step "2" "Verifying help banner, usage flags, and NO_COLOR terminal handling..."

HELP_DEFAULT="$("$PROJECT_ROOT/run.sh")"
HELP_EXPLICIT="$("$PROJECT_ROOT/run.sh" help)"
HELP_SHORT="$("$PROJECT_ROOT/run.sh" -h)"
HELP_LONG="$("$PROJECT_ROOT/run.sh" --help)"

[ "$HELP_DEFAULT" = "$HELP_EXPLICIT" ] || log_fail "Default no-arg output does not match './run.sh help'"
[ "$HELP_SHORT" = "$HELP_EXPLICIT" ] || log_fail "'./run.sh -h' output does not match './run.sh help'"
[ "$HELP_LONG" = "$HELP_EXPLICIT" ] || log_fail "'./run.sh --help' output does not match './run.sh help'"

REQUIRED_COMMANDS=(
    "build"
    "mvn:build"
    "build:offline"
    "build:host"
    "build:native"
    "build:clean"
    "mvn <args...>"
    "decoupled:up"
    "decoupled:local"
    "decoupled:override"
    "decoupled:down"
    "apps:up"
    "apps:local"
    "apps:override"
    "apps:down"
    "infra:up"
    "infra:down"
    "down"
    "stop"
    "status"
    "ps"
    "logs"
    "test:unit"
    "test:it"
    "test:local-dev"
    "ui:dev"
    "ui:build"
    "license:format"
    "format"
    "clean"
    "help"
)

for cmd in "${REQUIRED_COMMANDS[@]}"; do
    echo "$HELP_EXPLICIT" | grep -q "$cmd" || log_fail "Help output is missing command documentation for: $cmd"
done

NO_COLOR_OUT="$(NO_COLOR=1 "$PROJECT_ROOT/run.sh" help)"
if printf "%s" "$NO_COLOR_OUT" | grep -q $'\033'; then
    log_fail "NO_COLOR=1 output still contained ANSI escape sequences"
fi

log_pass "Help output and NO_COLOR compliance verified across all 31 command aliases"

# ------------------------------------------------------------------------------
# STEP 3: Negative Tests & Pre-flight Error Handling
# ------------------------------------------------------------------------------
log_step "3" "Verifying error handling for unknown commands, missing arguments, and pre-flight checks..."

if "$PROJECT_ROOT/run.sh" non_existent_command >/dev/null 2>"$TMP_DIR/err.log"; then
    log_fail "Expected './run.sh non_existent_command' to exit with non-zero code"
fi
grep -q "Unknown command: 'non_existent_command'" "$TMP_DIR/err.log" || log_fail "Missing unknown command error message"

# Set up mock environment for pre-flight tests
MOCK_BIN="$TMP_DIR/mock-bin"
mkdir -p "$MOCK_BIN"
MOCK_LOG="$TMP_DIR/invocations.log"

# Mock docker that reports daemon healthy
cat > "$MOCK_BIN/docker" <<EOF
#!/usr/bin/env bash
if [ "\${MOCK_DOCKER_DAEMON_DOWN:-0}" = "1" ] && [ "\${1:-}" = "info" ]; then
    exit 1
fi
if [ "\${1:-}" = "info" ] || [ "\${2:-}" = "version" ]; then
    exit 0
fi
echo "DOCKER_BUILDKIT=\${DOCKER_BUILDKIT:-} COMPOSE_DOCKER_CLI_BUILD=\${COMPOSE_DOCKER_CLI_BUILD:-} docker \$*" >> "$MOCK_LOG"
EOF
chmod +x "$MOCK_BIN/docker"

cat > "$MOCK_BIN/mvn" <<EOF
#!/usr/bin/env bash
echo "mvn \$*" >> "$MOCK_LOG"
EOF
chmod +x "$MOCK_BIN/mvn"

cat > "$MOCK_BIN/node" <<EOF
#!/usr/bin/env bash
echo "v22.0.0"
EOF
chmod +x "$MOCK_BIN/node"

cat > "$MOCK_BIN/npm" <<EOF
#!/usr/bin/env bash
echo "npm \$*" >> "$MOCK_LOG"
EOF
chmod +x "$MOCK_BIN/npm"

# Test './run.sh mvn' with no args
if PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" mvn >/dev/null 2>"$TMP_DIR/mvn_err.log"; then
    log_fail "Expected './run.sh mvn' with no arguments to fail"
fi
grep -q "Please provide Maven arguments" "$TMP_DIR/mvn_err.log" || log_fail "Missing error message for empty './run.sh mvn'"

# Test Docker daemon down detection
if MOCK_DOCKER_DAEMON_DOWN=1 PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" build >/dev/null 2>"$TMP_DIR/daemon_err.log"; then
    log_fail "Expected './run.sh build' to fail when Docker daemon is down"
fi
grep -q "Docker daemon is not running" "$TMP_DIR/daemon_err.log" || log_fail "Missing Docker daemon error message"

log_pass "Pre-flight checks and error handling verified"

# ------------------------------------------------------------------------------
# STEP 4: Build & Maven Command Dispatch Verification
# ------------------------------------------------------------------------------
log_step "4" "Verifying Build & Maven commands (build, build:offline, build:host, build:clean, mvn)..."

: > "$MOCK_LOG"
MOCK_HOME="$TMP_DIR/mock-home"
HOME="$MOCK_HOME" PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" build >/dev/null
[ -d "$MOCK_HOME/.m2" ] || log_fail "run.sh did not ensure \${HOME}/.m2 directory exists"
grep -q "DOCKER_BUILDKIT=1 COMPOSE_DOCKER_CLI_BUILD=1 docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn clean package -DskipTests" "$MOCK_LOG" \
    || log_fail "'./run.sh build' did not invoke expected docker compose command"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" mvn:build -T 1C >/dev/null
grep -q "docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn clean package -DskipTests -T 1C" "$MOCK_LOG" \
    || log_fail "'./run.sh mvn:build' did not forward extra flags"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" build:offline >/dev/null
grep -q "docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn clean package -o -DskipTests" "$MOCK_LOG" \
    || log_fail "'./run.sh build:offline' did not invoke expected command"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" build:host -pl oc-core >/dev/null
grep -q "^mvn clean package -DskipTests -pl oc-core$" "$MOCK_LOG" \
    || log_fail "'./run.sh build:host' did not invoke host mvn"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" build:clean >/dev/null
grep -q "docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn clean package$" "$MOCK_LOG" \
    || log_fail "'./run.sh build:clean' did not invoke expected command"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" mvn test -pl oc-runtime -Dtest=McpVectorServerTest >/dev/null
grep -q "docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn test -pl oc-runtime -Dtest=McpVectorServerTest" "$MOCK_LOG" \
    || log_fail "'./run.sh mvn ...' did not forward custom Maven arguments"

log_pass "All Build & Maven commands dispatch accurately"

# ------------------------------------------------------------------------------
# STEP 5: Docker Stack Lifecycle Command Dispatch Verification
# ------------------------------------------------------------------------------
log_step "5" "Verifying Docker Stack Lifecycle commands (decoupled, apps, infra, down)..."

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" decoupled:up >/dev/null
grep -q "docker compose -f docker-compose-decoupled-local.yml up -d --build" "$MOCK_LOG" \
    || log_fail "'./run.sh decoupled:up' failed dispatch check"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" decoupled:override >/dev/null
grep -q "docker compose -f docker-compose-decoupled.yml -f docker-compose-decoupled.local.yml up -d --build" "$MOCK_LOG" \
    || log_fail "'./run.sh decoupled:override' failed dispatch check"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" decoupled:down -v >/dev/null
grep -q "docker compose -f docker-compose-decoupled-local.yml down -v" "$MOCK_LOG" \
    || log_fail "'./run.sh decoupled:down' failed dispatch check"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" apps:up >/dev/null
grep -q "docker compose -f docker-compose-apps-local.yml up -d --build" "$MOCK_LOG" \
    || log_fail "'./run.sh apps:up' failed dispatch check"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" apps:override >/dev/null
grep -q "docker compose -f docker-compose.yml -f docker-compose-apps.yml -f docker-compose-apps.local.yml up -d --build" "$MOCK_LOG" \
    || log_fail "'./run.sh apps:override' failed dispatch check"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" apps:down >/dev/null
grep -q "docker compose -f docker-compose-apps-local.yml down" "$MOCK_LOG" \
    || log_fail "'./run.sh apps:down' failed dispatch check"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" infra:up >/dev/null
grep -q "docker compose up -d" "$MOCK_LOG" \
    || log_fail "'./run.sh infra:up' failed dispatch check"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" infra:down >/dev/null
grep -q "docker compose down" "$MOCK_LOG" \
    || log_fail "'./run.sh infra:down' failed dispatch check"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" down >/dev/null
grep -q "docker compose -f docker-compose-decoupled-local.yml down --remove-orphans" "$MOCK_LOG" \
    || log_fail "'./run.sh down' did not stop decoupled-local stack"
grep -q "docker compose -f docker-compose-apps-local.yml down --remove-orphans" "$MOCK_LOG" \
    || log_fail "'./run.sh down' did not stop apps-local stack"
grep -q "docker compose down --remove-orphans" "$MOCK_LOG" \
    || log_fail "'./run.sh down' did not stop core infra stack"

log_pass "All Docker Stack Lifecycle commands dispatch accurately"

# ------------------------------------------------------------------------------
# STEP 6: Developer Convenience & Productivity Commands Verification
# ------------------------------------------------------------------------------
log_step "6" "Verifying Developer Convenience commands (status, logs, test:*, ui:*, license:format, clean)..."

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" status >/dev/null
grep -q "docker compose -f docker-compose-decoupled-local.yml ps" "$MOCK_LOG" \
    || log_fail "'./run.sh status' did not check decoupled-local stack"
grep -q "docker compose -f docker-compose-apps-local.yml ps" "$MOCK_LOG" \
    || log_fail "'./run.sh status' did not check apps-local stack"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" logs oc-crawler >/dev/null
grep -q "docker compose -f docker-compose-decoupled-local.yml logs -f oc-crawler" "$MOCK_LOG" \
    || log_fail "'./run.sh logs oc-crawler' failed dispatch check"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" test:unit -pl oc-core >/dev/null
grep -q "docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn test -pl oc-core" "$MOCK_LOG" \
    || log_fail "'./run.sh test:unit' failed dispatch check"

# Verify test:it injects COMPOSE_FILE and runs ./run-integration-tests.sh --dry-run
IT_DRY_OUT="$(PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" test:it --dry-run mcp)"
echo "$IT_DRY_OUT" | grep -q "test-mcp-decoupled.sh" \
    || log_fail "'./run.sh test:it --dry-run mcp' did not select test-mcp-decoupled.sh"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" ui:dev --host >/dev/null
grep -q "npm run dev -- --host" "$MOCK_LOG" \
    || log_fail "'./run.sh ui:dev --host' failed dispatch check"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" ui:build >/dev/null
grep -q "npm run build" "$MOCK_LOG" \
    || log_fail "'./run.sh ui:build' failed dispatch check"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" license:format >/dev/null
grep -q "^mvn license:format$" "$MOCK_LOG" \
    || log_fail "'./run.sh license:format' failed dispatch check"

: > "$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" "$PROJECT_ROOT/run.sh" clean --prune >/dev/null
grep -q "^mvn clean$" "$MOCK_LOG" \
    || log_fail "'./run.sh clean --prune' did not run mvn clean"
grep -q "docker builder prune -f" "$MOCK_LOG" \
    || log_fail "'./run.sh clean --prune' did not run docker builder prune -f"

log_pass "All Developer Convenience & Productivity commands verified"

# ------------------------------------------------------------------------------
# STEP 7: Windows run.bat Feature Parity & Structure Verification
# ------------------------------------------------------------------------------
log_step "7" "Verifying Windows run.bat command parity and environment handling..."

BAT_LABELS=(
    ":cmd_help"
    ":cmd_build"
    ":cmd_build_offline"
    ":cmd_build_host"
    ":cmd_build_clean"
    ":cmd_mvn"
    ":cmd_decoupled_up"
    ":cmd_decoupled_override"
    ":cmd_decoupled_down"
    ":cmd_apps_up"
    ":cmd_apps_override"
    ":cmd_apps_down"
    ":cmd_infra_up"
    ":cmd_infra_down"
    ":cmd_down"
    ":cmd_status"
    ":cmd_logs"
    ":cmd_test_unit"
    ":cmd_test_it"
    ":cmd_test_local_dev"
    ":cmd_ui_dev"
    ":cmd_ui_build"
    ":cmd_license_format"
    ":cmd_clean"
    ":check_docker"
    ":check_host_mvn"
    ":check_node"
)

for label in "${BAT_LABELS[@]}"; do
    grep -q "^${label}" "$PROJECT_ROOT/run.bat" || log_fail "run.bat is missing label: $label"
done

grep -q 'if not defined HOME set "HOME=%USERPROFILE%"' "$PROJECT_ROOT/run.bat" \
    || log_fail "run.bat is missing %USERPROFILE% to HOME mapping for Docker Compose volume mounts"
grep -q 'docker-compose-maven-builder.yml' "$PROJECT_ROOT/run.bat" \
    || log_fail "run.bat is missing docker-compose-maven-builder.yml reference"
grep -q 'docker-compose-decoupled-local.yml' "$PROJECT_ROOT/run.bat" \
    || log_fail "run.bat is missing docker-compose-decoupled-local.yml reference"
grep -q 'docker-compose-decoupled.local.yml' "$PROJECT_ROOT/run.bat" \
    || log_fail "run.bat is missing docker-compose-decoupled.local.yml reference"
grep -q 'docker-compose-apps-local.yml' "$PROJECT_ROOT/run.bat" \
    || log_fail "run.bat is missing docker-compose-apps-local.yml reference"
grep -q 'docker-compose-apps.local.yml' "$PROJECT_ROOT/run.bat" \
    || log_fail "run.bat is missing docker-compose-apps.local.yml reference"

log_pass "Windows run.bat feature parity and command labels verified"

# ------------------------------------------------------------------------------
# Summary
# ------------------------------------------------------------------------------
echo -e "\n${BOLD}${GREEN}================================================================================${NC}"
echo -e "${BOLD}${GREEN}SUCCESS: All $TOTAL_TESTS Root CLI Runner (run.sh & run.bat) Tests Passed!${NC}"
echo -e "${BOLD}${GREEN}================================================================================${NC}"

exit 0
