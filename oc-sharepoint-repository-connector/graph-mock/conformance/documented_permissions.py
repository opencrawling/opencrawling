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
Which Microsoft Graph application permission the SharePoint connector needs, from Microsoft's documentation
(github.com/microsoftgraph/microsoft-graph-docs-contrib at a pinned commit): every API page has a permissions table with
the least privileged and the higher privileged permissions per permission type. For the Graph calls in a recording
(graph_mock.py with GRAPH_RECORD), this prints each call's documented application permissions and the permissions that
every call accepts, and fails unless the permission the connector's README asks for (--expect) is one of them.
Standard library only.
"""

import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path
from urllib.parse import unquote, urlsplit

RAW = "https://raw.githubusercontent.com/microsoftgraph/microsoft-graph-docs-contrib/{sha}/api-reference/v1.0/{path}"
# the documentation page of each Graph call the connector makes
PAGES = [
    (r"^/sites/[^/]+:/.+$|^/sites/[^/]+$", "site-get"),
    (r"^/sites/[^/]+/drives$", "drive-list"),
    (r"^/drives/[^/]+$", "drive-get"),
    (r"^/drives/[^/]+/root/delta$|^/drives/[^/]+/items/[^/]+/delta$", "driveitem-delta"),
    (r"^/drives/[^/]+/items/[^/]+/children$", "driveitem-list-children"),
    (r"^/drives/[^/]+/items/[^/]+/permissions$", "driveitem-list-permissions"),
    (r"^/drives/[^/]+/items/[^/]+/content$", "driveitem-get-content"),
    (r"^/drives/[^/]+/items/[^/]+/listItem(/fields)?$", "listitem-get"),
    (r"^/drives/[^/]+/(root(:/.*?:?)?|items/[^/]+)$", "driveitem-get"),
]


def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "opencrawling-conformance"}), timeout=60) as r:
        return r.read().decode("utf-8")


def application_permissions(sha, page):
    """(least privileged, higher privileged) application permissions of the page's first permissions table."""
    text = fetch(RAW.format(sha=sha, path=f"api/{page}.md"))
    include = re.search(r"\[!INCLUDE \[permissions-table\]\(\.\./(includes/permissions/[\w-]+\.md)\)\]", text)
    table = fetch(RAW.format(sha=sha, path=include.group(1))) if include else text
    for line in table.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 3 and cells[0].lower() == "application":
            split = lambda c: [p.strip() for p in c.split(",") if p.strip() and p.strip().lower() != "not supported."]
            return split(cells[1]), split(cells[2])
    return [], []


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("recordings", nargs="+", type=Path)
    parser.add_argument("--sha", required=True, help="microsoft-graph-docs-contrib commit")
    parser.add_argument("--expect", default="Sites.Read.All", help="the permission the connector documents")
    args = parser.parse_args()

    calls = set()
    for recording in args.recordings:
        for line in recording.read_text(encoding="utf-8").splitlines():
            if line.strip():
                e = json.loads(line)
                path = unquote(urlsplit(e["url"]).path)
                if path.startswith("/v1.0/"):
                    calls.add((e["method"], path[len("/v1.0"):]))
    pages = {}
    for method, path in sorted(calls):
        page = next((p for regex, p in PAGES if re.match(regex, path)), None)
        if page is None:
            print(f"FAIL  {method} {path}: no documentation page mapped")
            sys.exit(1)
        pages.setdefault(page, set()).add(method)

    common = None
    for page in sorted(pages):
        least, higher = application_permissions(args.sha, page)
        accepted = set(least) | set(higher)
        common = accepted if common is None else common & accepted
        print(f"  {page:<28} least privileged: {', '.join(least) or '-':<22} also accepted: {', '.join(higher) or '-'}")
    print(f"\nApplication permissions every call accepts: {', '.join(sorted(common)) or 'none'}")
    if args.expect not in common:
        print(f"FAIL  {args.expect} is not accepted by every call the connector makes.")
        sys.exit(1)
    print(f"ok    {args.expect} (the connector's documented prerequisite) covers every call "
          f"(microsoft-graph-docs-contrib {args.sha[:12]}).")


if __name__ == "__main__":
    main()
