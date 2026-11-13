/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import type { AxiosError, AxiosInstance, InternalAxiosRequestConfig } from 'axios'
import { UserManager, WebStorageStateStore, type User, type UserManagerSettings } from 'oidc-client-ts'

/** GET /api/auth/config: how this UI signs users in. `oidc` is null when it should not sign in. */
export interface AuthConfig {
  mode: 'none' | 'jwt'
  protectApi: boolean
  oidc: { issuer: string; clientId: string; scope: string } | null
}

/** GET /api/auth/me: the current caller and what they may do on the management plane. */
export interface Me {
  authenticated: boolean
  principal: string | null
  groups: string[]
  tenant: string | null
  managementAuthorization: boolean
  admin: boolean
  canCreateJobs: boolean
}

export type JobAction = 'READ' | 'EXECUTE' | 'MANAGE'

/**
 * What the UI does about signing in:
 * - `off`: no sign-in (identity mode none, or the API takes requests without a token);
 * - `oidc`: sign in with the identity provider;
 * - `misconfigured`: the API needs a token but no client is configured for this UI.
 */
export type SignInMode = 'off' | 'oidc' | 'misconfigured'

export const CALLBACK_PATH = '/auth/callback'

export function signInMode(config: AuthConfig | null): SignInMode {
  if (config?.oidc) return 'oidc'
  if (config?.mode === 'jwt' && config.protectApi) return 'misconfigured'
  return 'off'
}

/** Settings for the authorization code flow with PKCE (oidc-client-ts uses PKCE for `code` by default). */
export function userManagerSettings(oidc: NonNullable<AuthConfig['oidc']>, origin: string,
                                    store?: Storage): UserManagerSettings {
  return {
    authority: oidc.issuer,
    client_id: oidc.clientId,
    scope: oidc.scope,
    response_type: 'code',
    redirect_uri: origin + CALLBACK_PATH,
    post_logout_redirect_uri: origin + '/',
    // Renew with the refresh token shortly before the access token expires.
    automaticSilentRenew: true,
    // The session-check iframe needs third-party cookies; renewal tells us soon enough when the session ends.
    monitorSession: false,
    // Tokens live for this tab only and are gone when it closes.
    userStore: store ? new WebStorageStateStore({ store }) : undefined,
  }
}

/**
 * Where to go after signing in. Only a path on this site: anything else (another origin, `//host`, `/\host`)
 * falls back to the start page, so a crafted sign-in link cannot send the user elsewhere.
 */
export function safeReturnPath(state: unknown): string {
  const path = typeof state === 'object' && state !== null ? (state as { returnTo?: unknown }).returnTo : undefined
  if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//') || path.startsWith('/\\')
      || path.startsWith(CALLBACK_PATH)) {
    return '/'
  }
  return path
}

/** The parts of a user manager the request interceptors need; lets tests stand in for oidc-client-ts. */
export interface TokenSource {
  getUser(): Promise<User | null>
  signinSilent(): Promise<User | null>
  signinRedirect(args?: { state?: unknown }): Promise<void>
}

type RetriableConfig = InternalAxiosRequestConfig & { _authRetried?: boolean }

/**
 * Sends the access token with every API request. On a 401 it renews the token once and repeats the request; if
 * that fails it starts a new sign-in, coming back to the current page.
 */
export function installTokenInterceptors(api: AxiosInstance, tokens: TokenSource,
                                         currentPath: () => string): () => void {
  const request = api.interceptors.request.use(async (config: RetriableConfig) => {
    if (config._authRetried) {
      return config // a repeat after renewal already carries the renewed token
    }
    const user = await tokens.getUser()
    if (user && !user.expired && user.access_token) {
      config.headers.set('Authorization', `Bearer ${user.access_token}`)
    }
    return config
  })
  // Requests that fail together share one renewal and at most one redirect.
  let renewal: Promise<User | null> | null = null
  let redirecting = false
  const renew = () => {
    renewal ??= tokens.signinSilent().catch(() => null).finally(() => { renewal = null })
    return renewal
  }
  const response = api.interceptors.response.use(undefined, async (error: AxiosError) => {
    const config = error.config as RetriableConfig | undefined
    if (error.response?.status !== 401 || !config || config._authRetried) {
      throw error
    }
    config._authRetried = true
    const renewed = await renew()
    if (renewed?.access_token) {
      config.headers.set('Authorization', `Bearer ${renewed.access_token}`)
      return api.request(config)
    }
    if (!redirecting) {
      redirecting = true
      await tokens.signinRedirect({ state: { returnTo: currentPath() } })
    }
    throw error
  })
  return () => {
    api.interceptors.request.eject(request)
    api.interceptors.response.eject(response)
  }
}

export function createUserManager(oidc: NonNullable<AuthConfig['oidc']>): UserManager {
  return new UserManager(userManagerSettings(oidc, window.location.origin, window.sessionStorage))
}

/** Initials for the header badge. */
export function initials(principal: string | null | undefined): string {
  if (!principal) return '?'
  const parts = principal.split(/[\s._@-]+/).filter(Boolean)
  const letters = parts.length > 1 ? parts[0][0] + parts[1][0] : principal.slice(0, 2)
  return letters.toUpperCase()
}
