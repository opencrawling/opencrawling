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

"""Optional FastAPI / Starlette ASGI decorators and app factories (``opencrawling-sdk[hooks]``)."""

from __future__ import annotations

import inspect
from collections.abc import Awaitable, Callable
from functools import wraps
from importlib import import_module
from typing import Any

from pydantic import ValidationError

from opencrawling.hooks.models import (
    Decision,
    EnrichmentRequest,
    EnrichmentResponse,
    GovernanceRequest,
)
from opencrawling.hooks.signing import (
    DEFAULT_MAX_SKEW_SECONDS,
    HookSignatureError,
    verify_signature,
)

SecretProvider = str | Callable[[], str]
GovernanceHandler = Callable[[GovernanceRequest], Decision | Awaitable[Decision]]
EnrichmentHandler = Callable[[EnrichmentRequest], EnrichmentResponse | Awaitable[EnrichmentResponse]]


def _require_starlette() -> tuple[Any, Any, Any]:
    try:
        applications = import_module("starlette.applications")
        responses = import_module("starlette.responses")
        routing = import_module("starlette.routing")
        return applications.Starlette, responses.JSONResponse, routing.Route
    except ImportError as exc:
        raise ImportError(
            "ASGI hook helpers require the optional 'hooks' extra. "
            "Install with: pip install 'opencrawling-sdk[hooks]'"
        ) from exc


def _resolve_secret(secret: SecretProvider) -> str:
    return secret() if callable(secret) else secret


def governance_endpoint(
    *,
    secret: SecretProvider,
    max_skew_seconds: int = DEFAULT_MAX_SKEW_SECONDS,
    enforce_min_length: bool = True,
) -> Callable[[GovernanceHandler], Callable[[Any], Awaitable[Any]]]:
    """Decorate a sync or async ``(GovernanceRequest) -> Decision`` handler as an ASGI endpoint.

    Compatible with both FastAPI (``@app.post("/governance")``) and Starlette ``Route``s.
    Automatically verifies ``X-OpenCrawling-Timestamp`` and ``X-OpenCrawling-Signature`` in
    constant time before deserializing the JSON request body.
    """

    def decorator(handler: GovernanceHandler) -> Callable[[Any], Awaitable[Any]]:
        @wraps(handler)
        async def endpoint(request: Any) -> Any:
            _, json_response_cls, _ = _require_starlette()
            body: bytes = await request.body()
            try:
                verify_signature(
                    body,
                    _resolve_secret(secret),
                    headers=request.headers,
                    max_skew_seconds=max_skew_seconds,
                    enforce_min_length=enforce_min_length,
                )
            except HookSignatureError as exc:
                return json_response_cls(
                    {"error": exc.reason, "detail": str(exc)},
                    status_code=401,
                )

            try:
                gov_request = GovernanceRequest.model_validate_json(body)
            except ValidationError as exc:
                return json_response_cls(
                    {"error": "invalid_payload", "detail": str(exc)},
                    status_code=400,
                )

            result = handler(gov_request)
            decision = await result if inspect.isawaitable(result) else result
            if not isinstance(decision, Decision):
                raise TypeError(
                    f"Governance handler must return a Decision instance, got {type(decision).__name__}"
                )
            return json_response_cls(decision.to_payload(), status_code=200)

        return endpoint

    return decorator


def enrichment_endpoint(
    *,
    secret: SecretProvider,
    max_skew_seconds: int = DEFAULT_MAX_SKEW_SECONDS,
    enforce_min_length: bool = True,
) -> Callable[[EnrichmentHandler], Callable[[Any], Awaitable[Any]]]:
    """Decorate a sync or async ``(EnrichmentRequest) -> EnrichmentResponse`` handler as an ASGI endpoint."""

    def decorator(handler: EnrichmentHandler) -> Callable[[Any], Awaitable[Any]]:
        @wraps(handler)
        async def endpoint(request: Any) -> Any:
            _, json_response_cls, _ = _require_starlette()
            body: bytes = await request.body()
            try:
                verify_signature(
                    body,
                    _resolve_secret(secret),
                    headers=request.headers,
                    max_skew_seconds=max_skew_seconds,
                    enforce_min_length=enforce_min_length,
                )
            except HookSignatureError as exc:
                return json_response_cls(
                    {"error": exc.reason, "detail": str(exc)},
                    status_code=401,
                )

            try:
                enrich_request = EnrichmentRequest.model_validate_json(body)
            except ValidationError as exc:
                return json_response_cls(
                    {"error": "invalid_payload", "detail": str(exc)},
                    status_code=400,
                )

            result = handler(enrich_request)
            response = await result if inspect.isawaitable(result) else result
            if not isinstance(response, EnrichmentResponse):
                raise TypeError(
                    "Enrichment handler must return an EnrichmentResponse instance, "
                    f"got {type(response).__name__}"
                )
            return json_response_cls(response.to_payload(), status_code=200)

        return endpoint

    return decorator


def create_governance_app(
    handler: GovernanceHandler,
    *,
    secret: SecretProvider,
    path: str = "/governance",
    max_skew_seconds: int = DEFAULT_MAX_SKEW_SECONDS,
    enforce_min_length: bool = True,
) -> Any:
    """Create a standalone Starlette ASGI app serving a signed OpenCrawling governance hook."""
    starlette_cls, _, route_cls = _require_starlette()
    wrapped = governance_endpoint(
        secret=secret,
        max_skew_seconds=max_skew_seconds,
        enforce_min_length=enforce_min_length,
    )(handler)
    return starlette_cls(routes=[route_cls(path, wrapped, methods=["POST"])])


def create_enrichment_app(
    handler: EnrichmentHandler,
    *,
    secret: SecretProvider,
    path: str = "/enrich",
    max_skew_seconds: int = DEFAULT_MAX_SKEW_SECONDS,
    enforce_min_length: bool = True,
) -> Any:
    """Create a standalone Starlette ASGI app serving a signed OpenCrawling enrichment hook."""
    starlette_cls, _, route_cls = _require_starlette()
    wrapped = enrichment_endpoint(
        secret=secret,
        max_skew_seconds=max_skew_seconds,
        enforce_min_length=enforce_min_length,
    )(handler)
    return starlette_cls(routes=[route_cls(path, wrapped, methods=["POST"])])
