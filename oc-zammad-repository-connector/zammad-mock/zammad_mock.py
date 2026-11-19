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
Zammad REST API v1 emulator for testing OpenCrawling's Zammad Repository Connector
(oc-zammad-repository-connector) without requiring a full multi-container Zammad +
Elasticsearch stack. Uses Python's standard library only.

Serves a deterministic seed dataset covering:
  - Support tickets across multiple groups, states, priorities, and tags
  - Public customer articles vs. confidential internal agent notes
  - Binary/text article attachments (plus synthetic content-alternative email body attachments)
  - Shared (shared=true) vs. non-shared (shared=false) customer organizations
  - A fail-closed orphan ticket referencing an unresolvable group ID
  - Published, internal, and archived Knowledge Base answers (both inline and /answers/{id} fetched)
  - HTTP Token (Authorization: Token token=...), OAuth2 Bearer, Basic auth, and X-On-Behalf-Of
  - Incremental search filtering (updated_at:>=..., group.name:"...", group_id:...)
  - Mock-only admin endpoints under /mock/* to inspect state, mutate tickets/KB, and inject faults
"""

import base64
import copy
import json
import os
import re
import sys
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

PORT = int(os.environ.get("ZAMMAD_PORT", "8080"))
API_TOKEN = os.environ.get("ZAMMAD_MOCK_TOKEN", "zammad-test-token")
OAUTH_TOKEN = os.environ.get("ZAMMAD_MOCK_OAUTH_TOKEN", "zammad-oauth-token")
BASIC_USER = os.environ.get("ZAMMAD_MOCK_USER", "crawler@acme.com")
BASIC_PASSWORD = os.environ.get("ZAMMAD_MOCK_PASSWORD", "secret")
QUIET = os.environ.get("ZAMMAD_QUIET", "false").lower() == "true"


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(value):
    if not value:
        return None
    raw = value.strip()
    if raw.endswith("Z"):
        raw = raw[:-1] + "+00:00"
    try:
        dt = datetime.fromisoformat(raw)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc)
    except ValueError:
        return None


class ZammadStore:
    def __init__(self):
        self.lock = threading.RLock()
        self.reset()

    def reset(self):
        with self.lock:
            self.current_user = {
                "id": 2,
                "login": BASIC_USER,
                "email": BASIC_USER,
                "firstname": "OpenCrawling",
                "lastname": "Crawler",
                "active": True,
            }
            self.groups = {
                4: {"id": 4, "name": "Tier-2-Identity-Security", "active": True},
                5: {"id": 5, "name": "Billing-Operations", "active": True},
                6: {"id": 6, "name": "Archived-Queue", "active": False},
            }
            self.roles = {
                1: {"id": 1, "name": "Agent", "active": True},
                2: {"id": 2, "name": "Customer", "active": True},
                3: {"id": 3, "name": "Security-Auditor", "active": True},
            }
            self.ticket_states = {
                1: {"id": 1, "name": "new"},
                2: {"id": 2, "name": "open"},
                3: {"id": 3, "name": "pending reminder"},
                4: {"id": 4, "name": "closed"},
            }
            self.ticket_priorities = {
                1: {"id": 1, "name": "1 low"},
                2: {"id": 2, "name": "2 normal"},
                3: {"id": 3, "name": "3 high"},
            }
            self.users = {
                2: copy.deepcopy(self.current_user),
                101: {"id": 101, "login": "claire.dupont@clientcorp.eu", "email": "claire.dupont@clientcorp.eu"},
                102: {"id": 102, "login": "lukas.meyer@privatebank.ch", "email": "lukas.meyer@privatebank.ch"},
                103: {"id": 103, "login": "sofia.rossi@acme.com", "email": "sofia.rossi@acme.com"},
                202: {"id": 202, "login": "marcus.weber@acme.com", "email": "marcus.weber@acme.com"},
                203: {"id": 203, "login": "elena.kovacs@acme.com", "email": "elena.kovacs@acme.com"},
            }
            self.organizations = {
                301: {"id": 301, "name": "ClientCorp Europe", "shared": True, "active": True},
                302: {"id": 302, "name": "PrivateBank AG", "shared": False, "active": True},
            }
            self.tickets = {
                4821: {
                    "id": 4821,
                    "number": "9402817",
                    "title": "SSO SAML 2.0 signature validation failure",
                    "group_id": 4,
                    "state_id": 2,
                    "priority_id": 3,
                    "customer_id": 101,
                    "organization_id": 301,
                    "owner_id": 202,
                    "created_at": "2026-10-18T08:12:40Z",
                    "updated_at": "2026-10-19T14:30:11Z",
                    "close_at": None,
                },
                4822: {
                    "id": 4822,
                    "number": "9402818",
                    "title": "PrivateBank MFA hardware token reset",
                    "group_id": 4,
                    "state_id": 2,
                    "priority_id": 2,
                    "customer_id": 102,
                    "organization_id": 302,
                    "owner_id": 202,
                    "created_at": "2026-10-18T09:00:00Z",
                    "updated_at": "2026-10-19T15:00:00Z",
                    "close_at": None,
                },
                4823: {
                    "id": 4823,
                    "number": "9402819",
                    "title": "Q3 enterprise subscription invoice VAT update",
                    "group_id": 5,
                    "state_id": 4,
                    "priority_id": 1,
                    "customer_id": 103,
                    "organization_id": None,
                    "owner_id": 203,
                    "created_at": "2026-10-18T10:00:00Z",
                    "updated_at": "2026-10-19T16:00:00Z",
                    "close_at": "2026-10-19T16:00:00Z",
                },
                # Orphan ticket with an unresolvable group_id (999) to verify fail-closed ACL enforcement
                4824: {
                    "id": 4824,
                    "number": "9402820",
                    "title": "Orphan unassigned security escalation",
                    "group_id": 999,
                    "state_id": 2,
                    "priority_id": 2,
                    "customer_id": 101,
                    "organization_id": 301,
                    "owner_id": 202,
                    "created_at": "2026-10-18T11:00:00Z",
                    "updated_at": "2026-10-19T16:30:00Z",
                    "close_at": None,
                },
            }
            self.articles = {
                4821: [
                    {
                        "id": 1001,
                        "ticket_id": 4821,
                        "sender": "Customer",
                        "type": "email",
                        "from": "claire.dupont@clientcorp.eu",
                        "subject": "SAML failure",
                        "body": "Our SSO SAML 2.0 login fails after rotating the IdP certificate. Reference ZMD-4821-PUB.",
                        "content_type": "text/plain",
                        "internal": False,
                        "created_at": "2026-10-18T08:12:40Z",
                        "updated_at": "2026-10-18T08:12:40Z",
                        "attachments": [
                            {
                                "id": 501,
                                "filename": "saml-trace.log",
                                "size": len(b"SAML Response Signature Error trace ZMD-4821-ATT-PUB"),
                                "preferences": {"Mime-Type": "text/plain"},
                            },
                            # Synthetic email body attachment that the connector must skip
                            {
                                "id": 599,
                                "filename": "message.html",
                                "size": 42,
                                "preferences": {
                                    "Mime-Type": "text/html",
                                    "content-alternative": True,
                                },
                            },
                        ],
                    },
                    {
                        "id": 1002,
                        "ticket_id": 4821,
                        "sender": "Agent",
                        "type": "note",
                        "from": "marcus.weber@acme.com",
                        "subject": "Internal root cause",
                        "body": "<p>Confidential internal note: IdP metadata cache on node-3 was stale. Reference ZMD-4821-INT.</p>",
                        "content_type": "text/html",
                        "internal": True,
                        "created_at": "2026-10-18T09:00:00Z",
                        "updated_at": "2026-10-18T09:00:00Z",
                        "attachments": [
                            {
                                "id": 502,
                                "filename": "internal-runbook.txt",
                                "size": len(b"CONFIDENTIAL AGENT RUNBOOK STEP ZMD-4821-ATT-INT"),
                                "preferences": {"Mime-Type": "text/plain"},
                            }
                        ],
                    },
                ],
                4822: [
                    {
                        "id": 2001,
                        "ticket_id": 4822,
                        "sender": "Customer",
                        "type": "web",
                        "from": "lukas.meyer@privatebank.ch",
                        "subject": "Hardware token reset",
                        "body": "PrivateBank MFA hardware token reset request for SSO authentication. Reference ZMD-4822-PUB.",
                        "content_type": "text/plain",
                        "internal": False,
                        "created_at": "2026-10-18T09:00:00Z",
                        "updated_at": "2026-10-18T09:00:00Z",
                        "attachments": [],
                    },
                    {
                        "id": 2002,
                        "ticket_id": 4822,
                        "sender": "Agent",
                        "type": "note",
                        "from": "marcus.weber@acme.com",
                        "subject": "Internal identity check",
                        "body": "<p>Internal identity verification completed for PrivateBank SSO token. Reference ZMD-4822-INT.</p>",
                        "content_type": "text/html",
                        "internal": True,
                        "created_at": "2026-10-18T09:30:00Z",
                        "updated_at": "2026-10-18T09:30:00Z",
                        "attachments": [],
                    },
                ],
                4823: [
                    {
                        "id": 3001,
                        "ticket_id": 4823,
                        "sender": "Customer",
                        "type": "email",
                        "from": "sofia.rossi@acme.com",
                        "subject": "Invoice VAT update",
                        "body": "Q3 enterprise subscription invoice VAT registration update. Reference ZMD-4823-PUB.",
                        "content_type": "text/plain",
                        "internal": False,
                        "created_at": "2026-10-18T10:00:00Z",
                        "updated_at": "2026-10-18T10:00:00Z",
                        "attachments": [],
                    }
                ],
                4824: [
                    {
                        "id": 4001,
                        "ticket_id": 4824,
                        "sender": "Customer",
                        "type": "web",
                        "from": "claire.dupont@clientcorp.eu",
                        "subject": "Orphan ticket",
                        "body": "Unassigned orphan SSO security ticket that must fail closed. Reference ZMD-4824-CLOSED.",
                        "content_type": "text/plain",
                        "internal": False,
                        "created_at": "2026-10-18T11:00:00Z",
                        "updated_at": "2026-10-18T11:00:00Z",
                        "attachments": [],
                    }
                ],
            }
            self.tags = {
                4821: ["saml", "sso", "certificate"],
                4822: ["mfa", "totp"],
                4823: ["invoice", "vat"],
                4824: ["orphan"],
            }
            self.attachments = {
                (4821, 1001, 501): (
                    "text/plain",
                    b"SAML Response Signature Error trace ZMD-4821-ATT-PUB",
                ),
                (4821, 1001, 599): (
                    "text/html",
                    b"<p>Synthetic alternative body ZMD-4821-SKIP</p>",
                ),
                (4821, 1002, 502): (
                    "text/plain",
                    b"CONFIDENTIAL AGENT RUNBOOK STEP ZMD-4821-ATT-INT",
                ),
            }
            self.kb_locales = {
                1: {"id": 1, "system_locale": "en"},
            }
            self.kb_categories = {
                10: {
                    "id": 10,
                    "knowledge_base_id": 1,
                    "title": "Authentication & SSO",
                    "permissions": [{"role_id": 1, "access": "editor"}],
                },
                11: {
                    "id": 11,
                    "knowledge_base_id": 1,
                    "title": "Security Advisories",
                    "permissions": [{"role_id": 3, "access": "reader"}],
                },
            }
            # Answer 77 has inline content in /knowledge_bases/init;
            # Answer 78 omits inline content in /init so the connector exercises GET /knowledge_bases/1/answers/78?include_contents=true;
            # Answer 79 is archived and must not be indexed.
            self.kb_answers = {
                77: {
                    "id": 77,
                    "knowledge_base_id": 1,
                    "category_id": 10,
                    "published_at": "2026-10-15T12:00:00Z",
                    "internal_at": None,
                    "archived_at": None,
                    "updated_at": "2026-10-15T12:00:00Z",
                    "inline_in_init": True,
                    "translations": [
                        {
                            "id": 770,
                            "kb_locale_id": 1,
                            "locale": "en",
                            "title": "How to rotate SAML IdP certificates",
                            "content_id": 7700,
                            "body": "<p>Upload the new X.509 certificate under Security Settings for SSO SAML. Reference ZMD-KB-77-PUB.</p>",
                        }
                    ],
                },
                78: {
                    "id": 78,
                    "knowledge_base_id": 1,
                    "category_id": 11,
                    "published_at": None,
                    "internal_at": "2026-10-16T12:00:00Z",
                    "archived_at": None,
                    "updated_at": "2026-10-16T12:00:00Z",
                    "inline_in_init": False,
                    "translations": [
                        {
                            "id": 780,
                            "kb_locale_id": 1,
                            "locale": "en",
                            "title": "Internal Incident Escalation Matrix",
                            "content_id": 7800,
                            "body": "<p>Confidential SSO security incident escalation runbook for auditors. Reference ZMD-KB-78-INT.</p>",
                        }
                    ],
                },
                79: {
                    "id": 79,
                    "knowledge_base_id": 1,
                    "category_id": 10,
                    "published_at": "2026-10-10T12:00:00Z",
                    "internal_at": None,
                    "archived_at": "2026-10-14T12:00:00Z",
                    "updated_at": "2026-10-14T12:00:00Z",
                    "inline_in_init": True,
                    "translations": [
                        {
                            "id": 790,
                            "kb_locale_id": 1,
                            "locale": "en",
                            "title": "Legacy SSLv3 Guide (Archived)",
                            "content_id": 7900,
                            "body": "<p>Archived document that must not be indexed. Reference ZMD-KB-79-ARCHIVED.</p>",
                        }
                    ],
                },
            }
            self.next_ticket_id = 4825
            self.next_article_id = 5001
            self.next_attachment_id = 601
            self.faults = []
            self.auth_failures = 0
            self.last_on_behalf_of = None
            self.requests = {}

    def record_request(self, key):
        with self.lock:
            self.requests[key] = self.requests.get(key, 0) + 1

    def pop_fault(self):
        with self.lock:
            if not self.faults:
                return None
            return self.faults.pop(0)

    def ticket_payload(self, ticket, expand=False):
        data = copy.deepcopy(ticket)
        if expand:
            state = self.ticket_states.get(ticket.get("state_id"))
            if state:
                data["state"] = state["name"]
            prio = self.ticket_priorities.get(ticket.get("priority_id"))
            if prio:
                data["priority"] = prio["name"]
            org_id = ticket.get("organization_id")
            if org_id and org_id in self.organizations:
                # Zammad expand=true returns organization as a string name while organization_id triggers /organizations/{id}
                data["organization"] = self.organizations[org_id]["name"]
        return data

    def kb_init_payload(self):
        with self.lock:
            locales = {str(k): copy.deepcopy(v) for k, v in self.kb_locales.items()}
            categories = {}
            cat_translations = {}
            for cid, cat in self.kb_categories.items():
                categories[str(cid)] = {
                    "id": cat["id"],
                    "knowledge_base_id": cat["knowledge_base_id"],
                    "permissions_effective": copy.deepcopy(cat["permissions"]),
                }
                cat_translations[str(cid * 10)] = {
                    "id": cid * 10,
                    "category_id": cid,
                    "title": cat["title"],
                }
            answers = {}
            ans_translations = {}
            ans_contents = {}
            for aid, ans in self.kb_answers.items():
                answers[str(aid)] = {
                    "id": ans["id"],
                    "category_id": ans["category_id"],
                    "published_at": ans["published_at"],
                    "internal_at": ans["internal_at"],
                    "archived_at": ans["archived_at"],
                    "updated_at": ans["updated_at"],
                }
                for tr in ans["translations"]:
                    tr_obj = {
                        "id": tr["id"],
                        "answer_id": aid,
                        "kb_locale_id": tr["kb_locale_id"],
                        "title": tr["title"],
                        "content_id": tr["content_id"],
                    }
                    if ans.get("inline_in_init", True):
                        ans_contents[str(tr["content_id"])] = {
                            "id": tr["content_id"],
                            "body": tr["body"],
                        }
                    ans_translations[str(tr["id"])] = tr_obj
            return {
                "KnowledgeBaseLocale": locales,
                "KnowledgeBaseCategory": categories,
                "KnowledgeBaseCategoryTranslation": cat_translations,
                "KnowledgeBaseAnswer": answers,
                "KnowledgeBaseAnswerTranslation": ans_translations,
                "KnowledgeBaseAnswerTranslationContent": ans_contents,
            }

    def kb_answer_payload(self, kb_id, answer_id, include_contents=None):
        with self.lock:
            ans = self.kb_answers.get(answer_id)
            if not ans or ans.get("knowledge_base_id") != kb_id:
                return None
            requested_ids = None
            if include_contents and include_contents != "true":
                requested_ids = {int(x) for x in include_contents.split(",") if x.strip().isdigit()}
            ans_translations = {}
            ans_contents = {}
            for tr in ans["translations"]:
                ans_translations[str(tr["id"])] = {
                    "id": tr["id"],
                    "answer_id": answer_id,
                    "kb_locale_id": tr["kb_locale_id"],
                    "title": tr["title"],
                    "content_id": tr["content_id"],
                }
                if requested_ids is None or tr["content_id"] in requested_ids:
                    ans_contents[str(tr["content_id"])] = {
                        "id": tr["content_id"],
                        "body": tr["body"],
                    }
            return {
                "id": answer_id,
                "assets": {
                    "KnowledgeBaseAnswer": {
                        str(answer_id): {
                            "id": ans["id"],
                            "category_id": ans["category_id"],
                            "published_at": ans["published_at"],
                            "internal_at": ans["internal_at"],
                            "archived_at": ans["archived_at"],
                            "updated_at": ans["updated_at"],
                        }
                    },
                    "KnowledgeBaseAnswerTranslation": ans_translations,
                    "KnowledgeBaseAnswerTranslationContent": ans_contents,
                },
            }

    @staticmethod
    def is_kb_answer_active(ans, now=None):
        ref = now or datetime.now(timezone.utc)
        archived_at = parse_iso(ans.get("archived_at"))
        if archived_at and archived_at <= ref:
            return False
        published_at = parse_iso(ans.get("published_at"))
        if published_at and published_at <= ref:
            return True
        internal_at = parse_iso(ans.get("internal_at"))
        if internal_at and internal_at <= ref:
            return True
        return False

    def expected_split_documents(self):
        with self.lock:
            now = datetime.now(timezone.utc)
            count = 0
            for tid, _ticket in self.tickets.items():
                arts = self.articles.get(tid, [])
                has_public = False
                has_internal = False
                for art in arts:
                    is_int = bool(art.get("internal", False))
                    if is_int:
                        has_internal = True
                    else:
                        has_public = True
                    for att in art.get("attachments", []):
                        prefs = att.get("preferences", {})
                        if prefs.get("content-alternative") in (True, "true", "True"):
                            continue
                        count += 1
                if has_public:
                    count += 1
                if has_internal:
                    count += 1
            for _aid, ans in self.kb_answers.items():
                if self.is_kb_answer_active(ans, now):
                    count += len(ans.get("translations", []))
            return count

    def stats(self):
        with self.lock:
            now = datetime.now(timezone.utc)
            active_kb = sum(
                1
                for ans in self.kb_answers.values()
                if self.is_kb_answer_active(ans, now)
            )
            archived_kb = len(self.kb_answers) - active_kb
            total_articles = sum(len(a) for a in self.articles.values())
            real_attachments = sum(
                1
                for arts in self.articles.values()
                for art in arts
                for att in art.get("attachments", [])
                if att.get("preferences", {}).get("content-alternative") not in (True, "true", "True")
            )
            return {
                "tickets": len(self.tickets),
                "articles": total_articles,
                "attachments": real_attachments,
                "activeKbAnswers": active_kb,
                "archivedKbAnswers": archived_kb,
                "expectedDocuments": self.expected_split_documents(),
                "authFailures": self.auth_failures,
                "lastOnBehalfOf": self.last_on_behalf_of,
                "requests": dict(self.requests),
            }

    def search_tickets(self, query, sort_by, order_by):
        with self.lock:
            matched = list(self.tickets.values())
            q = (query or "*").strip()
            if q and q != "*":
                # Support Lucene range updated_at:[<ts> TO *] (real Zammad + ES) as well as updated_at:>=<ts> / >
                m_range = re.search(r"updated_at:\[(?P<ts>[^\s\]]+)\s+TO\s+\*\]", q)
                m_gte = None if m_range else re.search(r"updated_at:>=(?P<ts>[^\s]+)", q)
                m_gt = None if (m_range or m_gte) else re.search(r"updated_at:>(?P<ts>[^\s]+)", q)
                if m_range or m_gte:
                    cutoff = parse_iso((m_range or m_gte).group("ts"))
                    if cutoff:
                        matched = [
                            t for t in matched
                            if (parse_iso(t.get("updated_at")) or datetime.min.replace(tzinfo=timezone.utc)) >= cutoff
                        ]
                elif m_gt:
                    cutoff = parse_iso(m_gt.group("ts"))
                    if cutoff:
                        matched = [
                            t for t in matched
                            if (parse_iso(t.get("updated_at")) or datetime.min.replace(tzinfo=timezone.utc)) > cutoff
                        ]

                # Support group.name:"..." or group.name:...
                m_gname = re.search(r'group\.name:"([^"]+)"', q) or re.search(r"group\.name:([^\s]+)", q)
                if m_gname:
                    target_group = m_gname.group(1).strip().lower()
                    matched = [
                        t for t in matched
                        if self.groups.get(t.get("group_id"), {}).get("name", "").lower() == target_group
                    ]

                # Support group_id:<id>
                m_gid = re.search(r"group_id:(\d+)", q)
                if m_gid:
                    target_gid = int(m_gid.group(1))
                    matched = [t for t in matched if t.get("group_id") == target_gid]

            reverse = (order_by or "asc").lower() == "desc"
            if sort_by == "updated_at":
                matched.sort(key=lambda t: (t.get("updated_at") or "", t["id"]), reverse=reverse)
            else:
                matched.sort(key=lambda t: t["id"], reverse=reverse)
            return matched


STORE = ZammadStore()


class ZammadHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        if not QUIET:
            sys.stderr.write("[zammad-mock] " + (fmt % args) + "\n")

    def send_json(self, status, payload, extra_headers=None):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, str(v))
        self.end_headers()
        self.wfile.write(body)

    def send_bytes(self, status, content_type, data):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def read_json_body(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        if not raw.strip():
            return {}
        return json.loads(raw.decode("utf-8"))

    def check_auth(self):
        auth = (self.headers.get("Authorization") or "").strip()
        on_behalf = self.headers.get("X-On-Behalf-Of")
        if on_behalf:
            with STORE.lock:
                STORE.last_on_behalf_of = on_behalf.strip()
        expected_basic = "Basic " + base64.b64encode(f"{BASIC_USER}:{BASIC_PASSWORD}".encode("utf-8")).decode("ascii")
        valid = (
            auth == f"Token token={API_TOKEN}"
            or auth == f"Bearer {OAUTH_TOKEN}"
            or auth == expected_basic
        )
        if not valid:
            with STORE.lock:
                STORE.auth_failures += 1
            self.send_json(401, {"error": "Authentication failed"})
            return False
        return True

    def maybe_inject_fault(self):
        fault = STORE.pop_fault()
        if not fault:
            return False
        status = int(fault.get("status", 429))
        headers = {}
        if "retryAfter" in fault and fault["retryAfter"] is not None:
            headers["Retry-After"] = str(fault["retryAfter"])
        self.send_json(status, {"error": f"Injected transient HTTP {status}"}, extra_headers=headers)
        return True

    def do_GET(self):
        parsed = urlsplit(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)

        # ---- Mock admin endpoints (unauthenticated) ----
        if path == "/mock/stats":
            self.send_json(200, STORE.stats())
            return
        if path == "/mock/tickets":
            with STORE.lock:
                items = [STORE.ticket_payload(t, expand=True) for t in sorted(STORE.tickets.values(), key=lambda x: x["id"])]
            self.send_json(200, items)
            return

        # ---- Zammad REST API v1 endpoints ----
        if not path.startswith("/api/v1/"):
            self.send_json(404, {"error": "Not found"})
            return

        if not self.check_auth():
            return
        if self.maybe_inject_fault():
            return

        STORE.record_request(f"GET {path}")

        if path == "/api/v1/users/me":
            with STORE.lock:
                self.send_json(200, STORE.current_user)
            return

        m_user = re.fullmatch(r"/api/v1/users/(\d+)", path)
        if m_user:
            uid = int(m_user.group(1))
            with STORE.lock:
                user = STORE.users.get(uid)
            if user:
                self.send_json(200, user)
            else:
                self.send_json(404, {"error": "User not found"})
            return

        if path == "/api/v1/groups":
            page = max(1, int(qs.get("page", ["1"])[0]))
            per_page = min(1000, max(1, int(qs.get("per_page", ["500"])[0])))
            with STORE.lock:
                ordered = sorted(STORE.groups.values(), key=lambda g: g["id"])
                start = (page - 1) * per_page
                self.send_json(200, ordered[start : start + per_page])
            return

        if path == "/api/v1/roles":
            page = max(1, int(qs.get("page", ["1"])[0]))
            per_page = min(1000, max(1, int(qs.get("per_page", ["500"])[0])))
            with STORE.lock:
                ordered = sorted(STORE.roles.values(), key=lambda r: r["id"])
                start = (page - 1) * per_page
                self.send_json(200, ordered[start : start + per_page])
            return

        if path == "/api/v1/ticket_states":
            page = max(1, int(qs.get("page", ["1"])[0]))
            per_page = min(1000, max(1, int(qs.get("per_page", ["500"])[0])))
            with STORE.lock:
                ordered = sorted(STORE.ticket_states.values(), key=lambda s: s["id"])
                start = (page - 1) * per_page
                self.send_json(200, ordered[start : start + per_page])
            return

        if path == "/api/v1/ticket_priorities":
            page = max(1, int(qs.get("page", ["1"])[0]))
            per_page = min(1000, max(1, int(qs.get("per_page", ["500"])[0])))
            with STORE.lock:
                ordered = sorted(STORE.ticket_priorities.values(), key=lambda p: p["id"])
                start = (page - 1) * per_page
                self.send_json(200, ordered[start : start + per_page])
            return

        m_org = re.fullmatch(r"/api/v1/organizations/(\d+)", path)
        if m_org:
            oid = int(m_org.group(1))
            with STORE.lock:
                org = STORE.organizations.get(oid)
            if org:
                self.send_json(200, org)
            else:
                self.send_json(404, {"error": "Organization not found"})
            return

        if path == "/api/v1/tickets":
            page = max(1, int(qs.get("page", ["1"])[0]))
            # Mirror Zammad TicketsController#index paginate_with(max: 100)
            per_page = min(100, max(1, int(qs.get("per_page", ["50"])[0])))
            expand = qs.get("expand", ["false"])[0].lower() == "true"
            with STORE.lock:
                ordered = sorted(STORE.tickets.values(), key=lambda t: t["id"])
                start = (page - 1) * per_page
                sliced = [STORE.ticket_payload(t, expand=expand) for t in ordered[start : start + per_page]]
            self.send_json(200, sliced)
            return

        if path == "/api/v1/tickets/search":
            query = unquote(qs.get("query", ["*"])[0])
            sort_by = qs.get("sort_by", ["updated_at"])[0]
            order_by = qs.get("order_by", ["asc"])[0]
            page = max(1, int(qs.get("page", ["1"])[0]))
            # Mirror Zammad model_search_render paginate_with(max: 200, default: 50)
            per_page = min(200, max(1, int(qs.get("per_page", ["50"])[0])))
            expand = qs.get("expand", ["false"])[0].lower() == "true"
            matched = STORE.search_tickets(query, sort_by, order_by)
            start = (page - 1) * per_page
            page_items = matched[start : start + per_page]
            ticket_ids = [t["id"] for t in page_items]
            assets_map = {str(t["id"]): STORE.ticket_payload(t, expand=expand) for t in page_items}
            self.send_json(
                200,
                {
                    "tickets": ticket_ids,
                    "tickets_count": len(matched),
                    "assets": {"Ticket": assets_map},
                },
            )
            return

        m_ticket = re.fullmatch(r"/api/v1/tickets/(\d+)", path)
        if m_ticket:
            tid = int(m_ticket.group(1))
            expand = qs.get("expand", ["false"])[0].lower() == "true"
            with STORE.lock:
                t = STORE.tickets.get(tid)
                payload = STORE.ticket_payload(t, expand=expand) if t else None
            if payload:
                self.send_json(200, payload)
            else:
                self.send_json(404, {"error": "Ticket not found"})
            return

        m_articles = re.fullmatch(r"/api/v1/ticket_articles/by_ticket/(\d+)", path)
        if m_articles:
            tid = int(m_articles.group(1))
            with STORE.lock:
                if tid not in STORE.tickets:
                    self.send_json(404, {"error": "Ticket not found"})
                    return
                arts = copy.deepcopy(STORE.articles.get(tid, []))
            self.send_json(200, arts)
            return

        if path == "/api/v1/tags":
            o_id = int(qs.get("o_id", ["0"])[0])
            with STORE.lock:
                tags = list(STORE.tags.get(o_id, []))
            self.send_json(200, {"tags": tags})
            return

        m_att = re.fullmatch(r"/api/v1/ticket_attachment/(\d+)/(\d+)/(\d+)", path)
        if m_att:
            key = (int(m_att.group(1)), int(m_att.group(2)), int(m_att.group(3)))
            with STORE.lock:
                att = STORE.attachments.get(key)
            if att:
                self.send_bytes(200, att[0], att[1])
            else:
                self.send_json(404, {"error": "Attachment not found"})
            return

        if path == "/api/v1/knowledge_bases/init":
            self.send_json(200, STORE.kb_init_payload())
            return

        m_kb_ans = re.fullmatch(r"/api/v1/knowledge_bases/(\d+)/answers/(\d+)", path)
        if m_kb_ans:
            kb_id = int(m_kb_ans.group(1))
            ans_id = int(m_kb_ans.group(2))
            include_contents = qs.get("include_contents", [None])[0]
            payload = STORE.kb_answer_payload(kb_id, ans_id, include_contents)
            if payload:
                self.send_json(200, payload)
            else:
                self.send_json(404, {"error": "Knowledge Base answer not found"})
            return

        self.send_json(404, {"error": f"Unknown endpoint {path}"})

    def do_POST(self):
        parsed = urlsplit(self.path)
        path = parsed.path
        body = self.read_json_body()

        if path == "/api/v1/knowledge_bases/init":
            if not self.check_auth():
                return
            if self.maybe_inject_fault():
                return
            STORE.record_request(f"POST {path}")
            self.send_json(200, STORE.kb_init_payload())
            return

        if path == "/mock/reset":
            STORE.reset()
            self.send_json(200, {"status": "reset", "stats": STORE.stats()})
            return

        if path == "/mock/faults":
            status = int(body.get("status", 429))
            count = max(1, int(body.get("count", 1)))
            retry_after = body.get("retryAfter", 0)
            with STORE.lock:
                for _ in range(count):
                    STORE.faults.append({"status": status, "retryAfter": retry_after})
            self.send_json(200, {"queued": count, "status": status})
            return

        if path == "/mock/tickets":
            with STORE.lock:
                tid = int(body.get("id") or STORE.next_ticket_id)
                if tid >= STORE.next_ticket_id:
                    STORE.next_ticket_id = tid + 1
                ts = body.get("updated_at") or now_iso()
                ticket = {
                    "id": tid,
                    "number": str(body.get("number") or (9400000 + tid)),
                    "title": body.get("title") or f"Incremental Zammad Ticket #{tid}",
                    "group_id": int(body.get("group_id", 4)),
                    "state_id": int(body.get("state_id", 2)),
                    "priority_id": int(body.get("priority_id", 2)),
                    "customer_id": int(body.get("customer_id", 101)),
                    "organization_id": body.get("organization_id", 301),
                    "owner_id": int(body.get("owner_id", 202)),
                    "created_at": body.get("created_at") or ts,
                    "updated_at": ts,
                    "close_at": body.get("close_at"),
                }
                STORE.tickets[tid] = ticket
                STORE.tags[tid] = list(body.get("tags", ["incremental"]))
                arts = []
                pub_body = body.get("public_body")
                if pub_body is not None:
                    aid = STORE.next_article_id
                    STORE.next_article_id += 1
                    arts.append(
                        {
                            "id": aid,
                            "ticket_id": tid,
                            "sender": "Customer",
                            "type": "web",
                            "from": STORE.users.get(ticket["customer_id"], {}).get("email", "customer@clientcorp.eu"),
                            "subject": ticket["title"],
                            "body": pub_body,
                            "content_type": "text/plain",
                            "internal": False,
                            "created_at": ts,
                            "updated_at": ts,
                            "attachments": [],
                        }
                    )
                int_body = body.get("internal_body")
                if int_body is not None:
                    aid = STORE.next_article_id
                    STORE.next_article_id += 1
                    arts.append(
                        {
                            "id": aid,
                            "ticket_id": tid,
                            "sender": "Agent",
                            "type": "note",
                            "from": "marcus.weber@acme.com",
                            "subject": "Internal escalation note",
                            "body": int_body,
                            "content_type": "text/plain",
                            "internal": True,
                            "created_at": ts,
                            "updated_at": ts,
                            "attachments": [],
                        }
                    )
                STORE.articles[tid] = arts
            self.send_json(201, ticket)
            return

        m_upd = re.fullmatch(r"/mock/tickets/(\d+)/update", path)
        if m_upd:
            tid = int(m_upd.group(1))
            with STORE.lock:
                ticket = STORE.tickets.get(tid)
                if not ticket:
                    self.send_json(404, {"error": "Ticket not found"})
                    return
                ts = body.get("updated_at") or now_iso()
                for field in ("title", "group_id", "state_id", "priority_id", "organization_id"):
                    if field in body:
                        ticket[field] = body[field]
                ticket["updated_at"] = ts
                if "tags" in body:
                    STORE.tags[tid] = list(body["tags"])
                arts = STORE.articles.get(tid, [])
                if body.get("remove_internal", False):
                    arts = [a for a in arts if not a.get("internal", False)]
                if body.get("clear_attachments", False):
                    for a in arts:
                        a["attachments"] = []
                if "public_body" in body:
                    for a in arts:
                        if not a.get("internal", False):
                            a["body"] = body["public_body"]
                            a["updated_at"] = ts
                            break
                STORE.articles[tid] = arts
            self.send_json(200, ticket)
            return

        m_del = re.fullmatch(r"/mock/tickets/(\d+)/delete", path)
        if m_del:
            tid = int(m_del.group(1))
            with STORE.lock:
                existed = STORE.tickets.pop(tid, None) is not None
                STORE.articles.pop(tid, None)
                STORE.tags.pop(tid, None)
            if not existed:
                self.send_json(404, {"error": "Ticket not found"})
                return
            self.send_json(200, {"deleted": tid})
            return

        m_kb_arch = re.fullmatch(r"/mock/kb/(\d+)/archive", path)
        if m_kb_arch:
            aid = int(m_kb_arch.group(1))
            with STORE.lock:
                ans = STORE.kb_answers.get(aid)
                if not ans:
                    self.send_json(404, {"error": "KB answer not found"})
                    return
                ts = body.get("updated_at") or now_iso()
                ans["archived_at"] = ts
                ans["updated_at"] = ts
            self.send_json(200, {"archived": aid, "archived_at": ts})
            return

        m_kb_upd = re.fullmatch(r"/mock/kb/(\d+)/update", path)
        if m_kb_upd:
            aid = int(m_kb_upd.group(1))
            with STORE.lock:
                ans = STORE.kb_answers.get(aid)
                if not ans:
                    self.send_json(404, {"error": "KB answer not found"})
                    return
                ts = body.get("updated_at") or now_iso()
                ans["updated_at"] = ts
                if "title" in body and ans["translations"]:
                    ans["translations"][0]["title"] = body["title"]
                if "body" in body and ans["translations"]:
                    ans["translations"][0]["body"] = body["body"]
            self.send_json(200, {"updated": aid, "updated_at": ts})
            return

        self.send_json(404, {"error": f"Unknown POST endpoint {path}"})


def main():
    server = ThreadingHTTPServer(("0.0.0.0", PORT), ZammadHandler)
    if not QUIET:
        sys.stderr.write(f"[zammad-mock] Listening on 0.0.0.0:{PORT}\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
