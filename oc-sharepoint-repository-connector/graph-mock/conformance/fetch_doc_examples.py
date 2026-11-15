#!/usr/bin/env python3
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
"""
Extracts Microsoft's own example payloads for the Graph APIs the SharePoint connector uses from the Microsoft Graph
documentation (github.com/microsoftgraph/microsoft-graph-docs-contrib, at a pinned commit) into an output directory:

  exchanges/<page>-<n>.json   {"method", "url", "status", "headers", "body", "source"}: a documented request and response
  resources/<page>-<n>.json   {"type", "body", "source"}: a documented JSON representation of a resource

The examples are not stored in this repository: they are Microsoft's documentation and are fetched when the tests run.
Standard library only.
"""

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

RAW = "https://raw.githubusercontent.com/microsoftgraph/microsoft-graph-docs-contrib/{sha}/api-reference/v1.0/{kind}/{page}.md"
# the pages documenting the APIs the connector calls (and their neighbours that show the same resources); raw files only,
# so the GitHub API and its rate limit are never involved
API_PAGES = ["driveitem-get", "driveitem-list-children", "driveitem-delta", "driveitem-list-permissions", "driveitem-get-content",
             "driveitem-invite", "driveitem-createlink", "drive-get", "drive-list", "site-get", "site-getbypath", "site-list",
             "permission-get", "listitem-get", "list-get"]
RESOURCE_PAGES = {"permission", "sharepointidentityset", "sharepointidentity", "identityset", "identity", "driveitem",
                  "sharinglink", "itemreference", "deleted", "file", "folder", "root", "listitem", "fieldvalueset",
                  "drive", "site", "sharepointids"}

BLOCK = re.compile(r"<!--\s*(\{.*?\})\s*-->\s*```[\w-]*\n(.*?)```", re.S)


def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "opencrawling-conformance"}), timeout=60) as r:
        return r.read().decode("utf-8")


def loose_json(text):
    """The docs' JSON, tolerating the comments and trailing commas some examples carry."""
    text = re.sub(r"^\s*//.*$", "", text, flags=re.M)
    text = re.sub(r",(\s*[}\]])", r"\1", text)
    return json.loads(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sha", required=True, help="microsoft-graph-docs-contrib commit")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    pages = [("api", p) for p in API_PAGES] + [("resources", p) for p in sorted(RESOURCE_PAGES)]
    (args.out / "exchanges").mkdir(parents=True, exist_ok=True)
    (args.out / "resources").mkdir(parents=True, exist_ok=True)
    counts = {"exchanges": 0, "resources": 0, "skipped": 0}
    for kind, page in sorted(pages):
        url = RAW.format(sha=args.sha, kind=kind, page=page)
        try:
            text = fetch(url)
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
            print(f"no page {kind}/{page} at this commit", file=sys.stderr)
            continue
        request, n = None, 0
        for meta_text, code in BLOCK.findall(text):
            try:
                meta = json.loads(re.sub(r",(\s*})", r"\1", meta_text))
            except json.JSONDecodeError:
                continue
            block = meta.get("blockType")
            source = f"{url}#{meta.get('name', page)}"
            if block == "request":
                line = code.strip().splitlines()[0] if code.strip() else ""
                m = re.match(r"(GET|POST|PATCH|PUT|DELETE)\s+(\S+)", line)
                request = None
                if m:
                    target = re.sub(r"^https://graph\.microsoft\.com", "", m.group(2))
                    target = target if target.startswith("/v1.0/") or target.startswith("/beta/") else "/v1.0" + target
                    request = (m.group(1), target)
            elif block == "response" and request is not None:
                head, _, body_text = code.partition("\n\n")
                status = re.match(r"HTTP/1\.1\s+(\d+)", head.strip())
                if not status or request[1].startswith("/beta/"):
                    counts["skipped"] += 1
                    continue
                try:
                    body = loose_json(body_text) if body_text.strip() else None
                except json.JSONDecodeError:
                    counts["skipped"] += 1
                    continue
                headers = dict(h.split(":", 1) for h in head.strip().splitlines()[1:] if ":" in h)
                n += 1
                out = {"method": request[0], "url": request[1], "status": int(status.group(1)), "body": body,
                       "headers": {k.strip(): v.strip() for k, v in headers.items() if k.strip() in ("Location", "Retry-After")},
                       "source": source}
                (args.out / "exchanges" / f"{page}-{n}.json").write_text(json.dumps(out, indent=1), encoding="utf-8")
                counts["exchanges"] += 1
                request = None
            elif block == "resource":
                try:
                    body = loose_json(code)
                except json.JSONDecodeError:
                    counts["skipped"] += 1
                    continue
                n += 1
                out = {"type": meta.get("@odata.type"), "body": body, "source": source}
                (args.out / "resources" / f"{page}-{n}.json").write_text(json.dumps(out, indent=1), encoding="utf-8")
                counts["resources"] += 1
    (args.out / "SOURCE").write_text(f"microsoftgraph/microsoft-graph-docs-contrib@{args.sha}\n", encoding="utf-8")
    print(f"{len(pages)} pages: {counts['exchanges']} exchanges, {counts['resources']} resource representations, "
          f"{counts['skipped']} unparseable blocks skipped.", file=sys.stderr)


if __name__ == "__main__":
    main()
