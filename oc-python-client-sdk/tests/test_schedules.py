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

"""Unit tests for sync and async native job scheduler Platform API v1 clients."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import Any

import httpx

from opencrawling import (
    AsyncOpenCrawlingClient,
    DispatchOutcome,
    JobScheduleConfig,
    MisfirePolicy,
    OpenCrawlingClient,
    OverlapPolicy,
    PageRequest,
    RequestOptions,
    ScheduleFilter,
)


def _schedule_payload(job_id: str = "job-1", *, enabled: bool = True) -> dict[str, Any]:
    return {
        "jobId": job_id,
        "jobName": f"Pipeline {job_id}",
        "schedule": {
            "cron": "0 2 * * *",
            "timezone": "Europe/Rome",
            "enabled": enabled,
            "overlapPolicy": "SKIP",
            "misfirePolicy": "FIRE_ONCE",
            "jitterSeconds": 15,
            "maxConsecutiveFailures": 3,
        },
        "state": {
            "enabled": enabled,
            "nextFireAt": "2026-11-20T01:00:00Z",
            "lastFiredAt": "2026-11-19T01:00:00Z",
            "lastExecutionId": "exec-77",
            "lastOutcome": "STARTED",
            "coalescePending": False,
            "consecutiveFailures": 0,
            "updatedAt": "2026-11-19T01:00:02Z",
        },
        "nextFireTimes": ["2026-11-20T01:00:00Z", "2026-11-21T01:00:00Z"],
    }


def _dispatch_payload(dispatch_id: str, outcome: str = "STARTED") -> dict[str, Any]:
    return {
        "id": dispatch_id,
        "jobId": "job-1",
        "scheduledFor": "2026-11-19T01:00:00Z",
        "dispatchedAt": "2026-11-19T01:00:01Z",
        "runtimeId": "runtime-a",
        "executionId": "exec-77" if outcome == "STARTED" else None,
        "outcome": outcome,
        "detail": "ok",
    }


def test_sync_schedules_crud_actions_overview_validate_and_pagination() -> None:
    recorded: list[tuple[str, str, dict[str, str], dict[str, Any] | None]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        raw_path = request.url.path
        params = dict(request.url.params)
        body = json.loads(request.content.decode("utf-8")) if request.content else None
        recorded.append((request.method, raw_path, params, body))

        if request.method == "POST" and raw_path == "/api/v1/schedules:validate-cron":
            return httpx.Response(
                200,
                json={
                    "valid": True,
                    "cron": "0 2 * * *",
                    "normalizedCron": "0 2 * * *",
                    "timezone": "Europe/Rome",
                    "humanDescription": "Every day at 02:00",
                    "nextFireTimes": ["2026-11-20T01:00:00Z"],
                },
            )
        if request.method == "GET" and raw_path == "/api/v1/schedules/overview":
            return httpx.Response(
                200,
                json={
                    "schedulerEnabled": True,
                    "pollInterval": "PT5S",
                    "defaultTimezone": "UTC",
                    "totalScheduledJobs": 2,
                    "activeSchedules": 1,
                    "pausedSchedules": 1,
                    "autoPausedSchedules": 0,
                    "skippedLast24h": 4,
                    "upcoming": [
                        {
                            "jobId": "job-1",
                            "jobName": "Pipeline job-1",
                            "cron": "0 2 * * *",
                            "timezone": "Europe/Rome",
                            "scheduledAt": "2026-11-20T01:00:00Z",
                        }
                    ],
                },
            )
        if request.method == "GET" and raw_path == "/api/v1/schedules":
            cursor = params.get("cursor")
            if cursor is None:
                return httpx.Response(
                    200,
                    json={
                        "items": [_schedule_payload("job-1", enabled=True)],
                        "page": {"limit": 1, "nextCursor": "c2", "totalItems": 2},
                    },
                )
            return httpx.Response(
                200,
                json={
                    "items": [_schedule_payload("job-2", enabled=False)],
                    "page": {"limit": 1, "nextCursor": None, "totalItems": 2},
                },
            )
        if request.method == "GET" and raw_path == "/api/v1/jobs/job-1/schedule":
            if request.headers.get("if-none-match") == '"s1"':
                return httpx.Response(304)
            return httpx.Response(200, headers={"ETag": '"s1"'}, json=_schedule_payload("job-1", enabled=True))
        if request.method == "PUT" and raw_path == "/api/v1/jobs/job-1/schedule":
            assert request.headers.get("if-match") == '"s1"'
            return httpx.Response(
                200,
                headers={"ETag": '"s2"', "Location": "/api/v1/jobs/job-1/schedule"},
                json=_schedule_payload("job-1", enabled=True),
            )
        if request.method == "POST" and raw_path == "/api/v1/jobs/job-1/schedule:pause":
            return httpx.Response(200, headers={"ETag": '"s3"'}, json=_schedule_payload("job-1", enabled=False))
        if request.method == "POST" and raw_path == "/api/v1/jobs/job-1/schedule:resume":
            return httpx.Response(200, headers={"ETag": '"s4"'}, json=_schedule_payload("job-1", enabled=True))
        if request.method == "GET" and raw_path == "/api/v1/jobs/job-1/schedule/dispatches":
            cursor = params.get("cursor")
            if cursor is None:
                return httpx.Response(
                    200,
                    json={
                        "items": [_dispatch_payload("d-1", "STARTED")],
                        "page": {"limit": 1, "nextCursor": "dc-2", "totalItems": 2},
                    },
                )
            return httpx.Response(
                200,
                json={
                    "items": [_dispatch_payload("d-2", "SKIPPED_OVERLAP")],
                    "page": {"limit": 1, "nextCursor": None, "totalItems": 2},
                },
            )
        if request.method == "DELETE" and raw_path == "/api/v1/jobs/job-1/schedule":
            assert request.headers.get("if-match") == '"s4"'
            return httpx.Response(204)
        return httpx.Response(404)

    client = OpenCrawlingClient(
        "http://localhost:8080",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
    )

    val = client.schedules.validate_cron(
        "0 2 * * *",
        timezone="Europe/Rome",
        count=3,
        start_at=datetime(2026, 11, 20, 0, 0, tzinfo=UTC),
        end_at=datetime(2026, 12, 20, 0, 0, tzinfo=UTC),
    )
    assert val.valid is True
    assert val.human_description == "Every day at 02:00"
    assert len(val.next_fire_times) == 1

    overview = client.schedules.overview(horizon="48h", limit=10)
    assert overview.skipped_last_24h == 4
    assert overview.active_schedules == 1
    assert overview.upcoming[0].job_id == "job-1"

    items = list(
        client.schedules.iter(
            ScheduleFilter(enabled=True, outcome=DispatchOutcome.STARTED, q="Pipeline", sort="nextFireAt"),
            page_size=1,
        )
    )
    assert [s.job_id for s in items] == ["job-1", "job-2"]

    fetched = client.schedules.get("job-1")
    assert fetched.etag == '"s1"'
    assert client.schedules.get_if_changed("job-1", '"s1"') is None
    assert client.schedules.get_if_changed("job-1", '"stale"') is not None

    updated = client.schedules.set(
        "job-1",
        JobScheduleConfig(
            cron="0 2 * * *",
            timezone="Europe/Rome",
            enabled=True,
            overlap_policy=OverlapPolicy.SKIP,
            misfire_policy=MisfirePolicy.FIRE_ONCE,
            jitter_seconds=15,
            max_consecutive_failures=3,
        ),
        RequestOptions(if_match='"s1"'),
    )
    assert updated.etag == '"s2"'
    assert updated.location == "/api/v1/jobs/job-1/schedule"

    paused = client.schedules.pause("job-1")
    assert paused.data.schedule.enabled is False
    assert paused.etag == '"s3"'

    resumed = client.schedules.resume("job-1")
    assert resumed.data.schedule.enabled is True
    assert resumed.etag == '"s4"'

    page1 = client.schedules.dispatches("job-1", outcome=DispatchOutcome.STARTED, page=PageRequest(limit=1))
    assert len(page1.items) == 1
    assert page1.items[0].outcome == DispatchOutcome.STARTED

    all_dispatches = list(client.schedules.iter_dispatches("job-1", page_size=1))
    assert [d.id for d in all_dispatches] == ["d-1", "d-2"]

    client.schedules.delete("job-1", RequestOptions(if_match='"s4"'))

    validate_call = recorded[0][3]
    assert validate_call is not None
    assert "startAt" in validate_call and "endAt" in validate_call
    put_call = next(body for method, path, _, body in recorded if method == "PUT" and path == "/api/v1/jobs/job-1/schedule")
    assert put_call is not None
    assert put_call["overlapPolicy"] == "SKIP"
    assert put_call["misfirePolicy"] == "FIRE_ONCE"
    assert put_call["maxConsecutiveFailures"] == 3


async def test_async_schedules_client_full_lifecycle() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        raw_path = request.url.path
        params = dict(request.url.params)
        if request.method == "POST" and raw_path == "/api/v1/schedules:validate-cron":
            return httpx.Response(200, json={"valid": False, "cron": "bad", "error": "Invalid cron expression"})
        if request.method == "GET" and raw_path == "/api/v1/schedules/overview":
            return httpx.Response(200, json={"schedulerEnabled": True, "skippedLast24h": 7, "upcoming": []})
        if request.method == "GET" and raw_path == "/api/v1/schedules":
            cursor = params.get("cursor")
            if cursor is None:
                return httpx.Response(
                    200,
                    json={"items": [_schedule_payload("job-a")], "page": {"limit": 1, "nextCursor": "next"}},
                )
            return httpx.Response(
                200,
                json={"items": [_schedule_payload("job-b")], "page": {"limit": 1, "nextCursor": None}},
            )
        if request.method == "GET" and raw_path == "/api/v1/jobs/job-a/schedule":
            if request.headers.get("if-none-match") == '"a1"':
                return httpx.Response(304)
            return httpx.Response(200, headers={"ETag": '"a1"'}, json=_schedule_payload("job-a"))
        if request.method == "PUT" and raw_path == "/api/v1/jobs/job-a/schedule":
            return httpx.Response(200, headers={"ETag": '"a2"'}, json=_schedule_payload("job-a"))
        if request.method == "POST" and raw_path == "/api/v1/jobs/job-a/schedule:pause":
            return httpx.Response(200, headers={"ETag": '"a3"'}, json=_schedule_payload("job-a", enabled=False))
        if request.method == "POST" and raw_path == "/api/v1/jobs/job-a/schedule:resume":
            return httpx.Response(200, headers={"ETag": '"a4"'}, json=_schedule_payload("job-a", enabled=True))
        if request.method == "GET" and raw_path == "/api/v1/jobs/job-a/schedule/dispatches":
            cursor = params.get("cursor")
            if cursor is None:
                return httpx.Response(
                    200,
                    json={"items": [_dispatch_payload("ad-1")], "page": {"limit": 1, "nextCursor": "ad2"}},
                )
            return httpx.Response(
                200,
                json={"items": [_dispatch_payload("ad-2", "AUTO_PAUSED")], "page": {"limit": 1, "nextCursor": None}},
            )
        if request.method == "DELETE" and raw_path == "/api/v1/jobs/job-a/schedule":
            return httpx.Response(204)
        return httpx.Response(404)

    async with AsyncOpenCrawlingClient(
        "http://localhost:8080",
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    ) as client:
        invalid = await client.schedules.validate_cron("bad")
        assert invalid.valid is False
        assert invalid.error == "Invalid cron expression"

        overview = await client.schedules.overview()
        assert overview.skipped_last_24h == 7

        listed = [item.job_id async for item in client.schedules.iter(page_size=1)]
        assert listed == ["job-a", "job-b"]

        fetched = await client.schedules.get("job-a")
        assert fetched.etag == '"a1"'
        assert await client.schedules.get_if_changed("job-a", '"a1"') is None

        saved = await client.schedules.set("job-a", {"cron": "0 2 * * *", "timezone": "UTC", "enabled": True})
        assert saved.etag == '"a2"'

        paused = await client.schedules.pause("job-a")
        assert paused.data.schedule.enabled is False

        resumed = await client.schedules.resume("job-a")
        assert resumed.data.schedule.enabled is True

        dispatches = [d async for d in client.schedules.iter_dispatches("job-a", page_size=1)]
        assert [d.outcome for d in dispatches] == [DispatchOutcome.STARTED, DispatchOutcome.AUTO_PAUSED]

        await client.schedules.delete("job-a")
