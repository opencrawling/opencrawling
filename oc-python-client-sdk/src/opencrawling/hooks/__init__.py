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

"""Helpers for building OpenCrawling HTTP governance and enrichment hook services (#408)."""

from opencrawling.hooks.asgi import (
    EnrichmentHandler,
    GovernanceHandler,
    SecretProvider,
    create_enrichment_app,
    create_governance_app,
    enrichment_endpoint,
    governance_endpoint,
)
from opencrawling.hooks.models import (
    PROTECTED_METADATA_KEYS,
    PROTECTED_METADATA_PREFIXES,
    Decision,
    EnrichmentRequest,
    EnrichmentResponse,
    GovernanceDecisionType,
    GovernanceRequest,
    ProtectedMetadataKeyError,
    is_protected_metadata_key,
)
from opencrawling.hooks.signing import (
    DEFAULT_MAX_SKEW_SECONDS,
    MIN_SECRET_LENGTH,
    SIGNATURE_HEADER,
    SIGNATURE_PREFIX,
    TIMESTAMP_HEADER,
    HookSignatureError,
    build_signed_headers,
    sign_payload,
    verify_signature,
)
from opencrawling.hooks.testing import AsyncSignedHookTestClient, SignedHookTestClient

__all__ = [
    "DEFAULT_MAX_SKEW_SECONDS",
    "MIN_SECRET_LENGTH",
    "PROTECTED_METADATA_KEYS",
    "PROTECTED_METADATA_PREFIXES",
    "SIGNATURE_HEADER",
    "SIGNATURE_PREFIX",
    "TIMESTAMP_HEADER",
    "AsyncSignedHookTestClient",
    "Decision",
    "EnrichmentHandler",
    "EnrichmentRequest",
    "EnrichmentResponse",
    "GovernanceDecisionType",
    "GovernanceHandler",
    "GovernanceRequest",
    "HookSignatureError",
    "ProtectedMetadataKeyError",
    "SecretProvider",
    "SignedHookTestClient",
    "build_signed_headers",
    "create_enrichment_app",
    "create_governance_app",
    "enrichment_endpoint",
    "governance_endpoint",
    "is_protected_metadata_key",
    "sign_payload",
    "verify_signature",
]
