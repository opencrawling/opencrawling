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
"""Internal synchronous and asynchronous HTTP transports over ``httpx``."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import tempfile
import time
import uuid
from collections.abc import Awaitable, Callable, Mapping
from pathlib import Path
from typing import Any
from urllib.parse import quote

import httpx

from opencrawling._version import __version__
from opencrawling.auth.base import (
    AnyTokenProvider,
    StaticTokenProvider,
    TokenProvider,
    invalidate_async_token,
    resolve_async_token,
)
from opencrawling.config import (
    RETRYABLE_STATUSES,
    RequestOptions,
    RetryPolicy,
    parse_retry_after,
)
from opencrawling.errors import ApiProblemError, NetworkError, ProblemFieldError

logger = logging.getLogger("opencrawling.transport")

API_V1_BASE = "/api/v1"
JSON_MEDIA_TYPE = "application/json"
MERGE_PATCH_MEDIA_TYPE = "application/merge-patch+json"
DEFAULT_ACCEPT = "application/json, application/problem+json"
DEFAULT_USER_AGENT = f"opencrawling-python-sdk/{__version__}"
_PROBLEM_STANDARD_KEYS = frozenset({"type", "title", "status", "detail", "instance", "requestId", "errors"})


def normalize_base_url(base_url: str) -> str:
    cleaned = base_url.strip()
    if not cleaned:
        return "http://localhost:8080"
    return cleaned.rstrip("/")


def encode_path_segment(segment: str) -> str:
    if not segment:
        raise ValueError("A path segment (an id or name) must not be empty")
    return quote(segment, safe="")


def api_path(*segments: str) -> str:
    """Build ``/api/v1/<percent-encoded segments>``."""
    if not segments:
        return API_V1_BASE
    encoded = "/".join(encode_path_segment(seg) for seg in segments)
    return f"{API_V1_BASE}/{encoded}"


def api_action(path: str, action_name: str) -> str:
    """Build a Platform API v1 custom action path ``<path>:<action_name>``."""
    return f"{path}:{action_name}"


def filter_params(params: Mapping[str, Any] | None) -> dict[str, str]:
    """Convert non-``None`` query parameters to strings suitable for ``httpx``."""
    if not params:
        return {}
    result: dict[str, str] = {}
    for key, value in params.items():
        if value is None:
            continue
        if isinstance(value, bool):
            result[key] = "true" if value else "false"
        else:
            result[key] = str(value)
    return result


def _is_repeatable(method: str, options: RequestOptions, *, is_multipart: bool) -> bool:
    upper = method.upper()
    if upper in {"GET", "HEAD", "PUT", "DELETE"}:
        return True
    if upper == "POST":
        return bool(options.idempotency_key) and not is_multipart
    if upper == "PATCH":
        return bool(options.if_match)
    return False


def _is_retryable_response(response: httpx.Response) -> bool:
    if response.status_code in RETRYABLE_STATUSES:
        return True
    if response.status_code == 409:
        text = response.text
        if "problem-idempotency-in-progress" in text:
            return True
        try:
            payload = json.loads(text)
            if isinstance(payload, dict):
                ptype = payload.get("type")
                if isinstance(ptype, str) and (
                    ptype.endswith("#problem-idempotency-in-progress")
                    or ptype.endswith("/idempotency-in-progress")
                    or ptype == "idempotency-in-progress"
                ):
                    return True
        except ValueError:
            pass
    return False


def parse_problem_error(response: httpx.Response, *, body_bytes: bytes | None = None) -> ApiProblemError:
    """Parse an HTTP ``>= 400`` response into a structured RFC 9457 ``ApiProblemError``."""
    raw_bytes = body_bytes if body_bytes is not None else response.content
    text = raw_bytes.decode("utf-8", errors="replace")
    content_type = response.headers.get("content-type", "")
    header_request_id = response.headers.get("x-request-id")
    retry_after = parse_retry_after(response.headers.get("retry-after"))

    parsed: dict[str, Any] | None = None
    if "json" in content_type.lower() and text.strip():
        try:
            obj = json.loads(text)
            if isinstance(obj, dict):
                parsed = obj
        except ValueError:
            parsed = None

    if parsed is not None:
        raw_status = parsed.get("status")
        status = int(raw_status) if isinstance(raw_status, int) and raw_status > 0 else response.status_code
        ptype = parsed.get("type") if isinstance(parsed.get("type"), str) else None
        title = parsed.get("title") if isinstance(parsed.get("title"), str) else f"HTTP {response.status_code}"
        detail = parsed.get("detail") if isinstance(parsed.get("detail"), str) else None
        instance = parsed.get("instance") if isinstance(parsed.get("instance"), str) else None
        req_id = (
            parsed.get("requestId")
            if isinstance(parsed.get("requestId"), str)
            else header_request_id
        )
        raw_errors = parsed.get("errors")
        field_errors: list[ProblemFieldError] = []
        if isinstance(raw_errors, list):
            for entry in raw_errors:
                if isinstance(entry, dict):
                    field_name = str(entry.get("field", ""))
                    msg = str(entry.get("message", ""))
                    field_errors.append(ProblemFieldError(field=field_name, message=msg))
        extensions = {k: v for k, v in parsed.items() if k not in _PROBLEM_STANDARD_KEYS}
        return ApiProblemError(
            status=status,
            type=ptype,
            title=title,
            detail=detail,
            instance=instance,
            request_id=req_id,
            errors=field_errors,
            extensions=extensions,
            retry_after=retry_after,
            raw_body=text,
        )

    return ApiProblemError(
        status=response.status_code,
        type=None,
        title=f"HTTP {response.status_code}",
        detail=text.strip() or None,
        instance=None,
        request_id=header_request_id,
        errors=(),
        extensions={},
        retry_after=retry_after,
        raw_body=text,
    )


def _prepare_request_headers(
    *,
    api_key: str | None,
    options: RequestOptions,
    extra_headers: Mapping[str, str] | None,
    accept: str,
    content_type: str | None,
    is_multipart: bool,
) -> dict[str, str]:
    headers: dict[str, str] = {
        "Accept": accept,
        "User-Agent": DEFAULT_USER_AGENT,
    }
    if api_key:
        headers["X-API-Key"] = api_key
    if options.idempotency_key and not is_multipart:
        headers["Idempotency-Key"] = options.idempotency_key
    if options.correlation_id:
        headers["X-Correlation-Id"] = options.correlation_id
    if options.request_id:
        headers["X-Request-Id"] = options.request_id
    if options.if_match:
        headers["If-Match"] = options.if_match
    if options.if_none_match:
        headers["If-None-Match"] = options.if_none_match
    for key, value in options.extra_headers.items():
        headers[key] = value
    if extra_headers:
        for key, value in extra_headers.items():
            headers[key] = value
    if content_type and not is_multipart:
        headers["Content-Type"] = content_type
    return headers


def _encode_json_body(json_body: Any) -> bytes | None:
    if json_body is None:
        return None
    if isinstance(json_body, (bytes, bytearray)):
        return bytes(json_body)
    return json.dumps(json_body, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


class SyncTransport:
    """Synchronous HTTP transport wrapping ``httpx.Client`` with auth, idempotency, and retries."""

    def __init__(
        self,
        base_url: str,
        *,
        api_key: str | None = None,
        token_provider: TokenProvider | None = None,
        retry_policy: RetryPolicy | None = None,
        default_options: RequestOptions | None = None,
        timeout: float | httpx.Timeout = 30.0,
        http2: bool = False,
        verify: bool | str = True,
        http_client: httpx.Client | None = None,
        owns_client: bool | None = None,
        sleeper: Callable[[float], None] = time.sleep,
    ) -> None:
        self.base_url = normalize_base_url(base_url)
        self._api_key = api_key.strip() if api_key and api_key.strip() else None
        self._token_provider: TokenProvider = token_provider or StaticTokenProvider(None)
        self.retry_policy = retry_policy if retry_policy is not None else RetryPolicy.defaults()
        self._default_options = default_options.as_defaults() if default_options is not None else RequestOptions.none()
        self._sleeper = sleeper
        if http_client is not None:
            self._client = http_client
            self._owns_client = owns_client if owns_client is not None else False
        else:
            self._client = httpx.Client(
                base_url=self.base_url,
                timeout=timeout,
                http2=http2,
                verify=verify,
                follow_redirects=False,
            )
            self._owns_client = owns_client if owns_client is not None else True

    def with_defaults(self, options: RequestOptions) -> SyncTransport:
        merged = RequestOptions.merge(self._default_options, options.as_defaults())
        return SyncTransport(
            self.base_url,
            api_key=self._api_key,
            token_provider=self._token_provider,
            retry_policy=self.retry_policy,
            default_options=merged,
            http_client=self._client,
            owns_client=False,
            sleeper=self._sleeper,
        )

    def with_token_provider(self, token_provider: TokenProvider) -> SyncTransport:
        return SyncTransport(
            self.base_url,
            api_key=self._api_key,
            token_provider=token_provider,
            retry_policy=self.retry_policy,
            default_options=self._default_options,
            http_client=self._client,
            owns_client=False,
            sleeper=self._sleeper,
        )

    def close(self) -> None:
        if self._owns_client:
            self._client.close()

    def _build_url(self, path: str) -> str:
        if path.startswith(("http://", "https://")):
            return path
        suffix = path if path.startswith("/") else f"/{path}"
        return f"{self.base_url}{suffix}"

    def _send_once(
        self,
        method: str,
        url: str,
        *,
        params: dict[str, str],
        headers: dict[str, str],
        content: bytes | None,
        data: Mapping[str, Any] | None,
        files: Any | None,
    ) -> httpx.Response:
        def _execute() -> httpx.Response:
            req_headers = dict(headers)
            token = self._token_provider.access_token()
            if token and token.strip():
                req_headers["Authorization"] = f"Bearer {token.strip()}"
            logger.debug("HTTP %s %s", method, url)
            return self._client.request(
                method,
                url,
                params=params or None,
                headers=req_headers,
                content=content,
                data=data,
                files=files,
            )

        response = _execute()
        if response.status_code == 401 and self._token_provider.invalidate():
            logger.debug("HTTP 401 received for %s %s; refreshing token and retrying once", method, url)
            response = _execute()
        return response

    def request(
        self,
        method: str,
        path: str,
        *,
        params: Mapping[str, Any] | None = None,
        json_body: Any = None,
        content_type: str | None = None,
        data: Mapping[str, Any] | None = None,
        files: Any | None = None,
        options: RequestOptions | None = None,
        extra_headers: Mapping[str, str] | None = None,
        accept: str = DEFAULT_ACCEPT,
        retry: bool = True,
        auto_idempotency: bool = True,
        force_repeatable: bool = False,
    ) -> httpx.Response:
        upper = method.upper()
        is_multipart = files is not None or (
            content_type is not None and content_type.lower().startswith("multipart/")
        )
        merged = RequestOptions.merge(self._default_options, options)
        if is_multipart:
            merged = merged.with_idempotency_key(None)
        elif upper == "POST" and auto_idempotency and not merged.idempotency_key:
            merged = merged.with_idempotency_key(f"sdk-{uuid.uuid4()}")

        repeatable = force_repeatable or _is_repeatable(upper, merged, is_multipart=is_multipart)
        max_attempts = self.retry_policy.max_attempts if (retry and repeatable) else 1

        raw_content = _encode_json_body(json_body) if files is None else None
        effective_ct = (
            content_type
            if content_type is not None
            else (JSON_MEDIA_TYPE if raw_content is not None else None)
        )
        headers = _prepare_request_headers(
            api_key=self._api_key,
            options=merged,
            extra_headers=extra_headers,
            accept=accept,
            content_type=effective_ct,
            is_multipart=is_multipart,
        )
        query_params = filter_params(params)
        url = self._build_url(path)

        for attempt in range(1, max_attempts + 1):
            try:
                response = self._send_once(
                    upper,
                    url,
                    params=query_params,
                    headers=headers,
                    content=raw_content,
                    data=data,
                    files=files,
                )
            except httpx.HTTPError as exc:
                if attempt >= max_attempts:
                    raise NetworkError(
                        f"Network error while communicating with OpenCrawling endpoint: {url}",
                        cause=exc,
                    ) from exc
                delay = self.retry_policy.compute_delay(attempt, None)
                if delay is not None and delay > 0:
                    self._sleeper(delay)
                continue

            if attempt >= max_attempts or not _is_retryable_response(response):
                return response

            delay = self.retry_policy.compute_delay(attempt, response.headers.get("retry-after"))
            if delay is None:
                return response
            if delay > 0:
                self._sleeper(delay)

        raise NetworkError(f"Exhausted retry attempts for {upper} {url}")  # pragma: no cover

    def check_ok(self, response: httpx.Response) -> httpx.Response:
        if response.status_code >= 400:
            raise parse_problem_error(response)
        return response

    def parse_json(self, response: httpx.Response) -> Any:
        if not response.content:
            return None
        try:
            return response.json()
        except ValueError as exc:
            raise NetworkError(
                f"Could not parse JSON response from {response.request.url if response.request else 'runtime'}",
                cause=exc,
            ) from exc

    def stream_to_file(
        self,
        path: str,
        target: Path,
        *,
        params: Mapping[str, Any] | None = None,
        options: RequestOptions | None = None,
    ) -> Path:
        """Stream a ``GET`` response directly to disk via an atomic ``.part`` temporary file."""
        merged = RequestOptions.merge(self._default_options, options)
        headers = _prepare_request_headers(
            api_key=self._api_key,
            options=merged,
            extra_headers=None,
            accept="*/*",
            content_type=None,
            is_multipart=False,
        )
        query_params = filter_params(params)
        url = self._build_url(path)
        abs_target = target.resolve()
        abs_target.parent.mkdir(parents=True, exist_ok=True)

        def _open_and_write(retried_auth: bool) -> Path:
            req_headers = dict(headers)
            token = self._token_provider.access_token()
            if token and token.strip():
                req_headers["Authorization"] = f"Bearer {token.strip()}"
            try:
                with self._client.stream(
                    "GET",
                    url,
                    params=query_params or None,
                    headers=req_headers,
                ) as response:
                    if response.status_code == 401 and not retried_auth and self._token_provider.invalidate():
                        return _open_and_write(True)
                    if response.status_code >= 400:
                        body_bytes = response.read()
                        raise parse_problem_error(response, body_bytes=body_bytes)
                    fd, tmp_name = tempfile.mkstemp(
                        prefix=f".{abs_target.name}.",
                        suffix=".part",
                        dir=str(abs_target.parent),
                    )
                    tmp_path = Path(tmp_name)
                    try:
                        with os.fdopen(fd, "wb") as out:
                            for chunk in response.iter_bytes():
                                if chunk:
                                    out.write(chunk)
                        os.replace(tmp_path, abs_target)
                    except Exception:
                        tmp_path.unlink(missing_ok=True)
                        raise
                    return target
            except httpx.HTTPError as exc:
                raise NetworkError(f"Could not stream report to {target}", cause=exc) from exc

        return _open_and_write(False)


class AsyncTransport:
    """Asynchronous HTTP transport wrapping ``httpx.AsyncClient`` with auth, idempotency, and retries."""

    def __init__(
        self,
        base_url: str,
        *,
        api_key: str | None = None,
        token_provider: AnyTokenProvider | None = None,
        retry_policy: RetryPolicy | None = None,
        default_options: RequestOptions | None = None,
        timeout: float | httpx.Timeout = 30.0,
        http2: bool = False,
        verify: bool | str = True,
        http_client: httpx.AsyncClient | None = None,
        owns_client: bool | None = None,
        asleeper: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self.base_url = normalize_base_url(base_url)
        self._api_key = api_key.strip() if api_key and api_key.strip() else None
        self._token_provider: AnyTokenProvider = token_provider or StaticTokenProvider(None)
        self.retry_policy = retry_policy if retry_policy is not None else RetryPolicy.defaults()
        self._default_options = default_options.as_defaults() if default_options is not None else RequestOptions.none()
        self._asleeper = asleeper
        if http_client is not None:
            self._client = http_client
            self._owns_client = owns_client if owns_client is not None else False
        else:
            self._client = httpx.AsyncClient(
                base_url=self.base_url,
                timeout=timeout,
                http2=http2,
                verify=verify,
                follow_redirects=False,
            )
            self._owns_client = owns_client if owns_client is not None else True

    def with_defaults(self, options: RequestOptions) -> AsyncTransport:
        merged = RequestOptions.merge(self._default_options, options.as_defaults())
        return AsyncTransport(
            self.base_url,
            api_key=self._api_key,
            token_provider=self._token_provider,
            retry_policy=self.retry_policy,
            default_options=merged,
            http_client=self._client,
            owns_client=False,
            asleeper=self._asleeper,
        )

    def with_token_provider(self, token_provider: AnyTokenProvider) -> AsyncTransport:
        return AsyncTransport(
            self.base_url,
            api_key=self._api_key,
            token_provider=token_provider,
            retry_policy=self.retry_policy,
            default_options=self._default_options,
            http_client=self._client,
            owns_client=False,
            asleeper=self._asleeper,
        )

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    def _build_url(self, path: str) -> str:
        if path.startswith(("http://", "https://")):
            return path
        suffix = path if path.startswith("/") else f"/{path}"
        return f"{self.base_url}{suffix}"

    async def _send_once(
        self,
        method: str,
        url: str,
        *,
        params: dict[str, str],
        headers: dict[str, str],
        content: bytes | None,
        data: Mapping[str, Any] | None,
        files: Any | None,
    ) -> httpx.Response:
        async def _execute() -> httpx.Response:
            req_headers = dict(headers)
            token = await resolve_async_token(self._token_provider)
            if token and token.strip():
                req_headers["Authorization"] = f"Bearer {token.strip()}"
            logger.debug("HTTP %s %s", method, url)
            return await self._client.request(
                method,
                url,
                params=params or None,
                headers=req_headers,
                content=content,
                data=data,
                files=files,
            )

        response = await _execute()
        if response.status_code == 401 and await invalidate_async_token(self._token_provider):
            logger.debug("HTTP 401 received for %s %s; refreshing token and retrying once", method, url)
            response = await _execute()
        return response

    async def request(
        self,
        method: str,
        path: str,
        *,
        params: Mapping[str, Any] | None = None,
        json_body: Any = None,
        content_type: str | None = None,
        data: Mapping[str, Any] | None = None,
        files: Any | None = None,
        options: RequestOptions | None = None,
        extra_headers: Mapping[str, str] | None = None,
        accept: str = DEFAULT_ACCEPT,
        retry: bool = True,
        auto_idempotency: bool = True,
        force_repeatable: bool = False,
    ) -> httpx.Response:
        upper = method.upper()
        is_multipart = files is not None or (
            content_type is not None and content_type.lower().startswith("multipart/")
        )
        merged = RequestOptions.merge(self._default_options, options)
        if is_multipart:
            merged = merged.with_idempotency_key(None)
        elif upper == "POST" and auto_idempotency and not merged.idempotency_key:
            merged = merged.with_idempotency_key(f"sdk-{uuid.uuid4()}")

        repeatable = force_repeatable or _is_repeatable(upper, merged, is_multipart=is_multipart)
        max_attempts = self.retry_policy.max_attempts if (retry and repeatable) else 1

        raw_content = _encode_json_body(json_body) if files is None else None
        effective_ct = (
            content_type
            if content_type is not None
            else (JSON_MEDIA_TYPE if raw_content is not None else None)
        )
        headers = _prepare_request_headers(
            api_key=self._api_key,
            options=merged,
            extra_headers=extra_headers,
            accept=accept,
            content_type=effective_ct,
            is_multipart=is_multipart,
        )
        query_params = filter_params(params)
        url = self._build_url(path)

        for attempt in range(1, max_attempts + 1):
            try:
                response = await self._send_once(
                    upper,
                    url,
                    params=query_params,
                    headers=headers,
                    content=raw_content,
                    data=data,
                    files=files,
                )
            except httpx.HTTPError as exc:
                if attempt >= max_attempts:
                    raise NetworkError(
                        f"Network error while communicating with OpenCrawling endpoint: {url}",
                        cause=exc,
                    ) from exc
                delay = self.retry_policy.compute_delay(attempt, None)
                if delay is not None and delay > 0:
                    await self._asleeper(delay)
                continue

            if attempt >= max_attempts or not _is_retryable_response(response):
                return response

            delay = self.retry_policy.compute_delay(attempt, response.headers.get("retry-after"))
            if delay is None:
                return response
            if delay > 0:
                await self._asleeper(delay)

        raise NetworkError(f"Exhausted retry attempts for {upper} {url}")  # pragma: no cover

    def check_ok(self, response: httpx.Response) -> httpx.Response:
        if response.status_code >= 400:
            raise parse_problem_error(response)
        return response

    def parse_json(self, response: httpx.Response) -> Any:
        if not response.content:
            return None
        try:
            return response.json()
        except ValueError as exc:
            raise NetworkError(
                f"Could not parse JSON response from {response.request.url if response.request else 'runtime'}",
                cause=exc,
            ) from exc

    async def astream_to_file(
        self,
        path: str,
        target: Path,
        *,
        params: Mapping[str, Any] | None = None,
        options: RequestOptions | None = None,
    ) -> Path:
        """Stream a ``GET`` response asynchronously to disk via an atomic ``.part`` file."""
        merged = RequestOptions.merge(self._default_options, options)
        headers = _prepare_request_headers(
            api_key=self._api_key,
            options=merged,
            extra_headers=None,
            accept="*/*",
            content_type=None,
            is_multipart=False,
        )
        query_params = filter_params(params)
        url = self._build_url(path)
        abs_target = target.resolve()
        abs_target.parent.mkdir(parents=True, exist_ok=True)

        async def _open_and_write(retried_auth: bool) -> Path:
            req_headers = dict(headers)
            token = await resolve_async_token(self._token_provider)
            if token and token.strip():
                req_headers["Authorization"] = f"Bearer {token.strip()}"
            try:
                async with self._client.stream(
                    "GET",
                    url,
                    params=query_params or None,
                    headers=req_headers,
                ) as response:
                    if (
                        response.status_code == 401
                        and not retried_auth
                        and await invalidate_async_token(self._token_provider)
                    ):
                        return await _open_and_write(True)
                    if response.status_code >= 400:
                        body_bytes = await response.aread()
                        raise parse_problem_error(response, body_bytes=body_bytes)
                    fd, tmp_name = tempfile.mkstemp(
                        prefix=f".{abs_target.name}.",
                        suffix=".part",
                        dir=str(abs_target.parent),
                    )
                    tmp_path = Path(tmp_name)
                    try:
                        with os.fdopen(fd, "wb") as out:
                            async for chunk in response.aiter_bytes():
                                if chunk:
                                    out.write(chunk)
                        os.replace(tmp_path, abs_target)
                    except Exception:
                        tmp_path.unlink(missing_ok=True)
                        raise
                    return target
            except httpx.HTTPError as exc:
                raise NetworkError(f"Could not stream report to {target}", cause=exc) from exc

        return await _open_and_write(False)
