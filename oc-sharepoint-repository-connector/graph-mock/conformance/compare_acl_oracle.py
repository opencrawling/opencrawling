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
Holds the permissions the SharePoint connector indexed against an independent RBAC oracle: sergey-shulyak/mock-graph-server's
company.visibility.yaml, the exact files each of its users can read, generated from that mock's own permission model.

For every user it computes what the OpenCrawling MCP server would let that user read, from the exported rules
(IndependentGraphMockTest writes crawled-permissions.json): a rule grants the user when its identity is the user's UPN or
Entra object id, one of the user's Entra groups (transitively, as the oracle lists them), a SharePoint site group the user
belongs to (directly or through a group: the connector emits these as tokens a caller resolves; here they are resolved
from the mock's state), or "public". Every difference is reported: a file the oracle denies but the index grants is a
leak and fails the check; a file the oracle allows but the index denies is reported as a gap.
Needs pyyaml (uv run --with pyyaml).
"""

import argparse
import json
import sys
import urllib.request

import yaml


def graph(base, path):
    request = urllib.request.Request(f"{base}/v1.0/{path}", headers={"Authorization": "Bearer app"})
    with urllib.request.urlopen(request, timeout=30) as r:
        return json.loads(r.read())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--crawled", required=True)
    parser.add_argument("--oracle", required=True, help="company.visibility.yaml from the mock's image")
    parser.add_argument("--state", required=True, help="the mock's GET /_admin/state")
    parser.add_argument("--mock", required=True, help="the mock's base URL, to resolve fixture ids to wire ids")
    args = parser.parse_args()

    crawled = json.load(open(args.crawled, encoding="utf-8"))
    oracle = yaml.safe_load(open(args.oracle, encoding="utf-8"))["users"]
    state = yaml.safe_load(open(args.state, encoding="utf-8"))

    # fixture ids -> wire ids
    drive_wire = {}
    for site in state["sites"]:
        for drive in site.get("drives", []):
            drive_wire[drive["id"]] = graph(args.mock, f"drives/{drive['id']}")["id"]
    group_oid = {g["id"]: graph(args.mock, f"groups/{g['id']}")["id"].lower() for g in state["groups"]}
    # site groups: (site url path, display name) -> members
    site_groups = {}
    for site in state["sites"]:
        for sg in site.get("siteGroups", []):
            site_groups[(site["id"], sg["displayName"])] = sg.get("members", [])

    def site_key(site_url):
        return site_url.rstrip("/").rsplit("/", 1)[-1]

    files = {}  # "<wire drive id>:<path>" -> (site key, rules)
    for row in crawled:
        files[f"{row['driveId']}:{row['path']}"] = (site_key(row["siteUrl"]), row["rules"])
    wire_to_fixture = {v: k for k, v in drive_wire.items()}

    leaks, gaps, checked = [], [], 0
    for user_id, entry in sorted(oracle.items()):
        if user_id == "app":
            continue
        user = graph(args.mock, f"users/{user_id}")
        groups = set(entry.get("groups") or [])
        tokens = {user["userPrincipalName"].lower(), user["id"].lower(), "public"} | {group_oid[g] for g in groups if g in group_oid}

        def member(members):
            return any(m == f"user:{user_id}" or (m.startswith("group:") and m[6:] in groups) for m in members)

        readable = set()
        for key, (site, rules) in files.items():
            for rule in rules:
                identity = rule["identity"]
                if identity in tokens:
                    readable.add(key)
                    break
                if identity.startswith("sharepoint-group:") and member(site_groups.get((site, rule.get("displayName")), [])):
                    readable.add(key)
                    break
        expected = set()
        for f in entry.get("files") or []:
            drive, _, path = f.partition(":")
            expected.add(f"{drive_wire.get(drive, drive)}:{path}")
        checked += 1
        for key in sorted(readable - expected):
            leaks.append(f"{user_id}: index grants {wire_to_fixture.get(key.split(':')[0], '?')}:{key.split(':', 1)[1]}")
        for key in sorted(expected - readable):
            gaps.append(f"{user_id}: index denies {wire_to_fixture.get(key.split(':')[0], '?')}:{key.split(':', 1)[1]}")

    unknown = {f"{d}:{p}" for e in oracle.values() for f in (e.get("files") or []) for d, _, p in [f.partition(":")]
               if f"{drive_wire.get(d, d)}:{p}" not in files}
    print(f"{checked} users, {len(files)} indexed files, oracle files not indexed at all: {len(unknown)}")
    for line in sorted(unknown)[:10]:
        print(f"  not indexed: {line}")
    for line in leaks[:40]:
        print(f"LEAK  {line}")
    for line in gaps[:40]:
        print(f"GAP   {line}")
    print(f"\n{len(leaks)} leaks (the index grants what the oracle denies), {len(gaps)} gaps (the index denies what it allows).")
    sys.exit(1 if leaks or unknown else 0)


if __name__ == "__main__":
    main()
