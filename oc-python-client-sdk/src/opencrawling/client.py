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

"""Synchronous (``OpenCrawlingClient``) and asynchronous (``AsyncOpenCrawlingClient``) SDK entry points."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

import httpx

from opencrawling._transport import AsyncTransport, SyncTransport, api_path
from opencrawling.auth.base import AnyTokenProvider, StaticTokenProvider, TokenProvider
from opencrawling.auth.oauth2 import ClientCredentialsTokenProvider
from opencrawling.config import RequestOptions, RetryPolicy
from opencrawling.ingest.ois import AsyncOisIngestClient, OisIngestClient
from opencrawling.v1.connectors import AsyncConnectorsClient, ConnectorsClient
from opencrawling.v1.discovery import AsyncDiscoveryClient, DiscoveryClient
from opencrawling.v1.dynamic_jobs import AsyncDynamicJobsClient, DynamicJobsClient
from opencrawling.v1.executions import AsyncExecutionsClient, ExecutionsClient
from opencrawling.v1.job_runs import AsyncJobRunsClient, JobRunsClient
from opencrawling.v1.jobs import AsyncJobsClient, JobsClient
from opencrawling.v1.migrations import AsyncMigrationsClient, MigrationsClient
from opencrawling.v1.models import ApiIndex, CallerInfo, Health, VersionInfo
from opencrawling.v1.narrativization import AsyncNarrativizationClient, NarrativizationClient
from opencrawling.v1.operations import AsyncOperationsClient, OperationsClient
from opencrawling.v1.runs import AsyncRunsClient, RunsClient
from opencrawling.v1.schedules import AsyncSchedulesClient, SchedulesClient
from opencrawling.v1.settings import AsyncSettingsClient, SettingsClient
from opencrawling.v1.sidecars import AsyncSidecarsClient, SidecarsClient
from opencrawling.v1.vespa import AsyncVespaClient, VespaClient


def _build_client_credentials_provider(cfg: Mapping[str, str]) -> ClientCredentialsTokenProvider:
    token_endpoint = cfg.get("token_endpoint") or cfg.get("token_uri") or ""
    client_id = cfg.get("client_id") or ""
    client_secret = cfg.get("client_secret") or ""
    scope = cfg.get("scope")
    audience = cfg.get("audience")
    return ClientCredentialsTokenProvider(
        token_endpoint=token_endpoint,
        client_id=client_id,
        client_secret=client_secret,
        scope=scope,
        audience=audience,
    )


def _resolve_sync_token_provider(
    bearer_token: str | None,
    token_provider: TokenProvider | None,
    client_credentials: Mapping[str, str] | None,
) -> TokenProvider:
    configured = sum(x is not None for x in (bearer_token, token_provider, client_credentials))
    if configured > 1:
        raise ValueError(
            "Configure at most one of bearer_token, token_provider, or client_credentials"
        )
    if token_provider is not None:
        return token_provider
    if client_credentials is not None:
        return _build_client_credentials_provider(client_credentials)
    return StaticTokenProvider(bearer_token)


def _resolve_async_token_provider(
    bearer_token: str | None,
    token_provider: AnyTokenProvider | None,
    client_credentials: Mapping[str, str] | None,
) -> AnyTokenProvider:
    configured = sum(x is not None for x in (bearer_token, token_provider, client_credentials))
    if configured > 1:
        raise ValueError(
            "Configure at most one of bearer_token, token_provider, or client_credentials"
        )
    if token_provider is not None:
        return token_provider
    if client_credentials is not None:
        return _build_client_credentials_provider(client_credentials)
    return StaticTokenProvider(bearer_token)


class OpenCrawlingClient:
    """Synchronous OpenCrawling Client covering Platform API v1 (``/api/v1``) and OIS ingestion.

    Use as a context manager (``with OpenCrawlingClient(...) as client:``) or call ``close()``
    when finished to release pooled HTTP connections.
    """

    def __init__(
        self,
        base_url: str = "http://localhost:8080",
        *,
        api_key: str | None = None,
        bearer_token: str | None = None,
        token_provider: TokenProvider | None = None,
        client_credentials: Mapping[str, str] | None = None,
        retry_policy: RetryPolicy | None = None,
        default_options: RequestOptions | None = None,
        timeout: float | httpx.Timeout = 30.0,
        http2: bool = False,
        verify: bool | str = True,
        enable_tracing: bool = False,
        http_client: httpx.Client | None = None,
        _transport: SyncTransport | None = None,
    ) -> None:
        if _transport is not None:
            self._transport = _transport
        else:
            provider = _resolve_sync_token_provider(bearer_token, token_provider, client_credentials)
            self._transport = SyncTransport(
                base_url,
                api_key=api_key,
                token_provider=provider,
                retry_policy=retry_policy,
                default_options=default_options,
                timeout=timeout,
                http2=http2,
                verify=verify,
                enable_tracing=enable_tracing,
                http_client=http_client,
            )

        self.jobs = JobsClient(self._transport)
        self.schedules = SchedulesClient(self._transport)
        self.job_runs = JobRunsClient(self._transport)
        self.runs = RunsClient(self._transport)
        self.executions = ExecutionsClient(self._transport)
        self.connectors = ConnectorsClient(self._transport)
        self.settings = SettingsClient(self._transport)
        self.operations = OperationsClient(self._transport)
        self.migrations = MigrationsClient(self._transport)
        self.vespa = VespaClient(self._transport)
        self.narrativization = NarrativizationClient(self._transport)
        self.dynamic_jobs = DynamicJobsClient(self._transport)
        self.discovery = DiscoveryClient(self._transport)
        self.sidecars = SidecarsClient(self._transport)
        self.ingest = OisIngestClient(self._transport)

    @property
    def base_url(self) -> str:
        return self._transport.base_url

    def with_defaults(self, options: RequestOptions) -> OpenCrawlingClient:
        """Return a derived client view sending ``options`` defaults (correlation/request IDs) on every call."""
        return OpenCrawlingClient(_transport=self._transport.with_defaults(options))

    def index(self) -> ApiIndex:
        """Return ``GET /api/v1`` links and enabled optional features."""
        response = self._transport.check_ok(self._transport.request("GET", api_path()))
        return ApiIndex.model_validate(self._transport.parse_json(response))

    def version(self) -> VersionInfo:
        """Return ``GET /api/v1/version`` API and runtime version metadata."""
        response = self._transport.check_ok(self._transport.request("GET", api_path("version")))
        return VersionInfo.model_validate(self._transport.parse_json(response))

    def health(self) -> Health:
        """Return ``GET /api/v1/health`` without retrying or raising on ``503 UNHEALTHY``."""
        response = self._transport.request("GET", api_path("health"), retry=False)
        if response.status_code == 503:
            return Health.model_validate(self._transport.parse_json(response))
        self._transport.check_ok(response)
        return Health.model_validate(self._transport.parse_json(response))

    def me(self) -> CallerInfo:
        """Return ``GET /api/v1/me`` describing the authenticated caller and privileges."""
        response = self._transport.check_ok(self._transport.request("GET", api_path("me")))
        return CallerInfo.model_validate(self._transport.parse_json(response))

    def close(self) -> None:
        self._transport.close()

    def __enter__(self) -> OpenCrawlingClient:
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"OpenCrawlingClient(base_url={self.base_url!r})"


class AsyncOpenCrawlingClient:
    """Asynchronous OpenCrawling Client (``asyncio`` + ``httpx.AsyncClient``) for Platform API v1 & OIS.

    Use as an async context manager (``async with AsyncOpenCrawlingClient(...) as client:``)
    or await ``aclose()`` when finished.
    """

    def __init__(
        self,
        base_url: str = "http://localhost:8080",
        *,
        api_key: str | None = None,
        bearer_token: str | None = None,
        token_provider: AnyTokenProvider | None = None,
        client_credentials: Mapping[str, str] | None = None,
        retry_policy: RetryPolicy | None = None,
        default_options: RequestOptions | None = None,
        timeout: float | httpx.Timeout = 30.0,
        http2: bool = False,
        verify: bool | str = True,
        enable_tracing: bool = False,
        http_client: httpx.AsyncClient | None = None,
        _transport: AsyncTransport | None = None,
    ) -> None:
        if _transport is not None:
            self._transport = _transport
        else:
            provider = _resolve_async_token_provider(bearer_token, token_provider, client_credentials)
            self._transport = AsyncTransport(
                base_url,
                api_key=api_key,
                token_provider=provider,
                retry_policy=retry_policy,
                default_options=default_options,
                timeout=timeout,
                http2=http2,
                verify=verify,
                enable_tracing=enable_tracing,
                http_client=http_client,
            )

        self.jobs = AsyncJobsClient(self._transport)
        self.schedules = AsyncSchedulesClient(self._transport)
        self.job_runs = AsyncJobRunsClient(self._transport)
        self.runs = AsyncRunsClient(self._transport)
        self.executions = AsyncExecutionsClient(self._transport)
        self.connectors = AsyncConnectorsClient(self._transport)
        self.settings = AsyncSettingsClient(self._transport)
        self.operations = AsyncOperationsClient(self._transport)
        self.migrations = AsyncMigrationsClient(self._transport)
        self.vespa = AsyncVespaClient(self._transport)
        self.narrativization = AsyncNarrativizationClient(self._transport)
        self.dynamic_jobs = AsyncDynamicJobsClient(self._transport)
        self.discovery = AsyncDiscoveryClient(self._transport)
        self.sidecars = AsyncSidecarsClient(self._transport)
        self.ingest = AsyncOisIngestClient(self._transport)

    @property
    def base_url(self) -> str:
        return self._transport.base_url

    def with_defaults(self, options: RequestOptions) -> AsyncOpenCrawlingClient:
        """Return a derived async client view sending ``options`` defaults on every call."""
        return AsyncOpenCrawlingClient(_transport=self._transport.with_defaults(options))

    async def index(self) -> ApiIndex:
        response = self._transport.check_ok(await self._transport.request("GET", api_path()))
        return ApiIndex.model_validate(self._transport.parse_json(response))

    async def version(self) -> VersionInfo:
        response = self._transport.check_ok(await self._transport.request("GET", api_path("version")))
        return VersionInfo.model_validate(self._transport.parse_json(response))

    async def health(self) -> Health:
        """Return ``GET /api/v1/health`` without retrying or raising on ``503 UNHEALTHY``."""
        response = await self._transport.request("GET", api_path("health"), retry=False)
        if response.status_code == 503:
            return Health.model_validate(self._transport.parse_json(response))
        self._transport.check_ok(response)
        return Health.model_validate(self._transport.parse_json(response))

    async def me(self) -> CallerInfo:
        response = self._transport.check_ok(await self._transport.request("GET", api_path("me")))
        return CallerInfo.model_validate(self._transport.parse_json(response))

    async def aclose(self) -> None:
        await self._transport.aclose()

    async def __aenter__(self) -> AsyncOpenCrawlingClient:
        return self

    async def __aexit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        await self.aclose()

    def __repr__(self) -> str:
        return f"AsyncOpenCrawlingClient(base_url={self.base_url!r})"
