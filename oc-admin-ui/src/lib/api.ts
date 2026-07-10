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
///
/// Copyright © ${year} the original author or authors (piergiorgio@apache.org)
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json',
  },
})

export const jobApi = {
  getAll: () => api.get('/jobs'),
  getById: (id: string) => api.get(`/jobs/${id}`),
  start: (id: string) => api.post(`/jobs/${id}/start`),
  stop: (id: string) => api.post(`/jobs/${id}/stop`),
  pause: (id: string) => api.post(`/jobs/${id}/pause`),
  create: (data: any) => api.post('/jobs', data),
  delete: (id: string) => api.delete(`/jobs/${id}`),
}

export const connectorApi = {
  getAll: (type: string) => api.get(`/connectors/${type}`),
  create: (data: any) => api.post('/connectors', data),
  delete: (id: string) => api.delete(`/connectors/${id}`),
}

export const statusApi = {
  getSystemStatus: () => api.get('/system/status'),
  getThroughput: () => api.get('/system/throughput'),
  getLogs: () => api.get('/system/logs'),
  getSettings: () => api.get('/system/settings'),
  saveSettings: (data: any) => api.post('/system/settings', data),
}

export default api
