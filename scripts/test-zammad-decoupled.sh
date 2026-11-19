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
# OpenCrawling - Zammad Decoupled Pipeline Integration Test Script
#
# Description:
#   End-to-end test of the decoupled OpenCrawling pipeline against a REAL
#   Zammad Helpdesk & Knowledge Base instance (ghcr.io/zammad/zammad +
#   Elasticsearch + PostgreSQL + Redis + Memcached, derived from
#   https://github.com/zammad/zammad-docker-compose).
#
# Pipeline Lifecycle Tested:
#   1. Prerequisites verification (docker, docker compose, curl, jq)
#   2. Clean state & port checks for the Zammad decoupled stack
#   3. Build OpenCrawling decoupled microservice images
#   4. Start real Zammad stack (PostgreSQL, Redis, Memcached, Elasticsearch,
#      zammad-init, zammad-railsserver) and OpenCrawling pipeline (pgvector,
#      Redis, Ollama, Kafka, consumers, MCP server)
#   5. Seed real Zammad groups, roles, shared vs. non-shared organizations,
#      users, API token, tickets (public replies, internal agent notes, binary
#      attachments, tags), and Knowledge Base (published, internal, archived)
#   6. Initial oc-crawler scan -> Kafka -> Tika -> Ollama -> pgvector:
#      - exact document count (public threads, internal notes, attachments, KB)
#      - archived KB answer skipped
#      - structured zammad.* metadata & strict public vs. internal ACL isolation
#      - shared vs. non-shared organization ACLs & KB category role ACLs
#   7. MCP Server Zero-Trust ACL enforcement (secureVectorSearch,
#      getDocumentContent, listAccessibleSources) across Group Agent, Customer,
#      Shared Org Teammate, KB Security Auditor, and Outsider
#   8. Real Zammad mutations via REST API v1 + Rails + incremental search_delta
#      (Elasticsearch updated_at:[... TO *]) + full reconciliation tombstones
#   9. Summary & clean environment teardown
# ==============================================================================

set -euo pipefail

COMPOSE_FILE="oc-zammad-repository-connector/docker/docker-compose-decoupled-with-zammad.yml"
LOCAL_OVERLAY="${LOCAL_OVERLAY:-${COMPOSE_FILE%.yml}.local.yml}"
ZAMMAD_PORT="${ZAMMAD_PORT:-8094}"
ZAMMAD_HOST_URL="${ZAMMAD_HOST_URL:-http://localhost:${ZAMMAD_PORT}}"
MCP_PORT="${MCP_PORT:-8080}"
TIMEOUT="${TIMEOUT:-360}"
INGEST_TIMEOUT="${INGEST_TIMEOUT:-300}"
KEEP_CONTAINERS="${KEEP_CONTAINERS:-false}"
SKIP_BUILD="${SKIP_BUILD:-false}"
LOCAL_BUILD="${LOCAL_BUILD:-false}"

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
  ZAMMAD_PORT=8094        Host port mapped to zammad-railsserver:3000
  MCP_PORT=8080           Host port mapped to oc-mcp-server:8080
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

PG="postgres-vector-decoupled-zammad"
ZAMMAD_RAILS="zammad-railsserver-decoupled-zammad"
ZAMMAD_INIT="zammad-init-decoupled-zammad"
INSTANCE_KEY="zammad-railsserver:3000"
ZAMMAD_TOKEN=""

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

PASSED_COUNT=0
TOTAL_STEPS=9

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

log_step() {
  echo -e "\n${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
  echo -e "${BOLD}${CYAN}[STEP $1/$TOTAL_STEPS] $2${NC}"
  echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
}

log_pass() {
  PASSED_COUNT=$((PASSED_COUNT + 1))
  echo -e "${BOLD}${GREEN}[PASS $PASSED_COUNT/$TOTAL_STEPS] $1${NC}"
}

echo -e "\n${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${YELLOW}=== OpenCrawling Decoupled Pipeline with Real Zammad (7.2 + ES) Integration Test ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}\n"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

compose() {
  if [ "${LOCAL_BUILD}" = true ] && [ -f "${LOCAL_OVERLAY}" ]; then
    docker compose -f "${COMPOSE_FILE}" -f "${LOCAL_OVERLAY}" "$@"
  else
    docker compose -f "${COMPOSE_FILE}" "$@"
  fi
}

# psql helper: one value, whitespace stripped; a SQL error is reported instead of ending the script silently
sql() {
  local out
  if ! out=$(docker exec -i "${PG}" psql -v ON_ERROR_STOP=1 -U opencrawling -d opencrawling -t -A -P pager=off -c "$1" 2>&1); then
    log_error "SQL failed: ${out}" >&2
    echo "SQL-ERROR"
    return 0
  fi
  echo "$out" | tr -d '[:space:]'
}

zammad_api() { # <method> <path> [curl args...]
  local method="$1" path="$2"
  shift 2
  curl -sf -X "${method}" \
    -H "Authorization: Token token=${ZAMMAD_TOKEN}" \
    -H "Accept: application/json" \
    "$@" "${ZAMMAD_HOST_URL}${path}"
}

zammad_uri() {
  echo "zammad://${INSTANCE_KEY}$1"
}

run_crawler() {
  local mode="${1:-search_delta}"
  log_info "Running oc-crawler against real Zammad (${INSTANCE_KEY}, crawlMode=${mode})..."
  ZAMMAD_ACCESS_TOKEN="${ZAMMAD_TOKEN}" ZAMMAD_CRAWL_MODE="${mode}" \
    compose up -d --no-deps --force-recreate oc-crawler >/dev/null
  local elapsed=0
  until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-zammad 2>/dev/null || echo 'false')" == "false" ]; do
    if [ $elapsed -ge "$TIMEOUT" ]; then
      log_error "Timeout waiting for oc-crawler-service-zammad."
      compose logs oc-crawler --tail 60
      exit 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  local code
  code=$(docker inspect -f '{{.State.ExitCode}}' oc-crawler-service-zammad 2>/dev/null || echo 1)
  if [ "$code" -ne 0 ]; then
    log_error "oc-crawler-service-zammad exited with ${code}."
    compose logs oc-crawler --tail 80
    exit 1
  fi
  SCAN_SUMMARY=$(docker logs oc-crawler-service-zammad 2>&1 | grep "Zammad scan of" | tail -1 || true)
  log_success "Crawl finished: ${SCAN_SUMMARY#*-- }"
  echo "${SCAN_SUMMARY}" | grep -q " 0 failed" || { log_error "The crawl reported failures: ${SCAN_SUMMARY}"; exit 1; }
}

# waits until a SQL query's number satisfies a test ("-gt 0", "-eq 0", ...)
wait_sql() {
  local description="$1" query="$2" test="$3" elapsed=0 count
  while true; do
    count=$(sql "$query")
    count=${count:-0}
    case "$count" in
      ''|*[!0-9]*) log_error "${description}: the SQL query failed (${count})."; exit 1 ;;
    esac
    # shellcheck disable=SC2086
    if [ "$count" $test ]; then
      log_success "${description} (${count})."
      return 0
    fi
    if [ $elapsed -ge "$INGEST_TIMEOUT" ]; then
      log_error "${description}: not reached after ${INGEST_TIMEOUT}s (${count})."
      compose logs oc-ingestion-consumer --tail 30 || true
      compose logs oc-embedding-consumer --tail 30 || true
      compose logs oc-writer-consumer --tail 30 || true
      exit 1
    fi
    sleep 3
    elapsed=$((elapsed + 3))
  done
}

# waits until the number of chunks matching a condition satisfies a test
wait_for() {
  wait_sql "$1" "SELECT CASE WHEN to_regclass('public.vector_store_1024') IS NULL THEN 0 ELSE
    (SELECT count(*) FROM vector_store_1024 WHERE $2) END;" "$3"
}

cleanup() {
  local exit_code=$?
  if [ "$exit_code" -ne 0 ]; then
    log_error "Test execution interrupted or failed with exit code $exit_code."
  fi
  if [ "${KEEP_CONTAINERS}" = false ]; then
    log_info "Tearing down the decoupled Zammad Docker Compose environment..."
    compose down --remove-orphans >/dev/null 2>&1 || true
    log_success "Environment cleanup complete."
  else
    log_warn "KEEP_CONTAINERS=true set. Skipping docker compose teardown."
  fi
}
trap cleanup EXIT

# ------------------------------------------------------------------------------
# STEP 1: Prerequisites
# ------------------------------------------------------------------------------
log_step 1 "Verifying system prerequisites and dependencies..."
command -v docker >/dev/null 2>&1 || { log_error "docker is required but not installed."; exit 1; }
docker compose version >/dev/null 2>&1 || { log_error "docker compose is required."; exit 1; }
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required but not installed."; exit 1; }
log_pass "Prerequisites verified successfully."

# ------------------------------------------------------------------------------
# STEP 2: Clean state
# ------------------------------------------------------------------------------
log_step 2 "Removing previous Zammad stack containers and its own volumes..."
compose down --remove-orphans >/dev/null 2>&1 || true
# only this stack's volumes: the shared Ollama model volume is kept
for v in opencrawling-zammad-pgdata opencrawling-zammad-redisdata opencrawling-zammad-esdata opencrawling-zammad-storage \
         opencrawling-pgdata-zammad opencrawling-redisdata-zammad opencrawling-kafkadata-zammad \
         opencrawling-claims-zammad opencrawling-reportsdata-zammad opencrawling-state-zammad; do
  docker volume rm "$v" >/dev/null 2>&1 || true
done
export MCP_PORT ZAMMAD_PORT
for p in "${MCP_PORT}" "${ZAMMAD_PORT}" 9092 5432 6379 11434; do
  c_names=$(docker ps --filter "publish=$p" --format '{{.Names}}' 2>/dev/null | tr '\n' ' ' || true)
  if [ -n "${c_names// /}" ]; then
    log_error "Port ${p} is in use by ${c_names}; stop that stack first (or set MCP_PORT/ZAMMAD_PORT)."
    exit 1
  fi
done
log_pass "Previous state removed."

# ------------------------------------------------------------------------------
# STEP 3: Build OpenCrawling images
# ------------------------------------------------------------------------------
log_step 3 "Building the OpenCrawling decoupled microservice images (LOCAL_BUILD=${LOCAL_BUILD})..."
if [ "${SKIP_BUILD}" = true ]; then
  log_warn "SKIP_BUILD=true: using existing images."
else
  compose build oc-crawler oc-ingestion-consumer oc-embedding-consumer oc-writer-consumer oc-mcp-server
fi
log_pass "Images ready."

# ------------------------------------------------------------------------------
# STEP 4: Start Real Zammad + OpenCrawling Infrastructure & Consumers
# ------------------------------------------------------------------------------
log_step 4 "Starting real Zammad stack (PostgreSQL, Redis, Memcached, Elasticsearch, Rails) and OpenCrawling pipeline..."
compose up -d zammad-elasticsearch zammad-memcached zammad-redis zammad-postgresql zammad-init zammad-railsserver \
  postgres-vector redis ollama ollama-model-puller kafka \
  oc-ingestion-consumer oc-embedding-consumer oc-writer-consumer oc-mcp-server

wait_healthy() {
  local container="$1" elapsed=0
  until [ "$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null || echo starting)" == "healthy" ]; do
    if [ $elapsed -ge "$TIMEOUT" ]; then
      log_error "Timeout waiting for ${container}."
      docker logs "$container" --tail 40 || true
      exit 1
    fi
    sleep 3
    elapsed=$((elapsed + 3))
  done
  log_success "${container} is healthy."
}

wait_exited_zero() {
  local container="$1" elapsed=0
  log_info "Waiting for ${container} to finish..."
  until [ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null || echo false)" == "false" ]; do
    if [ $elapsed -ge "$TIMEOUT" ]; then
      log_error "Timeout waiting for ${container}."
      docker logs "$container" --tail 40 || true
      exit 1
    fi
    sleep 3
    elapsed=$((elapsed + 3))
  done
  local code
  code=$(docker inspect -f '{{.State.ExitCode}}' "$container" 2>/dev/null || echo 1)
  if [ "$code" -ne 0 ]; then
    log_error "${container} failed with exit code ${code}."
    docker logs "$container" --tail 50 || true
    exit 1
  fi
  log_success "${container} completed cleanly."
}

wait_healthy "${PG}"
wait_healthy redis-stack-decoupled-zammad
wait_healthy ollama-decoupled-zammad
wait_exited_zero ollama-model-puller-decoupled-zammad
wait_healthy zammad-elasticsearch-decoupled-zammad
wait_exited_zero "${ZAMMAD_INIT}"
wait_healthy "${ZAMMAD_RAILS}"
log_pass "Real Zammad and OpenCrawling decoupled infrastructure are healthy."

# ------------------------------------------------------------------------------
# STEP 5: Seed Real Zammad Dataset (Groups, Roles, Orgs, Tickets, Attachments, KB)
# ------------------------------------------------------------------------------
log_step 5 "Seeding real Zammad 7.2 instance with tickets, internal notes, attachments, tags, and Knowledge Base..."
SEED_OUT=$(docker exec -i "${ZAMMAD_RAILS}" bundle exec rails r - <<'RUBY'
Setting.set('api_token_access', true)

# Remove default welcome ticket so the seeded test dataset is deterministic
Ticket.destroy_all

admin_role = Role.find_by!(name: 'Admin')
agent_role = Role.find_by!(name: 'Agent')
customer_role = Role.find_by!(name: 'Customer')
kb_reader_perm = Permission.find_by!(name: 'knowledge_base.reader')
kb_editor_perm = Permission.find_by!(name: 'knowledge_base.editor')

agent_role.permissions << kb_editor_perm unless agent_role.permissions.include?(kb_editor_perm)

auditor_role = Role.find_or_create_by!(name: 'Security-Auditor') do |r|
  r.active = true; r.created_by_id = 1; r.updated_by_id = 1
end
auditor_role.permissions << kb_reader_perm unless auditor_role.permissions.include?(kb_reader_perm)

tier2 = Group.find_or_create_by!(name: 'Tier-2-Identity-Security') do |g|
  g.active = true; g.created_by_id = 1; g.updated_by_id = 1
end
billing = Group.find_or_create_by!(name: 'Billing-Operations') do |g|
  g.active = true; g.created_by_id = 1; g.updated_by_id = 1
end

[admin_role, agent_role].each do |r|
  [tier2, billing].each do |g|
    RoleGroup.find_or_create_by!(role_id: r.id, group_id: g.id, access: 'full')
  end
end

org_shared = Organization.find_or_create_by!(name: 'ClientCorp Europe') do |o|
  o.shared = true; o.active = true; o.created_by_id = 1; o.updated_by_id = 1
end
org_private = Organization.find_or_create_by!(name: 'PrivateBank AG') do |o|
  o.shared = false; o.active = true; o.created_by_id = 1; o.updated_by_id = 1
end

crawler = User.find_or_create_by!(email: 'crawler@acme.com') do |u|
  u.login = 'crawler@acme.com'; u.firstname = 'OpenCrawling'; u.lastname = 'Crawler'
  u.password = 'OpenCrawling123!'; u.active = true; u.verified = true
  u.roles = [admin_role, agent_role]; u.created_by_id = 1; u.updated_by_id = 1
end
[tier2, billing].each do |g|
  UserGroup.find_or_create_by!(user_id: crawler.id, group_id: g.id, access: 'full')
end

claire = User.find_or_create_by!(email: 'claire.dupont@clientcorp.eu') do |u|
  u.login = 'claire.dupont@clientcorp.eu'; u.firstname = 'Claire'; u.lastname = 'Dupont'
  u.organization = org_shared; u.active = true; u.roles = [customer_role]
  u.created_by_id = 1; u.updated_by_id = 1
end
lukas = User.find_or_create_by!(email: 'lukas.meyer@privatebank.ch') do |u|
  u.login = 'lukas.meyer@privatebank.ch'; u.firstname = 'Lukas'; u.lastname = 'Meyer'
  u.organization = org_private; u.active = true; u.roles = [customer_role]
  u.created_by_id = 1; u.updated_by_id = 1
end
sofia = User.find_or_create_by!(email: 'sofia.rossi@acme.com') do |u|
  u.login = 'sofia.rossi@acme.com'; u.firstname = 'Sofia'; u.lastname = 'Rossi'
  u.active = true; u.roles = [customer_role]; u.created_by_id = 1; u.updated_by_id = 1
end

UserInfo.current_user_id = crawler.id

token = Token.create!(
  action: 'api',
  persistent: true,
  user_id: crawler.id,
  name: 'opencrawling-decoupled-it',
  preferences: { permission: ['admin', 'ticket.agent', 'knowledge_base.editor', 'knowledge_base.reader'] }
)

state_open = Ticket::State.find_by!(name: 'open')
state_closed = Ticket::State.find_by!(name: 'closed')
prio_high = Ticket::Priority.find_by!(name: '3 high')
prio_normal = Ticket::Priority.find_by!(name: '2 normal')
prio_low = Ticket::Priority.find_by!(name: '1 low')
type_web = Ticket::Article::Type.find_by!(name: 'web')
type_note = Ticket::Article::Type.find_by!(name: 'note')
sender_customer = Ticket::Article::Sender.find_by!(name: 'Customer')
sender_agent = Ticket::Article::Sender.find_by!(name: 'Agent')

# Ticket 1: Tier-2, shared org (ClientCorp Europe), public + internal articles + 2 binary attachments + tags
t1 = Ticket.create!(
  title: 'SSO SAML 2.0 signature validation failure',
  group: tier2, customer: claire, organization: org_shared, owner: crawler,
  state: state_open, priority: prio_high
)
a1_pub = Ticket::Article.create!(
  ticket: t1, from: claire.email, subject: 'SAML failure',
  body: 'Our SSO SAML 2.0 login fails after rotating the IdP certificate. Reference ZMD-T1-PUB.',
  content_type: 'text/plain', type: type_web, sender: sender_customer, internal: false
)
att1_pub = Store.create!(
  object: 'Ticket::Article', o_id: a1_pub.id,
  data: 'SAML Response Signature Error trace ZMD-T1-ATT-PUB',
  filename: 'saml-trace.log', preferences: { 'Mime-Type' => 'text/plain' }
)
a1_int = Ticket::Article.create!(
  ticket: t1, from: crawler.email, subject: 'Internal root cause',
  body: '<p>Confidential internal note: IdP metadata cache on node-3 was stale. Reference ZMD-T1-INT.</p>',
  content_type: 'text/html', type: type_note, sender: sender_agent, internal: true
)
att1_int = Store.create!(
  object: 'Ticket::Article', o_id: a1_int.id,
  data: 'CONFIDENTIAL AGENT RUNBOOK STEP ZMD-T1-ATT-INT',
  filename: 'internal-runbook.txt', preferences: { 'Mime-Type' => 'text/plain' }
)
t1.tag_add('saml', sourceable: nil)
t1.tag_add('sso', sourceable: nil)
t1.tag_add('certificate', sourceable: nil)

# Ticket 2: Tier-2, non-shared org (PrivateBank AG), public + internal articles
t2 = Ticket.create!(
  title: 'PrivateBank MFA hardware token reset',
  group: tier2, customer: lukas, organization: org_private, owner: crawler,
  state: state_open, priority: prio_normal
)
Ticket::Article.create!(
  ticket: t2, from: lukas.email, subject: 'Hardware token reset',
  body: 'PrivateBank MFA hardware token reset request for SSO authentication. Reference ZMD-T2-PUB.',
  content_type: 'text/plain', type: type_web, sender: sender_customer, internal: false
)
Ticket::Article.create!(
  ticket: t2, from: crawler.email, subject: 'Internal identity check',
  body: '<p>Internal identity verification completed for PrivateBank SSO token. Reference ZMD-T2-INT.</p>',
  content_type: 'text/html', type: type_note, sender: sender_agent, internal: true
)

# Ticket 3: Billing-Operations, closed, public article only (will be hard-deleted in Step 8 reconciliation)
t3 = Ticket.create!(
  title: 'Q3 enterprise subscription invoice VAT update',
  group: billing, customer: sofia, owner: crawler,
  state: state_closed, priority: prio_low
)
Ticket::Article.create!(
  ticket: t3, from: sofia.email, subject: 'Invoice VAT update',
  body: 'Q3 enterprise subscription invoice VAT registration update. Reference ZMD-T3-PUB.',
  content_type: 'text/plain', type: type_web, sender: sender_customer, internal: false
)

# Knowledge Base: published (Agent), internal (Security-Auditor), archived (skipped)
sys_locale = Locale.find_by!(locale: 'en-us')
kb = KnowledgeBase.new(
  iconset: 'FontAwesome', color_highlight: '#38ae69', color_header: '#f5f5f5',
  color_header_link: '#000000', homepage_layout: 'grid', category_layout: 'grid', active: true
)
kb.kb_locales.build(system_locale: sys_locale, primary: true)
kb.save!
kb_locale = kb.kb_locales.first!
KnowledgeBase::Translation.find_or_create_by!(knowledge_base: kb, kb_locale: kb_locale) do |t|
  t.title = 'Enterprise Help Center'
  t.footer_note = 'OpenCrawling IT'
end

cat_sso = KnowledgeBase::Category.create!(knowledge_base: kb, category_icon: 'f000')
KnowledgeBase::Category::Translation.create!(category: cat_sso, kb_locale: kb_locale, title: 'Authentication & SSO')
KnowledgeBase::Permission.create!(permissionable: cat_sso, role: agent_role, access: 'editor')

cat_sec = KnowledgeBase::Category.create!(knowledge_base: kb, category_icon: 'f001')
KnowledgeBase::Category::Translation.create!(category: cat_sec, kb_locale: kb_locale, title: 'Security Advisories')
KnowledgeBase::Permission.create!(permissionable: cat_sec, role: auditor_role, access: 'reader')

ans_pub = KnowledgeBase::Answer.create!(category: cat_sso, published_at: 1.hour.ago)
KnowledgeBase::Answer::Translation.create!(
  answer: ans_pub, kb_locale: kb_locale,
  title: 'How to rotate SAML IdP certificates',
  content: KnowledgeBase::Answer::Translation::Content.new(
    body: '<p>Upload the new X.509 certificate under Security Settings for SSO SAML. Reference ZMD-KB-PUB.</p>'
  )
)

ans_int = KnowledgeBase::Answer.create!(category: cat_sec, internal_at: 1.hour.ago)
KnowledgeBase::Answer::Translation.create!(
  answer: ans_int, kb_locale: kb_locale,
  title: 'Internal Incident Escalation Matrix',
  content: KnowledgeBase::Answer::Translation::Content.new(
    body: '<p>Confidential SSO security incident escalation runbook for auditors. Reference ZMD-KB-INT.</p>'
  )
)

ans_arch = KnowledgeBase::Answer.create!(category: cat_sso, published_at: 2.days.ago, archived_at: 1.day.ago)
KnowledgeBase::Answer::Translation.create!(
  answer: ans_arch, kb_locale: kb_locale,
  title: 'Legacy SSLv3 Guide',
  content: KnowledgeBase::Answer::Translation::Content.new(
    body: '<p>Archived SSLv3 guide that must not be indexed. Reference ZMD-KB-ARCH.</p>'
  )
)

# Synchronously index all tickets into Elasticsearch
[t1, t2, t3].each(&:search_index_update_backend)

puts "SEED_JSON:" + JSON.generate({
  token: token.token,
  t1_id: t1.id,
  t1_number: t1.number,
  t1_pub_art: a1_pub.id,
  t1_pub_att: att1_pub.id,
  t1_int_art: a1_int.id,
  t1_int_att: att1_int.id,
  t2_id: t2.id,
  t3_id: t3.id,
  kb_id: kb.id,
  ans_pub_id: ans_pub.id,
  ans_int_id: ans_int.id,
  ans_arch_id: ans_arch.id,
  tier2_group_id: tier2.id
})
RUBY
)

SEED_JSON=$(echo "${SEED_OUT}" | sed -n 's/^SEED_JSON://p' | tail -1)
[ -n "${SEED_JSON}" ] || { log_error "Failed to seed real Zammad instance:\n${SEED_OUT}"; exit 1; }

ZAMMAD_TOKEN=$(echo "${SEED_JSON}" | jq -r '.token')
T1_ID=$(echo "${SEED_JSON}" | jq -r '.t1_id')
T1_NUMBER=$(echo "${SEED_JSON}" | jq -r '.t1_number')
T1_PUB_ART=$(echo "${SEED_JSON}" | jq -r '.t1_pub_art')
T1_PUB_ATT=$(echo "${SEED_JSON}" | jq -r '.t1_pub_att')
T1_INT_ART=$(echo "${SEED_JSON}" | jq -r '.t1_int_art')
T1_INT_ATT=$(echo "${SEED_JSON}" | jq -r '.t1_int_att')
T2_ID=$(echo "${SEED_JSON}" | jq -r '.t2_id')
T3_ID=$(echo "${SEED_JSON}" | jq -r '.t3_id')
KB_ID=$(echo "${SEED_JSON}" | jq -r '.kb_id')
ANS_PUB_ID=$(echo "${SEED_JSON}" | jq -r '.ans_pub_id')
ANS_INT_ID=$(echo "${SEED_JSON}" | jq -r '.ans_int_id')
ANS_ARCH_ID=$(echo "${SEED_JSON}" | jq -r '.ans_arch_id')
TIER2_GROUP_ID=$(echo "${SEED_JSON}" | jq -r '.tier2_group_id')
EXPECTED=9

# Force Elasticsearch refresh so newly seeded tickets are immediately searchable
docker exec zammad-elasticsearch-decoupled-zammad curl -sf -X POST "http://127.0.0.1:9200/_refresh" >/dev/null

# Verify REST API v1 authentication against live Zammad
ME_EMAIL=$(zammad_api GET /api/v1/users/me | jq -r '.email // empty')
[ "${ME_EMAIL}" = "crawler@acme.com" ] || { log_error "Real Zammad /api/v1/users/me auth check failed: '${ME_EMAIL}'"; exit 1; }
log_info "Seeded real Zammad: tickets=[${T1_ID} (#${T1_NUMBER}), ${T2_ID}, ${T3_ID}], kb=${KB_ID} (answers: pub=${ANS_PUB_ID}, int=${ANS_INT_ID}, arch=${ANS_ARCH_ID}), expected OIS docs=${EXPECTED}."
log_pass "Real Zammad seeded and authenticated via REST API v1."

# ------------------------------------------------------------------------------
# STEP 6: First crawl & pgvector verification
# ------------------------------------------------------------------------------
log_step 6 "Crawling real Zammad (search_delta) and verifying pgvector documents, metadata, and ACLs..."
run_crawler search_delta

URI_T1_PUB=$(zammad_uri "/tickets/${T1_ID}/public")
URI_T1_INT=$(zammad_uri "/tickets/${T1_ID}/internal")
URI_T1_ATT_PUB=$(zammad_uri "/tickets/${T1_ID}/articles/${T1_PUB_ART}/attachments/${T1_PUB_ATT}")
URI_T1_ATT_INT=$(zammad_uri "/tickets/${T1_ID}/articles/${T1_INT_ART}/attachments/${T1_INT_ATT}")
URI_T2_PUB=$(zammad_uri "/tickets/${T2_ID}/public")
URI_T3_PUB=$(zammad_uri "/tickets/${T3_ID}/public")
URI_KB_PUB=$(zammad_uri "/kb/${KB_ID}/answers/${ANS_PUB_ID}/en")
URI_KB_INT=$(zammad_uri "/kb/${KB_ID}/answers/${ANS_INT_ID}/en")

wait_for "Ticket ${T1_ID} public thread is indexed" "content LIKE '%ZMD-T1-PUB%'" "-gt 0"
wait_for "Ticket ${T1_ID} public attachment is indexed" "content LIKE '%ZMD-T1-ATT-PUB%'" "-gt 0"
wait_for "Ticket ${T1_ID} internal notes are indexed" "content LIKE '%ZMD-T1-INT%'" "-gt 0"
wait_for "Ticket ${T1_ID} internal attachment is indexed" "content LIKE '%ZMD-T1-ATT-INT%'" "-gt 0"
wait_for "KB Answer ${ANS_PUB_ID} (published) is indexed" "content LIKE '%ZMD-KB-PUB%'" "-gt 0"
wait_for "KB Answer ${ANS_INT_ID} (internal) is indexed" "content LIKE '%ZMD-KB-INT%'" "-gt 0"
wait_sql "All ${EXPECTED} Zammad OIS documents are indexed" "SELECT CASE WHEN to_regclass('public.vector_store_1024') IS NULL THEN 0 ELSE
  (SELECT count(DISTINCT metadata->>'documentId') FROM vector_store_1024 WHERE metadata->>'documentId' LIKE 'zammad://${INSTANCE_KEY}/%') END;" "-ge ${EXPECTED}"

DISTINCT_DOCS=$(sql "SELECT count(DISTINCT metadata->>'documentId') FROM vector_store_1024 WHERE metadata->>'documentId' LIKE 'zammad://${INSTANCE_KEY}/%';")
[ "$DISTINCT_DOCS" -eq "$EXPECTED" ] || { log_error "Expected ${EXPECTED} indexed documents, found ${DISTINCT_DOCS}."; exit 1; }
log_success "pgvector holds exactly ${DISTINCT_DOCS} Zammad OIS documents."

# Verify archived KB answer is NOT indexed
ARCHIVED_HITS=$(sql "SELECT count(*) FROM vector_store_1024 WHERE content LIKE '%ZMD-KB-ARCH%' OR metadata->>'documentId' LIKE '%/kb/${KB_ID}/answers/${ANS_ARCH_ID}/%';")
[ "$ARCHIVED_HITS" -eq 0 ] || { log_error "Archived KB answer ${ANS_ARCH_ID} must not be indexed (found ${ARCHIVED_HITS})."; exit 1; }
log_success "Archived KB answer ${ANS_ARCH_ID} was skipped."

# Verify structured metadata & internal confidentiality
PUB_META_OK=$(sql "SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' = '${URI_T1_PUB}'
  AND metadata->'zammad.entityType'->>0 = 'ticket'
  AND metadata->'zammad.ticketNumber'->>0 = '${T1_NUMBER}'
  AND metadata->'zammad.group'->>0 = 'Tier-2-Identity-Security'
  AND metadata->'zammad.internal'->>0 = 'false'
  AND metadata->'zammad.tags'->>0 LIKE '%saml%'
  AND content NOT LIKE '%ZMD-T1-INT%';")
[ "$PUB_META_OK" -gt 0 ] || { log_error "Ticket ${T1_ID} public metadata or content isolation check failed."; exit 1; }

INT_META_OK=$(sql "SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' = '${URI_T1_INT}'
  AND metadata->'zammad.entityType'->>0 = 'ticket_internal'
  AND metadata->'zammad.internal'->>0 = 'true'
  AND NOT (metadata::jsonb ? 'zammad.customer')
  AND NOT (metadata::jsonb ? 'zammad.organization');")
[ "$INT_META_OK" -gt 0 ] || { log_error "Ticket ${T1_ID} internal notes metadata check failed (customer/org must be omitted)."; exit 1; }
log_success "Structured Zammad metadata verified; public and internal threads are cleanly separated."

# Verify security rules stored in pgvector
SEC_PUB_T1=$(docker exec -i "${PG}" psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT metadata->'security' FROM vector_store_1024 WHERE metadata->>'documentId' = '${URI_T1_PUB}' LIMIT 1;")
echo "$SEC_PUB_T1" | jq -e '.permissions | any(.identity == "zammad-group-Tier-2-Identity-Security" and .access == "read")' >/dev/null \
  || { log_error "T1/public missing group ACL: ${SEC_PUB_T1}"; exit 1; }
echo "$SEC_PUB_T1" | jq -e '.permissions | any(.identity == "claire.dupont@clientcorp.eu" and .access == "read")' >/dev/null \
  || { log_error "T1/public missing customer ACL: ${SEC_PUB_T1}"; exit 1; }
echo "$SEC_PUB_T1" | jq -e '.permissions | any(.identity == "zammad-org-ClientCorp-Europe" and .access == "read")' >/dev/null \
  || { log_error "T1/public missing shared organization ACL: ${SEC_PUB_T1}"; exit 1; }

SEC_INT_T1=$(docker exec -i "${PG}" psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT metadata->'security' FROM vector_store_1024 WHERE metadata->>'documentId' = '${URI_T1_INT}' LIMIT 1;")
echo "$SEC_INT_T1" | jq -e '(.permissions | length) == 1 and .permissions[0].identity == "zammad-group-Tier-2-Identity-Security"' >/dev/null \
  || { log_error "T1/internal must grant ONLY group agents: ${SEC_INT_T1}"; exit 1; }

SEC_ATT_INT=$(docker exec -i "${PG}" psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT metadata->'security' FROM vector_store_1024 WHERE metadata->>'documentId' = '${URI_T1_ATT_INT}' LIMIT 1;")
echo "$SEC_ATT_INT" | jq -e '(.permissions | length) == 1 and .permissions[0].identity == "zammad-group-Tier-2-Identity-Security"' >/dev/null \
  || { log_error "Internal attachment must grant ONLY group agents: ${SEC_ATT_INT}"; exit 1; }

SEC_PUB_T2=$(docker exec -i "${PG}" psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT metadata->'security' FROM vector_store_1024 WHERE metadata->>'documentId' = '${URI_T2_PUB}' LIMIT 1;")
echo "$SEC_PUB_T2" | jq -e '.permissions | any(.identity == "zammad-org-PrivateBank-AG") | not' >/dev/null \
  || { log_error "Non-shared organization PrivateBank AG must NOT be granted on T2/public: ${SEC_PUB_T2}"; exit 1; }

SEC_KB_INT=$(docker exec -i "${PG}" psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT metadata->'security' FROM vector_store_1024 WHERE metadata->>'documentId' = '${URI_KB_INT}' LIMIT 1;")
echo "$SEC_KB_INT" | jq -e '.permissions | any(.identity == "zammad-role-Security-Auditor" and .access == "read")' >/dev/null \
  || { log_error "Internal KB answer missing zammad-role-Security-Auditor ACL: ${SEC_KB_INT}"; exit 1; }

log_success "Security rules verified: public vs. internal isolation, shared vs. private org, and KB role ACLs."
log_pass "First crawl against real Zammad verified in pgvector."

# ------------------------------------------------------------------------------
# STEP 7: MCP secure search & Zero-Trust ACL enforcement
# ------------------------------------------------------------------------------
log_step 7 "Checking MCP Server Zero-Trust ACL enforcement against real Zammad permissions..."
ELAPSED=0
until curl -s -o /dev/null --max-time 3 "http://localhost:${MCP_PORT}/" 2>/dev/null; do
  [ $ELAPSED -ge 120 ] && { log_error "MCP server not reachable."; compose logs oc-mcp-server --tail 40; exit 1; }
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done

MCP="http://localhost:${MCP_PORT}/mcp"
MCP_HEADERS=$(mktemp)
MCP_SESSION=""
mcp_call() {
  curl -s --max-time 30 -D "$MCP_HEADERS" -X POST "$MCP" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' ${MCP_SESSION:+-H "Mcp-Session-Id: ${MCP_SESSION}"} -d "$1" \
    | sed -n 's/^data: *//p; /^{/p' | grep '"jsonrpc"' | tail -1
}
INIT=$(mcp_call '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-zammad-decoupled","version":"1.0"}}}')
MCP_SESSION=$(grep -i '^mcp-session-id:' "$MCP_HEADERS" | awk '{print $2}' | tr -d '\r')
[ -n "$(echo "$INIT" | jq -r '.result.serverInfo.name // empty')" ] || { log_error "MCP initialize failed: ${INIT}"; exit 1; }
mcp_call '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null || true

SEARCH_ID=10
search_as() { # <principal> <roles>: distinct zammad:// document URIs returned by secureVectorSearch
  SEARCH_ID=$((SEARCH_ID + 1))
  local args
  args=$(jq -nc --arg p "$1" --arg r "$2" \
    '{query:"SSO SAML certificate token security invoice escalation", userPrincipal:$p, userRoles:$r, maxResults:200, minScore:0.0, dimensions:1024}')
  mcp_call "{\"jsonrpc\":\"2.0\",\"id\":${SEARCH_ID},\"method\":\"tools/call\",\"params\":{\"name\":\"secureVectorSearch\",\"arguments\":${args}}}" \
    | jq -r '.result.content[]?.text // empty' | grep -oE 'zammad://[A-Za-z0-9.:/_-]+' | sort -u || true
}
tool_text() { # <id> <tool> <arguments json>
  mcp_call "{\"jsonrpc\":\"2.0\",\"id\":$1,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":$3}}" \
    | jq -r '.result.content[]?.text // empty'
}

AGENT_HITS=$(search_as "marcus.weber@acme.com" "zammad-group-Tier-2-Identity-Security")
CUSTOMER_HITS=$(search_as "claire.dupont@clientcorp.eu" "")
ORG_TEAMMATE_HITS=$(search_as "colleague@clientcorp.eu" "zammad-org-ClientCorp-Europe")
AUDITOR_HITS=$(search_as "auditor@acme.com" "zammad-role-Security-Auditor")
OUTSIDER_HITS=$(search_as "outsider@example.org" "")

CUSTOMER_INT_CONTENT=$(tool_text 30 getDocumentContent "$(jq -nc --arg u "${URI_T1_INT}" '{documentUri:$u, userPrincipal:"claire.dupont@clientcorp.eu", userRoles:"", dimensions:1024}')")
OUTSIDER_LISTED=$(tool_text 31 listAccessibleSources '{"userPrincipal":"outsider@example.org","userRoles":"","dimensions":1024}')
rm -f "$MCP_HEADERS"

# 1. Tier-2 Group Agent sees public + internal threads and attachments of Tier-2, NOT Billing (T3)
echo "$AGENT_HITS" | grep -qx "${URI_T1_PUB}" || { log_error "Tier-2 agent must find T1/public."; exit 1; }
echo "$AGENT_HITS" | grep -qx "${URI_T1_INT}" || { log_error "Tier-2 agent must find T1/internal."; exit 1; }
echo "$AGENT_HITS" | grep -qx "${URI_T1_ATT_INT}" || { log_error "Tier-2 agent must find internal attachment."; exit 1; }
if echo "$AGENT_HITS" | grep -qx "${URI_T3_PUB}"; then
  log_error "Tier-2 agent must NOT see Billing ticket ${T3_ID}."
  exit 1
fi

# 2. Customer Claire sees T1/public & public attachment, NEVER T1/internal, internal attachment, or T2
echo "$CUSTOMER_HITS" | grep -qx "${URI_T1_PUB}" || { log_error "Customer Claire must find T1/public."; exit 1; }
echo "$CUSTOMER_HITS" | grep -qx "${URI_T1_ATT_PUB}" || { log_error "Customer Claire must find public attachment."; exit 1; }
for forbidden in "${URI_T1_INT}" "${URI_T1_ATT_INT}" "${URI_T2_PUB}"; do
  if echo "$CUSTOMER_HITS" | grep -qx "$forbidden"; then
    log_error "Customer Claire must NOT find ${forbidden}."
    exit 1
  fi
done
if echo "$CUSTOMER_INT_CONTENT" | grep -q "ZMD-T1-INT"; then
  log_error "getDocumentContent leaked internal agent notes to customer Claire!"
  exit 1
fi

# 3. Shared Organization teammate sees T1/public (shared=true), NEVER T1/internal or T2/public (shared=false)
echo "$ORG_TEAMMATE_HITS" | grep -qx "${URI_T1_PUB}" || { log_error "Shared org teammate must find T1/public."; exit 1; }
if echo "$ORG_TEAMMATE_HITS" | grep -qx "${URI_T1_INT}"; then
  log_error "Shared org teammate must NOT see internal notes."
  exit 1
fi
if echo "$ORG_TEAMMATE_HITS" | grep -qx "${URI_T2_PUB}"; then
  log_error "Shared org teammate must NOT see non-shared org ticket ${T2_ID}."
  exit 1
fi

# 4. KB Security Auditor sees internal KB answer, NOT Tier-2 ticket internal notes
echo "$AUDITOR_HITS" | grep -qx "${URI_KB_INT}" || { log_error "Security-Auditor must find internal KB Answer ${ANS_INT_ID}."; exit 1; }
if echo "$AUDITOR_HITS" | grep -qx "${URI_T1_INT}"; then
  log_error "Security-Auditor must NOT see Tier-2 ticket internal notes."
  exit 1
fi

# 5. Outsider sees NOTHING across secureVectorSearch and listAccessibleSources
[ -z "$OUTSIDER_HITS" ] || { log_error "Outsider without permissions found: ${OUTSIDER_HITS}"; exit 1; }
if echo "$OUTSIDER_LISTED" | grep -q "zammad://${INSTANCE_KEY}/"; then
  log_error "listAccessibleSources exposed Zammad documents to an outsider: ${OUTSIDER_LISTED}"
  exit 1
fi

log_success "Group Agent, Customer, Shared Org Teammate, Security Auditor, and Outsider ACL boundaries enforced."
log_pass "MCP Zero-Trust ACL enforcement verified."

# ------------------------------------------------------------------------------
# STEP 8: Real Zammad Mutations + Incremental search_delta & Full Reconciliation
# ------------------------------------------------------------------------------
log_step 8 "Mutating real Zammad via REST API v1 and running incremental search_delta + full reconciliation..."

# 8a. Create Ticket 4 with public article + internal note via real Zammad REST API v1
T4_RES=$(zammad_api POST /api/v1/tickets -H "Content-Type: application/json" -d "$(jq -nc --argjson gid "${TIER2_GROUP_ID}" '{
  title: "Incremental OAuth2 token refresh failure",
  group_id: $gid,
  customer: "claire.dupont@clientcorp.eu",
  article: {
    subject: "OAuth2 refresh error",
    body: "Public incremental ticket thread ZMD-T4-INCR-PUB.",
    type: "web",
    internal: false
  }
}')")
T4_ID=$(echo "${T4_RES}" | jq -r '.id')
[ -n "${T4_ID}" ] && [ "${T4_ID}" != "null" ] || { log_error "Failed to create Ticket 4 via Zammad REST API: ${T4_RES}"; exit 1; }

zammad_api POST /api/v1/ticket_articles -H "Content-Type: application/json" -d "$(jq -nc --argjson tid "${T4_ID}" '{
  ticket_id: $tid,
  subject: "Internal OAuth2 triage",
  body: "Confidential incremental agent note ZMD-T4-INCR-INT.",
  type: "note",
  internal: true
}')" >/dev/null

# 8b. Update Ticket 1: replace public body with ZMD-T1-V2, remove internal note & attachments, and sync ES index
docker exec -i "${ZAMMAD_RAILS}" bundle exec rails r - <<RUBY
UserInfo.current_user_id = User.find_by!(email: 'crawler@acme.com').id
t1 = Ticket.find(${T1_ID})
pub_art = Ticket::Article.find(${T1_PUB_ART})
int_art = Ticket::Article.find(${T1_INT_ART})
Store.remove(object: 'Ticket::Article', o_id: pub_art.id)
Store.remove(object: 'Ticket::Article', o_id: int_art.id)
int_art.destroy!
pub_art.update!(body: 'SSO SAML 2.0 issue resolved after IdP metadata refresh. Reference ZMD-T1-V2.')
t1.touch
t1.reload.search_index_update_backend
Ticket.find(${T4_ID}).search_index_update_backend
RUBY

# 8c. Archive internal KB answer via real Zammad REST API v1
ARCHIVE_RES=$(zammad_api POST "/api/v1/knowledge_bases/${KB_ID}/answers/${ANS_INT_ID}/archive" -H "Content-Type: application/json" -d '{}')
ARCHIVED_AT=$(echo "${ARCHIVE_RES}" | jq -r --arg id "${ANS_INT_ID}" '.assets.KnowledgeBaseAnswer[$id].archived_at // empty')
[ -n "${ARCHIVED_AT}" ] || { log_error "Failed to archive KB Answer ${ANS_INT_ID}: ${ARCHIVE_RES}"; exit 1; }

# Force ES refresh so incremental updated_at:[... TO *] search sees Ticket 1 & Ticket 4 immediately
docker exec zammad-elasticsearch-decoupled-zammad curl -sf -X POST "http://127.0.0.1:9200/_refresh" >/dev/null

log_info "Created ticket ${T4_ID} via REST API, updated ticket ${T1_ID} (removed internal note + attachments), archived KB Answer ${ANS_INT_ID}."
sleep 2
run_crawler search_delta

wait_for "New ticket ${T4_ID} public thread is indexed" "content LIKE '%ZMD-T4-INCR-PUB%'" "-gt 0"
wait_for "New ticket ${T4_ID} internal note is indexed" "content LIKE '%ZMD-T4-INCR-INT%'" "-gt 0"
wait_for "Updated ticket ${T1_ID} public thread (ZMD-T1-V2) is indexed" "content LIKE '%ZMD-T1-V2%'" "-gt 0"
wait_for "Old ticket ${T1_ID} public content (ZMD-T1-PUB) is replaced" "content LIKE '%ZMD-T1-PUB%'" "-eq 0"
wait_for "Removed ticket ${T1_ID} internal notes are purged" "metadata->>'documentId' = '${URI_T1_INT}'" "-eq 0"
wait_for "Removed public attachment is purged" "metadata->>'documentId' = '${URI_T1_ATT_PUB}'" "-eq 0"
wait_for "Removed internal attachment is purged" "metadata->>'documentId' = '${URI_T1_ATT_INT}'" "-eq 0"
wait_for "Archived KB Answer ${ANS_INT_ID} is purged" "metadata->>'documentId' = '${URI_KB_INT}'" "-eq 0"

# Unchanged tickets & KB remain intact
wait_for "Unchanged ticket ${T2_ID} remains indexed" "content LIKE '%ZMD-T2-PUB%'" "-gt 0"
wait_for "Unchanged KB Answer ${ANS_PUB_ID} remains indexed" "content LIKE '%ZMD-KB-PUB%'" "-gt 0"

# 8d. Hard-delete Ticket 3 via real Zammad REST API v1 and run full reconciliation crawl
zammad_api DELETE "/api/v1/tickets/${T3_ID}" >/dev/null
log_info "Hard-deleted ticket ${T3_ID} via REST API; running full reconciliation crawl..."
run_crawler full

wait_for "Hard-deleted ticket ${T3_ID} is purged by full reconciliation" "metadata->>'documentId' = '${URI_T3_PUB}'" "-eq 0"
log_pass "Incremental search_delta and full reconciliation tombstones brought pgvector up to date."

# ------------------------------------------------------------------------------
# STEP 9: Summary
# ------------------------------------------------------------------------------
log_step 9 "Summary"
LIVE_TICKETS=$(zammad_api GET "/api/v1/tickets?page=1&per_page=50" | jq 'length')
log_info "Remaining live tickets in real Zammad: ${LIVE_TICKETS} (tickets ${T1_ID}, ${T2_ID}, ${T4_ID})."
log_pass "Zammad decoupled integration test against real Zammad 7.2 + Elasticsearch complete."

echo -e "\n${BOLD}${GREEN}================================================================================${NC}"
echo -e "${BOLD}${GREEN}SUCCESS: All ${TOTAL_STEPS} Real Zammad Decoupled Integration Test Steps Passed!${NC}"
echo -e "${BOLD}${GREEN}================================================================================${NC}\n"
exit 0
