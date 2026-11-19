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
"""Tests for OAuth 2.0 / OIDC token providers, pre-expiry caching, 401 retry, and secret redaction."""

from __future__ import annotations

import base64
from urllib.parse import parse_qs

import httpx
import pytest

from opencrawling import (
    AsyncOpenCrawlingClient,
    ClientCredentialsTokenProvider,
    DeviceCodeLogin,
    OAuth2Client,
    OAuth2Error,
    OAuth2Tokens,
    OpenCrawlingClient,
    RefreshTokenProvider,
    StaticTokenProvider,
    TokenExchangeProvider,
)


class MutableClock:
    def __init__(self, start: float = 1_700_000_000.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def test_client_credentials_caches_renews_30s_before_expiry_and_retries_on_401() -> None:
    clock = MutableClock()
    token_calls: list[httpx.Request] = []
    issued_counter = 0

    def idp_handler(request: httpx.Request) -> httpx.Response:
        nonlocal issued_counter
        token_calls.append(request)
        issued_counter += 1
        return httpx.Response(
            200,
            json={"access_token": f"tok-{issued_counter}", "expires_in": 120, "token_type": "Bearer"},
        )

    oauth = OAuth2Client(http_client=httpx.Client(transport=httpx.MockTransport(idp_handler)))
    provider = ClientCredentialsTokenProvider(
        "https://idp.example.com/token",
        "svc-client",
        "super-secret-value-999",
        scope="opencrawling:admin",
        audience="https://api.opencrawling.local",
        oauth_client=oauth,
        clock=clock,
    )

    # First call fetches token
    assert provider.access_token() == "tok-1"
    assert len(token_calls) == 1
    expected_basic = base64.b64encode(b"svc-client:super-secret-value-999").decode("ascii")
    assert token_calls[0].headers["authorization"] == f"Basic {expected_basic}"
    form = parse_qs(token_calls[0].content.decode("utf-8"))
    assert form["grant_type"] == ["client_credentials"]
    assert form["scope"] == ["opencrawling:admin"]
    assert form["audience"] == ["https://api.opencrawling.local"]

    # Cached up to 89s (120 - 30 = 90s renewal boundary)
    clock.advance(89.0)
    assert provider.access_token() == "tok-1"
    assert len(token_calls) == 1

    # At 90s (30s before expiry), renews automatically
    clock.advance(1.5)
    assert provider.access_token() == "tok-2"
    assert len(token_calls) == 2

    # 401 refresh-and-retry via OpenCrawlingClient
    api_auth_headers: list[str | None] = []

    def api_handler(request: httpx.Request) -> httpx.Response:
        api_auth_headers.append(request.headers.get("authorization"))
        if len(api_auth_headers) == 1:
            return httpx.Response(401, json={"status": 401, "title": "Unauthorized"})
        return httpx.Response(200, json={"apiVersion": "1.0.0"})

    client = OpenCrawlingClient(
        "http://localhost:8080",
        token_provider=provider,
        http_client=httpx.Client(transport=httpx.MockTransport(api_handler)),
    )
    assert client.version().api_version == "1.0.0"
    assert api_auth_headers == ["Bearer tok-2", "Bearer tok-3"]


def test_short_lived_token_renews_halfway_through_lifetime() -> None:
    clock = MutableClock()
    counter = 0

    def idp_handler(_: httpx.Request) -> httpx.Response:
        nonlocal counter
        counter += 1
        return httpx.Response(200, json={"access_token": f"short-{counter}", "expires_in": 20})

    provider = ClientCredentialsTokenProvider(
        "https://idp.example.com/token",
        "c",
        "s",
        oauth_client=OAuth2Client(http_client=httpx.Client(transport=httpx.MockTransport(idp_handler))),
        clock=clock,
    )
    assert provider.access_token() == "short-1"
    clock.advance(9.0)
    assert provider.access_token() == "short-1"
    clock.advance(2.0)  # past 10s halfway mark
    assert provider.access_token() == "short-2"


async def test_async_token_exchange_rfc8693_caches_by_subject_digest_and_retries_401() -> None:
    clock = MutableClock()
    current_user_jwt = "user-jwt-alice-secret-123"
    exchanges: list[dict[str, list[str]]] = []

    async def idp_handler(request: httpx.Request) -> httpx.Response:
        form = parse_qs(request.content.decode("utf-8"))
        exchanges.append(form)
        idx = len(exchanges)
        return httpx.Response(200, json={"access_token": f"delegated-{idx}", "expires_in": 300})

    oauth = OAuth2Client(async_http_client=httpx.AsyncClient(transport=httpx.MockTransport(idp_handler)))
    provider = TokenExchangeProvider(
        "https://idp.example.com/token",
        "research-agent",
        client_secret="agent-secret-xyz",
        subject_token=lambda: current_user_jwt,
        actor_token="actor-jwt-456",
        scope="opencrawling:discovery",
        oauth_client=oauth,
        clock=clock,
    )

    api_tokens: list[str | None] = []

    async def api_handler(request: httpx.Request) -> httpx.Response:
        api_tokens.append(request.headers.get("authorization"))
        if len(api_tokens) == 2:
            return httpx.Response(401, json={"status": 401, "title": "Unauthorized"})
        return httpx.Response(200, json={"apiVersion": "1.0.0"})

    async with AsyncOpenCrawlingClient(
        "http://localhost:8080",
        token_provider=provider,
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(api_handler)),
    ) as client:
        await client.version()
        # 2nd request gets 401 -> invalidates -> exchanges fresh token -> succeeds
        await client.version()
        # Cached for Alice
        await client.version()
        # Switch user to Bob -> digest changes -> triggers immediate new exchange!
        current_user_jwt = "user-jwt-bob-secret-789"
        await client.version()

    assert len(exchanges) == 3
    assert exchanges[0]["grant_type"] == ["urn:ietf:params:oauth:grant-type:token-exchange"]
    assert exchanges[0]["subject_token"] == ["user-jwt-alice-secret-123"]
    assert exchanges[0]["actor_token"] == ["actor-jwt-456"]
    assert exchanges[2]["subject_token"] == ["user-jwt-bob-secret-789"]
    assert api_tokens == [
        "Bearer delegated-1",
        "Bearer delegated-1",  # 401 triggers refresh
        "Bearer delegated-2",
        "Bearer delegated-2",  # cached
        "Bearer delegated-3",  # Bob's fresh delegated token
    ]


def test_refresh_token_provider_rotation_and_invalid_grant() -> None:
    clock = MutableClock()
    renewed_events: list[OAuth2Tokens] = []
    call_idx = 0

    def idp_handler(_: httpx.Request) -> httpx.Response:
        nonlocal call_idx
        call_idx += 1
        if call_idx == 1:
            return httpx.Response(
                200,
                json={"access_token": "acc-2", "refresh_token": "ref-rotated", "expires_in": 120},
            )
        return httpx.Response(400, json={"error": "invalid_grant", "error_description": "refresh token revoked"})

    oauth = OAuth2Client(http_client=httpx.Client(transport=httpx.MockTransport(idp_handler)))
    provider = RefreshTokenProvider(
        "https://idp.example.com/token",
        "oc-cli",
        tokens=OAuth2Tokens(access_token="acc-1", refresh_token="ref-1", expires_at=clock.now + 60.0),
        on_renewed=renewed_events.append,
        oauth_client=oauth,
        clock=clock,
    )

    assert provider.access_token() == "acc-1"
    clock.advance(35.0)  # within 30s pre-expiry window
    assert provider.access_token() == "acc-2"
    assert len(renewed_events) == 1
    assert renewed_events[0].refresh_token == "ref-rotated"

    assert provider.invalidate() is True
    with pytest.raises(OAuth2Error) as exc_info:
        provider.access_token()
    assert exc_info.value.error == "login_required"


def test_device_code_login_discovery_polling_and_slow_down() -> None:
    clock = MutableClock()
    sleeps: list[float] = []
    poll_attempt = 0

    def fake_sleep(seconds: float) -> None:
        sleeps.append(seconds)
        clock.advance(seconds)

    def idp_handler(request: httpx.Request) -> httpx.Response:
        nonlocal poll_attempt
        if request.url.path.endswith("/.well-known/openid-configuration"):
            return httpx.Response(
                200,
                json={
                    "issuer": "https://idp.example.com/realms/oc",
                    "token_endpoint": "https://idp.example.com/token",
                    "device_authorization_endpoint": "https://idp.example.com/device",
                },
            )
        if request.url.path.endswith("/device"):
            return httpx.Response(
                200,
                json={
                    "device_code": "dev-secret-code",
                    "user_code": "ABCD-EFGH",
                    "verification_uri": "https://idp.example.com/activate",
                    "expires_in": 60,
                    "interval": 2,
                },
            )
        poll_attempt += 1
        if poll_attempt == 1:
            return httpx.Response(400, json={"error": "authorization_pending"})
        if poll_attempt == 2:
            return httpx.Response(400, json={"error": "slow_down"})
        return httpx.Response(
            200,
            json={"access_token": "device-access-tok", "refresh_token": "device-ref-tok", "expires_in": 300},
        )

    oauth = OAuth2Client(http_client=httpx.Client(transport=httpx.MockTransport(idp_handler)))
    login = DeviceCodeLogin(oauth_client=oauth, clock=clock, sleeper=fake_sleep)
    endpoints = login.discover("https://idp.example.com/realms/oc")
    auth = login.start(endpoints, "oc-cli", scope="openid offline_access")
    assert auth.user_code == "ABCD-EFGH"
    tokens = login.poll(endpoints, "oc-cli", auth)
    assert tokens.access_token == "device-access-tok"
    assert tokens.refresh_token == "device-ref-tok"
    assert sleeps == [2.0, 2.0, 7.0]  # slow_down added +5s


def test_discovery_rejects_mismatched_issuer() -> None:
    def idp_handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"issuer": "https://evil.example.com", "token_endpoint": "https://evil.example.com/token"},
        )

    oauth = OAuth2Client(http_client=httpx.Client(transport=httpx.MockTransport(idp_handler)))
    with pytest.raises(OAuth2Error, match="names another issuer"):
        oauth.discover("https://idp.example.com")


def test_zero_secret_leakage_in_repr_str_and_oauth_errors() -> None:
    secret_val = "TOP_SECRET_CLIENT_CRED_98765"
    subject_val = "TOP_SECRET_SUBJECT_JWT_54321"
    refresh_val = "TOP_SECRET_REFRESH_TOK_11111"
    static_val = "TOP_SECRET_BEARER_TOKEN_00000"

    def failing_idp(_: httpx.Request) -> httpx.Response:
        # Simulate a buggy IdP echoing secrets into both error_description and raw body
        return httpx.Response(
            401,
            json={
                "error": "invalid_client",
                "error_description": f"bad secret {secret_val} or subject {subject_val}",
                "leaked_token": "SHOULD_NEVER_APPEAR_IN_EXCEPTION",
            },
        )

    oauth = OAuth2Client(http_client=httpx.Client(transport=httpx.MockTransport(failing_idp)))
    cc = ClientCredentialsTokenProvider("https://idp.example.com/token", "cid", secret_val, oauth_client=oauth)
    tx = TokenExchangeProvider("https://idp.example.com/token", "cid", client_secret=secret_val, subject_token=subject_val, oauth_client=oauth)
    rf = RefreshTokenProvider("https://idp.example.com/token", "cid", refresh_token=refresh_val, oauth_client=oauth)
    st = StaticTokenProvider(static_val)

    for obj in (cc, tx, rf, st):
        rendered = f"{obj!r} {obj!s}"
        for s in (secret_val, subject_val, refresh_val, static_val):
            assert s not in rendered

    with pytest.raises(OAuth2Error) as exc_info:
        cc.access_token()
    msg = str(exc_info.value)
    assert secret_val not in msg
    assert "SHOULD_NEVER_APPEAR_IN_EXCEPTION" not in msg
    assert exc_info.value.error == "invalid_client"

    with pytest.raises(OAuth2Error) as tx_exc:
        tx.access_token()
    tx_msg = str(tx_exc.value)
    assert secret_val not in tx_msg
    assert subject_val not in tx_msg
    assert "SHOULD_NEVER_APPEAR_IN_EXCEPTION" not in tx_msg


def test_mutually_exclusive_auth_arguments_rejected() -> None:
    with pytest.raises(ValueError, match="at most one"):
        OpenCrawlingClient(
            "http://localhost:8080",
            bearer_token="tok",
            token_provider=StaticTokenProvider("other"),
        )
