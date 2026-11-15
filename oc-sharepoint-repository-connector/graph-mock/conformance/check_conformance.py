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
Checks recorded Graph traffic against Microsoft's own Microsoft Graph v1.0 OpenAPI description
(github.com/microsoftgraph/msgraph-metadata, openapi/v1.0/openapi.yaml, at a pinned commit).

Input: one or more JSON-lines recordings (graph_mock.py with GRAPH_RECORD=<file>), or JSON example files (--examples).
For every Graph exchange it checks that
  * the request's path and method exist in the spec (with Graph's documented addressing aliases: path-based
    addressing "root:/a/b:" and "sites/{host}:/{path}" address an item / a site, "root" is the root driveItem, and a
    function may be called without its parentheses);
  * every query parameter is one the operation declares, or one of the OData paging options Graph puts in its links;
  * a 2xx JSON body validates against the operation's response schema, and an error body against Graph's error schema;
  * (strict) no object carries a property its schema doesn't declare: this is what catches a field the emulator
    invented. Instance annotations ("@odata.*", "@microsoft.graph.*") and open types (fieldValueSet) are exempt, and
    so is a property that Microsoft's own examples (--documented, from fetch_doc_examples.py) show in the same place
    (the same last three path segments, e.g. lastModifiedBy.user.email): those are listed, not failed.
It also checks, over a recording: the Entra token responses against the documented OAuth 2.0 shape; that no download
request carried an Authorization header; that every Graph request carried a client-request-id (Microsoft's guidance);
and that every request with a paging token is exactly a link Graph returned before (@odata.nextLink, @odata.deltaLink,
or a 410's Location): a client must follow paging links verbatim, never build them. Exits 1 when anything fails.

Run with uv (no install): uv run --no-project --with pyyaml --with jsonschema python -I check_conformance.py ...
"""

import argparse
import json
import pickle
import re
import sys
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

import jsonschema
import yaml

SPEC_URL = "https://raw.githubusercontent.com/microsoftgraph/msgraph-metadata/{sha}/openapi/v1.0/openapi.yaml"
# OData options Graph itself writes into @odata.nextLink / @odata.deltaLink and Location headers
PAGING_OPTIONS = {"$skiptoken", "$deltatoken", "token"}
# open types: any property is allowed (list item columns are named by the site)
OPEN_TYPES = {"microsoft.graph.fieldValueSet"}


def load_spec(cache: Path, sha: str):
    cache.mkdir(parents=True, exist_ok=True)
    pickled = cache / f"openapi-{sha}.pickle"
    if pickled.exists():
        return pickle.loads(pickled.read_bytes())
    raw = cache / f"openapi-{sha}.yaml"
    if not raw.exists():
        print(f"Downloading the Graph v1.0 OpenAPI description at {sha[:12]} ...", file=sys.stderr)
        with urllib.request.urlopen(SPEC_URL.format(sha=sha), timeout=300) as r, open(raw, "wb") as out:
            out.write(r.read())
    spec = yaml.load(raw.read_text(encoding="utf-8"), Loader=getattr(yaml, "CSafeLoader", yaml.SafeLoader))
    pickled.write_bytes(pickle.dumps(spec))
    return spec


class Spec:
    def __init__(self, spec):
        self.spec = spec
        self.schemas = spec["components"]["schemas"]
        # Graph omits "@odata.type" when an instance has its declared type, although the description marks it required
        for schema in self.schemas.values():
            self._drop_odata_type(schema)
        self.templates = []
        for template, item in spec["paths"].items():
            regex = "^" + re.sub(r"\\\{[^}]+\\\}", "[^/]+", re.escape(template)) + "$"
            self.templates.append((re.compile(regex), template, item))
        self.validator_cache = {}

    @staticmethod
    def _drop_odata_type(schema):
        if isinstance(schema, dict):
            if isinstance(schema.get("required"), list) and "@odata.type" in schema["required"]:
                schema["required"] = [r for r in schema["required"] if r != "@odata.type"]
            for value in schema.values():
                Spec._drop_odata_type(value)
        elif isinstance(schema, list):
            for value in schema:
                Spec._drop_odata_type(value)

    def resolve(self, node):
        while isinstance(node, dict) and "$ref" in node:
            parts = node["$ref"].lstrip("#/").split("/")
            node = self.spec
            for p in parts:
                node = node[p]
        return node

    def match(self, path):
        """The spec path template for a concrete Graph path (without /v1.0), after Graph's addressing aliases."""
        candidates = []
        aliased = re.sub(r"^/sites/[^/:]+:/[^:]+:?(?=/|$)", "/sites/{site-id}", path)       # sites/{host}:/{path}
        aliased = re.sub(r"^/(me|(users|groups|sites)/[^/]+)/drive(?=/items|/root)", "/drives/{drive-id}", aliased)  # a drive
        aliased = re.sub(r"/root:/[^:]*:?(?=/|$)", "/items/{driveItem-id}", aliased)          # root:/a/b: path addressing
        aliased = re.sub(r"/root(?=/|$)", "/items/{driveItem-id}", aliased)                    # root = the root item
        candidates += [aliased, path] if ":" in path else [path, aliased]
        candidates += [re.sub(r"/(delta)$", r"/\1()", c) for c in list(candidates)]           # function without ()
        for c in candidates:
            for regex, template, item in self.templates:
                if regex.match(c):
                    return template, item, c != path
        return None, None, False

    def response_schema(self, operation, status):
        responses = operation.get("responses", {})
        key = next((k for k in (str(status), f"{str(status)[0]}XX", "default") if k in responses), None)
        if key is None:
            return None
        response = self.resolve(responses[key])
        content = response.get("content", {}).get("application/json")
        return content.get("schema") if content else None

    def query_parameters(self, path_item, operation):
        names = set()
        for p in path_item.get("parameters", []) + operation.get("parameters", []):
            p = self.resolve(p)
            if p.get("in") == "query":
                names.add(p["name"])
        return names

    def validator(self, schema):
        key = json.dumps(schema, sort_keys=True)
        if key not in self.validator_cache:
            wrapped = dict(schema)
            wrapped["components"] = self.spec["components"]
            self.validator_cache[key] = OAS30Validator(wrapped)
        return self.validator_cache[key]

    # -- strict: undeclared properties

    def declared(self, schema, seen=None):
        """(property name -> sub-schema, open?) for an object schema, merging allOf / anyOf / oneOf."""
        seen = seen if seen is not None else set()
        if isinstance(schema, dict) and "$ref" in schema:
            name = schema["$ref"].rsplit("/", 1)[-1]
            if name in OPEN_TYPES:
                return {}, True
            if name in seen:
                return {}, False
            seen = seen | {name}
        schema = self.resolve(schema)
        props, open_ = dict(schema.get("properties", {})), False
        if schema.get("additionalProperties") not in (None, False):
            open_ = True
        for key in ("allOf", "anyOf", "oneOf"):
            for sub in schema.get(key, []):
                p, o = self.declared(sub, seen)
                for k, v in p.items():
                    props.setdefault(k, v)
                open_ = open_ or o
        return props, open_

    def undeclared(self, instance, schema, where="$"):
        out = []
        if isinstance(instance, dict):
            props, open_ = self.declared(schema)
            if not open_:
                for k in instance:
                    if not k.startswith("@") and k not in props:
                        out.append(f"{where}.{k}")
            for k, v in instance.items():
                if k in props:
                    out += self.undeclared(v, props[k], f"{where}.{k}")
        elif isinstance(instance, list):
            items = self.resolve(schema).get("items") if isinstance(self.resolve(schema), dict) else None
            for i, v in enumerate(instance):
                if items is not None:
                    out += self.undeclared(v, items, f"{where}[{i}]")
        return out


class OAS30Validator:
    """OpenAPI 3.0 schema validation on top of jsonschema: nullable, and $refs into #/components."""

    def __init__(self, schema):
        self.schema = self._convert(schema)
        self.validator = jsonschema.Draft4Validator(self.schema)

    @classmethod
    def _convert(cls, node):
        if isinstance(node, dict):
            node = {k: cls._convert(v) for k, v in node.items()
                    if k not in ("discriminator", "example", "x-ms-discriminator-value", "readOnly", "writeOnly")}
            if node.pop("nullable", False):
                if "type" in node:
                    node["type"] = [node["type"], "null"] if isinstance(node["type"], str) else node["type"] + ["null"]
                elif "$ref" in node or "anyOf" in node or "allOf" in node:
                    node = {"anyOf": [node, {"type": "null"}]}
            if node.get("format") in ("date-time", "uuid", "int32", "int64", "float", "double", "base64url", "duration", "date", "time"):
                node.pop("format")  # format checks are informational in the Graph description; patterns already constrain
            return node
        if isinstance(node, list):
            return [cls._convert(v) for v in node]
        return node

    def errors(self, instance):
        return sorted(self.validator.iter_errors(instance), key=lambda e: list(e.absolute_path))


def context(path):
    """$.value[3].lastModifiedBy.user.email -> lastModifiedBy.user.email"""
    return ".".join(re.sub(r"\[\d+\]", "", path).lstrip("$.").split(".")[-3:])


def documented_contexts(directory):
    """Every property position in Microsoft's example payloads, as context() strings, with an example source."""
    seen = {}

    def walk(node, path, source):
        if isinstance(node, dict):
            for k, v in node.items():
                seen.setdefault(context(f"{path}.{k}"), source)
                walk(v, f"{path}.{k}", source)
        elif isinstance(node, list):
            for v in node:
                walk(v, path, source)

    for f in sorted(directory.glob("*/*.json")):
        example = json.loads(f.read_text(encoding="utf-8"))
        walk(example.get("body"), "$", example.get("source", f.name))
    return seen


def check_token(entry, problems):
    body = entry.get("body") or {}
    if entry["status"] == 200:
        for field, kind in (("token_type", str), ("expires_in", int), ("access_token", str)):
            if not isinstance(body.get(field), kind):
                problems.append(f"token response lacks {field} ({kind.__name__})")
        if body.get("token_type") != "Bearer":
            problems.append("token_type must be Bearer")
    else:
        # https://learn.microsoft.com/entra/identity-platform/v2-oauth2-client-creds-grant-flow#error-response
        if not isinstance(body.get("error"), str) or not isinstance(body.get("error_description"), str):
            problems.append("token error lacks error / error_description")
        elif not re.match(r"AADSTS\d+", body["error_description"]):
            problems.append("token error_description should start with an AADSTS code")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("recordings", nargs="*", type=Path, help="JSON-lines recordings from graph_mock.py")
    parser.add_argument("--examples", nargs="*", type=Path, default=[],
                        help="JSON files {\"method\", \"url\", \"status\", \"body\", \"source\"} (e.g. Microsoft's doc examples)")
    parser.add_argument("--sha", required=True, help="msgraph-metadata commit to check against")
    parser.add_argument("--cache", type=Path, default=Path.home() / ".cache" / "opencrawling" / "graph-openapi")
    parser.add_argument("--strict", action="store_true", help="fail on properties the schema doesn't declare")
    parser.add_argument("--documented", type=Path, help="fetch_doc_examples.py output: accept what Microsoft's examples show")
    args = parser.parse_args()

    spec = Spec(load_spec(args.cache, args.sha))
    documented = documented_contexts(args.documented) if args.documented else {}
    accepted = {}
    entries = []
    for recording in args.recordings:
        with open(recording, encoding="utf-8") as f:
            entries += [dict(json.loads(line), source=f"{recording.name}:{n}") for n, line in enumerate(f, 1) if line.strip()]
    for example in args.examples:
        entries.append(dict(json.loads(example.read_text(encoding="utf-8")), source=example.name))

    failures = defaultdict(list)  # route -> problems
    checked = Counter()
    returned_links = set()  # path + query of every paging link Graph handed out so far
    for e in entries:
        url = urlsplit(e["url"])
        path = unquote(url.path)
        status = e["status"]
        if e.get("kind") == "other":  # record_proxy.py: neither Graph, token nor download (e.g. a mock's admin API)
            continue
        if e.get("kind") == "token" or re.fullmatch(r"/[^/]+/oauth2/v2\.0/token", path):
            problems = []
            check_token(e, problems)
            route = f"{e['method']} /{{tenant}}/oauth2/v2.0/token {status}"
            checked[route] += 1
            failures[route] += [f"{e['source']}: {p}" for p in problems]
            continue
        if e.get("kind") == "download" or path.endswith("/_layouts/15/download.aspx"):
            route = f"{e['method']} {{download URL}} {status}"
            checked[route] += 1
            if e.get("authorization"):
                failures[route].append(f"{e['source']}: a download request carried an Authorization header")
            continue
        if not path.startswith("/v1.0/"):
            failures[f"{e['method']} {path}"].append(f"{e['source']}: not a Graph v1.0 request")
            continue
        if "clientRequestId" in e and not e["clientRequestId"]:  # recordings only: example files don't carry it
            failures[f"{e['method']} (any Graph route)"].append(f"{e['source']}: no client-request-id header")
        query = parse_qs(url.query, keep_blank_values=True)
        if "clientRequestId" in e and PAGING_OPTIONS & set(query) and f"{url.path}?{url.query}" not in returned_links:
            failures[f"{e['method']} (paging)"].append(f"{e['source']}: paging URL not returned by Graph: {e['url'][:160]}")
        body_links = e.get("body") if isinstance(e.get("body"), dict) else {}
        for link in [body_links.get("@odata.nextLink"), body_links.get("@odata.deltaLink"), (e.get("headers") or {}).get("Location")]:
            if link:
                parts = urlsplit(link)
                returned_links.add(f"{parts.path}?{parts.query}")
        template, path_item, aliased = spec.match(path[len("/v1.0"):])
        if template is None:
            failures[f"{e['method']} {path}"].append(f"{e['source']}: path not in the Graph v1.0 description")
            continue
        route = f"{e['method']} {template}{' (alias)' if aliased else ''} {status}"
        checked[route] += 1
        operation = path_item.get(e["method"].lower())
        if operation is None:
            failures[route].append(f"{e['source']}: method {e['method']} not defined for {template}")
            continue
        allowed = spec.query_parameters(path_item, operation) | PAGING_OPTIONS
        for name in parse_qs(url.query, keep_blank_values=True):
            if name not in allowed and f"${name}" not in allowed:  # the $ of OData options is optional on v1.0
                failures[route].append(f"{e['source']}: query parameter {name} not declared")
        body = e.get("body")
        if status in (204, 302) or body is None:
            if status == 302 and not (e.get("headers") or {}).get("Location"):
                failures[route].append(f"{e['source']}: 302 without Location")
            continue
        schema = spec.response_schema(operation, status)
        if schema is None:
            failures[route].append(f"{e['source']}: no response schema for status {status}")
            continue
        for err in spec.validator(schema).errors(body)[:5]:
            failures[route].append(f"{e['source']}: {'/'.join(map(str, err.absolute_path)) or '$'}: {err.message[:200]}")
        if args.strict:
            for prop in sorted(set(spec.undeclared(body, schema))):
                if context(prop) in documented:
                    accepted.setdefault(context(prop), documented[context(prop)])
                else:
                    failures[route].append(f"{e['source']}: undeclared property {prop}")

    width = max((len(r) for r in checked), default=10)
    bad = 0
    for route in sorted(set(checked) | set(failures)):
        problems = failures.get(route, [])
        distinct = sorted(set(re.sub(r"^[^:]+:\d+: |^[^:]+\.json: ", "", p) for p in problems))
        print(f"{'FAIL' if problems else 'ok  '}  {route:<{width}}  x{checked.get(route, 0)}")
        for p in distinct[:12]:
            print(f"        - {p}")
        if len(distinct) > 12:
            print(f"        ... {len(distinct) - 12} more")
        bad += bool(problems)
    for prop, source in sorted(accepted.items()):
        print(f"note  {prop}: not in the schema, but shown by Microsoft's example {source}")
    print(f"\n{sum(checked.values())} exchanges, {len(checked)} routes, {bad} failing (Graph v1.0 description {args.sha[:12]}"
          f"{', strict' if args.strict else ''}).")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
