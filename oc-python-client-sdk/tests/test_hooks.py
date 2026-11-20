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

"""Tests for OpenCrawling governance and enrichment hook helpers (#408)."""

from __future__ import annotations

import hashlib
import hmac
import sys
import time

import pytest
from starlette.applications import Starlette
from starlette.routing import Route

from opencrawling.hooks import (
    SIGNATURE_HEADER,
    TIMESTAMP_HEADER,
    AsyncSignedHookTestClient,
    Decision,
    EnrichmentRequest,
    EnrichmentResponse,
    GovernanceRequest,
    HookSignatureError,
    ProtectedMetadataKeyError,
    SignedHookTestClient,
    build_signed_headers,
    create_enrichment_app,
    create_governance_app,
    governance_endpoint,
    is_protected_metadata_key,
    sign_payload,
    verify_signature,
)

TEST_SECRET = "opencrawling-hook-shared-secret-32-chars-min!"


def test_signature_matches_java_http_governance_hook_contract() -> None:
    """Verify byte-for-byte parity with oc-runtime HttpGovernanceHook.sign(secret, timestamp, body)."""
    timestamp = "1700000000"
    body = b'{"documentId":"doc-1","uri":"https://example.org/doc-1","text":"hello"}'

    expected_hex = hmac.new(
        TEST_SECRET.encode("utf-8"),
        b"1700000000." + body,
        hashlib.sha256,
    ).hexdigest()
    expected = f"sha256={expected_hex}"

    actual = sign_payload(body, TEST_SECRET, timestamp=timestamp)
    assert actual == expected

    verified_ts = verify_signature(
        body,
        TEST_SECRET,
        signature=actual,
        timestamp=timestamp,
        max_skew_seconds=300,
        now=1700000050.0,
    )
    assert verified_ts == 1700000000


def test_verify_signature_rejects_invalid_cases() -> None:
    now = 1_700_000_000
    body = b'{"documentId":"doc-1"}'
    headers = build_signed_headers(body, TEST_SECRET, timestamp=now)

    # Valid with case-insensitive headers
    lower_headers = {k.lower(): v for k, v in headers.items()}
    assert verify_signature(body, TEST_SECRET, headers=lower_headers, now=float(now)) == now

    # Tampered body
    with pytest.raises(HookSignatureError) as exc_tampered:
        verify_signature(b'{"documentId":"doc-2"}', TEST_SECRET, headers=headers, now=float(now))
    assert exc_tampered.value.reason == "signature_mismatch"

    # Wrong secret
    with pytest.raises(HookSignatureError) as exc_secret:
        verify_signature(
            body,
            "different-shared-secret-that-is-also-32-chars!!",
            headers=headers,
            now=float(now),
        )
    assert exc_secret.value.reason == "signature_mismatch"

    # Replay window exceeded (past & future)
    with pytest.raises(HookSignatureError) as exc_expired:
        verify_signature(body, TEST_SECRET, headers=headers, max_skew_seconds=60, now=float(now + 61))
    assert exc_expired.value.reason == "timestamp_expired"

    with pytest.raises(HookSignatureError) as exc_future:
        verify_signature(body, TEST_SECRET, headers=headers, max_skew_seconds=60, now=float(now - 61))
    assert exc_future.value.reason == "timestamp_expired"

    # Missing headers
    with pytest.raises(HookSignatureError) as exc_missing_ts:
        verify_signature(body, TEST_SECRET, headers={SIGNATURE_HEADER: headers[SIGNATURE_HEADER]})
    assert exc_missing_ts.value.reason == "missing_timestamp"

    with pytest.raises(HookSignatureError) as exc_missing_sig:
        verify_signature(body, TEST_SECRET, headers={TIMESTAMP_HEADER: headers[TIMESTAMP_HEADER]})
    assert exc_missing_sig.value.reason == "missing_signature"

    # Weak secret (< 32 chars)
    with pytest.raises(HookSignatureError) as exc_weak:
        sign_payload(body, "short-secret", timestamp=now)
    assert exc_weak.value.reason == "weak_secret"

    # Malformed signature prefix
    with pytest.raises(HookSignatureError) as exc_prefix:
        verify_signature(
            body,
            TEST_SECRET,
            headers={TIMESTAMP_HEADER: str(now), SIGNATURE_HEADER: "md5=deadbeef"},
            now=float(now),
        )
    assert exc_prefix.value.reason == "malformed_signature"


@pytest.mark.parametrize(
    "protected_key",
    [
        "acl",
        "security",
        "sys_acl",
        "documentId",
        "uri",
        "lastModified",
        "ois:source",
        "ois:provenance",
        "ois:governance",
        "security_groups",
        "acl_allow",
        "ois:source.connector",
        "ois:provenance.sha256",
        "ois:governance.decision",
    ],
)
def test_protected_metadata_keys_rejected_client_side(protected_key: str) -> None:
    assert is_protected_metadata_key(protected_key) is True

    with pytest.raises(ProtectedMetadataKeyError) as exc_info:
        Decision.sanitize(metadata={protected_key: "tampered"})
    assert exc_info.value.key == protected_key

    with pytest.raises(ProtectedMetadataKeyError):
        EnrichmentResponse.of(metadata={protected_key: ["tampered"]})


def test_decision_builders_and_wire_payload() -> None:
    assert is_protected_metadata_key("department") is False

    approve = Decision.approve(audit={"policy": "v1"})
    assert approve.to_payload() == {"decision": "APPROVE", "audit": {"policy": "v1"}}

    sanitize = Decision.sanitize(
        text="clean text",
        metadata={"pii_redacted": "true", "labels": ["internal", "reviewed"]},
        reason="Masked SSN",
        audit={"rule": "pii-1"},
    )
    assert sanitize.to_payload() == {
        "decision": "SANITIZE",
        "text": "clean text",
        "metadata": {"pii_redacted": ["true"], "labels": ["internal", "reviewed"]},
        "reason": "Masked SSN",
        "audit": {"rule": "pii-1"},
    }

    reject = Decision.reject("Contains restricted export-controlled content")
    assert reject.to_payload() == {
        "decision": "REJECT",
        "reason": "Contains restricted export-controlled content",
    }

    with pytest.raises(ValueError, match="blank"):
        Decision.reject("   ")


def test_governance_asgi_endpoint_and_sync_test_client() -> None:
    def handler(req: GovernanceRequest) -> Decision:
        if req.text and "SECRET" in req.text:
            return Decision.sanitize(
                text=req.text.replace("SECRET", "[REDACTED]"),
                metadata={"redacted": "true"},
                audit={"scanner": "regex-v1"},
            )
        if req.document_id == "blocked":
            return Decision.reject("Policy violation")
        return Decision.approve()

    app = create_governance_app(handler, secret=TEST_SECRET, path="/governance")
    client = SignedHookTestClient(secret=TEST_SECRET, app=app)

    # Approve
    dec_ok = client.post_governance(
        GovernanceRequest(documentId="doc-1", uri="https://example.org/1", text="Hello world")
    )
    assert dec_ok.decision == "APPROVE"

    # Sanitize
    dec_san = client.post_governance(
        {"documentId": "doc-2", "text": "Contains SECRET value", "metadata": {"dept": ["legal"]}}
    )
    assert dec_san.decision == "SANITIZE"
    assert dec_san.text == "Contains [REDACTED] value"
    assert dec_san.metadata == {"redacted": ["true"]}
    assert dec_san.audit == {"scanner": "regex-v1"}

    # Reject
    dec_rej = client.post_governance({"documentId": "blocked", "text": "x"})
    assert dec_rej.decision == "REJECT"
    assert dec_rej.reason == "Policy violation"

    # Invalid signature -> 401
    resp_unauth = client.post_signed(
        "/governance",
        {"documentId": "doc-1"},
        secret="wrong-secret-with-at-least-32-characters-long!",
    )
    assert resp_unauth.status_code == 401
    assert resp_unauth.json()["error"] == "signature_mismatch"

    # Expired timestamp -> 401
    resp_expired = client.post_signed(
        "/governance",
        {"documentId": "doc-1"},
        timestamp=int(time.time()) - 600,
    )
    assert resp_expired.status_code == 401
    assert resp_expired.json()["error"] == "timestamp_expired"

    # Invalid JSON payload (valid signature) -> 400
    resp_bad = client.post_signed("/governance", b'{"missing_document_id":true}')
    assert resp_bad.status_code == 400
    assert resp_bad.json()["error"] == "invalid_payload"


async def test_async_governance_and_enrichment_endpoints() -> None:
    @governance_endpoint(secret=lambda: TEST_SECRET)
    async def async_gov(req: GovernanceRequest) -> Decision:
        return Decision.approve(audit={"doc": req.document_id})

    gov_app = Starlette(routes=[Route("/gov", async_gov, methods=["POST"])])
    async with AsyncSignedHookTestClient(secret=TEST_SECRET, app=gov_app) as client:
        decision = await client.post_governance({"documentId": "async-1"}, path="/gov")
        assert decision.decision == "APPROVE"
        assert decision.audit == {"doc": "async-1"}

    async def enrich_handler(req: EnrichmentRequest) -> EnrichmentResponse:
        return EnrichmentResponse.of(
            metadata={"language": "en", "word_count": str(len((req.text or "").split()))},
            audit={"enricher": "nlp-v1"},
        )

    enrich_app = create_enrichment_app(enrich_handler, secret=TEST_SECRET)
    async with AsyncSignedHookTestClient(secret=TEST_SECRET, app=enrich_app) as client:
        enriched = await client.post_enrichment(
            EnrichmentRequest(documentId="doc-9", mimeType="text/plain", text="hello open crawling")
        )
        assert enriched.metadata == {"language": ["en"], "word_count": ["3"]}
        assert enriched.audit == {"enricher": "nlp-v1"}


def test_hooks_asgi_missing_starlette_guidance(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setitem(sys.modules, "starlette.applications", None)
    with pytest.raises(ImportError, match="opencrawling-sdk\\[hooks\\]"):
        create_governance_app(lambda req: Decision.approve(), secret=TEST_SECRET)
