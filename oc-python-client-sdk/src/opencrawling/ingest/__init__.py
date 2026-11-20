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

"""Data-plane ingestion clients: OIS Direct HTTP Gateway and optional gRPC Push Streaming."""

from opencrawling.ingest.grpc_push import (
    AsyncGrpcPushClient,
    GrpcDocumentAction,
    GrpcIngestionAck,
    GrpcIngestionStatus,
    GrpcPingResponse,
    GrpcPushClient,
    GrpcPushDocument,
    GrpcPushFailure,
    GrpcPushOptions,
    GrpcPushRequest,
    GrpcPushResponse,
    GrpcSecurityConfig,
    GrpcStreamSummary,
    normalize_grpc_metadata,
)
from opencrawling.ingest.ois import (
    AsyncOisIngestClient,
    OisContent,
    OisDocument,
    OisIngestClient,
    OisIngestResponse,
    OisSource,
    PermissionRule,
    SecurityConfig,
)

__all__ = [
    "AsyncGrpcPushClient",
    "AsyncOisIngestClient",
    "GrpcDocumentAction",
    "GrpcIngestionAck",
    "GrpcIngestionStatus",
    "GrpcPingResponse",
    "GrpcPushClient",
    "GrpcPushDocument",
    "GrpcPushFailure",
    "GrpcPushOptions",
    "GrpcPushRequest",
    "GrpcPushResponse",
    "GrpcSecurityConfig",
    "GrpcStreamSummary",
    "OisContent",
    "OisDocument",
    "OisIngestClient",
    "OisIngestResponse",
    "OisSource",
    "PermissionRule",
    "SecurityConfig",
    "normalize_grpc_metadata",
]
