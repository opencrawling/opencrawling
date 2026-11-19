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
"""OAuth 2.0 / OIDC token providers (Client Credentials, Refresh Token, RFC 8693 Token Exchange)."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import threading
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import Any
from urllib.parse import quote_plus

import httpx

from opencrawling.errors import OAuth2Error

EARLY_RENEWAL_SECONDS = 30.0
DEFAULT_UNKNOWN_EXPIRY_SECONDS = 60.0


def _require_text(value: str | None, name: str) -> str:
    if value is None or not value.strip():
        raise ValueError(f"{name} is required")
    return value.strip()


def _redact_secrets(text: str, secrets: tuple[str | None, ...]) -> str:
    """Replace any known secret values appearing in an error string with ``[REDACTED]``."""
    redacted = text
    for secret in secrets:
        if secret and len(secret) >= 4 and secret in redacted:
            redacted = redacted.replace(secret, "[REDACTED]")
    return redacted


@dataclass(frozen=True, slots=True)
class OAuth2Endpoints:
    """OIDC discovery metadata for an issuer."""

    issuer: str
    token_endpoint: str
    device_authorization_endpoint: str | None = None
    revocation_endpoint: str | None = None


@dataclass(frozen=True, slots=True)
class OAuth2TokenResponse:
    """Parsed token endpoint response (secrets redacted in ``repr``)."""

    access_token: str
    refresh_token: str | None = None
    expires_in: float = 0.0
    scope: str | None = None

    def __repr__(self) -> str:
        return (
            f"OAuth2TokenResponse(access_token='<redacted>', "
            f"refresh_token={'<redacted>' if self.refresh_token else None!r}, "
            f"expires_in={self.expires_in!r}, scope={self.scope!r})"
        )


@dataclass(frozen=True, slots=True)
class OAuth2Tokens:
    """Persisted token set yielded by sign-in or refresh (secrets redacted in ``repr``)."""

    access_token: str | None
    refresh_token: str | None
    expires_at: float | None = None

    def __repr__(self) -> str:
        return (
            f"OAuth2Tokens(access_token={'<redacted>' if self.access_token else None!r}, "
            f"refresh_token={'<redacted>' if self.refresh_token else None!r}, "
            f"expires_at={self.expires_at!r})"
        )


@dataclass(frozen=True, slots=True)
class DeviceAuthorization:
    """Device authorization response (RFC 8628 §3.2)."""

    device_code: str
    user_code: str
    verification_uri: str
    verification_uri_complete: str | None = None
    expires_in: int = 600
    interval: int = 5

    def __repr__(self) -> str:
        return (
            f"DeviceAuthorization(device_code='<redacted>', user_code={self.user_code!r}, "
            f"verification_uri={self.verification_uri!r}, expires_in={self.expires_in!r}, "
            f"interval={self.interval!r})"
        )


@dataclass(frozen=True, slots=True)
class ExpiringToken:
    """Cached bearer token with pre-expiry renewal timestamp.

    Renews 30 seconds before expiry, or halfway through the token lifetime when the token
    lives 60 seconds or less.
    """

    value: str
    expires_at: float
    renew_at: float

    @classmethod
    def from_lifetime(
        cls,
        value: str,
        expires_in: float,
        *,
        now: float,
    ) -> ExpiringToken:
        seconds = expires_in if expires_in > 0 else DEFAULT_UNKNOWN_EXPIRY_SECONDS
        early = min(EARLY_RENEWAL_SECONDS, seconds / 2.0)
        return cls(value=value, expires_at=now + seconds, renew_at=now + (seconds - early))

    @classmethod
    def saved(cls, value: str, expires_at: float) -> ExpiringToken:
        return cls(value=value, expires_at=expires_at, renew_at=expires_at - EARLY_RENEWAL_SECONDS)

    def usable(self, now: float) -> bool:
        return bool(self.value) and now < self.renew_at

    def __repr__(self) -> str:
        return f"ExpiringToken(value='<redacted>', expires_at={self.expires_at!r}, renew_at={self.renew_at!r})"


class OAuth2Client:
    """Minimal OAuth 2.0 / OIDC HTTP helper supporting both sync and async execution.

    Uses ``httpx`` with zero additional dependencies and strictly avoids leaking secrets or
    raw token endpoint payloads in error messages.
    """

    def __init__(
        self,
        *,
        timeout: float = 30.0,
        http_client: httpx.Client | None = None,
        async_http_client: httpx.AsyncClient | None = None,
        verify: bool | str = True,
    ) -> None:
        self._timeout = timeout
        self._http_client = http_client
        self._async_http_client = async_http_client
        self._verify = verify

    def _build_form_headers_and_data(
        self,
        client_id: str,
        client_secret: str | None,
        grant: Mapping[str, str],
    ) -> tuple[dict[str, str], dict[str, str]]:
        headers: dict[str, str] = {
            "Accept": "application/json",
            "Content-Type": "application/x-www-form-urlencoded",
        }
        form = dict(grant)
        if client_secret:
            # RFC 6749 §2.3.1 client_secret_basic with form-urlencoded credentials
            raw = f"{quote_plus(client_id)}:{quote_plus(client_secret)}".encode()
            encoded = base64.b64encode(raw).decode("ascii")
            headers["Authorization"] = f"Basic {encoded}"
        else:
            form["client_id"] = client_id
        return headers, form

    def _parse_json_dict(self, text: str) -> dict[str, Any]:
        if not text or not text.strip():
            return {}
        try:
            import json

            parsed = json.loads(text)
            return parsed if isinstance(parsed, dict) else {}
        except ValueError:
            return {}

    def _make_error(
        self,
        body: dict[str, Any],
        status: int,
        url: str,
        *,
        secrets: tuple[str | None, ...] = (),
    ) -> OAuth2Error:
        err_code = body.get("error")
        err_str = str(err_code).strip() if isinstance(err_code, str) and err_code.strip() else None
        desc = body.get("error_description")
        desc_str = str(desc).strip() if isinstance(desc, str) and desc.strip() else None
        if desc_str:
            desc_str = _redact_secrets(desc_str, secrets)
        # Never echo raw response bodies because IdPs or proxies may echo secrets/tokens.
        parts = [f"The identity provider at {url} refused the request (HTTP {status}"]
        if err_str:
            parts.append(f", {err_str}")
        if desc_str:
            parts.append(f": {desc_str}")
        parts.append(").")
        return OAuth2Error("".join(parts), error=err_str, status=status)

    def _parse_token_response(
        self,
        response: httpx.Response,
        token_endpoint: str,
        *,
        secrets: tuple[str | None, ...] = (),
    ) -> OAuth2TokenResponse:
        body = self._parse_json_dict(response.text)
        if response.status_code >= 400 or body.get("error") is not None:
            raise self._make_error(body, response.status_code, token_endpoint, secrets=secrets)
        access_token = body.get("access_token")
        if not isinstance(access_token, str) or not access_token.strip():
            raise OAuth2Error(
                f"The token endpoint {token_endpoint} returned no access token.",
                status=response.status_code,
            )
        refresh = body.get("refresh_token")
        refresh_str = refresh if isinstance(refresh, str) and refresh.strip() else None
        raw_exp = body.get("expires_in", 0)
        try:
            expires_in = float(raw_exp) if raw_exp is not None else 0.0
        except (TypeError, ValueError):
            expires_in = 0.0
        scope = body.get("scope")
        scope_str = scope if isinstance(scope, str) and scope.strip() else None
        return OAuth2TokenResponse(
            access_token=access_token,
            refresh_token=refresh_str,
            expires_in=expires_in,
            scope=scope_str,
        )

    def _parse_discovery_response(self, response: httpx.Response, base: str, url: str) -> OAuth2Endpoints:
        if response.status_code >= 400:
            raise OAuth2Error(f"GET {url} answered HTTP {response.status_code}.", status=response.status_code)
        body = self._parse_json_dict(response.text)
        declared = body.get("issuer")
        if not isinstance(declared, str) or declared.rstrip("/") != base:
            raise OAuth2Error(
                f"The discovery document at {base} names another issuer: {declared!r}."
            )
        token_endpoint = body.get("token_endpoint")
        if not isinstance(token_endpoint, str) or not token_endpoint.strip():
            raise OAuth2Error(f"The issuer {base} publishes no token endpoint.")
        dev_ep = body.get("device_authorization_endpoint")
        rev_ep = body.get("revocation_endpoint")
        return OAuth2Endpoints(
            issuer=declared,
            token_endpoint=token_endpoint,
            device_authorization_endpoint=dev_ep if isinstance(dev_ep, str) and dev_ep.strip() else None,
            revocation_endpoint=rev_ep if isinstance(rev_ep, str) and rev_ep.strip() else None,
        )

    def _parse_device_auth_response(
        self,
        response: httpx.Response,
        endpoint: str,
    ) -> DeviceAuthorization:
        body = self._parse_json_dict(response.text)
        if response.status_code >= 400 or body.get("error") is not None:
            raise self._make_error(body, response.status_code, endpoint)
        device_code = body.get("device_code")
        user_code = body.get("user_code")
        verification_uri = body.get("verification_uri")
        if not all(isinstance(v, str) and v.strip() for v in (device_code, user_code, verification_uri)):
            raise OAuth2Error(
                f"The device authorization endpoint {endpoint} returned an incomplete response.",
                status=response.status_code,
            )
        v_complete = body.get("verification_uri_complete")
        expires_in = int(body.get("expires_in", 600))
        interval = max(1, int(body.get("interval", 5)))
        return DeviceAuthorization(
            device_code=str(device_code),
            user_code=str(user_code),
            verification_uri=str(verification_uri),
            verification_uri_complete=str(v_complete) if isinstance(v_complete, str) and v_complete.strip() else None,
            expires_in=expires_in,
            interval=interval,
        )

    def discover(self, issuer: str) -> OAuth2Endpoints:
        """Read ``<issuer>/.well-known/openid-configuration`` synchronously."""
        base = _require_text(issuer, "issuer").rstrip("/")
        url = f"{base}/.well-known/openid-configuration"
        response = self._sync_request("GET", url, headers={"Accept": "application/json"})
        return self._parse_discovery_response(response, base, url)

    async def adiscover(self, issuer: str) -> OAuth2Endpoints:
        """Read ``<issuer>/.well-known/openid-configuration`` asynchronously."""
        base = _require_text(issuer, "issuer").rstrip("/")
        url = f"{base}/.well-known/openid-configuration"
        response = await self._async_request("GET", url, headers={"Accept": "application/json"})
        return self._parse_discovery_response(response, base, url)

    def token(
        self,
        token_endpoint: str,
        client_id: str,
        client_secret: str | None,
        grant: Mapping[str, str],
    ) -> OAuth2TokenResponse:
        """Post an OAuth 2.0 grant to ``token_endpoint`` synchronously."""
        headers, form = self._build_form_headers_and_data(client_id, client_secret, grant)
        secrets = (
            client_secret,
            grant.get("refresh_token"),
            grant.get("subject_token"),
            grant.get("actor_token"),
            grant.get("device_code"),
        )
        response = self._sync_request("POST", token_endpoint, headers=headers, data=form)
        return self._parse_token_response(response, token_endpoint, secrets=secrets)

    async def atoken(
        self,
        token_endpoint: str,
        client_id: str,
        client_secret: str | None,
        grant: Mapping[str, str],
    ) -> OAuth2TokenResponse:
        """Post an OAuth 2.0 grant to ``token_endpoint`` asynchronously."""
        headers, form = self._build_form_headers_and_data(client_id, client_secret, grant)
        secrets = (
            client_secret,
            grant.get("refresh_token"),
            grant.get("subject_token"),
            grant.get("actor_token"),
            grant.get("device_code"),
        )
        response = await self._async_request("POST", token_endpoint, headers=headers, data=form)
        return self._parse_token_response(response, token_endpoint, secrets=secrets)

    def start_device_authorization(
        self,
        endpoint: str,
        client_id: str,
        scope: str | None = None,
    ) -> DeviceAuthorization:
        """Start an RFC 8628 device authorization grant synchronously."""
        grant: dict[str, str] = {}
        if scope and scope.strip():
            grant["scope"] = scope.strip()
        headers, form = self._build_form_headers_and_data(client_id, None, grant)
        response = self._sync_request("POST", endpoint, headers=headers, data=form)
        return self._parse_device_auth_response(response, endpoint)

    async def astart_device_authorization(
        self,
        endpoint: str,
        client_id: str,
        scope: str | None = None,
    ) -> DeviceAuthorization:
        """Start an RFC 8628 device authorization grant asynchronously."""
        grant: dict[str, str] = {}
        if scope and scope.strip():
            grant["scope"] = scope.strip()
        headers, form = self._build_form_headers_and_data(client_id, None, grant)
        response = await self._async_request("POST", endpoint, headers=headers, data=form)
        return self._parse_device_auth_response(response, endpoint)

    def revoke(
        self,
        endpoint: str,
        client_id: str,
        token: str,
        *,
        token_type_hint: str | None = None,
        client_secret: str | None = None,
    ) -> None:
        """Revoke an OAuth 2.0 token (RFC 7009) synchronously."""
        grant: dict[str, str] = {"token": token}
        if token_type_hint:
            grant["token_type_hint"] = token_type_hint
        headers, form = self._build_form_headers_and_data(client_id, client_secret, grant)
        response = self._sync_request("POST", endpoint, headers=headers, data=form)
        if response.status_code >= 400:
            raise self._make_error(
                self._parse_json_dict(response.text),
                response.status_code,
                endpoint,
                secrets=(token, client_secret),
            )

    async def arevoke(
        self,
        endpoint: str,
        client_id: str,
        token: str,
        *,
        token_type_hint: str | None = None,
        client_secret: str | None = None,
    ) -> None:
        """Revoke an OAuth 2.0 token (RFC 7009) asynchronously."""
        grant: dict[str, str] = {"token": token}
        if token_type_hint:
            grant["token_type_hint"] = token_type_hint
        headers, form = self._build_form_headers_and_data(client_id, client_secret, grant)
        response = await self._async_request("POST", endpoint, headers=headers, data=form)
        if response.status_code >= 400:
            raise self._make_error(
                self._parse_json_dict(response.text),
                response.status_code,
                endpoint,
                secrets=(token, client_secret),
            )

    def _sync_request(
        self,
        method: str,
        url: str,
        *,
        headers: dict[str, str],
        data: dict[str, str] | None = None,
    ) -> httpx.Response:
        try:
            if self._http_client is not None:
                return self._http_client.request(method, url, headers=headers, data=data, timeout=self._timeout)
            with httpx.Client(timeout=self._timeout, verify=self._verify) as client:
                return client.request(method, url, headers=headers, data=data)
        except httpx.HTTPError as exc:
            raise OAuth2Error(
                f"Could not reach the identity provider at {url}.",
                cause=exc,
            ) from exc

    async def _async_request(
        self,
        method: str,
        url: str,
        *,
        headers: dict[str, str],
        data: dict[str, str] | None = None,
    ) -> httpx.Response:
        try:
            if self._async_http_client is not None:
                return await self._async_http_client.request(
                    method, url, headers=headers, data=data, timeout=self._timeout
                )
            async with httpx.AsyncClient(timeout=self._timeout, verify=self._verify) as client:
                return await client.request(method, url, headers=headers, data=data)
        except httpx.HTTPError as exc:
            raise OAuth2Error(
                f"Could not reach the identity provider at {url}.",
                cause=exc,
            ) from exc


class ClientCredentialsTokenProvider:
    """OAuth 2.0 Client Credentials grant (RFC 6749 §4.4) token provider.

    Caches tokens in memory, renews 30 seconds prior to expiry (or halfway for tokens living
    <= 60 seconds), supports both sync (`access_token`) and async (`aaccess_token`) callers,
    and redacts all secrets in ``repr`` / ``str``.
    """

    def __init__(
        self,
        token_endpoint: str,
        client_id: str,
        client_secret: str,
        *,
        scope: str | None = None,
        audience: str | None = None,
        oauth_client: OAuth2Client | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._oauth = oauth_client or OAuth2Client()
        self._token_endpoint = _require_text(token_endpoint, "token_endpoint")
        self._client_id = _require_text(client_id, "client_id")
        self._client_secret = _require_text(client_secret, "client_secret")
        self._scope = scope.strip() if scope and scope.strip() else None
        self._audience = audience.strip() if audience and audience.strip() else None
        self._clock = clock
        self._lock = threading.Lock()
        self._async_lock = asyncio.Lock()
        self._current: ExpiringToken | None = None

    def _build_grant(self) -> dict[str, str]:
        grant: dict[str, str] = {"grant_type": "client_credentials"}
        if self._scope:
            grant["scope"] = self._scope
        if self._audience:
            grant["audience"] = self._audience
        return grant

    def access_token(self) -> str:
        with self._lock:
            now = self._clock()
            if self._current is None or not self._current.usable(now):
                resp = self._oauth.token(
                    self._token_endpoint,
                    self._client_id,
                    self._client_secret,
                    self._build_grant(),
                )
                self._current = ExpiringToken.from_lifetime(
                    resp.access_token,
                    resp.expires_in,
                    now=self._clock(),
                )
            return self._current.value

    async def aaccess_token(self) -> str:
        async with self._async_lock:
            now = self._clock()
            if self._current is None or not self._current.usable(now):
                resp = await self._oauth.atoken(
                    self._token_endpoint,
                    self._client_id,
                    self._client_secret,
                    self._build_grant(),
                )
                self._current = ExpiringToken.from_lifetime(
                    resp.access_token,
                    resp.expires_in,
                    now=self._clock(),
                )
            return self._current.value

    def invalidate(self) -> bool:
        with self._lock:
            self._current = None
            return True

    def __repr__(self) -> str:
        return (
            f"ClientCredentialsTokenProvider(client_id={self._client_id!r}, "
            f"token_endpoint={self._token_endpoint!r}, client_secret='***')"
        )

    __str__ = __repr__


class RefreshTokenProvider:
    """OAuth 2.0 Refresh Token grant (RFC 6749 §6) token provider.

    Uses a signed-in user's access token and renews it via the refresh token prior to expiry or
    after an HTTP ``401``. Invokes ``on_renewed`` with rotated tokens so callers can persist them.
    """

    def __init__(
        self,
        token_endpoint: str,
        client_id: str,
        *,
        tokens: OAuth2Tokens | None = None,
        refresh_token: str | None = None,
        access_token: str | None = None,
        expires_at: float | None = None,
        client_secret: str | None = None,
        on_renewed: Callable[[OAuth2Tokens], None] | None = None,
        oauth_client: OAuth2Client | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._oauth = oauth_client or OAuth2Client()
        self._token_endpoint = _require_text(token_endpoint, "token_endpoint")
        self._client_id = _require_text(client_id, "client_id")
        self._client_secret = client_secret if client_secret else None
        if tokens is None:
            tokens = OAuth2Tokens(
                access_token=access_token,
                refresh_token=refresh_token,
                expires_at=expires_at,
            )
        self._tokens = tokens
        self._access: ExpiringToken | None = (
            ExpiringToken.saved(tokens.access_token, tokens.expires_at)
            if tokens.access_token and tokens.expires_at is not None
            else None
        )
        self._on_renewed = on_renewed
        self._clock = clock
        self._lock = threading.Lock()
        self._async_lock = asyncio.Lock()

    def _prepare_refresh_grant(self) -> dict[str, str]:
        if not self._tokens.refresh_token:
            raise OAuth2Error("The sign-in has expired; sign in again.", error="login_required")
        return {
            "grant_type": "refresh_token",
            "refresh_token": self._tokens.refresh_token,
        }

    def _apply_renewed(self, resp: OAuth2TokenResponse) -> str:
        now = self._clock()
        self._access = ExpiringToken.from_lifetime(resp.access_token, resp.expires_in, now=now)
        next_refresh = resp.refresh_token if resp.refresh_token else self._tokens.refresh_token
        self._tokens = OAuth2Tokens(
            access_token=self._access.value,
            refresh_token=next_refresh,
            expires_at=self._access.expires_at,
        )
        if self._on_renewed is not None:
            self._on_renewed(self._tokens)
        return self._access.value

    def access_token(self) -> str:
        with self._lock:
            now = self._clock()
            if self._access is None or not self._access.usable(now):
                grant = self._prepare_refresh_grant()
                try:
                    resp = self._oauth.token(
                        self._token_endpoint,
                        self._client_id,
                        self._client_secret,
                        grant,
                    )
                except OAuth2Error as exc:
                    if exc.error == "invalid_grant":
                        raise OAuth2Error(
                            "The sign-in has expired or was revoked; sign in again.",
                            error="login_required",
                            status=exc.status,
                            cause=exc,
                        ) from exc
                    raise
                return self._apply_renewed(resp)
            return self._access.value

    async def aaccess_token(self) -> str:
        async with self._async_lock:
            now = self._clock()
            if self._access is None or not self._access.usable(now):
                grant = self._prepare_refresh_grant()
                try:
                    resp = await self._oauth.atoken(
                        self._token_endpoint,
                        self._client_id,
                        self._client_secret,
                        grant,
                    )
                except OAuth2Error as exc:
                    if exc.error == "invalid_grant":
                        raise OAuth2Error(
                            "The sign-in has expired or was revoked; sign in again.",
                            error="login_required",
                            status=exc.status,
                            cause=exc,
                        ) from exc
                    raise
                return self._apply_renewed(resp)
            return self._access.value

    def invalidate(self) -> bool:
        with self._lock:
            if not self._tokens.refresh_token:
                return False
            self._access = None
            return True

    def __repr__(self) -> str:
        return (
            f"RefreshTokenProvider(client_id={self._client_id!r}, "
            f"token_endpoint={self._token_endpoint!r})"
        )

    __str__ = __repr__


class TokenExchangeProvider:
    """RFC 8693 OAuth 2.0 Token Exchange provider for AI agents and multi-agent delegation.

    Exchanges an end user's ``subject_token`` (and optional ``actor_token``) for a delegated
    OpenCrawling-scoped access token carrying ``act.sub`` provenance. Caches exchanged tokens
    keyed by the SHA-256 digest of ``(subject_token, actor_token)`` and re-exchanges automatically
    when the token nears expiry, when the subject/actor token changes, or on HTTP ``401``.
    """

    GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange"
    ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token"

    def __init__(
        self,
        token_endpoint: str,
        client_id: str,
        *,
        client_secret: str | None = None,
        subject_token: str | Callable[[], str],
        subject_token_type: str = ACCESS_TOKEN_TYPE,
        actor_token: str | Callable[[], str | None] | None = None,
        actor_token_type: str = ACCESS_TOKEN_TYPE,
        scope: str | None = None,
        audience: str | None = None,
        oauth_client: OAuth2Client | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._oauth = oauth_client or OAuth2Client()
        self._token_endpoint = _require_text(token_endpoint, "token_endpoint")
        self._client_id = _require_text(client_id, "client_id")
        self._client_secret = client_secret if client_secret else None
        if callable(subject_token):
            self._subject_token_supplier: Callable[[], str] = subject_token
        else:
            fixed_subject = _require_text(subject_token, "subject_token")
            self._subject_token_supplier = lambda: fixed_subject
        self._subject_token_type = _require_text(subject_token_type, "subject_token_type")
        if actor_token is None:
            self._actor_token_supplier: Callable[[], str | None] | None = None
        elif callable(actor_token):
            self._actor_token_supplier = actor_token
        else:
            fixed_actor = actor_token.strip() if actor_token.strip() else None
            self._actor_token_supplier = lambda: fixed_actor
        self._actor_token_type = _require_text(actor_token_type, "actor_token_type")
        self._scope = scope.strip() if scope and scope.strip() else None
        self._audience = audience.strip() if audience and audience.strip() else None
        self._clock = clock
        self._lock = threading.Lock()
        self._async_lock = asyncio.Lock()
        self._current: ExpiringToken | None = None
        self._current_digest: str | None = None

    @staticmethod
    def _digest(subject_token: str, actor_token: str | None) -> str:
        h = hashlib.sha256()
        h.update(subject_token.encode("utf-8"))
        h.update(b"\x00")
        if actor_token is not None:
            h.update(actor_token.encode("utf-8"))
        return h.hexdigest()

    def _resolve_inputs(self) -> tuple[str, str | None, str]:
        subject = _require_text(self._subject_token_supplier(), "subject_token")
        actor_raw = self._actor_token_supplier() if self._actor_token_supplier is not None else None
        actor = actor_raw.strip() if actor_raw and actor_raw.strip() else None
        return subject, actor, self._digest(subject, actor)

    def _build_grant(self, subject: str, actor: str | None) -> dict[str, str]:
        grant: dict[str, str] = {
            "grant_type": self.GRANT_TYPE,
            "subject_token": subject,
            "subject_token_type": self._subject_token_type,
        }
        if actor is not None:
            grant["actor_token"] = actor
            grant["actor_token_type"] = self._actor_token_type
        if self._scope:
            grant["scope"] = self._scope
        if self._audience:
            grant["audience"] = self._audience
        return grant

    def access_token(self) -> str:
        with self._lock:
            subject, actor, digest = self._resolve_inputs()
            now = self._clock()
            if self._current is None or digest != self._current_digest or not self._current.usable(now):
                resp = self._oauth.token(
                    self._token_endpoint,
                    self._client_id,
                    self._client_secret,
                    self._build_grant(subject, actor),
                )
                self._current = ExpiringToken.from_lifetime(
                    resp.access_token,
                    resp.expires_in,
                    now=self._clock(),
                )
                self._current_digest = digest
            return self._current.value

    async def aaccess_token(self) -> str:
        async with self._async_lock:
            subject, actor, digest = self._resolve_inputs()
            now = self._clock()
            if self._current is None or digest != self._current_digest or not self._current.usable(now):
                resp = await self._oauth.atoken(
                    self._token_endpoint,
                    self._client_id,
                    self._client_secret,
                    self._build_grant(subject, actor),
                )
                self._current = ExpiringToken.from_lifetime(
                    resp.access_token,
                    resp.expires_in,
                    now=self._clock(),
                )
                self._current_digest = digest
            return self._current.value

    def invalidate(self) -> bool:
        with self._lock:
            self._current = None
            self._current_digest = None
            return True

    def __repr__(self) -> str:
        return (
            f"TokenExchangeProvider(client_id={self._client_id!r}, "
            f"token_endpoint={self._token_endpoint!r})"
        )

    __str__ = __repr__
