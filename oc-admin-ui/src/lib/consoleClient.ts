/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
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
import axios from 'axios'

/**
 * HTTP clients of the API console. Every status is an answer to show, not an error; bodies are read as bytes so
 * files can be downloaded and text decoded. `consoleApi` carries the signed-in user's token (installed with the
 * other API clients when sign-in starts); `anonymousConsoleApi` sends requests without one, to see how the API
 * answers an anonymous caller.
 */
const options = {
  validateStatus: () => true,
  responseType: 'arraybuffer' as const,
  transformResponse: [(data: unknown) => data],
  timeout: 120000,
}

export const consoleApi = axios.create(options)
export const anonymousConsoleApi = axios.create(options)
