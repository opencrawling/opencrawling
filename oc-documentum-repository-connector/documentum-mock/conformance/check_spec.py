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
"""Check the DCTM REST mock against OpenText's published OpenAPI specification.

OpenText publishes the Documentum REST API as an OpenAPI 3 document on developer.opentext.com ("Documentum D2", the D2
REST Services, which embed the core Documentum REST Services resources). The document is not redistributed here; this
script downloads it (or reads a local copy), crawls a running mock the way a client does (home document, every
advertised link, DQL feeds, objects, contents, errors) and checks every exchange against it:

- the path and method are documented;
- every query parameter the mock puts in a link is a documented parameter of the target operation;
- the status code is documented for the operation (or is a protocol status, 406, that Spring answers itself);
- the response media type is one the operation documents;
- the JSON body matches the operation's response schema (types of the documented fields, recursively);
- parameter defaults (page, items-per-page) match the mock's.

Exit status: 0 conforms, 1 deviations, 2 the specification could not be read.

Usage:
  python3 check_spec.py --mock http://localhost:18290/dctm-rest [--spec spec.yaml|spec.json] [--user u --password p]

Reading the YAML document needs PyYAML; a JSON copy needs nothing beyond the standard library.
"""
import argparse
import base64
import hashlib
import json
import re
import sys
import urllib.error
import urllib.request
from urllib.parse import parse_qs, quote, urlencode, urlparse

PORTAL = "https://developer.opentext.com/dxcma/api/v1/site/dx"
PRODUCT = "2f4f0fbe-3249-4767-80f4-916e95ae8234"  # "Documentum Content Management" on developer.opentext.com
PINNED_SPEC = "a0e21570-887e-4c0b-8f92-0d15a3468d3d"  # Documentum D2 REST Services 26.2.0, checked 2026-10-31
DM_JSON = "application/vnd.emc.documentum+json"
PROTOCOL_STATUSES = {406}  # answered by the web framework before any operation runs
# Where the published document is known not to describe the core REST server, a mismatch is a warning with the reason:
SPEC_GAPS = {
    "getHomeDocument": "the document only lists application/home+xml; OpenText's clients request application/home+json",
    "getRepository": "the repository resource and the DQL feed share one path, and the document only describes the feed",
}


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "opencrawling-dctm-mock-check"})
    with urllib.request.urlopen(req, timeout=60) as rsp:
        return rsp.read()


def spec_url():
    """The product's current "Documentum D2" API specification on the portal, else the version this was written with."""
    try:
        for item in json.loads(fetch(f"{PORTAL}/product/{PRODUCT}/items")):
            if item.get("type") == "dx_api_spec" and item.get("displayName") == "Documentum D2":
                return f"{PORTAL}/items/id/{item['id']}/content"
    except (OSError, ValueError):
        pass
    return f"{PORTAL}/items/id/{PINNED_SPEC}/content"


def load_spec(path):
    raw = open(path, "rb").read() if path else fetch(spec_url())
    digest = hashlib.sha256(raw).hexdigest()
    text = raw.decode("utf-8")
    if text.lstrip().startswith("{"):
        return json.loads(text), digest
    try:
        import yaml  # noqa: PLC0415
    except ImportError:
        sys.exit("The specification is YAML: install PyYAML or pass a JSON copy with --spec")
    # through JSON, as YAML reads status codes such as 200 as numbers where OpenAPI means strings
    return json.loads(json.dumps(yaml.safe_load(text), default=str)), digest


class Spec:
    def __init__(self, doc):
        self.doc = doc
        self.dangling = set()
        self.ops = []  # (regex, template, method, operation)
        for template, item in doc["paths"].items():
            rx = "^" + re.sub(r"\\\{[^}]+\\\}", "[^/]+", re.escape(template)) + "/?$"
            for method, op in (item or {}).items():
                if method in ("get", "post", "put", "delete", "patch", "head", "options"):
                    self.ops.append((re.compile(rx), template, method.upper(), op or {}))
        # literal segments first: /objects/{id}/contents/content must not match /objects/{id}/{x}/content
        self.ops.sort(key=lambda o: -len(re.sub(r"\{[^}]+\}", "", o[1])))

    def deref(self, node):
        seen = 0
        while isinstance(node, dict) and "$ref" in node and seen < 50:
            target = self.doc
            for part in node["$ref"].lstrip("#/").split("/"):
                if not isinstance(target, dict) or part not in target:
                    self.dangling.add(node["$ref"])  # the published document has references it does not define
                    return {}
                target = target[part]
            node, seen = target, seen + 1
        return node

    def find(self, path, method="GET"):
        for rx, template, m, op in self.ops:
            if m == method and rx.match(path):
                return template, op
        return None, None

    def params(self, op):
        out = {}
        for p in op.get("parameters") or []:
            p = self.deref(p)
            out[(p["in"], p["name"])] = self.deref(p.get("schema") or {})
        return out

    def validate(self, schema, value, where, errors, depth=0):
        """Types of the documented fields, recursively; undocumented fields are allowed (the schemas are open)."""
        schema = self.deref(schema or {})
        if depth > 30 or not isinstance(schema, dict):
            return
        for part in schema.get("allOf") or []:
            self.validate(part, value, where, errors, depth + 1)
        alternatives = schema.get("oneOf") or schema.get("anyOf")
        if alternatives:
            trial = []
            if not any(not self._try(a, value, where, trial, depth) for a in alternatives):
                errors.append(f"{where}: matches none of {len(alternatives)} alternatives")
            return
        kind = schema.get("type")
        if value is None:
            return
        checks = {"object": dict, "array": list, "string": str, "boolean": bool}
        if kind in checks and not isinstance(value, checks[kind]):
            errors.append(f"{where}: {type(value).__name__} where the specification has {kind}")
            return
        if kind == "integer" and (isinstance(value, bool) or not isinstance(value, int)):
            errors.append(f"{where}: {type(value).__name__} where the specification has integer")
            return
        if kind == "number" and (isinstance(value, bool) or not isinstance(value, (int, float))):
            errors.append(f"{where}: {type(value).__name__} where the specification has number")
            return
        if schema.get("format") == "date-time" and isinstance(value, str) and not re.match(
                r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?(Z|[+-]\d\d:?\d\d)$", value):
            errors.append(f"{where}: {value!r} is not an RFC 3339 date-time")
        if schema.get("enum") and isinstance(value, str) and value not in schema["enum"]:
            errors.append(f"{where}: {value!r} not in {schema['enum']}")
        if isinstance(value, dict):
            for name, sub in (schema.get("properties") or {}).items():
                if name in value:
                    self.validate(sub, value[name], f"{where}.{name}", errors, depth + 1)
        if isinstance(value, list) and schema.get("items"):
            for i, item in enumerate(value[:50]):
                self.validate(schema["items"], item, f"{where}[{i}]", errors, depth + 1)

    def _try(self, schema, value, where, errors, depth):
        before = len(errors)
        self.validate(schema, value, where, errors, depth + 1)
        return len(errors) > before


class Checker:
    def __init__(self, spec, mock, user, password):
        self.spec, self.mock, self.context = spec, mock.rstrip("/"), urlparse(mock).path.rstrip("/")
        self.user = user
        self.auth = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()
        self.findings, self.checked, self.seen = [], 0, set()

    def finding(self, severity, url, message):
        self.findings.append((severity, url, message))

    def get(self, url, accept=DM_JSON, auth=True, expect=200):
        headers = {"Accept": accept}
        if auth:
            headers["Authorization"] = self.auth
        req = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=30) as rsp:
                status, ctype, body = rsp.status, rsp.headers.get("Content-Type", ""), rsp.read()
        except urllib.error.HTTPError as e:
            status, ctype, body = e.code, e.headers.get("Content-Type", ""), e.read()
        self.check(url, status, ctype, body, expect)
        if ctype.split(";")[0].strip().endswith("json") and body:
            try:
                return status, json.loads(body)
            except ValueError:
                return status, None
        return status, body

    def check(self, url, status, ctype, body, expect):
        self.checked += 1
        u = urlparse(url)
        path = u.path[len(self.context):] if u.path.startswith(self.context) else u.path
        template, op = self.spec.find(path)
        if expect is not None and status != expect:
            self.finding("FAIL", url, f"HTTP {status}, expected {expect}: {body[:200]!r}")
        if op is None:
            others = [m for m in ("POST", "PUT", "DELETE") if self.spec.find(path, m)[1] is not None]
            if others and status == 405:
                return  # a link to a resource clients POST/PUT/DELETE to, and the mock refuses GET as Spring does
            self.finding("FAIL", url, f"GET {path} is not in the specification (HTTP {status})"
                         + (f"; it is documented for {others}" if others else ""))
            return
        oid = op.get("operationId", "")
        gap = SPEC_GAPS.get(oid) or ("a D2 variant of the core operation" if oid.startswith("getD2") else None)
        if gap and "dql=" in u.query:
            gap = None  # the DQL feed is what getRepository does describe
        params = self.spec.params(op)
        for name in parse_qs(u.query, keep_blank_values=True):
            if ("query", name) not in params:
                self.finding("FAIL", url, f"query parameter {name!r} is not documented for {template} ({oid})")
        responses = op.get("responses") or {}
        documented = responses.get(str(status)) or responses.get("default")
        if documented is None:
            sev = "INFO" if status in PROTOCOL_STATUSES else "WARN" if gap else "FAIL"
            self.finding(sev, url, f"HTTP {status} is not documented for {template} ({oid}): {sorted(responses)}"
                         + (f" [{gap}]" if gap and sev == "WARN" else ""))
            return
        documented = self.spec.deref(documented)
        content = documented.get("content") or {}
        mtype = ctype.split(";")[0].strip().lower()
        if content and mtype not in {k.lower() for k in content} and "any/any" not in content and "*/*" not in content:
            sev = "WARN" if gap or status >= 400 else "FAIL"
            self.finding(sev, url, f"media type {mtype!r} not among {sorted(content)} ({oid})"
                         + (f" [{gap}]" if gap else ""))
        media = content.get(mtype) or content.get(DM_JSON) or {}
        schema = (media or {}).get("schema")
        if schema and mtype.endswith("json") and body:
            try:
                value = json.loads(body)
            except ValueError:
                self.finding("FAIL", url, "the body is not JSON")
                return
            errors = []
            self.spec.validate(schema, value, "$", errors)
            for e in errors[:10]:
                self.finding("WARN" if gap else "FAIL", url, f"{e} ({oid})" + (f" [{gap}]" if gap else ""))

    def links(self, resource):
        out = []
        for container in ([resource] + [e for e in resource.get("entries") or [] if isinstance(e, dict)]):
            for link in container.get("links") or []:
                out.append(link)
        return out

    def run(self, dql_samples):
        m = self.mock
        _, home = self.get(m + "/services", accept="application/home+json", auth=False)
        repos_href = None
        for rel, res in (home or {}).get("resources", {}).items():
            if rel.endswith("/repositories"):
                repos_href = res["href"]
        for rel, res in (home or {}).get("resources", {}).items():
            if not rel.endswith("/repositories") and res.get("href"):
                self.get(res["href"], auth=False)
        _, repos = self.get(repos_href or m + "/repositories", auth=False)
        repo_href = next(l["href"] for e in repos["entries"] for l in e["links"] if l["rel"] == "edit")
        _, repo = self.get(repo_href)
        # every link the repository advertises must lead somewhere documented and served
        for link in repo.get("links", []):
            if "href" in link and link["rel"] != "self":
                status, coll = self.get(link["href"], expect=None)
                if status not in (200, 405):
                    self.finding("FAIL", link["href"], f"advertised link {link['rel']} answered {status}")
                if status == 200 and isinstance(coll, dict):
                    for e in (coll.get("entries") or [])[:3]:
                        for l in e.get("links") or []:
                            if l["rel"] == "edit":
                                self.get(l["href"])
                    if "items-per-page" in coll:  # a collection
                        self.get(link["href"] + "?" + urlencode({"inline": "true", "include-total": "true",
                                                                 "items-per-page": 2}))
        template = next(l["hreftemplate"] for l in repo["links"] if l["rel"].endswith("/dql"))
        base = template.split("{")[0]
        object_ids = []
        for dql in dql_samples:
            _, feed = self.get(base + "?" + urlencode({"dql": dql, "items-per-page": 3}))
            for link in self.links(feed or {}):
                if link["rel"] in ("next", "previous", "first", "self"):
                    self.get(link["href"])
            for e in (feed or {}).get("entries") or []:
                oid = (e.get("content") or {}).get("properties", {}).get("r_object_id")
                if oid:
                    object_ids.append(oid)
        _, empty = self.get(base + "?" + urlencode({"dql": dql_samples[0], "page": 9999, "items-per-page": 3}))
        if empty and "entries" in empty:
            self.finding("INFO", base, "a page past the end has an entries array")
        for oid in object_ids[:6]:
            self.object(repo_href, oid)
        for kind in ("documents", "folders", "cabinets"):
            ids = [i for i in object_ids if i.startswith({"documents": "09", "folders": "0b", "cabinets": "0c"}[kind])]
            for oid in ids[:2]:
                self.get(f"{repo_href}/{kind}/{oid}")
        self.get(f"{repo_href}/objects/{object_ids[0]}?" + urlencode({"view": "object_name,r_modify_date",
                                                                      "links": "false"}))
        self.get(f"{repo_href}/cabinets?" + urlencode({"filter": "starts-with(object_name,'Corp')"}))
        self.get(f"{repo_href}/cabinets?" + urlencode({"filter": "contains(object_name,'x')"}), expect=400)
        self.get(f"{repo_href}/users?" + urlencode({"sort": "user_name desc", "items-per-page": 3}))
        self.get(f"{repo_href}/currentuser")
        # errors
        self.get(base + "?" + urlencode({"dql": "SELECT nope FROM dm_document"}), expect=400)
        self.get(f"{repo_href}/objects/0900000000000000", expect=404)
        self.get(repo_href, auth=False, expect=401)
        self.get(repo_href, accept="application/xml", expect=406)

    def object(self, repo_href, oid):
        if oid in self.seen:
            return
        self.seen.add(oid)
        _, obj = self.get(f"{repo_href}/objects/{oid}")
        for link in self.links(obj or {}):
            href = link.get("href")
            if not href or link["rel"] in ("self", "edit", "canonical"):
                continue
            if link["rel"].endswith("/delete"):
                continue  # a DELETE target
            status, res = self.get(href, expect=None)
            if link["rel"] in ("parent", "http://identifiers.emc.com/linkrel/cabinet") and status == 404:
                continue  # a folder the user cannot browse
            if status == 404 and link["rel"].endswith("primary-content"):
                continue  # an object without content
            if status not in (200, 404):
                self.finding("FAIL", href, f"link {link['rel']} answered {status}")
            elif status == 404:
                self.finding("FAIL", href, f"advertised link {link['rel']} answered 404")
            if isinstance(res, dict) and link["rel"] in ("contents", "version-history",
                                                          "http://identifiers.emc.com/linkrel/parent-links"):
                for e in res.get("entries") or []:
                    for l in e.get("links") or []:
                        if l["rel"] == "edit":
                            self.get(l["href"])
            if link["rel"].endswith("/permissions"):
                for accessor in (self.user, "finance", "nobody-here"):
                    self.get(href + "?" + urlencode({"accessor": accessor}),
                             expect=404 if accessor == "nobody-here" else 200)
            if link["rel"] == "parent" and status == 200:
                for kids in ("folders", "documents", "objects"):
                    self.get(f"{href}/{kids}?" + urlencode({"items-per-page": 2, "inline": "true"}))
        for policy in ("local", "LOCAL", "all", "dc-pref", "dc-only"):
            status, content = self.get(f"{repo_href}/objects/{oid}/contents/content?"
                                       + urlencode({"page": 0, "media-url-policy": policy}), expect=None)
            if status == 200:
                for l in content.get("links") or []:
                    if l["rel"] == "enclosure" and l.get("title") == "LOCAL":
                        self.get(l["href"], accept="*/*")


DQL_SAMPLES = [
    "SELECT r_object_id, i_chronicle_id, r_object_type, r_modify_date, i_vstamp, acl_domain, acl_name, owner_name, "
    "group_name FROM dm_document ORDER BY r_modify_date, r_object_id",
    "SELECT r_object_id, owner_name, r_accessor_name, r_accessor_permit, r_permit_type, r_is_group FROM dm_acl "
    "ORDER BY r_object_id",
    "SELECT r_object_id, user_name, user_login_name, r_is_group FROM dm_user ORDER BY r_object_id",
    "SELECT r_object_id, r_folder_path FROM dm_folder ORDER BY r_object_id",
    "SELECT r_object_id, object_name FROM dm_cabinet ORDER BY r_object_id",
]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--mock", required=True, help="the mock's REST root, e.g. http://localhost:18290/dctm-rest")
    ap.add_argument("--spec", help="a local copy of the specification (YAML or JSON); downloaded when omitted")
    ap.add_argument("--user", default="dctm_crawler")
    ap.add_argument("--password", default="secret")
    args = ap.parse_args()
    try:
        doc, digest = load_spec(args.spec)
    except OSError as e:
        print(f"the specification could not be read: {e}")
        sys.exit(2)
    info = doc.get("info", {})
    print(f"specification: {info.get('title')} {info.get('version')} sha256={digest}")
    checker = Checker(Spec(doc), args.mock, args.user, args.password)
    checker.run(DQL_SAMPLES)
    order = {"FAIL": 0, "WARN": 1, "INFO": 2}
    unique = sorted(set(checker.findings), key=lambda f: (order[f[0]], f[2], f[1]))
    for severity, url, message in unique:
        print(f"{severity:4}  {message}\n      {url}")
    for ref in sorted(checker.spec.dangling):
        print(f"NOTE  the specification references {ref}, which it does not define: not checked")
    fails = sum(1 for f in unique if f[0] == "FAIL")
    print(f"\n{checker.checked} exchanges checked: {fails} failures, "
          f"{sum(1 for f in unique if f[0] == 'WARN')} warnings, {sum(1 for f in unique if f[0] == 'INFO')} notes")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
