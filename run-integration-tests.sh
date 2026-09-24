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

# Master Integration Test Suite Runner for OpenCrawling
# Discovers, executes, monitors, and aggregates results for all integration tests in scripts/
# Compatible with macOS default Bash 3.2+ and Linux environments.

set -o pipefail

# ------------------------------------------------------------------------------
# Terminal Color & Styling Setup
# ------------------------------------------------------------------------------
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  BOLD='\033[1m'
  DIM='\033[2m'
  RED='\033[0;31m'
  GREEN='\033[0;32m'
  YELLOW='\033[1;33m'
  BLUE='\033[0;34m'
  MAGENTA='\033[0;35m'
  CYAN='\033[0;36m'
  WHITE='\033[1;37m'
  BG_RED='\033[41m'
  NC='\033[0m' # No Color
  CHECK_MARK="✔"
  CROSS_MARK="✖"
  ARROW="➜"
  DOT="•"
else
  BOLD=''
  DIM=''
  RED=''
  GREEN=''
  YELLOW=''
  BLUE=''
  MAGENTA=''
  CYAN=''
  WHITE=''
  BG_RED=''
  NC=''
  CHECK_MARK="OK"
  CROSS_MARK="FAIL"
  ARROW="->"
  DOT="*"
fi

# ------------------------------------------------------------------------------
# Script & Working Directory Resolution
# ------------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
SCRIPTS_DIR="${SCRIPT_DIR}/scripts"
DEFAULT_LOGS_DIR="${SCRIPT_DIR}/logs/integration-tests"
LOGS_DIR="${DEFAULT_LOGS_DIR}"

# ------------------------------------------------------------------------------
# Configuration & Flags
# ------------------------------------------------------------------------------
VERBOSE=false
FAIL_FAST=false
TAIL_LINES=40
LIST_ONLY=false
DRY_RUN=false
USER_FILTERS=()
EXPLICIT_TESTS=()

# ------------------------------------------------------------------------------
# Helper: Print Usage
# ------------------------------------------------------------------------------
print_usage() {
  printf "%b\n" "
${BOLD}${CYAN}OpenCrawling Integration Test Runner${NC}
Executes integration test suites in ${BOLD}scripts/${NC}, captures detailed per-test logs,
pinpoints failures, and provides diagnostic context.

${BOLD}USAGE:${NC}
  ./run-integration-tests.sh [OPTIONS] [FILTERS / SCRIPT PATHS...]

${BOLD}OPTIONS:${NC}
  ${CYAN}-h, --help${NC}             Show this help message and exit
  ${CYAN}-l, --list${NC}             List all discovered integration test scripts without executing
  ${CYAN}-v, --verbose${NC}          Stream real-time script output to terminal in addition to saving logs
  ${CYAN}-f, --filter <regex>${NC}   Filter tests whose script filename matches pattern (case-insensitive)
  ${CYAN}--fail-fast${NC}            Stop immediately upon encountering the first failed test
  ${CYAN}--tail <N>${NC}             Number of log lines to show upon test failure (default: 40)
  ${CYAN}--logs-dir <DIR>${NC}       Directory to store test execution logs (default: logs/integration-tests)
  ${CYAN}--dry-run${NC}              Display the ordered list of tests that would be executed

${BOLD}POSITIONAL ARGUMENTS:${NC}
  Any positional argument can be:
  1. A file path to a specific test script (e.g., ${CYAN}scripts/test-cli.sh${NC} or ${CYAN}test-alfresco-decoupled.sh${NC})
  2. A filter keyword matching tests (e.g., ${CYAN}alfresco${NC}, ${CYAN}decoupled${NC}, ${CYAN}grpc${NC})

${BOLD}EXAMPLES:${NC}
  ${DIM}# Run all integration tests${NC}
  ./run-integration-tests.sh

  ${DIM}# Run only Alfresco tests (matching test-alfresco-*.sh)${NC}
  ./run-integration-tests.sh alfresco

  ${DIM}# Run a single specific test with live output streaming${NC}
  ./run-integration-tests.sh -v scripts/test-alfresco-decoupled.sh

  ${DIM}# Run CLI and gRPC tests and stop immediately if any fails${NC}
  ./run-integration-tests.sh --fail-fast cli grpc

  ${DIM}# List all available integration test suites${NC}
  ./run-integration-tests.sh --list
"
}

# ------------------------------------------------------------------------------
# Helper: Duration Formatter
# ------------------------------------------------------------------------------
format_duration() {
  local total_secs=${1:-0}
  if [ "$total_secs" -lt 60 ]; then
    printf "%ds" "$total_secs"
  elif [ "$total_secs" -lt 3600 ]; then
    local mins=$((total_secs / 60))
    local secs=$((total_secs % 60))
    printf "%dm %ds" "$mins" "$secs"
  else
    local hours=$((total_secs / 3600))
    local remainder=$((total_secs % 3600))
    local mins=$((remainder / 60))
    local secs=$((remainder % 60))
    printf "%dh %dm %ds" "$hours" "$mins" "$secs"
  fi
}

# ------------------------------------------------------------------------------
# Argument Parsing
# ------------------------------------------------------------------------------
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help)
      print_usage
      exit 0
      ;;
    -l|--list)
      LIST_ONLY=true
      shift
      ;;
    -v|--verbose)
      VERBOSE=true
      shift
      ;;
    --fail-fast)
      FAIL_FAST=true
      shift
      ;;
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    --tail)
      if [ -n "${2:-}" ] && [ "$2" -eq "$2" ] 2>/dev/null; then
        TAIL_LINES="$2"
        shift 2
      else
        echo -e "${RED}[ERROR] --tail requires an integer argument.${NC}" >&2
        exit 1
      fi
      ;;
    --logs-dir)
      if [ -n "${2:-}" ]; then
        LOGS_DIR="$2"
        shift 2
      else
        echo -e "${RED}[ERROR] --logs-dir requires a directory path.${NC}" >&2
        exit 1
      fi
      ;;
    -f|--filter)
      if [ -n "${2:-}" ]; then
        USER_FILTERS+=("$2")
        shift 2
      else
        echo -e "${RED}[ERROR] -f/--filter requires a search pattern.${NC}" >&2
        exit 1
      fi
      ;;
    -*)
      echo -e "${RED}[ERROR] Unknown option: $1${NC}" >&2
      print_usage
      exit 1
      ;;
    *)
      # Check if positional argument is an existing script or path
      POS_VAL="$1"
      if [ -f "$POS_VAL" ]; then
        EXPLICIT_TESTS+=("$POS_VAL")
      elif [ -f "${SCRIPTS_DIR}/${POS_VAL}" ]; then
        EXPLICIT_TESTS+=("${SCRIPTS_DIR}/${POS_VAL}")
      elif [ -f "${SCRIPTS_DIR}/${POS_VAL}.sh" ]; then
        EXPLICIT_TESTS+=("${SCRIPTS_DIR}/${POS_VAL}.sh")
      else
        # Treat as filter substring
        USER_FILTERS+=("$POS_VAL")
      fi
      shift
      ;;
  esac
done

# ------------------------------------------------------------------------------
# Test Discovery & Selection
# ------------------------------------------------------------------------------
ALL_DISCOVERED=()
if [ -d "$SCRIPTS_DIR" ]; then
  while IFS= read -r script_file; do
    if [ -n "$script_file" ]; then
      rel_path="${script_file#${SCRIPT_DIR}/}"
      ALL_DISCOVERED+=("$rel_path")
    fi
  done < <(find "$SCRIPTS_DIR" -maxdepth 1 -name "test-*.sh" -type f | sort)
fi

if [ "${#ALL_DISCOVERED[@]}" -eq 0 ]; then
  echo -e "${RED}[ERROR] No integration test scripts found in ${SCRIPTS_DIR}${NC}" >&2
  exit 1
fi

SELECTED_TESTS=()

if [ "${#EXPLICIT_TESTS[@]}" -gt 0 ]; then
  # Use explicitly specified tests
  for exp in ${EXPLICIT_TESTS[@]+"${EXPLICIT_TESTS[@]}"}; do
    # Canonicalize path relative to project root
    clean_path="${exp#${SCRIPT_DIR}/}"
    clean_path="${clean_path#./}"
    if [ -f "$clean_path" ]; then
      SELECTED_TESTS+=("$clean_path")
    else
      echo -e "${RED}[ERROR] Specified test script not found: ${exp}${NC}" >&2
      exit 1
    fi
  done
elif [ "${#USER_FILTERS[@]}" -gt 0 ]; then
  # Filter discovered tests matching any provided filter pattern
  for script_path in ${ALL_DISCOVERED[@]+"${ALL_DISCOVERED[@]}"}; do
    script_base="$(basename "$script_path")"
    matched=false
    for flt in ${USER_FILTERS[@]+"${USER_FILTERS[@]}"}; do
      if echo "$script_base" | grep -i -E "$flt" >/dev/null 2>&1; then
        matched=true
        break
      fi
    done
    if [ "$matched" = true ]; then
      SELECTED_TESTS+=("${script_path#./}")
    fi
  done
else
  # Default: run all discovered tests
  for script_path in ${ALL_DISCOVERED[@]+"${ALL_DISCOVERED[@]}"}; do
    SELECTED_TESTS+=("${script_path#./}")
  done
fi

# ------------------------------------------------------------------------------
# Handle --list and --dry-run
# ------------------------------------------------------------------------------
if [ "$LIST_ONLY" = true ] || [ "$DRY_RUN" = true ]; then
  echo -e "${BOLD}${CYAN}================================================================================${NC}"
  if [ "$LIST_ONLY" = true ]; then
    echo -e "${BOLD}${CYAN}   OpenCrawling Discovered Integration Test Suites (${#ALL_DISCOVERED[@]} available)${NC}"
  else
    echo -e "${BOLD}${CYAN}   OpenCrawling Integration Tests To Execute (${#SELECTED_TESTS[@]} selected)${NC}"
  fi
  echo -e "${BOLD}${CYAN}================================================================================${NC}"

  LIST_TARGETS=()
  if [ "$LIST_ONLY" = true ]; then
    LIST_TARGETS=("${ALL_DISCOVERED[@]}")
  else
    LIST_TARGETS=("${SELECTED_TESTS[@]}")
  fi

  idx=1
  for st in ${LIST_TARGETS[@]+"${LIST_TARGETS[@]}"}; do
    base_name="$(basename "$st")"
    line_count=$(wc -l < "$st" 2>/dev/null | tr -d ' ' || echo "N/A")
    printf "  ${BOLD}%2d.${NC} %-36s ${DIM}(lines: %4s, path: %s)${NC}\n" "$idx" "$base_name" "$line_count" "$st"
    idx=$((idx + 1))
  done

  echo -e "${BOLD}${CYAN}================================================================================${NC}"
  if [ "$DRY_RUN" = true ]; then
    echo -e "${YELLOW}Dry-run mode: No tests were executed.${NC}"
  fi
  exit 0
fi

# Check if selection is empty
if [ "${#SELECTED_TESTS[@]}" -eq 0 ]; then
  echo -e "${YELLOW}[WARN] No integration tests matched your filters: [${USER_FILTERS[*]}]${NC}"
  echo -e "${YELLOW}Available tests:${NC}"
  for st in ${ALL_DISCOVERED[@]+"${ALL_DISCOVERED[@]}"}; do
    echo "  - $(basename "$st")"
  done
  exit 1
fi

# ------------------------------------------------------------------------------
# Prepare Logging Directory
# ------------------------------------------------------------------------------
mkdir -p "$LOGS_DIR"
GLOBAL_RUN_START=$(date +%s)

# ------------------------------------------------------------------------------
# Interruption / Signal Handling
# ------------------------------------------------------------------------------
INTERRUPTED=0
CURRENT_CHILD_PID=""

cleanup_on_interrupt() {
  INTERRUPTED=1
  echo ""
  echo -e "${YELLOW}[WARN] Test run interrupted by user (SIGINT/SIGTERM). Stopping active execution...${NC}"
  if [ -n "${CURRENT_CHILD_PID:-}" ] && kill -0 "$CURRENT_CHILD_PID" 2>/dev/null; then
    kill -TERM "$CURRENT_CHILD_PID" 2>/dev/null || true
    sleep 1
    kill -KILL "$CURRENT_CHILD_PID" 2>/dev/null || true
  fi
}
trap cleanup_on_interrupt INT TERM

# ------------------------------------------------------------------------------
# Tracking State Arrays (Bash 3.2 Compatible Indexed Arrays)
# ------------------------------------------------------------------------------
RUN_SCRIPTS=()
RUN_BASENAMES=()
RUN_STATUSES=()
RUN_EXIT_CODES=()
RUN_DURATIONS=()
RUN_LOG_FILES=()

TOTAL_SELECTED=${#SELECTED_TESTS[@]}
PASSED_COUNT=0
FAILED_COUNT=0
SKIPPED_COUNT=0

# ------------------------------------------------------------------------------
# Banner
# ------------------------------------------------------------------------------
echo -e "${BOLD}${CYAN}================================================================================${NC}"
echo -e "${BOLD}${WHITE}   OpenCrawling Integration Test Suite Execution${NC}"
echo -e "${BOLD}${CYAN}================================================================================${NC}"
echo -e "  ${BOLD}Total Tests Selected:${NC} ${TOTAL_SELECTED}"
echo -e "  ${BOLD}Logs Directory:${NC}       ${LOGS_DIR}"
echo -e "  ${BOLD}Fail-Fast:${NC}            ${FAIL_FAST}"
echo -e "  ${BOLD}Verbose Streaming:${NC}    ${VERBOSE}"
echo -e "${BOLD}${CYAN}================================================================================${NC}\n"

# ------------------------------------------------------------------------------
# Helper: Extract Key Error Highlights from Log File
# ------------------------------------------------------------------------------
extract_error_highlights() {
  local log_file="$1"
  local max_items=12
  if [ -f "$log_file" ]; then
    grep -E -i "(\[ERROR\]|FATAL:|Exception:|AssertionError|unbound variable|command not found|BUILD FAILURE|No such file|Connection refused|timed out|TimeoutException|Check failed|failed with exit code)" "$log_file" \
      | grep -v "DEBUG" \
      | tail -n "$max_items" || true
  fi
}

# ------------------------------------------------------------------------------
# Main Test Execution Loop
# ------------------------------------------------------------------------------
current_idx=0

for test_path in ${SELECTED_TESTS[@]+"${SELECTED_TESTS[@]}"}; do
  current_idx=$((current_idx + 1))
  test_base="$(basename "$test_path")"
  test_name_no_ext="${test_base%.sh}"
  test_log="${LOGS_DIR}/${test_name_no_ext}.log"

  RUN_SCRIPTS+=("$test_path")
  RUN_BASENAMES+=("$test_base")
  RUN_LOG_FILES+=("$test_log")

  # If previously interrupted or aborted by fail-fast
  if [ "$INTERRUPTED" -eq 1 ]; then
    RUN_STATUSES+=("SKIPPED")
    RUN_EXIT_CODES+=("-")
    RUN_DURATIONS+=("0")
    SKIPPED_COUNT=$((SKIPPED_COUNT + 1))
    continue
  fi

  # Ensure log directory exists (even if deleted by a maven clean during a previous test)
  mkdir -p "$(dirname "$test_log")"
  > "$test_log"

  echo -e "${BOLD}${BLUE}────────────────────────────────────────────────────────────────────────────────${NC}"
  echo -e "${BOLD}${WHITE}[${current_idx}/${TOTAL_SELECTED}] RUNNING:${NC} ${CYAN}${test_path}${NC}"
  echo -e "  ${DIM}Log: ${test_log}${NC}"
  echo -e "${BOLD}${BLUE}────────────────────────────────────────────────────────────────────────────────${NC}"

  # Make sure script is executable
  chmod +x "$test_path" 2>/dev/null || true

  test_start=$(date +%s)
  test_exit_code=0

  if [ "$VERBOSE" = true ]; then
    # Stream live to stdout and write to log file simultaneously
    bash "$test_path" 2>&1 | tee "$test_log"
    test_exit_code=${PIPESTATUS[0]}
  else
    # Run in background and redirect output to log
    bash "$test_path" > "$test_log" 2>&1 &
    CURRENT_CHILD_PID=$!

    # Active status indicator
    if [ -t 1 ]; then
      spin_chars="/-\\|"
      spin_idx=0
      while kill -0 "$CURRENT_CHILD_PID" 2>/dev/null; do
        now=$(date +%s)
        elapsed=$((now - test_start))
        spin_char="${spin_chars:$spin_idx:1}"
        spin_idx=$(( (spin_idx + 1) % 4 ))
        printf "\r  ${YELLOW}%s${NC} Running ${BOLD}%s${NC} (Elapsed: %s)... " "$spin_char" "$test_base" "$(format_duration $elapsed)"
        sleep 1
      done
      # Clear status line
      printf "\r\033[K"
    else
      # Non-interactive mode (e.g., CI)
      while kill -0 "$CURRENT_CHILD_PID" 2>/dev/null; do
        sleep 2
      done
    fi

    wait "$CURRENT_CHILD_PID" 2>/dev/null
    test_exit_code=$?
    CURRENT_CHILD_PID=""
  fi

  test_end=$(date +%s)
  test_duration=$((test_end - test_start))
  RUN_DURATIONS+=("$test_duration")

  # Evaluate result
  if [ "$INTERRUPTED" -eq 1 ]; then
    RUN_STATUSES+=("INTERRUPTED")
    RUN_EXIT_CODES+=("130")
    FAILED_COUNT=$((FAILED_COUNT + 1))
    echo -e "${BOLD}${YELLOW}  ${ARROW} [INTERRUPTED] ${test_base} (Duration: $(format_duration $test_duration))${NC}\n"
    break
  elif [ "$test_exit_code" -eq 0 ]; then
    RUN_STATUSES+=("PASSED")
    RUN_EXIT_CODES+=("0")
    PASSED_COUNT=$((PASSED_COUNT + 1))
    echo -e "${BOLD}${GREEN}  ${CHECK_MARK} [PASSED] ${test_base} (Duration: $(format_duration $test_duration))${NC}\n"
  else
    RUN_STATUSES+=("FAILED")
    RUN_EXIT_CODES+=("$test_exit_code")
    FAILED_COUNT=$((FAILED_COUNT + 1))

    echo -e "${BOLD}${RED}  ${CROSS_MARK} [FAILED] ${test_base} (Exit code: ${test_exit_code}, Duration: $(format_duration $test_duration))${NC}"

    # --------------------------------------------------------------------------
    # PROBLEM DIAGNOSTICS SECTION
    # --------------------------------------------------------------------------
    echo -e "\n${BOLD}${RED}╔════════════════════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BOLD}${RED}║ FAILURE DIAGNOSTICS: ${test_base}${NC}"
    echo -e "${BOLD}${RED}╚════════════════════════════════════════════════════════════════════════════════╝${NC}"

    # 1. Error Highlights Extraction
    HIGHLIGHTS=$(extract_error_highlights "$test_log")
    if [ -n "$HIGHLIGHTS" ]; then
      echo -e "${BOLD}${YELLOW}>>> Key Error Highlights Detected in Log:${NC}"
      while IFS= read -r line; do
        echo -e "  ${RED}${DOT}${NC} ${line}"
      done <<< "$HIGHLIGHTS"
      echo ""
    fi

    # 2. Tail of Log Output
    if [ -f "$test_log" ]; then
      echo -e "${BOLD}${YELLOW}>>> Last ${TAIL_LINES} lines of ${test_base} output:${NC}"
      echo -e "${DIM}────────────────────────────────────────────────────────────────────────────────${NC}"
      tail -n "$TAIL_LINES" "$test_log" | sed 's/^/  /'
      echo -e "${DIM}────────────────────────────────────────────────────────────────────────────────${NC}"
    fi

    # 3. Log location & Fast Re-run command
    echo -e "${BOLD}${WHITE}>>> Full Log File:${NC}"
    echo -e "  ${CYAN}file://${test_log}${NC}"
    echo -e "${BOLD}${WHITE}>>> Re-run only this test:${NC}"
    echo -e "  ${GREEN}./run-integration-tests.sh ${test_path}${NC}"
    echo -e "  ${DIM}or:${NC} ${GREEN}./${test_path#./}${NC}\n"

    # Fail fast handling
    if [ "$FAIL_FAST" = true ]; then
      echo -e "${BOLD}${YELLOW}[NOTICE] --fail-fast flag is set. Aborting remaining test executions.${NC}\n"
      # Mark all remaining tests as SKIPPED
      remaining_start=$((current_idx + 1))
      if [ "$remaining_start" -le "$TOTAL_SELECTED" ]; then
        for rem_idx in $(seq "$remaining_start" "$TOTAL_SELECTED"); do
          rem_test="${SELECTED_TESTS[$((rem_idx - 1))]}"
          rem_base="$(basename "$rem_test")"
          RUN_SCRIPTS+=("$rem_test")
          RUN_BASENAMES+=("$rem_base")
          RUN_LOG_FILES+=("${LOGS_DIR}/${rem_base%.sh}.log")
          RUN_STATUSES+=("SKIPPED")
          RUN_EXIT_CODES+=("-")
          RUN_DURATIONS+=("0")
          SKIPPED_COUNT=$((SKIPPED_COUNT + 1))
        done
      fi
      break
    fi
  fi
done

# ------------------------------------------------------------------------------
# Final Summary Report
# ------------------------------------------------------------------------------
GLOBAL_RUN_END=$(date +%s)
TOTAL_ELAPSED=$((GLOBAL_RUN_END - GLOBAL_RUN_START))

echo -e "\n${BOLD}${CYAN}==============================================================================================================${NC}"
echo -e "${BOLD}${WHITE}                                   INTEGRATION TESTS SUMMARY REPORT${NC}"
echo -e "${BOLD}${CYAN}==============================================================================================================${NC}"
printf "${BOLD}%-4s %-36s %-12s %-10s %-40s${NC}\n" " # " "TEST SCRIPT" "STATUS" "DURATION" "LOG FILE"
echo -e "${DIM}--------------------------------------------------------------------------------------------------------------${NC}"

total_recorded=${#RUN_SCRIPTS[@]}
for ((i=0; i<total_recorded; i++)); do
  t_idx=$((i + 1))
  t_name="${RUN_BASENAMES[$i]}"
  t_status="${RUN_STATUSES[$i]}"
  t_dur="${RUN_DURATIONS[$i]}"
  t_log="${RUN_LOG_FILES[$i]}"

  dur_str=$(format_duration "$t_dur")
  rel_log="${t_log#${SCRIPT_DIR}/}"

  status_color="${GREEN}"
  if [ "$t_status" = "FAILED" ] || [ "$t_status" = "INTERRUPTED" ]; then
    status_color="${RED}"
  elif [ "$t_status" = "SKIPPED" ]; then
    status_color="${YELLOW}"
  fi

  printf " %2d. %-36s ${status_color}%-12s${NC} %-10s %-40s\n" "$t_idx" "$t_name" "$t_status" "$dur_str" "$rel_log"
done

echo -e "${BOLD}${CYAN}==============================================================================================================${NC}"
echo -e " ${BOLD}TOTAL:${NC} ${TOTAL_SELECTED} | ${GREEN}${BOLD}PASSED:${NC} ${PASSED_COUNT} | ${RED}${BOLD}FAILED:${NC} ${FAILED_COUNT} | ${YELLOW}${BOLD}SKIPPED:${NC} ${SKIPPED_COUNT} | ${BOLD}TIME:${NC} $(format_duration "$TOTAL_ELAPSED")"
echo -e "${BOLD}${CYAN}==============================================================================================================${NC}"

# ------------------------------------------------------------------------------
# Actionable Failure Summary
# ------------------------------------------------------------------------------
if [ "$FAILED_COUNT" -gt 0 ]; then
  echo -e "\n${BOLD}${RED}==============================================================================================================${NC}"
  echo -e "${BOLD}${RED} ACTION REQUIRED: FAILED TEST SUITES (${FAILED_COUNT})${NC}"
  echo -e "${BOLD}${RED}==============================================================================================================${NC}"
  for ((i=0; i<total_recorded; i++)); do
    if [ "${RUN_STATUSES[$i]}" = "FAILED" ] || [ "${RUN_STATUSES[$i]}" = "INTERRUPTED" ]; then
      f_script="${RUN_SCRIPTS[$i]}"
      f_code="${RUN_EXIT_CODES[$i]}"
      f_dur=$(format_duration "${RUN_DURATIONS[$i]}")
      f_log="${RUN_LOG_FILES[$i]}"
      echo -e "  ${RED}${CROSS_MARK}${NC} ${BOLD}${f_script}${NC} ${DIM}(Exit code: ${f_code}, Duration: ${f_dur})${NC}"
      echo -e "     ${BOLD}Log:${NC}      ${CYAN}file://${f_log}${NC}"
      echo -e "     ${BOLD}Re-run:${NC}   ${GREEN}./run-integration-tests.sh ${f_script}${NC}"
      echo -e "     ${DIM}Direct:${NC}   ${GREEN}./${f_script#./}${NC}"
      echo ""
    fi
  done
  echo -e "${BOLD}${RED}==============================================================================================================${NC}\n"
  
  if [ "$INTERRUPTED" -eq 1 ]; then
    exit 130
  fi
  exit 1
fi

echo -e "\n${BOLD}${GREEN}✔ All executed integration tests completed successfully!${NC}\n"
exit 0
