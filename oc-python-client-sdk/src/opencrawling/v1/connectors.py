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

"""Platform API v1 Connectors resource clients (``/api/v1/connectors``)."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from opencrawling._transport import AsyncTransport, SyncTransport, api_action, api_path
from opencrawling.config import RequestOptions
from opencrawling.errors import ApiProblemError
from opencrawling.pagination import AsyncPageIterator, Page, PageRequest, SyncPageIterator
from opencrawling.v1.models import ConnectionCheckResponse, Connector, ConnectorInput
from opencrawling.versioned import Versioned


def _dump_connector_input(connector: ConnectorInput | Mapping[str, Any]) -> dict[str, Any]:
    if isinstance(connector, ConnectorInput):
        return connector.model_dump(mode="json", by_alias=True, exclude_none=True)
    return dict(connector)


def _extract_connector_name(connector: ConnectorInput | Mapping[str, Any]) -> str:
    name = connector.name if isinstance(connector, ConnectorInput) else connector.get("name")
    if not isinstance(name, str) or not name.strip():
        raise ValueError("connector.name is required for upsert")
    return name


class ConnectorsClient:
    """Synchronous client for ``/api/v1/connectors``."""

    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport

    def list(
        self,
        *,
        type: str | None = None,
        q: str | None = None,
        page: PageRequest | None = None,
    ) -> Page[Connector]:
        p = page or PageRequest.first()
        response = self._transport.check_ok(
            self._transport.request(
                "GET",
                api_path("connectors"),
                params={"type": type, "q": q, "limit": p.limit, "cursor": p.cursor},
            )
        )
        return Page[Connector].model_validate(self._transport.parse_json(response))

    def iter(
        self,
        *,
        type: str | None = None,
        q: str | None = None,
        page_size: int | None = None,
    ) -> SyncPageIterator[Connector]:
        return SyncPageIterator(lambda req: self.list(type=type, q=q, page=req), page_size=page_size)

    def get(self, name: str) -> Versioned[Connector]:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("connectors", name))
        )
        return Versioned(
            data=Connector.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def get_if_changed(self, name: str, etag: str | None) -> Versioned[Connector] | None:
        opts = RequestOptions(if_none_match=etag) if etag else None
        response = self._transport.request("GET", api_path("connectors", name), options=opts)
        if response.status_code == 304:
            return None
        self._transport.check_ok(response)
        return Versioned(
            data=Connector.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def create(
        self,
        connector: ConnectorInput | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[Connector]:
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_path("connectors"),
                json_body=_dump_connector_input(connector),
                options=options,
            )
        )
        return Versioned(
            data=Connector.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def replace(
        self,
        name: str,
        connector: ConnectorInput | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[Connector]:
        response = self._transport.check_ok(
            self._transport.request(
                "PUT",
                api_path("connectors", name),
                json_body=_dump_connector_input(connector),
                options=options,
            )
        )
        return Versioned(
            data=Connector.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def upsert(
        self,
        connector: ConnectorInput | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[Connector]:
        """Create a connector or replace it if a connector with the same name already exists."""
        name = _extract_connector_name(connector)
        try:
            return self.create(connector, options=options)
        except ApiProblemError as exc:
            if exc.status == 409 and exc.code == "connector-exists":
                return self.replace(name, connector, options=options)
            raise

    def delete(
        self,
        name: str,
        *,
        force: bool = False,
        options: RequestOptions | None = None,
    ) -> None:
        params = {"force": True} if force else None
        self._transport.check_ok(
            self._transport.request("DELETE", api_path("connectors", name), params=params, options=options)
        )

    def check(self, name: str) -> ConnectionCheckResponse:
        response = self._transport.check_ok(
            self._transport.request("POST", api_action(api_path("connectors", name), "check"))
        )
        return ConnectionCheckResponse.model_validate(self._transport.parse_json(response))

    def check_definition(
        self,
        connector: ConnectorInput | Mapping[str, Any],
    ) -> ConnectionCheckResponse:
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_action(api_path("connectors"), "check"),
                json_body=_dump_connector_input(connector),
            )
        )
        return ConnectionCheckResponse.model_validate(self._transport.parse_json(response))


class AsyncConnectorsClient:
    """Asynchronous client for ``/api/v1/connectors``."""

    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport

    async def list(
        self,
        *,
        type: str | None = None,
        q: str | None = None,
        page: PageRequest | None = None,
    ) -> Page[Connector]:
        p = page or PageRequest.first()
        response = self._transport.check_ok(
            await self._transport.request(
                "GET",
                api_path("connectors"),
                params={"type": type, "q": q, "limit": p.limit, "cursor": p.cursor},
            )
        )
        return Page[Connector].model_validate(self._transport.parse_json(response))

    def iter(
        self,
        *,
        type: str | None = None,
        q: str | None = None,
        page_size: int | None = None,
    ) -> AsyncPageIterator[Connector]:
        return AsyncPageIterator(lambda req: self.list(type=type, q=q, page=req), page_size=page_size)

    aiter = iter

    async def get(self, name: str) -> Versioned[Connector]:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("connectors", name))
        )
        return Versioned(
            data=Connector.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def get_if_changed(self, name: str, etag: str | None) -> Versioned[Connector] | None:
        opts = RequestOptions(if_none_match=etag) if etag else None
        response = await self._transport.request("GET", api_path("connectors", name), options=opts)
        if response.status_code == 304:
            return None
        self._transport.check_ok(response)
        return Versioned(
            data=Connector.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def create(
        self,
        connector: ConnectorInput | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[Connector]:
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_path("connectors"),
                json_body=_dump_connector_input(connector),
                options=options,
            )
        )
        return Versioned(
            data=Connector.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def replace(
        self,
        name: str,
        connector: ConnectorInput | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[Connector]:
        response = self._transport.check_ok(
            await self._transport.request(
                "PUT",
                api_path("connectors", name),
                json_body=_dump_connector_input(connector),
                options=options,
            )
        )
        return Versioned(
            data=Connector.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def upsert(
        self,
        connector: ConnectorInput | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[Connector]:
        """Create a connector or replace it if a connector with the same name already exists."""
        name = _extract_connector_name(connector)
        try:
            return await self.create(connector, options=options)
        except ApiProblemError as exc:
            if exc.status == 409 and exc.code == "connector-exists":
                return await self.replace(name, connector, options=options)
            raise

    async def delete(
        self,
        name: str,
        *,
        force: bool = False,
        options: RequestOptions | None = None,
    ) -> None:
        params = {"force": True} if force else None
        self._transport.check_ok(
            await self._transport.request("DELETE", api_path("connectors", name), params=params, options=options)
        )

    async def check(self, name: str) -> ConnectionCheckResponse:
        response = self._transport.check_ok(
            await self._transport.request("POST", api_action(api_path("connectors", name), "check"))
        )
        return ConnectionCheckResponse.model_validate(self._transport.parse_json(response))

    async def check_definition(
        self,
        connector: ConnectorInput | Mapping[str, Any],
    ) -> ConnectionCheckResponse:
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_action(api_path("connectors"), "check"),
                json_body=_dump_connector_input(connector),
            )
        )
        return ConnectionCheckResponse.model_validate(self._transport.parse_json(response))
