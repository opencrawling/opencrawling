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
Conformance check against IBM's published Content Services GraphQL schema.

Without a FileNet installation to test against, two things keep the mock and the connector honest:

1. The mock's schema (schema.graphql) is a subset of IBM's: every type exists with the same kind, every field and
   argument with exactly the same type, and every interface an object type implements is implemented in IBM's too.
2. Every GraphQL document the connector sends (src/main/resources/filenet-graphql/*.graphql, with the shared
   *.fragment.graphql it spreads) validates against IBM's full schema and the mock's, so the connector asks nothing a real server would reject.

IBM's schema is downloaded at a pinned commit (it is distributed under IBM's licence and is not part of this
repository). Pass --schema to use a local copy. Exit code 0 means conformant; 2 means the schema could not be loaded.

    python3 conformance.py [--schema csgraphql_schema.graphqls] [--queries DIR]
"""

import argparse
import pathlib
import re
import sys
import urllib.request

from graphql import (GraphQLEnumType, GraphQLInterfaceType, GraphQLObjectType, GraphQLScalarType, build_schema, parse,
                     validate)

IBM_COMMIT = "5c9d21862d486fa105e91580f69d790c4f27012d"
IBM_SCHEMA_URL = ("https://raw.githubusercontent.com/ibm-ecm/ibm-content-platform-engine-samples/"
                  f"{IBM_COMMIT}/CS-GraphQL-Schema/csgraphql_schema.graphqls")
HERE = pathlib.Path(__file__).resolve().parent
DEFAULT_QUERIES = HERE.parent / "src" / "main" / "resources" / "filenet-graphql"


def load_ibm(path):
    if path:
        return pathlib.Path(path).read_text(encoding="utf-8")
    with urllib.request.urlopen(IBM_SCHEMA_URL, timeout=60) as response:
        return response.read().decode("utf-8")


def kind(t):
    for k in (GraphQLObjectType, GraphQLInterfaceType, GraphQLEnumType, GraphQLScalarType):
        if isinstance(t, k):
            return k.__name__.replace("GraphQL", "").replace("Type", "") or "Object"
    return type(t).__name__


def subset_problems(ours, ibm):
    problems = []
    for name, t in ours.type_map.items():
        if name.startswith("__"):
            continue
        theirs = ibm.type_map.get(name)
        if theirs is None:
            problems.append(f"type {name}: not in IBM's schema")
            continue
        if kind(t) != kind(theirs):
            problems.append(f"type {name}: {kind(t)} here, {kind(theirs)} in IBM's schema")
            continue
        if isinstance(t, GraphQLEnumType):
            extra = set(t.values) - set(theirs.values)
            if extra:
                problems.append(f"enum {name}: values {sorted(extra)} not in IBM's schema")
            continue
        if isinstance(t, (GraphQLObjectType, GraphQLInterfaceType)):
            missing = {i.name for i in t.interfaces} - {i.name for i in theirs.interfaces}
            if missing:
                problems.append(f"type {name}: implements {sorted(missing)}, IBM's does not")
            for field_name, field in t.fields.items():
                their_field = theirs.fields.get(field_name)
                if their_field is None:
                    problems.append(f"field {name}.{field_name}: not in IBM's schema")
                    continue
                if str(field.type) != str(their_field.type):
                    problems.append(f"field {name}.{field_name}: {field.type} here, {their_field.type} in IBM's schema")
                for arg_name, arg in field.args.items():
                    their_arg = their_field.args.get(arg_name)
                    if their_arg is None:
                        problems.append(f"argument {name}.{field_name}({arg_name}): not in IBM's schema")
                    elif str(arg.type) != str(their_arg.type):
                        problems.append(f"argument {name}.{field_name}({arg_name}): {arg.type} here, "
                                        f"{their_arg.type} in IBM's schema")
                # an argument IBM requires must be required here as well, or the mock accepts what FileNet rejects
                for arg_name, their_arg in their_field.args.items():
                    if str(their_arg.type).endswith("!") and arg_name not in field.args:
                        problems.append(f"argument {name}.{field_name}({arg_name}): required by IBM, missing here")
    return problems


def query_problems(schema, label, queries_dir):
    problems = []
    every = sorted(pathlib.Path(queries_dir).glob("*.graphql"))
    fragments = [f for f in every if f.name.endswith(".fragment.graphql")]
    files = [f for f in every if f not in fragments]
    if not files:
        problems.append(f"no GraphQL documents in {queries_dir}")
    for f in files:
        # like the connector, append the shared fragments an operation spreads
        text = f.read_text(encoding="utf-8")
        for fragment in fragments:
            fragment_text = fragment.read_text(encoding="utf-8")
            name = re.search(r"fragment\s+(\w+)", fragment_text).group(1)
            if "..." + name in text:
                text += "\n" + fragment_text
        try:
            errors = validate(schema, parse(text))
        except Exception as e:  # a syntax error
            errors = [e]
        problems += [f"{f.name} against {label}: {e}" for e in errors]
    return problems, len(files)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--schema", help="a local copy of IBM's csgraphql_schema.graphqls (default: download)")
    parser.add_argument("--queries", default=str(DEFAULT_QUERIES), help="the connector's GraphQL documents")
    args = parser.parse_args()
    try:
        ibm = build_schema(load_ibm(args.schema))
    except Exception as e:
        print(f"Could not load IBM's schema: {e}", file=sys.stderr)
        return 2
    ours = build_schema((HERE / "schema.graphql").read_text(encoding="utf-8"))

    problems = subset_problems(ours, ibm)
    ibm_problems, count = query_problems(ibm, "IBM's schema", args.queries)
    mock_problems, _ = query_problems(ours, "the mock's schema", args.queries)
    problems += ibm_problems + mock_problems
    print(f"IBM schema {IBM_COMMIT[:7]}: {len(ibm.type_map)} types; mock schema: "
          f"{len([n for n in ours.type_map if not n.startswith('__')])} types; {count} connector documents")
    for p in problems:
        print("  NOT CONFORMANT: " + p)
    print("conformant" if not problems else f"{len(problems)} problem(s)")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
