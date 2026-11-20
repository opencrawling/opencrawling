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

"""Platform API v1 Settings resource clients (``/api/v1/settings``)."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from opencrawling._transport import AsyncTransport, SyncTransport, api_action, api_path
from opencrawling.config import RequestOptions
from opencrawling.v1.models import LosslessModel, SystemSettings, TransportSettings
from opencrawling.versioned import Versioned


def _serialize_lossless(settings: LosslessModel | Mapping[str, Any]) -> dict[str, Any]:
    if isinstance(settings, LosslessModel):
        return settings.to_payload()
    return dict(settings)


def _grpc_target_body(host: str | None, port: int | None) -> dict[str, Any]:
    body: dict[str, Any] = {}
    if host is not None:
        body["host"] = host
    if port is not None:
        body["port"] = port
    return body


class SettingsClient:
    """Synchronous client for ``/api/v1/settings``."""

    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport

    def system(self) -> Versioned[SystemSettings]:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("settings", "system"))
        )
        return Versioned(
            data=SystemSettings.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def replace_system(
        self,
        settings: SystemSettings | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[SystemSettings]:
        response = self._transport.check_ok(
            self._transport.request(
                "PUT",
                api_path("settings", "system"),
                json_body=_serialize_lossless(settings),
                options=options,
            )
        )
        return Versioned(
            data=SystemSettings.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def transport(self) -> Versioned[TransportSettings]:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("settings", "transport"))
        )
        return Versioned(
            data=TransportSettings.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def replace_transport(
        self,
        settings: TransportSettings | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[TransportSettings]:
        response = self._transport.check_ok(
            self._transport.request(
                "PUT",
                api_path("settings", "transport"),
                json_body=_serialize_lossless(settings),
                options=options,
            )
        )
        return Versioned(
            data=TransportSettings.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def test_grpc(self, host: str | None = None, port: int | None = None) -> dict[str, Any]:
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_action(api_path("settings", "transport"), "test-grpc"),
                json_body=_grpc_target_body(host, port),
            )
        )
        parsed = self._transport.parse_json(response)
        return parsed if isinstance(parsed, dict) else {"result": parsed}


class AsyncSettingsClient:
    """Asynchronous client for ``/api/v1/settings``."""

    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport

    async def system(self) -> Versioned[SystemSettings]:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("settings", "system"))
        )
        return Versioned(
            data=SystemSettings.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def replace_system(
        self,
        settings: SystemSettings | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[SystemSettings]:
        response = self._transport.check_ok(
            await self._transport.request(
                "PUT",
                api_path("settings", "system"),
                json_body=_serialize_lossless(settings),
                options=options,
            )
        )
        return Versioned(
            data=SystemSettings.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def transport(self) -> Versioned[TransportSettings]:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("settings", "transport"))
        )
        return Versioned(
            data=TransportSettings.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def replace_transport(
        self,
        settings: TransportSettings | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[TransportSettings]:
        response = self._transport.check_ok(
            await self._transport.request(
                "PUT",
                api_path("settings", "transport"),
                json_body=_serialize_lossless(settings),
                options=options,
            )
        )
        return Versioned(
            data=TransportSettings.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def test_grpc(self, host: str | None = None, port: int | None = None) -> dict[str, Any]:
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_action(api_path("settings", "transport"), "test-grpc"),
                json_body=_grpc_target_body(host, port),
            )
        )
        parsed = self._transport.parse_json(response)
        return parsed if isinstance(parsed, dict) else {"result": parsed}
