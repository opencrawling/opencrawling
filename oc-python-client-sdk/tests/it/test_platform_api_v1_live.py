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

"""Live end-to-end integration tests against a running ``oc-runtime`` instance.

Skipped automatically unless ``OPENCRAWLING_BASE_URL`` is configured in the environment.
Optional authentication env vars: ``OPENCRAWLING_BEARER_TOKEN``, ``OPENCRAWLING_API_KEY``,
and ``OPENCRAWLING_OIS_TOKEN``.
"""

from __future__ import annotations

import os
import uuid

import pytest

from opencrawling import (
    ApiProblemError,
    AsyncOpenCrawlingClient,
    ConnectorInput,
    JobInput,
    OpenCrawlingClient,
    RequestOptions,
)

BASE_URL = os.environ.get("OPENCRAWLING_BASE_URL")

pytestmark = [
    pytest.mark.live,
    pytest.mark.skipif(not BASE_URL, reason="Set OPENCRAWLING_BASE_URL to run live integration tests"),
]


def _client_kwargs() -> dict[str, str | None]:
    return {
        "api_key": os.environ.get("OPENCRAWLING_API_KEY"),
        "bearer_token": os.environ.get("OPENCRAWLING_BEARER_TOKEN"),
    }


def test_live_platform_api_v1_crud_and_etag_lifecycle() -> None:
    assert BASE_URL is not None
    tag = uuid.uuid4().hex[:8]
    with OpenCrawlingClient(BASE_URL, **_client_kwargs()) as client:
        index = client.index()
        assert index.api_version.startswith("1.")
        version = client.version()
        assert version.api_version == index.api_version
        health = client.health()
        assert health.status in {"HEALTHY", "DEGRADED", "UNHEALTHY", "UNKNOWN"}
        me = client.me()
        assert isinstance(me.admin, bool)

        # Connector create -> get -> conditional replace -> delete
        conn_name = f"PySDK_{tag}"
        created_conn = client.connectors.create(
            ConnectorInput(
                name=conn_name,
                description="Python SDK live IT",
                type="repository",
                class_name="org.opencrawling.filesystem.FileSystemRepositoryConnector",
                configuration={"basePath": "/tmp"},
            ),
            options=RequestOptions.idempotent(f"py-conn-{tag}"),
        )
        assert created_conn.data.name == conn_name
        try:
            read_conn = client.connectors.get(conn_name)
            assert read_conn.etag is not None
            replaced_conn = client.connectors.replace(
                conn_name,
                read_conn.data.to_input().model_copy(update={"description": "Updated by Python SDK IT"}),
                options=RequestOptions.matching(read_conn.etag),
            )
            assert replaced_conn.data.description == "Updated by Python SDK IT"

            # Job create -> conditional 304 -> patch with If-Match -> stale ETag 412 -> delete
            created_job = client.jobs.create(
                JobInput(
                    name=f"PySDK Job {tag}",
                    repository_connector=conn_name,
                    output_connector="PGVector_Output",
                    path="/tmp",
                ),
                options=RequestOptions.idempotent(f"py-job-{tag}"),
            )
            job_id = created_job.data.id
            try:
                assert created_job.etag is not None
                assert client.jobs.get_if_changed(job_id, created_job.etag) is None
                patched = client.jobs.patch(
                    job_id,
                    {"path": "/tmp/updated"},
                    options=RequestOptions.matching(created_job.etag),
                )
                assert patched.data.path == "/tmp/updated"
                with pytest.raises(ApiProblemError) as conflict:
                    client.jobs.patch(
                        job_id,
                        {"path": "/tmp/stale"},
                        options=RequestOptions.matching(created_job.etag),
                    )
                assert conflict.value.status == 412
            finally:
                client.jobs.delete(job_id)
        finally:
            client.connectors.delete(conn_name, force=True)

        # Settings lossless read
        sys_settings = client.settings.system()
        assert sys_settings.etag is not None
        assert sys_settings.data.chunk_size is not None


async def test_live_async_platform_api_v1_smoke() -> None:
    assert BASE_URL is not None
    async with AsyncOpenCrawlingClient(BASE_URL, **_client_kwargs()) as client:
        idx = await client.index()
        assert idx.api_version.startswith("1.")
        status = await client.operations.status()
        assert status.system is not None
