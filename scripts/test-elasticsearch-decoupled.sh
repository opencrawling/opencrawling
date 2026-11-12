#!/usr/bin/env bash
#
# Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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

# End-to-end test for docker-compose-decoupled-with-elasticsearch.yml:
# filesystem crawl -> Kafka -> Tika/chunking -> Ollama embedding -> Elasticsearch writer -> MCP server.
#
# ELASTICSEARCH_VERSION selects the server (default 8.19.20; e.g. 9.4.3).
set -e

# Configuration flags (CLI flags take precedence over environment variables)
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
NC='\033[0m'

export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_CLIENT_TYPE="${OZONE_CLIENT_TYPE:-NATIVE}"
export ELASTICSEARCH_VERSION="${ELASTICSEARCH_VERSION:-8.19.20}"
ES_URL="http://localhost:9200"
INDEX="enterprise_kb"

echo -e "${YELLOW}=== Starting OpenCrawling Decoupled Pipeline with Elasticsearch ${ELASTICSEARCH_VERSION} Integration Test ===${NC}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo -e "${RED}Docker Compose is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo -e "${RED}jq is required but not installed. Aborting.${NC}" >&2; exit 1; }

COMPOSE_FILE="oc-elasticsearch-output-connector/docker/docker-compose-decoupled-with-elasticsearch.yml"
LOCAL_OVERLAY="${LOCAL_OVERLAY:-${COMPOSE_FILE%.yml}.local.yml}"

compose() {
  if [ "${LOCAL_BUILD}" = true ] && [ -f "${LOCAL_OVERLAY}" ]; then
    docker compose -f "${COMPOSE_FILE}" -f "${LOCAL_OVERLAY}" "$@"
  else
    docker compose -f "${COMPOSE_FILE}" "$@"
  fi
}

cleanup() {
  echo -e "\n${YELLOW}Tearing down Elasticsearch decoupled test environment...${NC}"
  compose down --remove-orphans >/dev/null 2>&1 || true
  rm -f "${TEST_FILE:-}"
}
trap cleanup EXIT

wait_until() {
  # wait_until <description> <timeout-seconds> <command...>
  local description="$1" timeout="$2" elapsed=0
  shift 2
  echo -e "${YELLOW}Waiting for ${description}...${NC}"
  until "$@" >/dev/null 2>&1; do
    if [ "$elapsed" -ge "$timeout" ]; then
      echo -e "${RED}Timeout waiting for ${description}.${NC}"
      return 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo -e "${GREEN}${description} ready.${NC}"
}

container_stopped() {
  [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || echo false)" == "false" ]
}

container_healthy() {
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null || echo starting)" == "healthy" ]
}

es_count() {
  curl -s "${ES_URL}/${INDEX}/_count" | jq -r '.count // 0' 2>/dev/null || echo 0
}

echo -e "${YELLOW}Cleaning up previous Elasticsearch decoupled containers...${NC}"
compose down --remove-orphans || true

echo -e "${YELLOW}Building OpenCrawling decoupled microservice images from source...${NC}"
if [ "${SKIP_BUILD}" = true ]; then
  echo -e "${YELLOW:-}SKIP_BUILD=true: Skipping compose build and using existing images.${NC:-}"
else
  echo -e "${YELLOW:-}Building OpenCrawling decoupled microservice images from source (LOCAL_BUILD=${LOCAL_BUILD})...${NC:-}"
  compose build
fi

echo -e "${YELLOW}Starting the decoupled Elasticsearch stack...${NC}"
compose up -d

wait_until "Elasticsearch ${ELASTICSEARCH_VERSION}" 240 container_healthy elasticsearch-standalone || { compose logs elasticsearch; exit 1; }
PRODUCT=$(curl -s -D - -o /dev/null "${ES_URL}/" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-elastic-product"{print $2}')
if [ "$PRODUCT" != "Elasticsearch" ]; then
  echo -e "${RED}Port 9200 is not served by Elasticsearch (X-Elastic-Product='${PRODUCT}'). Is another search engine running?${NC}"
  exit 1
fi

wait_until "Ollama" 180 container_healthy ollama-decoupled-elasticsearch || { compose logs ollama; exit 1; }
wait_until "Ollama model puller to finish" 600 container_stopped ollama-model-puller-decoupled-elasticsearch || { compose logs ollama-model-puller; exit 1; }
if [ "$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled-elasticsearch 2>/dev/null || echo 1)" -ne 0 ]; then
  echo -e "${RED}Ollama model puller failed.${NC}"
  compose logs ollama-model-puller
  exit 1
fi
wait_until "Apache Ozone S3 Gateway" 180 curl -s -o /dev/null "http://localhost:9878/" || { compose logs ozone-s3g; exit 1; }

# The writer (or any stage with output.type=elasticsearch) creates the index on startup.
wait_until "index ${INDEX} to be created" 120 curl -sf -o /dev/null "${ES_URL}/${INDEX}" || { compose logs oc-writer-consumer; exit 1; }
MAPPING=$(curl -s "${ES_URL}/${INDEX}/_mapping")
VECTOR_TYPE=$(echo "$MAPPING" | jq -r ".\"${INDEX}\".mappings.properties.embeddings.type")
VECTOR_DIMS=$(echo "$MAPPING" | jq -r ".\"${INDEX}\".mappings.properties.embeddings.dims")
READ_KEYS_TYPE=$(echo "$MAPPING" | jq -r ".\"${INDEX}\".mappings.properties.security_read_keys.type")
if [ "$VECTOR_TYPE" != "dense_vector" ] || [ "$VECTOR_DIMS" != "1024" ] || [ "$READ_KEYS_TYPE" != "keyword" ]; then
  echo -e "${RED}Unexpected index mapping: embeddings=${VECTOR_TYPE}/${VECTOR_DIMS}, security_read_keys=${READ_KEYS_TYPE}${NC}"
  echo "$MAPPING" | jq .
  exit 1
fi
echo -e "${GREEN}Index mapping verified (dense_vector[1024], keyword ACL read keys).${NC}"

TEST_DOC_DIR="./oc-runtime/data"
mkdir -p "$TEST_DOC_DIR"
TEST_FILE="$TEST_DOC_DIR/elasticsearch-decoupled-integration-test.txt"
echo "OpenCrawling is an awesome open-source pipeline! Decoupled integration test with Elasticsearch worked successfully." > "$TEST_FILE"
echo -e "${GREEN}Created test document: $TEST_FILE${NC}"

echo -e "${YELLOW}Restarting crawler service to trigger directory scan...${NC}"
compose restart oc-crawler
wait_until "oc-crawler to finish scanning" 180 container_stopped oc-crawler-service-elasticsearch || { compose logs oc-crawler; exit 1; }

echo -e "${YELLOW}Waiting for Kafka consumers to store vectors in Elasticsearch...${NC}"
RECORD_COUNT=0
ELAPSED=0
TIMEOUT=180
until [ "$RECORD_COUNT" -gt 0 ] 2>/dev/null || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))
  curl -s -o /dev/null -X POST "${ES_URL}/${INDEX}/_refresh" || true
  RECORD_COUNT=$(es_count)
  printf "  Elapsed: %ds, chunks in %s: %s\r" "$ELAPSED" "$INDEX" "$RECORD_COUNT"
done
echo ""
if [ "$RECORD_COUNT" -eq 0 ] 2>/dev/null || [ -z "$RECORD_COUNT" ]; then
  echo -e "${RED}Elasticsearch decoupled integration test failed: no chunks in index ${INDEX}!${NC}"
  compose logs oc-writer-consumer
  exit 1
fi
echo -e "Elasticsearch chunk count: ${GREEN}$RECORD_COUNT${NC}"

# Every stored chunk must carry the vector and the flattened ACL fields the MCP pre-filter relies on.
SAMPLE=$(curl -s "${ES_URL}/${INDEX}/_search?size=1" -H 'Content-Type: application/json' \
  -d '{"query":{"wildcard":{"uri":"*elasticsearch-decoupled-integration-test.txt"}}}')
SAMPLE_HITS=$(echo "$SAMPLE" | jq -r '.hits.total.value')
SAMPLE_DIMS=$(echo "$SAMPLE" | jq -r '.hits.hits[0]._source.embeddings | length')
SAMPLE_KEYS=$(echo "$SAMPLE" | jq -r '.hits.hits[0]._source.security_read_keys | length')
if [ "$SAMPLE_HITS" = "0" ] || [ "$SAMPLE_DIMS" != "1024" ] || [ "$SAMPLE_KEYS" = "0" ] || [ "$SAMPLE_KEYS" = "null" ]; then
  echo -e "${RED}Stored chunk is incomplete: hits=${SAMPLE_HITS}, vector dims=${SAMPLE_DIMS}, read keys=${SAMPLE_KEYS}${NC}"
  echo "$SAMPLE" | jq '.hits.hits[0]._source | del(.embeddings)'
  exit 1
fi
echo -e "${GREEN}Test document chunk stored with a 1024-dim vector and ACL read keys.${NC}"

# kNN search with the same read-key pre-filter the MCP backend applies (a public document matches "public").
KNN_VECTOR=$(echo "$SAMPLE" | jq -c '.hits.hits[0]._source.embeddings')
KNN=$(curl -s "${ES_URL}/${INDEX}/_search" -H 'Content-Type: application/json' -d "{
  \"size\": 3,
  \"knn\": {\"field\": \"embeddings\", \"query_vector\": ${KNN_VECTOR}, \"k\": 3, \"num_candidates\": 50,
            \"filter\": {\"terms\": {\"security_read_keys\": [\"public\", \"u:anyone\"]}}},
  \"_source\": [\"uri\"]
}")
if ! echo "$KNN" | jq -e '.hits.hits | map(._source.uri) | any(test("elasticsearch-decoupled-integration-test"))' >/dev/null; then
  echo -e "${RED}kNN search with the ACL pre-filter did not return the test document.${NC}"
  echo "$KNN" | jq .
  exit 1
fi
echo -e "${GREEN}kNN search with ACL pre-filter returned the test document.${NC}"

echo -e "${YELLOW}Waiting for MCP Server endpoint...${NC}"
HTTP_STATUS="000"
ELAPSED=0
until [[ "$HTTP_STATUS" =~ ^(200|400|404|405|406)$ ]] || [ $ELAPSED -ge 90 ]; do
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8080/mcp 2>/dev/null || echo "000")
  [[ "$HTTP_STATUS" =~ ^(200|400|404|405|406)$ ]] || { sleep 2; ELAPSED=$((ELAPSED + 2)); }
done
if ! [[ "$HTTP_STATUS" =~ ^(200|400|404|405|406)$ ]]; then
  echo -e "${RED}MCP Server returned unexpected status $HTTP_STATUS${NC}"
  compose logs oc-mcp-server
  exit 1
fi
if ! compose logs oc-mcp-server 2>/dev/null | grep -q "Initialized ElasticsearchMcpSearchBackend"; then
  echo -e "${RED}MCP Server did not activate the Elasticsearch search backend.${NC}"
  compose logs oc-mcp-server | tail -50
  exit 1
fi
echo -e "${GREEN}MCP Server is reachable (HTTP $HTTP_STATUS) and uses the Elasticsearch backend.${NC}"

echo -e "${YELLOW}Running the Elasticsearch writer tombstone/DELETE tests...${NC}"
mvn -B test -pl oc-elasticsearch-output-connector -Dtest=ElasticsearchStoreWriterConsumerTest -Dsurefire.failIfNoSpecifiedTests=false

echo -e "${GREEN}================================================================================${NC}"
echo -e "${GREEN}SUCCESS: Elasticsearch Decoupled Multi-Service Pipeline Integration Test Passed!${NC}"
echo -e "${GREEN}================================================================================${NC}"
exit 0
