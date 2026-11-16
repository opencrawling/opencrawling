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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError, AxiosHeaders, type AxiosAdapter, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import api, {
  connectorApi, fetchAll, isPreconditionFailed, jobApi, mcfMigrationApi, narrativizationApi, observabilityApi,
  problemMessage, reportApi, statusApi, toConnectorInput, toJob, toJobInput, toJobPatch, transportApi, vespaInsightsApi,
  CHECK_PLACEHOLDER_NAME, MAX_PAGE_SIZE, type JobResource,
} from './api'

interface Call {
  method: string
  url: string
  params: Record<string, unknown> | undefined
  headers: AxiosHeaders
  data: unknown
}

type Reply = { status?: number; data?: unknown; headers?: Record<string, string> }

let calls: Call[]
let replies: ((call: Call) => Reply)[]
const originalAdapter = api.defaults.adapter

/** Answers each request with the next queued reply (or `{}`), recording what was sent. */
const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
  const call: Call = {
    method: (config.method ?? 'get').toUpperCase(),
    url: `${config.baseURL ?? ''}${config.url ?? ''}`,
    params: config.params,
    headers: AxiosHeaders.from(config.headers),
    data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
  }
  calls.push(call)
  const reply: Reply = (replies.shift() ?? ((): Reply => ({})))(call)
  const response: AxiosResponse = {
    data: reply.data ?? {}, status: reply.status ?? 200, statusText: '', headers: AxiosHeaders.from(reply.headers ?? {}), config,
  }
  if (response.status >= 400) {
    throw new AxiosError(`Request failed with status code ${response.status}`, 'ERR_BAD_REQUEST', config, null, response)
  }
  return response
}

const reply = (r: Reply) => replies.push(() => r)
const page = <T>(items: T[], nextCursor: string | null = null) => ({ data: { items, page: { limit: 500, nextCursor, totalItems: items.length } } })

beforeEach(() => {
  calls = []
  replies = []
  api.defaults.adapter = adapter
})

afterEach(() => {
  api.defaults.adapter = originalAdapter
})

const jobResource: JobResource = {
  id: '17', name: 'HR', repositoryConnector: 'FS', outputConnector: 'PG', authorityConnector: null,
  transformationConnector: 'Ollama_Embedding_Default', path: '/data/hr', pipelineMode: 'RAG', crawlSpec: { seeds: ['a'] },
  narrativization: null, access: null,
  state: { status: 'Running', stage: 'Scanning', documents: 42, lastRun: null }, permissions: ['READ', 'EXECUTE'],
}

describe('paging', () => {
  it('follows nextCursor until it is null, keeping the filters', async () => {
    reply(page([1, 2], 'c1'))
    reply(page([3], 'c2'))
    reply(page([4]))
    expect(await fetchAll<number>('/things', { type: 'x' })).toEqual([1, 2, 3, 4])
    expect(calls.map((c) => c.params)).toEqual([
      { type: 'x', limit: MAX_PAGE_SIZE, cursor: undefined },
      { type: 'x', limit: MAX_PAGE_SIZE, cursor: 'c1' },
      { type: 'x', limit: MAX_PAGE_SIZE, cursor: 'c2' },
    ])
    expect(calls.every((c) => c.method === 'GET' && c.url === '/api/v1/things')).toBe(true)
  })
})

describe('jobApi', () => {
  it('lists every page of jobs and maps them to the flat UI shape', async () => {
    reply(page([jobResource], 'next'))
    reply(page([{ ...jobResource, id: '18', state: { status: 'Ready', stage: 'Idle', documents: 0, lastRun: '2026-11-01' }, permissions: null }]))
    const res = await jobApi.getAll()
    expect(calls.map((c) => c.url)).toEqual(['/api/v1/jobs', '/api/v1/jobs'])
    expect(res.data[0]).toEqual({
      id: '17', name: 'HR', repositoryConnector: 'FS', outputConnector: 'PG', authorityConnector: '', path: '/data/hr',
      status: 'Running', currentStage: 'Scanning', documents: 42, lastRun: 'N/A',
      transformationConnector: 'Ollama_Embedding_Default', narrativization: undefined, pipelineMode: 'rag',
      crawlSpec: { seeds: ['a'] }, permissions: ['READ', 'EXECUTE'],
    })
    expect(res.data[1].lastRun).toBe('2026-11-01')
    expect(res.data[1].permissions).toEqual([])
  })

  it('derives the permissions map from the jobs', async () => {
    reply(page([jobResource, { ...jobResource, id: '18', permissions: ['READ'] }]))
    expect((await jobApi.permissions()).data).toEqual({ '17': ['READ', 'EXECUTE'], '18': ['READ'] })
    expect(calls[0].url).toBe('/api/v1/jobs')
  })

  it('reads one job with an encoded id', async () => {
    reply({ data: jobResource })
    expect((await jobApi.getById('a/b')).data.id).toBe('17')
    expect(calls[0]).toMatchObject({ method: 'GET', url: '/api/v1/jobs/a%2Fb' })
  })

  it('starts, stops and pauses with custom methods, the colon kept literal', async () => {
    await jobApi.start('j 1')
    await jobApi.stop('j 1')
    await jobApi.pause('j 1')
    reply({ data: { id: 'j 1' }, headers: { ETag: '"d1"' } })
    await jobApi.delete('j 1')
    expect(calls[4].headers.get('If-Match')).toBe('"d1"')
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'POST /api/v1/jobs/j%201:start', 'POST /api/v1/jobs/j%201:stop', 'POST /api/v1/jobs/j%201:pause',
      'GET /api/v1/jobs/j%201', 'DELETE /api/v1/jobs/j%201',
    ])
  })

  it('creates a new job with POST and a JobInput without blank optional connectors', async () => {
    reply({ status: 201, data: jobResource })
    const res = await jobApi.create({
      id: 'new', name: 'HR', repositoryConnector: 'FS', outputConnector: 'PG', authorityConnector: '', path: '/data/hr',
      transformationConnector: 'Ollama_Embedding_Default', pipelineMode: 'rag',
      narrativization: { enabled: false, template: '', connectorType: 'iceberg' },
    })
    expect(calls[0]).toMatchObject({ method: 'POST', url: '/api/v1/jobs' })
    expect(calls[0].data).toEqual({
      name: 'HR', repositoryConnector: 'FS', outputConnector: 'PG', path: '/data/hr',
      transformationConnector: 'Ollama_Embedding_Default', pipelineMode: 'rag',
      narrativization: { enabled: false, template: '', connectorType: 'iceberg' },
    })
    expect(res.data.status).toBe('Running')
  })

  it('changes an existing job with a merge patch that keeps what the form does not edit', async () => {
    reply({ data: jobResource })
    await jobApi.create({ id: '17', name: 'HR2', repositoryConnector: 'FS', outputConnector: 'PG', authorityConnector: '', path: '/x', pipelineMode: 'migration' })
    expect(calls[0]).toMatchObject({ method: 'PATCH', url: '/api/v1/jobs/17' })
    expect(calls[0].headers.get('Content-Type')).toBe('application/merge-patch+json')
    expect(calls[0].data).toEqual({
      name: 'HR2', repositoryConnector: 'FS', outputConnector: 'PG', path: '/x', authorityConnector: null,
      transformationConnector: null, pipelineMode: 'migration',
    })
    expect(calls[0].data).not.toHaveProperty('crawlSpec')
    expect(calls[0].data).not.toHaveProperty('access')
  })

  it('reads a job with its version and saves a change with If-Match', async () => {
    reply({ data: { id: '7', name: 'HR', state: { status: 'Ready' } }, headers: { ETag: '"j1"' } })
    reply({ data: { id: '7', name: 'HR 2', state: { status: 'Ready' } }, headers: { ETag: '"j2"' } })
    const read = await jobApi.getVersioned('7')
    expect(read.etag).toBe('"j1"')
    expect(read.data.name).toBe('HR')
    await jobApi.create({ ...read.data, name: 'HR 2' }, read.etag)
    expect(calls[1]).toMatchObject({ method: 'PATCH', url: '/api/v1/jobs/7' })
    expect(calls[1].headers.get('If-Match')).toBe('"j1"')
  })

  it('maps unknown pipeline modes to null and fills missing state', () => {
    const job = toJob({ ...jobResource, pipelineMode: null, state: { status: 'Error', stage: null, documents: 0, lastRun: null } })
    expect(job.pipelineMode).toBeNull()
    expect(job.currentStage).toBe('Idle')
    expect(toJobInput({ name: 'n', repositoryConnector: 'r', outputConnector: 'o', path: 'p', authorityConnector: 'A' }))
      .toEqual({ name: 'n', repositoryConnector: 'r', outputConnector: 'o', path: 'p', authorityConnector: 'A' })
    expect(toJobPatch({ name: 'n', repositoryConnector: 'r', outputConnector: 'o', path: 'p', crawlSpec: { a: 1 } }))
      .toMatchObject({ crawlSpec: { a: 1 } })
  })
})

describe('connectorApi', () => {
  const resource = { name: 'FS', description: null, type: 'repository', className: 'c.FS', maxConnections: null, configuration: null }

  it('lists every page of one type and fills the form defaults', async () => {
    reply(page([resource], 'n'))
    reply(page([{ ...resource, name: 'FS2', configuration: { password: '********' }, maxConnections: 3 }]))
    const res = await connectorApi.getAll('repository')
    expect(calls.map((c) => [c.url, c.params?.type, c.params?.cursor])).toEqual([
      ['/api/v1/connectors', 'repository', undefined], ['/api/v1/connectors', 'repository', 'n'],
    ])
    expect(res.data).toEqual([
      { name: 'FS', description: '', type: 'repository', className: 'c.FS', maxConnections: 10, configuration: {} },
      { name: 'FS2', description: '', type: 'repository', className: 'c.FS', maxConnections: 3, configuration: { password: '********' } },
    ])
  })

  it('creates a new connector with POST and replaces the edited one with PUT, sending the mask back', async () => {
    reply({ status: 201, data: resource })
    reply({ data: resource })
    reply({ status: 201, data: resource })
    const def = { name: 'My FS', type: 'repository', className: 'c.FS', maxConnections: '5', description: 'd',
      configuration: { password: '********', tls: true, port: 22, gone: undefined } }
    await connectorApi.create(def)
    await connectorApi.create(def, 'My FS')
    await connectorApi.create({ ...def, name: 'Copy' }, 'My FS')
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'POST /api/v1/connectors', 'PUT /api/v1/connectors/My%20FS', 'POST /api/v1/connectors',
    ])
    expect(calls[1].data).toEqual({
      name: 'My FS', description: 'd', type: 'repository', className: 'c.FS', maxConnections: 5,
      configuration: { password: '********', tls: 'true', port: '22' },
    })
  })

  it('reads a connector with its version and replaces it with If-Match, but creates without one', async () => {
    reply({ data: { name: 'HR Share', type: 'repository', className: 'x.Fs', configuration: { password: '********' } },
      headers: { ETag: '"c1"' } })
    reply({ data: { name: 'HR Share', type: 'repository' } })
    reply({ data: { name: 'New', type: 'repository' } })
    const read = await connectorApi.getVersioned('HR Share')
    expect(calls[0].url).toBe('/api/v1/connectors/HR%20Share')
    expect(read.etag).toBe('"c1"')
    await connectorApi.create({ ...read.data, type: 'repository' }, 'HR Share', read.etag)
    expect(calls[1]).toMatchObject({ method: 'PUT' })
    expect(calls[1].headers.get('If-Match')).toBe('"c1"')
    await connectorApi.create({ ...read.data, name: 'New', type: 'repository' }, 'HR Share', read.etag)
    expect(calls[2]).toMatchObject({ method: 'POST', url: '/api/v1/connectors' })
    expect(calls[2].headers.has('If-Match')).toBe(false)
  })

  it('deletes and checks connectors', async () => {
    reply({ data: { name: 'A B' }, headers: { ETag: '"c9"' } })
    await connectorApi.delete('A B')
    expect(calls[1].headers.get('If-Match')).toBe('"c9"')
    reply({ data: { success: true, message: 'ok' } })
    const res = await connectorApi.checkConnection({ name: '', type: 'output', className: 'c.PG', configuration: {} })
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual(['GET /api/v1/connectors/A%20B', 'DELETE /api/v1/connectors/A%20B',
      'POST /api/v1/connectors:check'])
    expect(calls[2].data).toMatchObject({ name: CHECK_PLACEHOLDER_NAME, type: 'output', className: 'c.PG' })
    expect(res.data.success).toBe(true)
  })

  it('drops empty optional fields from the input', () => {
    expect(toConnectorInput({ name: 'n', type: 'output', className: 'c', maxConnections: '', description: '', configuration: { h: { a: 1 }, n: null } }))
      .toEqual({ name: 'n', type: 'output', className: 'c', configuration: { h: '{"a":1}', n: null } })
  })
})

describe('statusApi and transportApi', () => {
  it('reads system status, throughput and logs', async () => {
    await statusApi.getSystemStatus()
    await statusApi.getThroughput()
    await statusApi.getLogs()
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'GET /api/v1/system/status', 'GET /api/v1/system/throughput', 'GET /api/v1/system/logs',
    ])
  })

  it('reads the system settings with their ETag and saves them with If-Match', async () => {
    reply({ data: { chunkSize: 800 }, headers: { ETag: '"v1"' } })
    reply({ data: { chunkSize: 900 }, headers: { ETag: '"v2"' } })
    const read = await statusApi.getSettings()
    expect(read).toEqual({ data: { chunkSize: 800 }, etag: '"v1"' })
    const saved = await statusApi.saveSettings({ chunkSize: 900 }, read.etag)
    expect(calls[0]).toMatchObject({ method: 'GET', url: '/api/v1/settings/system' })
    expect(calls[1]).toMatchObject({ method: 'PUT', url: '/api/v1/settings/system', data: { chunkSize: 900 } })
    expect(calls[1].headers.get('If-Match')).toBe('"v1"')
    expect(saved.etag).toBe('"v2"')
  })

  it('sends no If-Match without an ETag, and reports 412 as a precondition failure', async () => {
    reply({ status: 412, data: { title: 'Precondition failed', status: 412, detail: 'The resource changed since you read it.' } })
    const err = await statusApi.saveSettings({ a: 1 }).catch((e: unknown) => e)
    expect(calls[0].headers.has('If-Match')).toBe(false)
    expect(isPreconditionFailed(err)).toBe(true)
    expect(isPreconditionFailed(new Error('x'))).toBe(false)
  })

  it('uses the v1 transport settings routes', async () => {
    await transportApi.getSettings()
    await transportApi.saveSettings({ mode: 'AUTO' })
    await transportApi.testGrpc({ port: 9095 })
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'GET /api/v1/settings/transport', 'PUT /api/v1/settings/transport', 'POST /api/v1/settings/transport:test-grpc',
    ])
    expect(calls[2].data).toEqual({ port: 9095 })
  })

  it('reads the transport settings with their ETag and saves them with If-Match', async () => {
    reply({ data: { mode: 'AUTO', grpcPort: 9095 }, headers: { ETag: '"t1"' } })
    reply({ data: { mode: 'REST', grpcPort: 9095 }, headers: { ETag: '"t2"' } })
    const read = await transportApi.getSettings()
    expect(read).toEqual({ data: { mode: 'AUTO', grpcPort: 9095 }, etag: '"t1"' })
    const saved = await transportApi.saveSettings({ mode: 'REST', grpcPort: 9095 }, read.etag)
    expect(calls[1].headers.get('If-Match')).toBe('"t1"')
    expect(saved).toEqual({ data: { mode: 'REST', grpcPort: 9095 }, etag: '"t2"' })
  })
})

describe('observabilityApi', () => {
  it('uses the per-job sub-resources and the metrics route', async () => {
    await observabilityApi.diagnoseJob('j/1')
    await observabilityApi.getJobTraces('j1')
    await observabilityApi.getErrorLogs('j1')
    await observabilityApi.getErrorLogs('j1', '24h')
    await observabilityApi.getMetrics()
    await observabilityApi.getMetrics('FS')
    expect(calls.map((c) => [c.url, c.params])).toEqual([
      ['/api/v1/jobs/j%2F1/diagnosis', undefined], ['/api/v1/jobs/j1/traces', undefined], ['/api/v1/jobs/j1/errors', undefined],
      ['/api/v1/jobs/j1/errors', { timeframe: '24h' }], ['/api/v1/observability/metrics', undefined],
      ['/api/v1/observability/metrics', { connectorId: 'FS' }],
    ])
  })
})

describe('tools', () => {
  it('uses the Vespa integration routes', async () => {
    await vespaInsightsApi.getHealth('http://v:8080')
    await vespaInsightsApi.getDocumentCounts('http://v:8080')
    await vespaInsightsApi.runQuery({ endpoint: 'e', documentType: 'd', queryText: 'q', rankProfile: 'r' })
    await vespaInsightsApi.deployBundledSchema('http://c:19071')
    await vespaInsightsApi.deployCustomSchema('http://c:19071', new File(['x'], 'app.zip'))
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'GET /api/v1/integrations/vespa/health', 'GET /api/v1/integrations/vespa/document-counts',
      'POST /api/v1/integrations/vespa:query', 'POST /api/v1/integrations/vespa:deploy-bundled',
      'POST /api/v1/integrations/vespa:deploy-custom',
    ])
    expect(calls[0].params).toEqual({ endpoint: 'http://v:8080' })
    expect(calls[3].data).toEqual({ configEndpoint: 'http://c:19071' })
    expect(calls[4].data).toBeInstanceOf(FormData)
  })

  it('uses the narrativization and ManifoldCF custom methods', async () => {
    await narrativizationApi.generate({ connectorType: 'iceberg', fields: [] })
    await mcfMigrationApi.plan({ mcfUrl: 'http://mcf' })
    await mcfMigrationApi.apply({ mcfUrl: 'http://mcf', selectedJobs: ['a'] })
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'POST /api/v1/narrativization:generate-template', 'POST /api/v1/migrations/manifoldcf:plan',
      'POST /api/v1/migrations/manifoldcf:apply',
    ])
    expect(calls[2].data).toEqual({ mcfUrl: 'http://mcf', selectedJobs: ['a'] })
  })
})

describe('reportApi', () => {
  it('lists every page of executions', async () => {
    reply(page([{ executionId: 'e1' }], 'c'))
    reply(page([{ executionId: 'e2' }]))
    const res = await reportApi.getAll()
    expect(res.data.map((r) => r.executionId)).toEqual(['e1', 'e2'])
    expect(calls.map((c) => c.url)).toEqual(['/api/v1/executions', '/api/v1/executions'])
  })

  it('reads one execution', async () => {
    await reportApi.getById('exec-1')
    expect(calls[0]).toMatchObject({ method: 'GET', url: '/api/v1/executions/exec-1' })
  })

  it('reads a ledger page with the v1 filter names and maps the paging', async () => {
    reply({ data: { items: [{ documentId: 'd1' }], page: { limit: 25, nextCursor: 'nx', totalItems: 60 } } })
    const res = await reportApi.getDocumentLedger('exec-1', { status: 'FAILED', query: 'hr', limit: 25, cursor: 'cur' })
    expect(calls[0]).toMatchObject({ method: 'GET', url: '/api/v1/executions/exec-1/documents' })
    expect(calls[0].params).toEqual({ status: 'FAILED', q: 'hr', limit: 25, cursor: 'cur' })
    expect(res.data).toEqual({ items: [{ documentId: 'd1' }], total: 60, limit: 25, nextCursor: 'nx' })
  })

  it('runs a query', async () => {
    reply({ data: { columns: ['n'], rows: [[1]] } })
    const res = await reportApi.runQuery('SELECT 1')
    expect(calls[0]).toMatchObject({ method: 'POST', url: '/api/v1/executions:query', data: { sql: 'SELECT 1' } })
    expect(res.data.columns).toEqual(['n'])
  })

  it('downloads an export through the client', async () => {
    const createObjectURL = vi.fn(() => 'blob:x')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL })
    const click = vi.fn()
    const link = { href: '', download: '', click, remove: vi.fn() }
    vi.stubGlobal('document', { createElement: () => link, body: { appendChild: vi.fn() } })
    try {
      reply({ data: new Blob(['a']) })
      await reportApi.downloadExport('exec/1', 'csv', 'm.csv')
      expect(calls[0]).toMatchObject({ method: 'GET', url: '/api/v1/executions/exec%2F1/export', params: { format: 'csv' } })
      expect(link.download).toBe('m.csv')
      expect(click).toHaveBeenCalled()
    } finally {
      vi.unstubAllGlobals()
    }
  })
})

describe('problemMessage', () => {
  const failing = async (r: Reply) => {
    reply(r)
    return statusApi.getSystemStatus().catch((e: unknown) => e)
  }

  it('shows the detail and the field errors of a problem', async () => {
    const err = await failing({ status: 400, data: { title: 'Validation failed', detail: 'The request has 1 invalid value.', errors: [{ field: 'name', message: 'must not be blank' }] } })
    expect(problemMessage(err, 'x')).toBe('The request has 1 invalid value. name: must not be blank')
  })

  it('falls back to the title, legacy fields, text bodies, the error message and the fallback', async () => {
    expect(problemMessage(await failing({ status: 403, data: { title: 'Forbidden' } }), 'x')).toBe('Forbidden')
    expect(problemMessage(await failing({ status: 500, data: { message: 'boom' } }), 'x')).toBe('boom')
    expect(problemMessage(await failing({ status: 500, data: 'plain text' }), 'x')).toBe('plain text')
    expect(problemMessage(await failing({ status: 502, data: {} }), 'x')).toBe('Request failed with status code 502')
    expect(problemMessage(undefined, 'fallback')).toBe('fallback')
  })
})
