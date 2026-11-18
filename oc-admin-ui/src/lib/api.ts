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
import type { JobAction } from './auth'

/**
 * The runtime's Platform API (`/api/v1`, docs/Platform-API.md). Every call of the admin UI goes through this client,
 * except the public sign-in bootstrap `GET /api/auth/config`. The functions below map the API's resources to the
 * shapes the components use, so the components do not have to know about paging or nested job state.
 */
const api = axios.create({
  baseURL: '/api/v1',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

/** Path segment of an id: encoded, so a custom method's `:` after it stays the separator. */
const seg = (id: string) => encodeURIComponent(id)

// ---- Paging -------------------------------------------------------------------------------------------------

export interface PageInfo {
  limit: number
  nextCursor: string | null
  totalItems: number
}

export interface Page<T> {
  items: T[]
  page: PageInfo
}

/** The largest page the API serves; whole lists are read with as few requests as possible. */
export const MAX_PAGE_SIZE = 500
/** Stops a misbehaving server from keeping the UI in an endless loop of pages. */
const MAX_PAGES = 1000

/** Reads every item of a collection, following `page.nextCursor` until it is null. */
export async function fetchAll<T>(url: string, params: Record<string, string | undefined> = {}): Promise<T[]> {
  const items: T[] = []
  let cursor: string | undefined
  for (let i = 0; i < MAX_PAGES; i++) {
    const res = await api.get<Page<T>>(url, { params: { ...params, limit: MAX_PAGE_SIZE, cursor } })
    items.push(...(res.data?.items ?? []))
    const next = res.data?.page?.nextCursor
    if (!next) break
    cursor = next
  }
  return items
}

// ---- Problem details ----------------------------------------------------------------------------------------

/** An RFC 9457 problem detail, as the Platform API answers errors. */
export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  errors?: { field: string; message: string }[]
  requestId?: string
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null

/**
 * The message to show for a failed request: the problem's `detail` (or `title`) with its field errors, else whatever
 * text the response carries, else the error's own message, else `fallback`.
 */
export function problemMessage(err: unknown, fallback: string): string {
  const data: unknown = axios.isAxiosError(err) ? err.response?.data : undefined
  if (isRecord(data)) {
    const problem = data as ProblemDetail & { message?: unknown; error?: unknown }
    const main = problem.detail || problem.title
    if (main) {
      const fields = Array.isArray(problem.errors)
        ? problem.errors.filter(isRecord).map((e) => `${e.field}: ${e.message}`)
        : []
      return fields.length ? `${main} ${fields.join('; ')}` : main
    }
    if (typeof problem.message === 'string' && problem.message) return problem.message
    if (typeof problem.error === 'string' && problem.error) return problem.error
  } else if (typeof data === 'string' && data.trim()) {
    return data
  }
  if (err instanceof Error && err.message) return err.message
  return fallback
}

/** Whether a save was refused because the resource changed since it was read (`412`, If-Match). */
export const isPreconditionFailed = (err: unknown) => axios.isAxiosError(err) && err.response?.status === 412

/** 409 job-not-running: the job has no run to resume. */
export const isNotRunning = (err: unknown) => axios.isAxiosError(err) && err.response?.status === 409

/** A resource with the ETag it was read with, to send back as If-Match when saving it. */
export interface Versioned<T> {
  data: T
  etag: string | null
}

const etagOf = (headers: unknown): string | null => {
  if (!isRecord(headers)) return null
  const value = typeof headers.get === 'function' ? (headers.get as (name: string) => unknown)('etag') : headers.etag
  return typeof value === 'string' && value ? value : null
}

const ifMatch = (etag?: string | null) => (etag ? { 'If-Match': etag } : {})

// ---- Jobs ---------------------------------------------------------------------------------------------------

export type JobStatus = 'Running' | 'Paused' | 'Error' | 'Finished' | 'Ready'
export type PipelineMode = 'rag' | 'migration'

export interface NarrativizationConfig {
  enabled: boolean
  template: string
  connectorType: string
}

/** A job as `GET /api/v1/jobs` returns it. */
export interface JobResource {
  id: string
  name: string
  repositoryConnector: string
  outputConnector: string
  authorityConnector: string | null
  transformationConnector: string | null
  path: string
  pipelineMode: string | null
  crawlSpec: Record<string, unknown> | null
  narrativization: NarrativizationConfig | null
  access: Record<string, unknown> | null
  state: { status: string; stage: string | null; documents: number; lastRun: string | null }
  permissions: JobAction[] | null
}

/** A job as the UI shows it (the legacy flat shape). */
export interface Job {
  id: string
  name: string
  repositoryConnector: string
  outputConnector: string
  authorityConnector: string
  path: string
  status: JobStatus
  currentStage: string
  documents: number
  /** `N/A` when the job never ran. */
  lastRun: string
  transformationConnector: string
  narrativization?: NarrativizationConfig
  pipelineMode?: PipelineMode | null
  crawlSpec?: Record<string, unknown>
  permissions: JobAction[]
}

export function toJob(job: JobResource): Job {
  const mode = job.pipelineMode?.toLowerCase()
  return {
    id: job.id,
    name: job.name ?? '',
    repositoryConnector: job.repositoryConnector ?? '',
    outputConnector: job.outputConnector ?? '',
    authorityConnector: job.authorityConnector ?? '',
    path: job.path ?? '',
    status: (job.state?.status ?? 'Ready') as JobStatus,
    currentStage: job.state?.stage ?? 'Idle',
    documents: job.state?.documents ?? 0,
    lastRun: job.state?.lastRun ?? 'N/A',
    transformationConnector: job.transformationConnector ?? '',
    narrativization: job.narrativization ?? undefined,
    pipelineMode: mode === 'rag' || mode === 'migration' ? mode : null,
    crawlSpec: job.crawlSpec ?? undefined,
    permissions: job.permissions ?? [],
  }
}

/** What the job form saves. Without an id (or with `new`) it creates a job, otherwise it changes that job. */
export interface JobDefinition {
  id?: string
  name: string
  repositoryConnector: string
  outputConnector: string
  authorityConnector?: string
  transformationConnector?: string
  path: string
  pipelineMode?: PipelineMode | null
  narrativization?: NarrativizationConfig
  crawlSpec?: Record<string, unknown>
}

const blank = (value?: string | null) => value === undefined || value === null || value.trim() === ''

/** The `JobInput` to create a job: optional connector names that are blank are left out. */
export function toJobInput(job: JobDefinition): Record<string, unknown> {
  const input: Record<string, unknown> = {
    name: job.name,
    repositoryConnector: job.repositoryConnector,
    outputConnector: job.outputConnector,
    path: job.path,
  }
  if (!blank(job.authorityConnector)) input.authorityConnector = job.authorityConnector
  if (!blank(job.transformationConnector)) input.transformationConnector = job.transformationConnector
  if (job.pipelineMode) input.pipelineMode = job.pipelineMode
  if (job.narrativization) input.narrativization = job.narrativization
  if (job.crawlSpec) input.crawlSpec = job.crawlSpec
  return input
}

/**
 * The JSON Merge Patch for the fields the job form edits. Everything else (crawl settings, access) stays as stored,
 * as the legacy save did; a blank optional connector is removed.
 */
export function toJobPatch(job: JobDefinition): Record<string, unknown> {
  const patch: Record<string, unknown> = {
    name: job.name,
    repositoryConnector: job.repositoryConnector,
    outputConnector: job.outputConnector,
    path: job.path,
    authorityConnector: blank(job.authorityConnector) ? null : job.authorityConnector,
    transformationConnector: blank(job.transformationConnector) ? null : job.transformationConnector,
  }
  if (job.pipelineMode !== undefined) patch.pipelineMode = job.pipelineMode
  if (job.narrativization !== undefined) patch.narrativization = job.narrativization
  if (job.crawlSpec !== undefined) patch.crawlSpec = job.crawlSpec
  return patch
}

const listJobs = async () => (await fetchAll<JobResource>('/jobs')).map(toJob)

export const jobApi = {
  getAll: async () => ({ data: await listJobs() }),
  getById: async (id: string) => ({ data: toJob((await api.get<JobResource>(`/jobs/${seg(id)}`)).data) }),
  /** The job with its version (ETag), read together, to edit it and save with If-Match. */
  getVersioned: async (id: string): Promise<Versioned<Job>> => {
    const res = await api.get<JobResource>(`/jobs/${seg(id)}`)
    return { data: toJob(res.data), etag: etagOf(res.headers) }
  },
  start: (id: string) => api.post(`/jobs/${seg(id)}:start`),
  stop: (id: string) => api.post(`/jobs/${seg(id)}:stop`),
  pause: (id: string) => api.post(`/jobs/${seg(id)}:pause`),
  /** Resumes the job's paused run; 409 `job-not-running` when it has none (then it can be started). */
  resume: (id: string) => api.post(`/jobs/${seg(id)}:resume`),
  /**
   * Creates a job (no id, or `new`) or saves the form's fields of an existing one; with the ETag it was read with, a
   * change made in between (by someone else, or by its run) answers 412 instead of being overwritten.
   */
  create: async (job: JobDefinition, etag?: string | null) => {
    const res = !job.id || job.id === 'new'
      ? await api.post<JobResource>('/jobs', toJobInput(job))
      : await api.patch<JobResource>(`/jobs/${seg(job.id)}`, toJobPatch(job),
        { headers: { 'Content-Type': 'application/merge-patch+json', ...ifMatch(etag) } })
    return { data: toJob(res.data) }
  },
  /** Deletes the job as it is now: read, then delete that version (works when the runtime requires If-Match). */
  delete: async (id: string) => {
    const current = await api.get<JobResource>(`/jobs/${seg(id)}`)
    return api.delete(`/jobs/${seg(id)}`, { headers: ifMatch(etagOf(current.headers)) })
  },
  /** What the caller may do with each job they can see (READ, EXECUTE, MANAGE), from the jobs' `permissions`. */
  permissions: async () => ({
    data: Object.fromEntries((await listJobs()).map((job) => [job.id, job.permissions])) as Record<string, JobAction[]>,
  }),
}

// ---- Connectors ---------------------------------------------------------------------------------------------

export type ConnectorType = 'repository' | 'output' | 'authority' | 'transformation'

/** The secret value the API answers instead of a stored secret; sent back, it keeps the stored value. */
export const SECRET_MASK = '********'

export interface ConnectorResource {
  name: string
  description: string | null
  type: string
  className: string
  maxConnections: number | null
  /** Null for callers who may not see connector settings (job creators). */
  configuration: Record<string, string> | null
}

export interface Connector {
  name: string
  description: string
  type: ConnectorType
  className: string
  maxConnections: number
  configuration: Record<string, string>
}

export function toConnector(connector: ConnectorResource): Connector {
  return {
    name: connector.name,
    description: connector.description ?? '',
    type: connector.type as ConnectorType,
    className: connector.className ?? '',
    maxConnections: connector.maxConnections ?? 10,
    configuration: connector.configuration ?? {},
  }
}

/** What the connector form saves or checks. */
export interface ConnectorDefinition {
  name: string
  description?: string | null
  type: string
  className: string
  maxConnections?: number | string | null
  configuration?: Record<string, unknown> | null
}

/** Name sent when a definition is checked before it has one (the API requires a name). */
export const CHECK_PLACEHOLDER_NAME = 'connection-check'

/** The `ConnectorInput` for a definition: settings as strings, as the API stores them. */
export function toConnectorInput(connector: ConnectorDefinition): Record<string, unknown> {
  const configuration: Record<string, string | null> = {}
  Object.entries(connector.configuration ?? {}).forEach(([key, value]) => {
    if (value === undefined) return
    if (value === null || typeof value === 'string') configuration[key] = value
    else if (typeof value === 'object') configuration[key] = JSON.stringify(value)
    else configuration[key] = String(value)
  })
  const max = connector.maxConnections === null || connector.maxConnections === undefined || connector.maxConnections === ''
    ? undefined : Number(connector.maxConnections)
  const input: Record<string, unknown> = {
    name: connector.name,
    type: connector.type,
    className: connector.className,
    configuration,
  }
  if (connector.description) input.description = connector.description
  if (max !== undefined && !Number.isNaN(max)) input.maxConnections = max
  return input
}

export const connectorApi = {
  getAll: async (type: ConnectorType) => ({
    data: (await fetchAll<ConnectorResource>('/connectors', { type })).map(toConnector),
  }),
  /** The connector with its version (ETag), read together, to edit it and save with If-Match. */
  getVersioned: async (name: string): Promise<Versioned<ReturnType<typeof toConnector>>> => {
    const res = await api.get<ConnectorResource>(`/connectors/${seg(name)}`)
    return { data: toConnector(res.data), etag: etagOf(res.headers) }
  },
  /**
   * Saves a connector: replaces `existingName` (the connector being edited) when the name is unchanged, otherwise
   * creates a new one (the API refuses a name that is taken). With the ETag the connector was read with, a change made
   * in between answers 412 instead of being overwritten.
   */
  create: async (connector: ConnectorDefinition, existingName?: string | null, etag?: string | null) => {
    const input = toConnectorInput(connector)
    const res = existingName && existingName === connector.name
      ? await api.put<ConnectorResource>(`/connectors/${seg(existingName)}`, input, { headers: ifMatch(etag) })
      : await api.post<ConnectorResource>('/connectors', input)
    return { data: toConnector(res.data) }
  },
  /** Deletes the connector as it is now: read, then delete that version (works when the runtime requires If-Match). */
  delete: async (name: string) => {
    const current = await api.get<ConnectorResource>(`/connectors/${seg(name)}`)
    return api.delete(`/connectors/${seg(name)}`, { headers: ifMatch(etagOf(current.headers)) })
  },
  checkConnection: (connector: ConnectorDefinition) =>
    api.post<{ success: boolean; message: string; details?: string }>('/connectors:check', toConnectorInput({
      ...connector,
      name: blank(connector.name) ? CHECK_PLACEHOLDER_NAME : connector.name,
    })),
}

// ---- System and settings ------------------------------------------------------------------------------------

/** The system settings as the runtime stores them; the Settings page owns the field list. */
export type SystemSettings = Record<string, unknown>

export const statusApi = {
  getSystemStatus: () => api.get('/system/status'),
  getThroughput: () => api.get('/system/throughput'),
  getLogs: () => api.get<string[]>('/system/logs'),
  getSettings: async <T = SystemSettings>(): Promise<Versioned<T>> => {
    const res = await api.get<T>('/settings/system')
    return { data: res.data, etag: etagOf(res.headers) }
  },
  /** Saves the settings; with the ETag they were read with, a change by someone else in between answers 412. */
  saveSettings: async <T = SystemSettings>(data: T, etag?: string | null): Promise<Versioned<T>> => {
    const res = await api.put<T>('/settings/system', data, { headers: ifMatch(etag) })
    return { data: res.data, etag: etagOf(res.headers) }
  },
}

export const transportApi = {
  /** The transport settings document, with its ETag. */
  getSettings: async <T>(): Promise<Versioned<T>> => {
    const res = await api.get<T>('/settings/transport')
    return { data: res.data, etag: etagOf(res.headers) }
  },
  /** Saves the settings; with the ETag they were read with, a change by someone else in between answers 412. */
  saveSettings: async <T>(data: T, etag?: string | null): Promise<Versioned<T>> => {
    const res = await api.put<T>('/settings/transport', data, { headers: ifMatch(etag) })
    return { data: res.data, etag: etagOf(res.headers) }
  },
  testGrpc: (data?: { host?: string; port?: number }) => api.post('/settings/transport:test-grpc', data),
}

// ---- Observability ------------------------------------------------------------------------------------------

export const observabilityApi = {
  diagnoseJob: (jobId: string) => api.get(`/jobs/${seg(jobId)}/diagnosis`),
  getJobTraces: (jobId: string) => api.get(`/jobs/${seg(jobId)}/traces`),
  getErrorLogs: (jobId: string, timeframe?: string) =>
    api.get(`/jobs/${seg(jobId)}/errors`, { params: timeframe ? { timeframe } : undefined }),
  getMetrics: (connectorId?: string) =>
    api.get('/observability/metrics', { params: connectorId ? { connectorId } : undefined }),
}

// ---- Tools --------------------------------------------------------------------------------------------------

export const vespaInsightsApi = {
  getHealth: (endpoint: string) => api.get('/integrations/vespa/health', { params: { endpoint } }),
  getDocumentCounts: (endpoint: string) => api.get('/integrations/vespa/document-counts', { params: { endpoint } }),
  runQuery: (data: { endpoint: string; documentType: string; queryText: string; rankProfile: string }) =>
    api.post('/integrations/vespa:query', data),
  deployBundledSchema: (configEndpoint: string) => api.post('/integrations/vespa:deploy-bundled', { configEndpoint }),
  deployCustomSchema: (configEndpoint: string, file: File) => {
    const formData = new FormData()
    formData.append('configEndpoint', configEndpoint)
    formData.append('file', file)
    // Clear the shared instance's default JSON header so the browser can set the correct
    // multipart/form-data boundary itself.
    return api.post('/integrations/vespa:deploy-custom', formData, {
      headers: { 'Content-Type': undefined },
    })
  },
}

export const narrativizationApi = {
  generate: (data: { connectorType: string; fields: { name: string; type: string; description: string }[] }) =>
    api.post('/narrativization:generate-template', data),
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
  plan: (data: McfMigrationRequest) => api.post<McfMigrationResponse>('/migrations/manifoldcf:plan', data),
  apply: (data: McfMigrationRequest) => api.post<McfMigrationResponse>('/migrations/manifoldcf:apply', data),
}

export interface JobReportSummary {
  jobId: string
  executionId: string
  jobName: string
  pipelineMode: 'RAG' | 'MIGRATION'
  repositoryConnector: string
  outputConnector: string
  status: 'SUCCESS' | 'PARTIAL' | 'FAILED'
  startTime: string
  endTime: string
  durationMs: number
  totalDocuments: number
  successfulDocuments: number
  failedDocuments: number
  skippedDocuments: number
  // RAG
  totalChunks?: number
  totalTokens?: number
  estimatedEmbeddingCostUsd?: number
  embeddingModel?: string
  // Migration
  totalBinaryBytes?: number
  totalReclaimedBytes?: number
  safePrunedCount?: number
  verifiedChecksumCount?: number
  // Artifact paths
  reportPathParquet?: string
  reportPathCsv?: string
  reportPathJson?: string
}

export interface DocumentLedgerEntry {
  jobId: string
  executionId: string
  pipelineMode: 'RAG' | 'MIGRATION'
  documentId: string
  sourceUri: string
  connectorSource: string
  connectorTarget: string
  status: 'SUCCESS' | 'FAILED' | 'SKIPPED' | 'QUARANTINED'
  durationMs: number
  timestampUtc: string
  errorCode?: string
  errorMessage?: string
  // RAG
  ragChunkCount?: number
  ragTokenCount?: number
  ragEmbeddingModel?: string
  ragEmbeddingDimensions?: number
  ragVectorTable?: string
  // Migration
  migBinarySizeBytes?: number
  migSha256Checksum?: string
  migTargetBucket?: string
  migTargetKey?: string
  migSidecarWritten?: boolean
  migCallbackHttpStatus?: number
  migSafePruned?: boolean
  migReclaimedBytes?: number
}

/** One page of an execution's document ledger. */
export interface PaginatedLedgerResponse {
  items: DocumentLedgerEntry[]
  /** Documents matching the filters, over all pages. */
  total: number
  limit: number
  /** Cursor of the next page; null on the last page. */
  nextCursor: string | null
}

export interface LedgerQuery {
  /** SUCCESS, FAILED, SKIPPED or QUARANTINED; omit for all. */
  status?: string
  /** Text the document id or source URI contains. */
  query?: string
  limit?: number
  /** `nextCursor` of the previous page, made with the same filters and limit. */
  cursor?: string
}

export const reportApi = {
  getAll: async () => ({ data: await fetchAll<JobReportSummary>('/executions') }),
  getById: (executionId: string) => api.get<JobReportSummary>(`/executions/${seg(executionId)}`),
  getDocumentLedger: async (executionId: string, params: LedgerQuery = {}) => {
    const res = await api.get<Page<DocumentLedgerEntry>>(`/executions/${seg(executionId)}/documents`, {
      params: { status: params.status, q: params.query, limit: params.limit, cursor: params.cursor },
    })
    const page = res.data?.page
    const data: PaginatedLedgerResponse = {
      items: res.data?.items ?? [],
      total: page?.totalItems ?? 0,
      limit: page?.limit ?? params.limit ?? 0,
      nextCursor: page?.nextCursor ?? null,
    }
    return { data }
  },
  /**
   * Downloads an export through the API client, so the request carries the access token (a plain link would not),
   * and saves it under `filename`.
   */
  downloadExport: async (executionId: string, format: 'csv' | 'parquet' | 'json', filename: string) => {
    const res = await api.get<Blob>(`/executions/${seg(executionId)}/export`,
      { params: { format }, responseType: 'blob', timeout: 120000 })
    const url = URL.createObjectURL(res.data)
    try {
      const link = document.createElement('a')
      link.href = url
      link.download = filename
      document.body.appendChild(link)
      link.click()
      link.remove()
    } finally {
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    }
  },
  runQuery: (sql: string) => api.post<{ columns: string[]; rows: unknown[][] }>('/executions:query', { sql }),
}

export default api
