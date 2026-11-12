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
import { describe, expect, it, vi } from 'vitest'
import axios, { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { User } from 'oidc-client-ts'
import {
  CALLBACK_PATH, initials, installTokenInterceptors, safeReturnPath, signInMode, userManagerSettings,
  type AuthConfig, type TokenSource,
} from './auth'

const OIDC = { issuer: 'http://localhost:8180/realms/opencrawling', clientId: 'opencrawling-admin-ui', scope: 'openid profile' }

function user(token: string, expiresInSeconds = 300): User {
  return new User({
    access_token: token,
    token_type: 'Bearer',
    profile: { sub: 'u1', iss: OIDC.issuer, aud: OIDC.clientId, exp: 0, iat: 0 },
    expires_at: Math.floor(Date.now() / 1000) + expiresInSeconds,
  })
}

/** An API client whose "server" accepts exactly one token, and records what it was sent. */
function apiAccepting(acceptedToken: string | null) {
  const seen: (string | undefined)[] = []
  const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
    const header = config.headers.get('Authorization') as string | undefined
    seen.push(header)
    const status = acceptedToken !== null && header === `Bearer ${acceptedToken}` ? 200 : 401
    const response = { data: status === 200 ? 'ok' : 'no', status, statusText: String(status), headers: {}, config }
    if (status !== 200) {
      throw new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, null, response)
    }
    return response
  }
  return { api: axios.create({ adapter }), seen }
}

function tokens(current: User | null, renewed: User | null | Error): TokenSource & { redirects: unknown[]; renewals: number } {
  const source = {
    redirects: [] as unknown[],
    renewals: 0,
    getUser: async () => current,
    signinSilent: async () => {
      source.renewals++
      if (renewed instanceof Error) throw renewed
      return renewed
    },
    signinRedirect: async (args?: { state?: unknown }) => {
      source.redirects.push(args?.state)
    },
  }
  return source
}

describe('signInMode', () => {
  it('signs in when the runtime names an issuer and a client', () => {
    expect(signInMode({ mode: 'jwt', protectApi: true, oidc: OIDC })).toBe('oidc')
  })

  it('stays off without one, unless the API needs a token', () => {
    expect(signInMode(null)).toBe('off')
    expect(signInMode({ mode: 'none', protectApi: false, oidc: null })).toBe('off')
    expect(signInMode({ mode: 'jwt', protectApi: false, oidc: null })).toBe('off')
    expect(signInMode({ mode: 'jwt', protectApi: true, oidc: null } as AuthConfig)).toBe('misconfigured')
  })
})

describe('userManagerSettings', () => {
  it('uses the authorization code flow back to this site', () => {
    const settings = userManagerSettings(OIDC, 'https://oc.example')
    expect(settings.authority).toBe(OIDC.issuer)
    expect(settings.client_id).toBe('opencrawling-admin-ui')
    expect(settings.response_type).toBe('code')
    expect(settings.redirect_uri).toBe('https://oc.example' + CALLBACK_PATH)
    expect(settings.post_logout_redirect_uri).toBe('https://oc.example/')
    expect(settings.automaticSilentRenew).toBe(true)
  })
})

describe('safeReturnPath', () => {
  it('returns to a page on this site', () => {
    expect(safeReturnPath({ returnTo: '/jobs?x=1#y' })).toBe('/jobs?x=1#y')
  })

  it('never leaves the site or loops back to the callback', () => {
    for (const returnTo of ['https://evil.example/', '//evil.example', '/\\evil.example', 'jobs', CALLBACK_PATH + '?code=1', 42]) {
      expect(safeReturnPath({ returnTo })).toBe('/')
    }
    expect(safeReturnPath(undefined)).toBe('/')
    expect(safeReturnPath('/jobs')).toBe('/')
  })
})

describe('installTokenInterceptors', () => {
  it('sends the current access token', async () => {
    const { api, seen } = apiAccepting('t1')
    installTokenInterceptors(api, tokens(user('t1'), null), () => '/')
    await expect(api.get('/jobs')).resolves.toMatchObject({ status: 200 })
    expect(seen).toEqual(['Bearer t1'])
  })

  it('does not send an expired token', async () => {
    const { api, seen } = apiAccepting(null)
    installTokenInterceptors(api, tokens(user('old', -10), null), () => '/')
    await expect(api.get('/jobs')).rejects.toThrow()
    expect(seen[0]).toBeUndefined()
  })

  it('renews the token once on a 401 and repeats the request', async () => {
    const { api, seen } = apiAccepting('t2')
    const source = tokens(user('t1'), user('t2'))
    installTokenInterceptors(api, source, () => '/jobs')
    await expect(api.get('/jobs')).resolves.toMatchObject({ status: 200 })
    expect(seen).toEqual(['Bearer t1', 'Bearer t2'])
    expect(source.renewals).toBe(1)
    expect(source.redirects).toEqual([])
  })

  it('signs in again, back to the current page, when renewal fails', async () => {
    const { api } = apiAccepting('never')
    const source = tokens(user('t1'), new Error('session ended'))
    installTokenInterceptors(api, source, () => '/reports')
    await expect(api.get('/jobs')).rejects.toThrow()
    expect(source.redirects).toEqual([{ returnTo: '/reports' }])
  })

  it('does not retry forever when the renewed token is refused too', async () => {
    const { api, seen } = apiAccepting('never')
    const source = tokens(user('t1'), user('t2'))
    installTokenInterceptors(api, source, () => '/')
    await expect(api.get('/jobs')).rejects.toThrow()
    expect(seen).toEqual(['Bearer t1', 'Bearer t2'])
    expect(source.renewals).toBe(1)
  })

  it('shares one renewal and one redirect between requests that fail together', async () => {
    const { api } = apiAccepting('never')
    const source = tokens(user('t1'), null)
    const renewal = vi.spyOn(source, 'signinSilent')
    installTokenInterceptors(api, source, () => '/')
    await Promise.allSettled([api.get('/a'), api.get('/b'), api.get('/c')])
    expect(renewal).toHaveBeenCalledTimes(1)
    expect(source.redirects).toHaveLength(1)
  })

  it('leaves other errors alone', async () => {
    const adapter: AxiosAdapter = async (config) => {
      throw new AxiosError('Forbidden', 'ERR_BAD_REQUEST', config, null,
        { data: '', status: 403, statusText: '403', headers: {}, config })
    }
    const api = axios.create({ adapter })
    const source = tokens(user('t1'), user('t2'))
    installTokenInterceptors(api, source, () => '/')
    await expect(api.get('/jobs')).rejects.toMatchObject({ response: { status: 403 } })
    expect(source.renewals).toBe(0)
  })

  it('can be removed', async () => {
    const { api, seen } = apiAccepting(null)
    const remove = installTokenInterceptors(api, tokens(user('t1'), null), () => '/')
    remove()
    await expect(api.get('/jobs')).rejects.toThrow()
    expect(seen).toEqual([undefined])
  })
})

describe('initials', () => {
  it('uses the first letters of the name parts', () => {
    expect(initials('hr_admin')).toBe('HA')
    expect(initials('jane.doe@example.com')).toBe('JD')
    expect(initials('ops')).toBe('OP')
    expect(initials(null)).toBe('?')
  })
})
