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

"""Platform API v1 Executions & Lakehouse Audit Reporting clients (``/api/v1/executions``)."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from opencrawling._transport import AsyncTransport, SyncTransport, api_action, api_path
from opencrawling.pagination import AsyncPageIterator, Page, PageRequest, SyncPageIterator
from opencrawling.v1.models import (
    DocumentLedgerEntry,
    ExecutionFilter,
    JobReportSummary,
    QueryResult,
)


def _execution_list_params(filter: ExecutionFilter | None, page: PageRequest | None) -> dict[str, Any]:
    f = filter or ExecutionFilter()
    p = page or PageRequest.first()
    return {
        "jobId": f.job_id,
        "status": f.status,
        "pipelineMode": f.pipeline_mode,
        "sort": f.sort,
        "limit": p.limit,
        "cursor": p.cursor,
    }


class ExecutionsClient:
    """Synchronous client for ``/api/v1/executions`` and ``/api/v1/jobs/{jobId}/executions``."""

    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport

    def list(
        self,
        filter: ExecutionFilter | None = None,
        page: PageRequest | None = None,
    ) -> Page[JobReportSummary]:
        response = self._transport.check_ok(
            self._transport.request(
                "GET",
                api_path("executions"),
                params=_execution_list_params(filter, page),
            )
        )
        return Page[JobReportSummary].model_validate(self._transport.parse_json(response))

    def iter(
        self,
        filter: ExecutionFilter | None = None,
        *,
        page_size: int | None = None,
    ) -> SyncPageIterator[JobReportSummary]:
        return SyncPageIterator(lambda req: self.list(filter=filter, page=req), page_size=page_size)

    def list_for_job(
        self,
        job_id: str,
        *,
        status: str | None = None,
        sort: str | None = None,
        page: PageRequest | None = None,
    ) -> Page[JobReportSummary]:
        p = page or PageRequest.first()
        response = self._transport.check_ok(
            self._transport.request(
                "GET",
                api_path("jobs", job_id, "executions"),
                params={"status": status, "sort": sort, "limit": p.limit, "cursor": p.cursor},
            )
        )
        return Page[JobReportSummary].model_validate(self._transport.parse_json(response))

    def get(self, execution_id: str) -> JobReportSummary:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("executions", execution_id))
        )
        return JobReportSummary.model_validate(self._transport.parse_json(response))

    def documents(
        self,
        execution_id: str,
        *,
        status: str | None = None,
        q: str | None = None,
        page: PageRequest | None = None,
    ) -> Page[DocumentLedgerEntry]:
        p = page or PageRequest.first()
        response = self._transport.check_ok(
            self._transport.request(
                "GET",
                api_path("executions", execution_id, "documents"),
                params={"status": status, "q": q, "limit": p.limit, "cursor": p.cursor},
            )
        )
        return Page[DocumentLedgerEntry].model_validate(self._transport.parse_json(response))

    def iter_documents(
        self,
        execution_id: str,
        *,
        status: str | None = None,
        q: str | None = None,
        page_size: int = 50,
    ) -> SyncPageIterator[DocumentLedgerEntry]:
        return SyncPageIterator(
            lambda req: self.documents(execution_id, status=status, q=q, page=req),
            page_size=page_size,
        )

    def export(self, execution_id: str, format: str = "json") -> bytes:
        """Download an execution audit report (``json``, ``csv``, ``parquet``, or ``avro``) into memory."""
        response = self._transport.check_ok(
            self._transport.request(
                "GET",
                api_path("executions", execution_id, "export"),
                params={"format": format},
                accept="*/*",
            )
        )
        return response.content

    def export_to(self, execution_id: str, format: str, path: str | Path) -> Path:
        """Stream an execution audit report directly to ``path`` with constant memory usage."""
        target = Path(path)
        return self._transport.stream_to_file(
            api_path("executions", execution_id, "export"),
            target,
            params={"format": format},
        )

    def query(self, sql: str) -> QueryResult:
        """Execute a read-only DuckDB analytical SQL query over the audit lakehouse."""
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_action(api_path("executions"), "query"),
                json_body={"sql": sql},
            )
        )
        return QueryResult.model_validate(self._transport.parse_json(response))


class AsyncExecutionsClient:
    """Asynchronous client for ``/api/v1/executions`` and ``/api/v1/jobs/{jobId}/executions``."""

    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport

    async def list(
        self,
        filter: ExecutionFilter | None = None,
        page: PageRequest | None = None,
    ) -> Page[JobReportSummary]:
        response = self._transport.check_ok(
            await self._transport.request(
                "GET",
                api_path("executions"),
                params=_execution_list_params(filter, page),
            )
        )
        return Page[JobReportSummary].model_validate(self._transport.parse_json(response))

    def iter(
        self,
        filter: ExecutionFilter | None = None,
        *,
        page_size: int | None = None,
    ) -> AsyncPageIterator[JobReportSummary]:
        return AsyncPageIterator(lambda req: self.list(filter=filter, page=req), page_size=page_size)

    aiter = iter

    async def list_for_job(
        self,
        job_id: str,
        *,
        status: str | None = None,
        sort: str | None = None,
        page: PageRequest | None = None,
    ) -> Page[JobReportSummary]:
        p = page or PageRequest.first()
        response = self._transport.check_ok(
            await self._transport.request(
                "GET",
                api_path("jobs", job_id, "executions"),
                params={"status": status, "sort": sort, "limit": p.limit, "cursor": p.cursor},
            )
        )
        return Page[JobReportSummary].model_validate(self._transport.parse_json(response))

    async def get(self, execution_id: str) -> JobReportSummary:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("executions", execution_id))
        )
        return JobReportSummary.model_validate(self._transport.parse_json(response))

    async def documents(
        self,
        execution_id: str,
        *,
        status: str | None = None,
        q: str | None = None,
        page: PageRequest | None = None,
    ) -> Page[DocumentLedgerEntry]:
        p = page or PageRequest.first()
        response = self._transport.check_ok(
            await self._transport.request(
                "GET",
                api_path("executions", execution_id, "documents"),
                params={"status": status, "q": q, "limit": p.limit, "cursor": p.cursor},
            )
        )
        return Page[DocumentLedgerEntry].model_validate(self._transport.parse_json(response))

    def iter_documents(
        self,
        execution_id: str,
        *,
        status: str | None = None,
        q: str | None = None,
        page_size: int = 50,
    ) -> AsyncPageIterator[DocumentLedgerEntry]:
        return AsyncPageIterator(
            lambda req: self.documents(execution_id, status=status, q=q, page=req),
            page_size=page_size,
        )

    aiter_documents = iter_documents

    async def export(self, execution_id: str, format: str = "json") -> bytes:
        response = self._transport.check_ok(
            await self._transport.request(
                "GET",
                api_path("executions", execution_id, "export"),
                params={"format": format},
                accept="*/*",
            )
        )
        return response.content

    async def export_to(self, execution_id: str, format: str, path: str | Path) -> Path:
        """Stream an execution audit report asynchronously to ``path``."""
        target = Path(path)
        return await self._transport.astream_to_file(
            api_path("executions", execution_id, "export"),
            target,
            params={"format": format},
        )

    async def query(self, sql: str) -> QueryResult:
        """Execute a read-only DuckDB analytical SQL query over the audit lakehouse."""
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_action(api_path("executions"), "query"),
                json_body={"sql": sql},
            )
        )
        return QueryResult.model_validate(self._transport.parse_json(response))
