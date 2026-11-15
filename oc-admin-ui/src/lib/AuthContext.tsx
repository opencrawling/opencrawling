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
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import axios from 'axios'
import type { User, UserManager } from 'oidc-client-ts'
import api from './api'
import { AuthContext, type AuthStatus } from './useAuth'
import {
  CALLBACK_PATH, createUserManager, installTokenInterceptors, safeReturnPath, signInMode,
  type AuthConfig, type Me,
} from './auth'

/** Without sign-in (or with management authorization off) every caller may do everything. */
const EVERYONE: Me = {
  authenticated: false, principal: null, groups: [], tenant: null,
  managementAuthorization: false, admin: true, canCreateJobs: true,
}

const currentPath = () => window.location.pathname + window.location.search + window.location.hash

interface Startup {
  mode: 'off' | 'oidc' | 'misconfigured'
  manager: UserManager | null
  user: User | null
  error: string | null
}

let startup: Promise<Startup> | null = null

/**
 * Reads the sign-in settings and finishes a sign-in that is coming back from the identity provider. Runs once per
 * page load (React runs effects twice in development, and a sign-in response can only be redeemed once).
 */
function startAuth(): Promise<Startup> {
  startup ??= (async (): Promise<Startup> => {
    let config: AuthConfig | null = null
    try {
      config = (await axios.get<AuthConfig>('/api/auth/config')).data
    } catch (err) {
      // A runtime without the endpoint predates sign-in; one that is down shows as unreachable anyway.
      console.warn('Could not read /api/auth/config; continuing without sign-in.', err)
    }
    const mode = signInMode(config)
    if (mode !== 'oidc' || !config?.oidc) {
      return { mode, manager: null, user: null, error: null }
    }
    const manager = createUserManager(config.oidc)
    installTokenInterceptors(api, manager, currentPath)
    try {
      let user: User | null
      if (window.location.pathname === CALLBACK_PATH) {
        user = await manager.signinRedirectCallback()
        window.history.replaceState(null, '', safeReturnPath(user.state))
      } else {
        user = await manager.getUser()
        if (user?.expired) {
          user = await manager.signinSilent().catch(() => null)
        }
      }
      return { mode, manager, user, error: null }
    } catch (err) {
      window.history.replaceState(null, '', '/')
      return { mode, manager, user: null, error: err instanceof Error ? err.message : String(err) }
    }
  })()
  return startup
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading')
  const [me, setMe] = useState<Me | null>(null)
  const [error, setError] = useState<string | null>(null)
  const manager = useRef<UserManager | null>(null)

  useEffect(() => {
    let cancelled = false
    const signedOut = () => setStatus('signed-out')

    startAuth().then(async (result) => {
      if (cancelled) return
      if (result.mode !== 'oidc' || !result.manager) {
        setMe(EVERYONE)
        setStatus(result.mode === 'misconfigured' ? 'misconfigured' : 'off')
        return
      }
      manager.current = result.manager
      result.manager.events.addUserSignedOut(signedOut)
      result.manager.events.addSilentRenewError(signedOut)
      setError(result.error)
      if (!result.user) {
        setStatus('signed-out')
        return
      }
      try {
        const res = await api.get<Me>('/auth/me')
        if (cancelled) return
        setMe(res.data)
        setStatus('signed-in')
      } catch (err) {
        if (cancelled) return
        setError(err instanceof Error ? err.message : String(err))
        setStatus('signed-out')
      }
    })
    return () => {
      cancelled = true
      manager.current?.events.removeUserSignedOut(signedOut)
      manager.current?.events.removeSilentRenewError(signedOut)
    }
  }, [])

  const signIn = useCallback(() => {
    setError(null)
    manager.current?.signinRedirect({ state: { returnTo: currentPath() } })
  }, [])

  const signOut = useCallback(() => {
    const um = manager.current
    if (!um) return
    um.signoutRedirect().catch(() => um.removeUser().then(() => setStatus('signed-out')))
  }, [])

  const value = useMemo(() => ({ status, me, error, signIn, signOut }), [status, me, error, signIn, signOut])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
