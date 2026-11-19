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

"""Authentication providers for OpenCrawling Python Client SDK."""

from opencrawling.auth.base import (
    AnyTokenProvider,
    AsyncTokenProvider,
    StaticTokenProvider,
    TokenProvider,
)
from opencrawling.auth.device import DeviceCodeLogin
from opencrawling.auth.oauth2 import (
    ClientCredentialsTokenProvider,
    DeviceAuthorization,
    ExpiringToken,
    OAuth2Client,
    OAuth2Endpoints,
    OAuth2TokenResponse,
    OAuth2Tokens,
    RefreshTokenProvider,
    TokenExchangeProvider,
)

__all__ = [
    "AnyTokenProvider",
    "AsyncTokenProvider",
    "ClientCredentialsTokenProvider",
    "DeviceAuthorization",
    "DeviceCodeLogin",
    "ExpiringToken",
    "OAuth2Client",
    "OAuth2Endpoints",
    "OAuth2TokenResponse",
    "OAuth2Tokens",
    "RefreshTokenProvider",
    "StaticTokenProvider",
    "TokenExchangeProvider",
    "TokenProvider",
]
