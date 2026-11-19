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

"""Platform API v1 Vespa Integration clients (``/api/v1/integrations/vespa*``)."""

from __future__ import annotations

import io
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from opencrawling._transport import AsyncTransport, SyncTransport, api_action, api_path


def _deploy_custom_data(config_endpoint: str | None) -> dict[str, str]:
    if config_endpoint is None:
        return {}
    return {"configEndpoint": config_endpoint}


class VespaClient:
    """Synchronous client for ``/api/v1/integrations/vespa``."""

    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport

    def health(self, endpoint: str | None = None) -> dict[str, Any]:
        response = self._transport.check_ok(
            self._transport.request(
                "GET",
                api_path("integrations", "vespa", "health"),
                params={"endpoint": endpoint},
            )
        )
        parsed = self._transport.parse_json(response)
        return parsed if isinstance(parsed, dict) else {"result": parsed}

    def document_counts(self, endpoint: str | None = None) -> list[dict[str, Any]]:
        response = self._transport.check_ok(
            self._transport.request(
                "GET",
                api_path("integrations", "vespa", "document-counts"),
                params={"endpoint": endpoint},
            )
        )
        parsed = self._transport.parse_json(response)
        return [dict(item) for item in parsed] if isinstance(parsed, list) else []

    def query(self, request: Mapping[str, Any]) -> dict[str, Any]:
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_action(api_path("integrations", "vespa"), "query"),
                json_body=dict(request),
            )
        )
        parsed = self._transport.parse_json(response)
        return parsed if isinstance(parsed, dict) else {"result": parsed}

    def deploy_bundled_schema(self, config_endpoint: str | None = None) -> dict[str, Any]:
        body: dict[str, Any] = {}
        if config_endpoint is not None:
            body["configEndpoint"] = config_endpoint
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_action(api_path("integrations", "vespa"), "deploy-bundled"),
                json_body=body,
            )
        )
        parsed = self._transport.parse_json(response)
        return parsed if isinstance(parsed, dict) else {"result": parsed}

    def deploy_custom_schema(
        self,
        application_package_zip: Path | str | bytes,
        config_endpoint: str | None = None,
        *,
        filename: str = "application-package.zip",
    ) -> dict[str, Any]:
        """Upload a custom zipped Vespa application package via multipart form-data (no ``Idempotency-Key``)."""
        path = api_action(api_path("integrations", "vespa"), "deploy-custom")
        data = _deploy_custom_data(config_endpoint)
        if isinstance(application_package_zip, (bytes, bytearray)):
            stream = io.BytesIO(bytes(application_package_zip))
            response = self._transport.check_ok(
                self._transport.request(
                    "POST",
                    path,
                    data=data,
                    files={"file": (filename, stream, "application/zip")},
                    retry=False,
                    auto_idempotency=False,
                )
            )
        else:
            zip_path = Path(application_package_zip)
            with zip_path.open("rb") as fh:
                response = self._transport.check_ok(
                    self._transport.request(
                        "POST",
                        path,
                        data=data,
                        files={"file": (zip_path.name, fh, "application/zip")},
                        retry=False,
                        auto_idempotency=False,
                    )
                )
        parsed = self._transport.parse_json(response)
        return parsed if isinstance(parsed, dict) else {"result": parsed}


class AsyncVespaClient:
    """Asynchronous client for ``/api/v1/integrations/vespa``."""

    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport

    async def health(self, endpoint: str | None = None) -> dict[str, Any]:
        response = self._transport.check_ok(
            await self._transport.request(
                "GET",
                api_path("integrations", "vespa", "health"),
                params={"endpoint": endpoint},
            )
        )
        parsed = self._transport.parse_json(response)
        return parsed if isinstance(parsed, dict) else {"result": parsed}

    async def document_counts(self, endpoint: str | None = None) -> list[dict[str, Any]]:
        response = self._transport.check_ok(
            await self._transport.request(
                "GET",
                api_path("integrations", "vespa", "document-counts"),
                params={"endpoint": endpoint},
            )
        )
        parsed = self._transport.parse_json(response)
        return [dict(item) for item in parsed] if isinstance(parsed, list) else []

    async def query(self, request: Mapping[str, Any]) -> dict[str, Any]:
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_action(api_path("integrations", "vespa"), "query"),
                json_body=dict(request),
            )
        )
        parsed = self._transport.parse_json(response)
        return parsed if isinstance(parsed, dict) else {"result": parsed}

    async def deploy_bundled_schema(self, config_endpoint: str | None = None) -> dict[str, Any]:
        body: dict[str, Any] = {}
        if config_endpoint is not None:
            body["configEndpoint"] = config_endpoint
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_action(api_path("integrations", "vespa"), "deploy-bundled"),
                json_body=body,
            )
        )
        parsed = self._transport.parse_json(response)
        return parsed if isinstance(parsed, dict) else {"result": parsed}

    async def deploy_custom_schema(
        self,
        application_package_zip: Path | str | bytes,
        config_endpoint: str | None = None,
        *,
        filename: str = "application-package.zip",
    ) -> dict[str, Any]:
        """Upload a custom zipped Vespa application package asynchronously (no ``Idempotency-Key``)."""
        path = api_action(api_path("integrations", "vespa"), "deploy-custom")
        data = _deploy_custom_data(config_endpoint)
        if isinstance(application_package_zip, (bytes, bytearray)):
            stream = io.BytesIO(bytes(application_package_zip))
            response = self._transport.check_ok(
                await self._transport.request(
                    "POST",
                    path,
                    data=data,
                    files={"file": (filename, stream, "application/zip")},
                    retry=False,
                    auto_idempotency=False,
                )
            )
        else:
            zip_path = Path(application_package_zip)
            with zip_path.open("rb") as fh:
                response = self._transport.check_ok(
                    await self._transport.request(
                        "POST",
                        path,
                        data=data,
                        files={"file": (zip_path.name, fh, "application/zip")},
                        retry=False,
                        auto_idempotency=False,
                    )
                )
        parsed = self._transport.parse_json(response)
        return parsed if isinstance(parsed, dict) else {"result": parsed}
