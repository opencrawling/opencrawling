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
  timeout: 10000,
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
  checkConnection: (data: any) => api.post('/connectors/check', data),
}

export const statusApi = {
  getSystemStatus: () => api.get('/system/status'),
  getThroughput: () => api.get('/system/throughput'),
  getLogs: () => api.get('/system/logs'),
  getSettings: () => api.get('/system/settings'),
  saveSettings: (data: any) => api.post('/system/settings', data),
}

export const transportApi = {
  getSettings: () => api.get('/v1/admin/settings/transport'),
  saveSettings: (data: any) => api.put('/v1/admin/settings/transport', data),
  testGrpc: (data?: { host?: string; port?: number }) => api.post('/v1/admin/settings/transport/test-grpc', data),
}

export const observabilityApi = {
  diagnoseJob: (jobId: string) => api.get(`/observability/diagnose/${jobId}`),
  getJobTraces: (jobId: string) => api.get(`/observability/traces/${jobId}`),
  getErrorLogs: (jobId: string, timeframe?: string) => api.get(`/observability/errors/${jobId}${timeframe ? `?timeframe=${timeframe}` : ''}`),
  getMetrics: (connectorId?: string) => api.get(`/observability/metrics${connectorId ? `?connectorId=${connectorId}` : ''}`),
}

export const vespaInsightsApi = {
  getHealth: (endpoint: string) => api.get('/vespa/health', { params: { endpoint } }),
  getDocumentCounts: (endpoint: string) => api.get('/vespa/document-counts', { params: { endpoint } }),
  runQuery: (data: { endpoint: string; documentType: string; queryText: string; rankProfile: string }) =>
    api.post('/vespa/query', data),
  deployBundledSchema: (configEndpoint: string) => api.post('/vespa/deploy/bundled', { configEndpoint }),
  deployCustomSchema: (configEndpoint: string, file: File) => {
    const formData = new FormData()
    formData.append('configEndpoint', configEndpoint)
    formData.append('file', file)
    // Clear the shared instance's default JSON header so the browser can set the correct
    // multipart/form-data boundary itself.
    return api.post('/vespa/deploy/custom', formData, {
      headers: { 'Content-Type': undefined },
    })
  },
}

export const narrativizationApi = {
  generate: (data: { connectorType: string; fields: { name: string; type: string; description: string }[] }) =>
    api.post('/transformation/copilot/generate', data),
}

export interface McfMigrationRequest {
  mcfUrl: string
  mcfUsername?: string
  mcfPassword?: string
  defaultEmbeddingDimensions?: number
  /** Only meaningful on /apply — restricts which of the supported connections actually get applied. Omit/empty = all. */
  selectedConnections?: string[]
  /** Only meaningful on /apply — restricts which of the supported jobs actually get applied. Omit/empty = all. */
  selectedJobs?: string[]
}

export interface McfMigrationNote {
  field: string
  kind: 'DROPPED' | 'DEFAULTED' | 'CONVERTED' | 'SCOPE_CHANGE' | 'RUNTIME_RISK'
  recommendedAction?: string
  message: string
}

export interface McfConnectionSummary {
  name: string
  manifoldClass: string
  supported: boolean
  targetType?: string
  targetClass?: string
  reason?: string
  recommendedAction?: string
  notes: McfMigrationNote[]
}

export interface McfJobSummary {
  name: string
  supported: boolean
  repositoryConnector?: string
  outputConnector?: string
  transformationConnector?: string
  path?: string
  blockingConnectors: string[]
  reason?: string
  recommendedAction?: string
  notes: McfMigrationNote[]
}

export interface McfApplyResult {
  success: boolean
  detail: string
}

export interface McfMigrationResponse {
  connections: McfConnectionSummary[]
  jobs: McfJobSummary[]
  summary: {
    connectionsTotal: number
    connectionsMigrated: number
    jobsTotal: number
    jobsMigrated: number
    compatibilityScorePercentage: number
  }
  connectionResults?: Record<string, McfApplyResult>
  jobResults?: Record<string, McfApplyResult>
}

export const mcfMigrationApi = {
  plan: (data: McfMigrationRequest) => api.post<McfMigrationResponse>('/mcf-migration/plan', data),
  apply: (data: McfMigrationRequest) => api.post<McfMigrationResponse>('/mcf-migration/apply', data),
}

export default api
