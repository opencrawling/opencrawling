#!/usr/bin/env bash
#
# Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
# OpenCrawling - Release Candidate Docker Compose Manifest Generator
#
# Generates docker-compose-apps-rc.yml and docker-compose-decoupled-rc.yml
# pointing to the RC container tag (<rc-version>, overridable via
# OPENCRAWLING_IMAGE_TAG) without modifying the GA docker-compose-* files.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

RC_VER="${1:-rc}"
RC_VER="${RC_VER#v}"
OUT_DIR="${2:-dist}"

mkdir -p "$OUT_DIR"

python3 - "$RC_VER" "$OUT_DIR" <<'PY'
import pathlib
import re
import sys

rc_ver = sys.argv[1]
out_dir = pathlib.Path(sys.argv[2])
tag_expr = f"${{OPENCRAWLING_IMAGE_TAG:-{rc_ver}}}"

apps_src = pathlib.Path("docker-compose-apps-dockerhub.yml").read_text(encoding="utf-8")
apps_rc = re.sub(r"(image:\s*opencrawling/[^:\s]+):latest\b", rf"\1:{tag_expr}", apps_src)

# Ensure monolithic apps mode enables ingestion and writer consumers even when
# backed by the unified oc-mcp-server image built with consumer defaults disabled.
apps_env_injection = (
    "      - SPRING_OPENCRAWLING_REPORTING_LOCAL_DIR=/data/reports\n"
    "      - TIKA_EXTRAS_DIR=/app/lib\n"
    "      - OPENCRAWLING_CONSUMER_INGESTION_ENABLED=true\n"
    "      - OPENCRAWLING_CONSUMER_EMBEDDING_ENABLED=false\n"
    "      - OPENCRAWLING_CONSUMER_WRITER_ENABLED=true\n"
    "      - OPENCRAWLING_MCP_SERVER_ENABLED=true"
)
if "OPENCRAWLING_CONSUMER_INGESTION_ENABLED=" not in apps_rc:
    apps_rc = apps_rc.replace(
        "      - SPRING_OPENCRAWLING_REPORTING_LOCAL_DIR=/data/reports",
        apps_env_injection,
        1,
    )

(out_dir / "docker-compose-apps-rc.yml").write_text(apps_rc, encoding="utf-8")

decoupled_src = pathlib.Path("docker-compose-decoupled-dockerhub.yml").read_text(encoding="utf-8")
decoupled_rc = re.sub(r"(image:\s*opencrawling/[^:\s]+):latest\b", rf"\1:{tag_expr}", decoupled_src)
(out_dir / "docker-compose-decoupled-rc.yml").write_text(decoupled_rc, encoding="utf-8")

print(f"Generated {out_dir / 'docker-compose-apps-rc.yml'} and {out_dir / 'docker-compose-decoupled-rc.yml'} for tag {tag_expr}")
PY
