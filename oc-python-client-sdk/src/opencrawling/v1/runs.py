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

"""Platform API v1 Runs resource clients (``/api/v1/runs``): job runs and dynamic jobs together."""

from __future__ import annotations

from opencrawling._transport import AsyncTransport, SyncTransport, api_action, api_path
from opencrawling.pagination import AsyncPageIterator, Page, PageRequest, SyncPageIterator
from opencrawling.v1.models import Run, RunHistory


def _list_params(kind: str | None, state: str | None, job_id: str | None, page: PageRequest) -> dict[str, object]:
    return {"kind": kind, "state": state, "jobId": job_id, "limit": page.limit, "cursor": page.cursor}


class RunsClient:
    """Synchronous client for ``/api/v1/runs``.

    Lists and follows every run the caller may see: job runs (``kind="JOB"``) and dynamic jobs
    (``"DYNAMIC_FOLDER"``, ``"DYNAMIC_DOCUMENT"``), newest first.
    """

    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport

    def list(
        self,
        kind: str | None = None,
        state: str | None = None,
        job_id: str | None = None,
        page: PageRequest | None = None,
    ) -> Page[Run]:
        p = page or PageRequest.first()
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("runs"), params=_list_params(kind, state, job_id, p))
        )
        return Page[Run].model_validate(self._transport.parse_json(response))

    def iter(
        self,
        kind: str | None = None,
        state: str | None = None,
        job_id: str | None = None,
        *,
        page_size: int | None = None,
    ) -> SyncPageIterator[Run]:
        return SyncPageIterator(
            lambda req: self.list(kind=kind, state=state, job_id=job_id, page=req), page_size=page_size
        )

    def get(self, run_id: str) -> Run:
        response = self._transport.check_ok(self._transport.request("GET", api_path("runs", run_id)))
        return Run.model_validate(self._transport.parse_json(response))

    def history(self, run_id: str) -> RunHistory:
        response = self._transport.check_ok(self._transport.request("GET", api_path("runs", run_id, "history")))
        return RunHistory.model_validate(self._transport.parse_json(response))

    def stop(self, run_id: str) -> Run:
        return self._action(run_id, "stop")

    def pause(self, run_id: str) -> Run:
        return self._action(run_id, "pause")

    def resume(self, run_id: str) -> Run:
        return self._action(run_id, "resume")

    def _action(self, run_id: str, action: str) -> Run:
        response = self._transport.check_ok(
            self._transport.request("POST", api_action(api_path("runs", run_id), action))
        )
        return Run.model_validate(self._transport.parse_json(response))


class AsyncRunsClient:
    """Asynchronous client for ``/api/v1/runs``."""

    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport

    async def list(
        self,
        kind: str | None = None,
        state: str | None = None,
        job_id: str | None = None,
        page: PageRequest | None = None,
    ) -> Page[Run]:
        p = page or PageRequest.first()
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("runs"), params=_list_params(kind, state, job_id, p))
        )
        return Page[Run].model_validate(self._transport.parse_json(response))

    def iter(
        self,
        kind: str | None = None,
        state: str | None = None,
        job_id: str | None = None,
        *,
        page_size: int | None = None,
    ) -> AsyncPageIterator[Run]:
        return AsyncPageIterator(
            lambda req: self.list(kind=kind, state=state, job_id=job_id, page=req), page_size=page_size
        )

    aiter = iter

    async def get(self, run_id: str) -> Run:
        response = self._transport.check_ok(await self._transport.request("GET", api_path("runs", run_id)))
        return Run.model_validate(self._transport.parse_json(response))

    async def history(self, run_id: str) -> RunHistory:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("runs", run_id, "history"))
        )
        return RunHistory.model_validate(self._transport.parse_json(response))

    async def stop(self, run_id: str) -> Run:
        return await self._action(run_id, "stop")

    async def pause(self, run_id: str) -> Run:
        return await self._action(run_id, "pause")

    async def resume(self, run_id: str) -> Run:
        return await self._action(run_id, "resume")

    async def _action(self, run_id: str, action: str) -> Run:
        response = self._transport.check_ok(
            await self._transport.request("POST", api_action(api_path("runs", run_id), action))
        )
        return Run.model_validate(self._transport.parse_json(response))
