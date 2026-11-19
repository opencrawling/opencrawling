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
"""RFC 8628 OAuth 2.0 Device Authorization Grant helper."""

from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable, Callable

from opencrawling.auth.oauth2 import (
    DeviceAuthorization,
    ExpiringToken,
    OAuth2Client,
    OAuth2Endpoints,
    OAuth2Tokens,
)
from opencrawling.errors import OAuth2Error


class DeviceCodeLogin:
    """Signs a user in via OAuth 2.0 Device Authorization Grant (RFC 8628).

    Supports both synchronous (``start`` / ``poll``) and asynchronous (``astart`` / ``apoll``)
    execution with injectable clocks and sleepers for deterministic testing.
    """

    GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

    def __init__(
        self,
        *,
        oauth_client: OAuth2Client | None = None,
        clock: Callable[[], float] = time.time,
        sleeper: Callable[[float], None] = time.sleep,
        asleeper: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self._oauth = oauth_client or OAuth2Client()
        self._clock = clock
        self._sleeper = sleeper
        self._asleeper = asleeper

    def discover(self, issuer: str) -> OAuth2Endpoints:
        """Discover OIDC endpoints for ``issuer`` synchronously."""
        return self._oauth.discover(issuer)

    async def adiscover(self, issuer: str) -> OAuth2Endpoints:
        """Discover OIDC endpoints for ``issuer`` asynchronously."""
        return await self._oauth.adiscover(issuer)

    def start(
        self,
        endpoints: OAuth2Endpoints,
        client_id: str,
        *,
        scope: str | None = None,
    ) -> DeviceAuthorization:
        """Initiate a device authorization flow synchronously."""
        if not endpoints.device_authorization_endpoint:
            raise OAuth2Error(
                f"The issuer {endpoints.issuer} does not offer the device authorization grant."
            )
        return self._oauth.start_device_authorization(
            endpoints.device_authorization_endpoint,
            client_id,
            scope=scope,
        )

    async def astart(
        self,
        endpoints: OAuth2Endpoints,
        client_id: str,
        *,
        scope: str | None = None,
    ) -> DeviceAuthorization:
        """Initiate a device authorization flow asynchronously."""
        if not endpoints.device_authorization_endpoint:
            raise OAuth2Error(
                f"The issuer {endpoints.issuer} does not offer the device authorization grant."
            )
        return await self._oauth.astart_device_authorization(
            endpoints.device_authorization_endpoint,
            client_id,
            scope=scope,
        )

    def poll(
        self,
        endpoints: OAuth2Endpoints,
        client_id: str,
        authorization: DeviceAuthorization,
    ) -> OAuth2Tokens:
        """Poll the token endpoint synchronously until the user confirms, declines, or the code expires."""
        deadline = self._clock() + authorization.expires_in
        interval = float(max(1, authorization.interval))
        grant = {
            "grant_type": self.GRANT_TYPE,
            "device_code": authorization.device_code,
        }
        while self._clock() < deadline:
            self._sleeper(interval)
            if self._clock() >= deadline:
                break
            try:
                resp = self._oauth.token(endpoints.token_endpoint, client_id, None, grant)
                access = ExpiringToken.from_lifetime(
                    resp.access_token,
                    resp.expires_in,
                    now=self._clock(),
                )
                return OAuth2Tokens(
                    access_token=access.value,
                    refresh_token=resp.refresh_token,
                    expires_at=access.expires_at,
                )
            except OAuth2Error as exc:
                if exc.error == "authorization_pending":
                    continue
                if exc.error == "slow_down":
                    interval += 5.0
                    continue
                if exc.error == "access_denied":
                    raise OAuth2Error("The sign-in was declined.", error="access_denied") from exc
                if exc.error == "expired_token":
                    break
                raise
        raise OAuth2Error(
            "The sign-in code expired before it was confirmed; try again.",
            error="expired_token",
        )

    async def apoll(
        self,
        endpoints: OAuth2Endpoints,
        client_id: str,
        authorization: DeviceAuthorization,
    ) -> OAuth2Tokens:
        """Poll the token endpoint asynchronously until the user confirms, declines, or the code expires."""
        deadline = self._clock() + authorization.expires_in
        interval = float(max(1, authorization.interval))
        grant = {
            "grant_type": self.GRANT_TYPE,
            "device_code": authorization.device_code,
        }
        while self._clock() < deadline:
            await self._asleeper(interval)
            if self._clock() >= deadline:
                break
            try:
                resp = await self._oauth.atoken(endpoints.token_endpoint, client_id, None, grant)
                access = ExpiringToken.from_lifetime(
                    resp.access_token,
                    resp.expires_in,
                    now=self._clock(),
                )
                return OAuth2Tokens(
                    access_token=access.value,
                    refresh_token=resp.refresh_token,
                    expires_at=access.expires_at,
                )
            except OAuth2Error as exc:
                if exc.error == "authorization_pending":
                    continue
                if exc.error == "slow_down":
                    interval += 5.0
                    continue
                if exc.error == "access_denied":
                    raise OAuth2Error("The sign-in was declined.", error="access_denied") from exc
                if exc.error == "expired_token":
                    break
                raise
        raise OAuth2Error(
            "The sign-in code expired before it was confirmed; try again.",
            error="expired_token",
        )
