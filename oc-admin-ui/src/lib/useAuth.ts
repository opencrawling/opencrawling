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
import { createContext, useContext } from 'react'
import type { Me } from './auth'

export type AuthStatus = 'loading' | 'off' | 'signed-out' | 'signed-in' | 'misconfigured'

export interface AuthContextValue {
  status: AuthStatus
  me: Me | null
  /** Why signing in failed, shown on the sign-in screen. */
  error: string | null
  signIn: () => void
  signOut: () => void
}

export const AuthContext = createContext<AuthContextValue>({
  status: 'loading', me: null, error: null, signIn: () => {}, signOut: () => {},
})

export function useAuth() {
  return useContext(AuthContext)
}
