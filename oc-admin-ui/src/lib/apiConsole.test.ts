/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
import { describe, expect, it } from 'vitest'
import {
  addToHistory, buildUrl, matchPath, filterOperations, groupByTag, isProblem, isTextual, jsonError, loadHistory, missingRequired,
  parseLink, parseOperations, prettyBody, toCurl, HISTORY_SIZE,
} from './apiConsole'

const doc = {
  tags: [{ name: 'Jobs', description: 'Pipeline jobs' }, { name: 'Platform', description: 'Entry point' }],
  paths: {
    '/api/v1': { get: { operationId: 'getApiIndex', tags: ['Platform'], summary: 'Entry point', responses: { '200': { description: 'ok' } } } },
    '/api/v1/jobs/{id}': {
      get: {
        operationId: 'getJob', tags: ['Jobs'], summary: 'Get a job', description: 'The job.',
        parameters: [
          { name: 'id', in: 'path', required: true, description: 'Job id', schema: { type: 'string' } },
          { name: 'If-None-Match', in: 'header', description: 'ETag', schema: { type: 'string' } },
        ],
        responses: { '200': { description: 'The job' }, '404': { description: 'Missing' } },
        'x-codeSamples': [{ lang: 'Shell', source: 'curl ...' }],
      },
      patch: {
        operationId: 'patchJob', tags: ['Jobs'], summary: 'Patch',
        requestBody: { required: true, content: { 'application/merge-patch+json': { example: { name: 'x' } } } },
        responses: { '200': { description: 'ok' } },
      },
    },
    '/api/v1/jobs': {
      get: {
        operationId: 'listJobs', tags: ['Jobs'], summary: 'List jobs',
        parameters: [{ name: 'status', in: 'query', schema: { type: 'string', enum: ['Ready', 'Running'] } }],
        responses: {},
      },
    },
  },
}

describe('parseOperations', () => {
  it('reads every operation with its parameters, body example and code sample', () => {
    const ops = parseOperations(doc)
    expect(ops.map((o) => o.id)).toEqual(['getApiIndex', 'getJob', 'patchJob', 'listJobs'])
    const get = ops.find((o) => o.id === 'getJob')!
    expect(get.method).toBe('GET')
    expect(get.parameters).toEqual([
      { name: 'id', in: 'path', required: true, description: 'Job id', type: 'string', enumValues: undefined },
      { name: 'If-None-Match', in: 'header', required: false, description: 'ETag', type: 'string', enumValues: undefined },
    ])
    expect(get.codeSample).toBe('curl ...')
    expect(get.responses.map((r) => r.code)).toEqual(['200', '404'])
    const patch = ops.find((o) => o.id === 'patchJob')!
    expect(patch.body).toEqual({ contentTypes: ['application/merge-patch+json'], example: { name: 'x' }, required: true })
    expect(ops.find((o) => o.id === 'listJobs')!.parameters[0].enumValues).toEqual(['Ready', 'Running'])
  })

  it('copes with an empty or missing document', () => {
    expect(parseOperations(undefined)).toEqual([])
    expect(parseOperations({ paths: {} })).toEqual([])
  })
})

describe('groupByTag and filterOperations', () => {
  it('groups in the document tag order and searches across fields', () => {
    const ops = parseOperations(doc)
    const groups = groupByTag(ops, doc.tags)
    expect(groups.map((g) => g.tag)).toEqual(['Jobs', 'Platform'])
    expect(groups[0].description).toBe('Pipeline jobs')
    expect(filterOperations(ops, 'patch').map((o) => o.id)).toEqual(['patchJob'])
    expect(filterOperations(ops, 'get jobs').map((o) => o.id)).toEqual(['getJob', 'listJobs'])
    expect(filterOperations(ops, '  ')).toHaveLength(4)
  })
})

describe('building requests', () => {
  it('encodes path values and drops empty query values', () => {
    expect(buildUrl('/api/v1/connectors/{name}:check', { name: 'HR Share/1' }, {})).toBe('/api/v1/connectors/HR%20Share%2F1:check')
    expect(buildUrl('/api/v1/jobs', {}, { status: 'Ready', q: '', sort: '-name' })).toBe('/api/v1/jobs?status=Ready&sort=-name')
  })

  it('lists required parameters that have no value', () => {
    const get = parseOperations(doc).find((o) => o.id === 'getJob')!
    expect(missingRequired(get, {})).toEqual(['id'])
    expect(missingRequired(get, { 'path:id': ' 42 ' })).toEqual([])
  })

  it('checks a body is JSON', () => {
    expect(jsonError('{"a":1}')).toBeNull()
    expect(jsonError('')).toBeNull()
    expect(jsonError('{a:1}')).toBeTruthy()
  })

  it('writes curl without ever writing the token', () => {
    const curl = toCurl({ method: 'POST', url: '/api/v1/jobs', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer secret',
      'Idempotency-Key': 'k1' }, body: `{"name":"it's"}` }, 'https://oc.example', true)
    expect(curl).toContain(`curl -sS -i -X POST 'https://oc.example/api/v1/jobs'`)
    expect(curl).toContain('-H "Authorization: Bearer $OC_TOKEN"')
    expect(curl).not.toContain('secret')
    expect(curl).toContain(`-H 'Idempotency-Key: k1'`)
    expect(curl).toContain(`--data-raw '{"name":"it'\\''s"}'`)
    expect(toCurl({ method: 'GET', url: '/api/v1', headers: {} }, '', false)).not.toContain('Authorization')
  })
})

describe('matchPath', () => {
  it('reads path variables, custom methods and encoded values', () => {
    expect(matchPath('/api/v1/jobs/{id}', '/api/v1/jobs/42')).toEqual({ id: '42' })
    expect(matchPath('/api/v1/jobs/{id}:start', '/api/v1/jobs/42:start')).toEqual({ id: '42' })
    expect(matchPath('/api/v1/connectors/{name}', '/api/v1/connectors/HR%20Share')).toEqual({ name: 'HR Share' })
    expect(matchPath('/api/v1/jobs/{id}', '/api/v1/jobs/42/executions')).toBeNull()
    expect(matchPath('/api/v1/jobs', '/api/v1/jobs')).toEqual({})
    expect(matchPath('/api/v1/executions:query', '/api/v1/executionsXquery')).toBeNull()
  })
})

describe('reading responses', () => {
  it('parses Link headers', () => {
    expect(parseLink('<http://h/api/v1/jobs?cursor=abc>; rel="next", <http://h/api/v1/jobs>; rel="first"'))
      .toEqual({ next: 'http://h/api/v1/jobs?cursor=abc', first: 'http://h/api/v1/jobs' })
    expect(parseLink(undefined)).toEqual({})
  })

  it('recognizes problem details by type or shape', () => {
    expect(isProblem('application/problem+json; charset=UTF-8', {})).toBe(true)
    expect(isProblem('application/json', { type: 't', title: 'x', status: 400 })).toBe(true)
    expect(isProblem('application/json', { items: [] })).toBe(false)
  })

  it('tells text from binary and pretty-prints JSON', () => {
    expect(isTextual('application/json')).toBe(true)
    expect(isTextual('text/csv; charset=UTF-8')).toBe(true)
    expect(isTextual('application/vnd.apache.parquet')).toBe(false)
    expect(prettyBody('{"a":[1]}', 'application/json')).toBe('{\n  "a": [\n    1\n  ]\n}')
    expect(prettyBody('not json', 'text/plain')).toBe('not json')
  })
})

describe('history', () => {
  const memory = () => {
    const data: Record<string, string> = {}
    return { getItem: (k: string) => data[k] ?? null, setItem: (k: string, v: string) => { data[k] = v } }
  }

  it('keeps the newest entries and never stores the token', () => {
    const storage = memory()
    for (let i = 0; i < HISTORY_SIZE + 3; i++) {
      addToHistory(storage, { at: String(i), operationId: 'listJobs', method: 'GET', url: `/api/v1/jobs?i=${i}`,
        headers: { Authorization: 'Bearer secret', Accept: 'application/json' }, status: 200, durationMs: 5 })
    }
    const history = loadHistory(storage)
    expect(history).toHaveLength(HISTORY_SIZE)
    expect(history[0].url).toBe(`/api/v1/jobs?i=${HISTORY_SIZE + 2}`)
    expect(JSON.stringify(history)).not.toContain('secret')
  })

  it('survives corrupt or missing storage', () => {
    expect(loadHistory({ getItem: () => '{not json' })).toEqual([])
    expect(loadHistory(undefined)).toEqual([])
  })
})
