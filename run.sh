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
# OpenCrawling Developer CLI Runner (macOS / Linux)
# ==============================================================================
# Unified root entrypoint for fast local Maven builds (via oc-maven-builder or
# host Maven), BuildKit-accelerated Docker Compose stacks, testing, UI dev,
# and day-to-day developer workflows.
# Compatible with macOS default Bash 3.2+ and modern Linux distributions.
# ==============================================================================

set -euo pipefail

# ------------------------------------------------------------------------------
# Terminal Color & Styling Setup (respects NO_COLOR and non-TTY stdout)
# ------------------------------------------------------------------------------
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  BOLD='\033[1m'
  DIM='\033[2m'
  RED='\033[0;31m'
  GREEN='\033[0;32m'
  YELLOW='\033[1;33m'
  BLUE='\033[0;34m'
  CYAN='\033[0;36m'
  NC='\033[0m'
else
  BOLD=''
  DIM=''
  RED=''
  GREEN=''
  YELLOW=''
  BLUE=''
  CYAN=''
  NC=''
fi

# ------------------------------------------------------------------------------
# Resolve Project Root Directory
# ------------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

export DOCKER_BUILDKIT="${DOCKER_BUILDKIT:-1}"
export COMPOSE_DOCKER_CLI_BUILD="${COMPOSE_DOCKER_CLI_BUILD:-1}"

# ------------------------------------------------------------------------------
# Logging Helpers
# ------------------------------------------------------------------------------
log_info() {
  printf "%b\n" "${CYAN}[INFO]${NC} $*"
}

log_ok() {
  printf "%b\n" "${GREEN}[OK]${NC} $*"
}

log_warn() {
  printf "%b\n" "${YELLOW}[WARN]${NC} $*"
}

log_error() {
  printf "%b\n" "${RED}[ERROR]${NC} $*" >&2
}

log_header() {
  printf "\n%b\n" "${BOLD}${BLUE}==> $*${NC}"
}

# ------------------------------------------------------------------------------
# Pre-flight Environment Checks
# ------------------------------------------------------------------------------
ensure_m2_dir() {
  if [ -n "${HOME:-}" ]; then
    mkdir -p "${HOME}/.m2"
  fi
}

check_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    log_error "Docker CLI is not installed or not in PATH."
    exit 1
  fi
  if ! docker info >/dev/null 2>&1; then
    log_error "Docker daemon is not running. Please start Docker Desktop or the Docker service."
    exit 1
  fi
  if ! docker compose version >/dev/null 2>&1; then
    log_error "'docker compose' plugin is not available. Please install Docker Compose v2+."
    exit 1
  fi
  ensure_m2_dir
}

check_host_mvn() {
  if ! command -v mvn >/dev/null 2>&1; then
    log_error "Host 'mvn' was not found in PATH. Use './run.sh build' to build via containerized oc-maven-builder instead."
    exit 1
  fi
}

check_node() {
  if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
    log_error "Node.js and npm are required for UI commands but were not found in PATH."
    exit 1
  fi
}

# ------------------------------------------------------------------------------
# Usage / Help Banner
# ------------------------------------------------------------------------------
print_help() {
  printf "%b\n" "${BOLD}${CYAN}OpenCrawling Developer CLI Runner (run.sh)${NC}
Fast local builds, Docker Compose stack orchestration, and developer workflows.

${BOLD}USAGE:${NC}
  ./run.sh <command> [args...]

${BOLD}1. BUILD & MAVEN COMMANDS (Local ~/.m2 Cache Acceleration):${NC}
  ${GREEN}build${NC}, ${GREEN}mvn:build${NC}          Build all modules via containerized ${CYAN}oc-maven-builder${NC} (-DskipTests)
  ${GREEN}build:offline${NC}              Fast offline build via ${CYAN}oc-maven-builder${NC} (-o -DskipTests)
  ${GREEN}build:host${NC}, ${GREEN}build:native${NC}  Build using host Maven & JDK 25 (mvn clean package -DskipTests)
  ${GREEN}build:clean${NC}                Full clean build with unit tests via ${CYAN}oc-maven-builder${NC}
  ${GREEN}mvn <args...>${NC}              Run custom Maven goals inside ${CYAN}oc-maven-builder${NC}

${BOLD}2. DOCKER STACK LIFECYCLE COMMANDS (Local Compose Topologies):${NC}
  ${GREEN}decoupled:up${NC}, ${GREEN}decoupled:local${NC}  Start standalone decoupled stack with local BuildKit cache mounts
  ${GREEN}decoupled:override${NC}         Start decoupled stack via compose override overlay
  ${GREEN}decoupled:down${NC}             Stop decoupled local stack
  ${GREEN}apps:up${NC}, ${GREEN}apps:local${NC}        Start standalone apps stack (backend, embedding, UI) with local Dockerfiles
  ${GREEN}apps:override${NC}              Start apps stack via compose override overlay
  ${GREEN}apps:down${NC}                  Stop apps local stack
  ${GREEN}infra:up${NC}                   Start core infrastructure only (PostgreSQL/pgvector, Redis, Ollama, Kafka)
  ${GREEN}infra:down${NC}                 Stop core infrastructure stack
  ${GREEN}down${NC}, ${GREEN}stop${NC}                 Stop all active local OpenCrawling Compose stacks

${BOLD}3. DEVELOPER CONVENIENCE & PRODUCTIVITY COMMANDS:${NC}
  ${GREEN}status${NC}, ${GREEN}ps${NC}                 Show status and port bindings of running local containers
  ${GREEN}logs [service...]${NC}          Follow container logs (defaults to decoupled local stack)
  ${GREEN}test:unit [args...]${NC}        Run unit test suite via ${CYAN}oc-maven-builder${NC}
  ${GREEN}test:it [filter...]${NC}        Run integration tests with local BuildKit cache overlay
  ${GREEN}test:local-dev${NC}             Run dedicated local Docker build acceleration test suite
  ${GREEN}ui:dev${NC}                     Start React Admin UI Vite dev server on http://localhost:5173
  ${GREEN}ui:build${NC}                   Compile and build React Admin UI production bundle
  ${GREEN}license:format${NC}, ${GREEN}format${NC}     Apply Apache 2.0 license headers across project files
  ${GREEN}clean${NC}                      Clean Maven target directories (pass ${CYAN}--prune${NC} to also prune Docker build cache)
  ${GREEN}help${NC}, ${GREEN}-h${NC}, ${GREEN}--help${NC}           Display this help guide

${BOLD}4. INTEGRATION TEST CLI FLAGS & CACHING (scripts/test-*-decoupled.sh):${NC}
  All 34 decoupled integration test scripts standardize flags and environment variables:
  ${CYAN}-s, --skip-build${NC} / ${CYAN}SKIP_BUILD=true${NC}
                              Skip compose build entirely and reuse existing local images
  ${CYAN}-l, --local, --local-build${NC} / ${CYAN}LOCAL_BUILD=true${NC}
                              Use local Dockerfiles with BuildKit shared ~/.m2 cache mounts
  ${CYAN}-k, --keep, --keep-containers${NC} / ${CYAN}KEEP_CONTAINERS=true${NC}
                              Preserve running containers and volumes on exit/failure for debugging
  ${CYAN}-h, --help${NC}              Display script-specific usage guide

${BOLD}EXAMPLES:${NC}
  ${DIM}# Compile all modules in container using host ~/.m2 cache${NC}
  ./run.sh build

  ${DIM}# Launch decoupled microservices stack with BuildKit ~/.m2 cache mounts${NC}
  ./run.sh decoupled:up

  ${DIM}# Run unit tests for a single module inside oc-maven-builder${NC}
  ./run.sh mvn test -pl oc-runtime

  ${DIM}# Run an integration test with local BuildKit cache mounts (~/.m2)${NC}
  ./scripts/test-iceberg-output-decoupled.sh --local

  ${DIM}# Fast local re-test skipping Docker builds and preserving containers on exit${NC}
  ./scripts/test-iceberg-output-decoupled.sh -s -k

  ${DIM}# Run MCP integration tests using local fast Dockerfiles${NC}
  ./run.sh test:it mcp"
}

# ------------------------------------------------------------------------------
# Command Dispatcher
# ------------------------------------------------------------------------------
COMMAND="${1:-help}"
if [ $# -gt 0 ]; then
  shift
fi

case "$COMMAND" in
  help|-h|--help)
    print_help
    ;;

  # ----------------------------------------------------------------------------
  # 1. Build & Maven Commands
  # ----------------------------------------------------------------------------
  build|mvn:build)
    check_docker
    log_header "Building OpenCrawling via oc-maven-builder (mounting ${HOME}/.m2)..."
    docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn clean package -DskipTests "$@"
    log_ok "Build completed successfully."
    ;;

  build:offline)
    check_docker
    log_header "Running fast offline build via oc-maven-builder (-o -DskipTests)..."
    docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn clean package -o -DskipTests "$@"
    log_ok "Offline build completed successfully."
    ;;

  build:host|build:native)
    check_host_mvn
    log_header "Building OpenCrawling using host Maven..."
    mvn clean package -DskipTests "$@"
    log_ok "Host Maven build completed successfully."
    ;;

  build:clean)
    check_docker
    log_header "Running full clean build with tests via oc-maven-builder..."
    docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn clean package "$@"
    log_ok "Full clean build completed successfully."
    ;;

  mvn)
    check_docker
    if [ $# -eq 0 ]; then
      log_error "Please provide Maven arguments, e.g.: ./run.sh mvn test -pl oc-runtime"
      exit 1
    fi
    log_header "Executing 'mvn $*' inside oc-maven-builder..."
    docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn "$@"
    ;;

  # ----------------------------------------------------------------------------
  # 2. Docker Stack Lifecycle Commands
  # ----------------------------------------------------------------------------
  decoupled:up|decoupled:local)
    check_docker
    log_header "Starting standalone decoupled stack with local BuildKit cache mounts..."
    docker compose -f docker-compose-decoupled-local.yml up -d --build "$@"
    log_ok "Decoupled local stack is up."
    ;;

  decoupled:override)
    check_docker
    log_header "Starting decoupled stack via compose override overlay..."
    docker compose -f docker-compose-decoupled.yml -f docker-compose-decoupled.local.yml up -d --build "$@"
    log_ok "Decoupled stack (override mode) is up."
    ;;

  decoupled:down)
    check_docker
    log_header "Stopping decoupled local stack..."
    docker compose -f docker-compose-decoupled-local.yml down "$@"
    log_ok "Decoupled local stack stopped."
    ;;

  apps:up|apps:local)
    check_docker
    log_header "Starting standalone apps stack (backend, embedding, UI) with local Dockerfiles..."
    docker compose -f docker-compose-apps-local.yml up -d --build "$@"
    log_ok "Apps local stack is up."
    ;;

  apps:override)
    check_docker
    log_header "Starting apps stack via compose override overlay..."
    docker compose -f docker-compose.yml -f docker-compose-apps.yml -f docker-compose-apps.local.yml up -d --build "$@"
    log_ok "Apps stack (override mode) is up."
    ;;

  apps:down)
    check_docker
    log_header "Stopping apps local stack..."
    docker compose -f docker-compose-apps-local.yml down "$@"
    log_ok "Apps local stack stopped."
    ;;

  infra:up)
    check_docker
    log_header "Starting core infrastructure services (PostgreSQL/pgvector, Redis, Ollama, Kafka)..."
    docker compose up -d "$@"
    log_ok "Core infrastructure is up."
    ;;

  infra:down)
    check_docker
    log_header "Stopping core infrastructure services..."
    docker compose down "$@"
    log_ok "Core infrastructure stopped."
    ;;

  down|stop)
    check_docker
    log_header "Stopping all active local OpenCrawling stacks..."
    docker compose -f docker-compose-decoupled-local.yml down --remove-orphans "$@" 2>/dev/null || true
    docker compose -f docker-compose-apps-local.yml down --remove-orphans "$@" 2>/dev/null || true
    docker compose down --remove-orphans "$@" 2>/dev/null || true
    log_ok "All local stacks stopped."
    ;;

  # ----------------------------------------------------------------------------
  # 3. Developer Convenience & Productivity Commands
  # ----------------------------------------------------------------------------
  status|ps)
    check_docker
    log_header "Container Status (Decoupled Local Stack):"
    docker compose -f docker-compose-decoupled-local.yml ps "$@" || true
    log_header "Container Status (Apps Local Stack):"
    docker compose -f docker-compose-apps-local.yml ps "$@" || true
    ;;

  logs)
    check_docker
    log_header "Following container logs..."
    docker compose -f docker-compose-decoupled-local.yml logs -f "$@"
    ;;

  test:unit)
    check_docker
    log_header "Running unit tests via oc-maven-builder..."
    docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn test "$@"
    log_ok "Unit tests completed."
    ;;

  test:it)
    check_docker
    log_header "Running integration tests with local BuildKit cache overlay..."
    COMPOSE_FILE="docker-compose-decoupled.yml:docker-compose-decoupled.local.yml" ./run-integration-tests.sh "$@"
    ;;

  test:local-dev)
    check_docker
    log_header "Running dedicated local Docker build acceleration test suite..."
    ./scripts/test-docker-local-dev.sh "$@"
    ;;

  ui:dev)
    check_node
    log_header "Starting React Admin UI Vite dev server on :5173..."
    cd oc-admin-ui
    if [ ! -d "node_modules" ]; then
      log_info "Installing npm dependencies in oc-admin-ui..."
      npm install
    fi
    npm run dev -- "$@"
    ;;

  ui:build)
    check_node
    log_header "Building React Admin UI production bundle..."
    cd oc-admin-ui
    if [ ! -d "node_modules" ]; then
      log_info "Installing npm dependencies in oc-admin-ui..."
      npm install
    fi
    npm run build "$@"
    log_ok "React Admin UI build completed."
    ;;

  license:format|format)
    log_header "Applying Apache 2.0 license headers..."
    if command -v mvn >/dev/null 2>&1; then
      mvn license:format "$@"
    else
      check_docker
      docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn license:format "$@"
    fi
    log_ok "License headers formatted."
    ;;

  clean)
    PRUNE_CACHE=false
    CLEAN_ARGS=()
    for arg in "$@"; do
      if [ "$arg" = "--prune" ]; then
        PRUNE_CACHE=true
      else
        CLEAN_ARGS+=("$arg")
      fi
    done

    log_header "Cleaning Maven target directories..."
    if command -v mvn >/dev/null 2>&1; then
      if [ ${#CLEAN_ARGS[@]} -gt 0 ]; then
        mvn clean "${CLEAN_ARGS[@]}"
      else
        mvn clean
      fi
    else
      check_docker
      if [ ${#CLEAN_ARGS[@]} -gt 0 ]; then
        docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn clean "${CLEAN_ARGS[@]}"
      else
        docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn clean
      fi
    fi

    if [ "$PRUNE_CACHE" = "true" ]; then
      check_docker
      log_header "Pruning dangling Docker build cache..."
      docker builder prune -f
    fi
    log_ok "Clean completed."
    ;;

  *)
    log_error "Unknown command: '${COMMAND}'"
    printf "\nRun ${BOLD}./run.sh help${NC} for a list of available commands.\n" >&2
    exit 1
    ;;
esac
