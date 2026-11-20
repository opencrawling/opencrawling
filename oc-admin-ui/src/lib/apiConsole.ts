/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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
export type ParamLocation = 'path' | 'query' | 'header'

export interface OperationParam {
  name: string
  in: ParamLocation
  required: boolean
  description: string
  type: string
  enumValues?: string[]
}

export interface ApiOperation {
  id: string
  method: string
  path: string
  tag: string
  summary: string
  description: string
  parameters: OperationParam[]
  body?: { contentTypes: string[]; example?: unknown; required: boolean }
  codeSample?: string
  responses: { code: string; description: string }[]
}

export interface TagGroup {
  tag: string
  description: string
  operations: ApiOperation[]
}

const METHODS = ['get', 'post', 'put', 'patch', 'delete'] as const

/** The parts of an OpenAPI 3 document the console reads. */
interface OpenApiParameter {
  name: string
  in: string
  required?: boolean
  description?: string
  schema?: { type?: string; enum?: unknown[] }
}

interface OpenApiMedia {
  example?: unknown
  schema?: { example?: unknown }
}

interface OpenApiOperation {
  operationId?: string
  tags?: string[]
  summary?: string
  description?: string
  parameters?: OpenApiParameter[]
  requestBody?: { required?: boolean; content?: Record<string, OpenApiMedia> }
  responses?: Record<string, { description?: string }>
  'x-codeSamples'?: { source?: string }[]
}

type OpenApiPathItem = Partial<Record<(typeof METHODS)[number], OpenApiOperation>> & { parameters?: OpenApiParameter[] }

export interface OpenApiDocument {
  paths?: Record<string, OpenApiPathItem>
  tags?: { name: string; description?: string }[]
}

/** The operations of an OpenAPI 3 document, in document order. */
export function parseOperations(doc: OpenApiDocument | null | undefined): ApiOperation[] {
  const operations: ApiOperation[] = []
  for (const [path, item] of Object.entries(doc?.paths ?? {})) {
    for (const method of METHODS) {
      const op = item?.[method]
      if (!op) continue
      const parameters: OperationParam[] = [...(item.parameters ?? []), ...(op.parameters ?? [])]
        .filter((p) => p && ['path', 'query', 'header'].includes(p.in))
        .map((p) => ({
          name: p.name,
          in: p.in as ParamLocation,
          required: p.in === 'path' || !!p.required,
          description: p.description ?? '',
          type: p.schema?.type ?? 'string',
          enumValues: Array.isArray(p.schema?.enum) ? p.schema.enum.map(String) : undefined,
        }))
      let body: ApiOperation['body']
      const content = op.requestBody?.content
      if (content) {
        const contentTypes = Object.keys(content)
        const first = content[contentTypes.find((t) => t.includes('json')) ?? contentTypes[0]]
        body = { contentTypes, example: first?.example ?? first?.schema?.example, required: !!op.requestBody?.required }
      }
      operations.push({
        id: op.operationId ?? `${method} ${path}`,
        method: method.toUpperCase(),
        path,
        tag: op.tags?.[0] ?? 'Other',
        summary: op.summary ?? '',
        description: op.description ?? '',
        parameters,
        body,
        codeSample: op['x-codeSamples']?.[0]?.source,
        responses: Object.entries(op.responses ?? {}).map(([code, r]) => ({ code, description: r?.description ?? '' })),
      })
    }
  }
  return operations
}

/** Operations grouped by tag, in the document's tag order, then any remaining tags alphabetically. */
export function groupByTag(operations: ApiOperation[], tags: { name: string; description?: string }[] = []): TagGroup[] {
  const order = tags.map((t) => t.name)
  const names = [...new Set(operations.map((o) => o.tag))].sort((a, b) => {
    const ia = order.indexOf(a), ib = order.indexOf(b)
    if (ia >= 0 && ib >= 0) return ia - ib
    if (ia >= 0) return -1
    if (ib >= 0) return 1
    return a.localeCompare(b)
  })
  return names.map((tag) => ({
    tag,
    description: tags.find((t) => t.name === tag)?.description ?? '',
    operations: operations.filter((o) => o.tag === tag),
  }))
}

/** Operations matching a search over method, path, summary, id and tag. */
export function filterOperations(operations: ApiOperation[], query: string): ApiOperation[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean)
  if (words.length === 0) return operations
  return operations.filter((o) => {
    const text = `${o.method} ${o.path} ${o.summary} ${o.id} ${o.tag}`.toLowerCase()
    return words.every((w) => text.includes(w))
  })
}

/** The request URL: path variables encoded, empty query values left out. */
export function buildUrl(path: string, pathValues: Record<string, string>, queryValues: Record<string, string>): string {
  const filled = path.replace(/\{([^}]+)\}/g, (_, name: string) => encodeURIComponent(pathValues[name] ?? ''))
  const query = Object.entries(queryValues)
    .filter(([, value]) => value !== undefined && value !== '')
    .map(([name, value]) => `${encodeURIComponent(name)}=${encodeURIComponent(value)}`)
  return query.length ? `${filled}?${query.join('&')}` : filled
}

/**
 * The path variables of a concrete path for a template such as {@code /api/v1/jobs/{id}:start}, or null when the
 * path does not match it. Values are decoded.
 */
export function matchPath(template: string, pathname: string): Record<string, string> | null {
  const names: string[] = []
  const pattern = template.split(/(\{[^}]+\})/).map((part) => {
    const variable = part.match(/^\{([^}]+)\}$/)
    if (variable) {
      names.push(variable[1])
      return '([^/]+?)'
    }
    return part.replace(/[.*+?^$()|[\]\\{}]/g, '\\$&')
  }).join('')
  const match = pathname.match(new RegExp(`^${pattern}$`))
  if (!match) return null
  const values: Record<string, string> = {}
  names.forEach((name, i) => { values[name] = decodeURIComponent(match[i + 1]) })
  return values
}

/** Required parameters without a value, by name. */
export function missingRequired(operation: ApiOperation, values: Record<string, string>): string[] {
  return operation.parameters.filter((p) => p.required && !(values[`${p.in}:${p.name}`] ?? '').trim()).map((p) => p.name)
}

/** Whether a body is valid JSON; null when it is, the parser's message otherwise. */
export function jsonError(text: string): string | null {
  if (!text.trim()) return null
  try {
    JSON.parse(text)
    return null
  } catch (e) {
    return e instanceof Error ? e.message : String(e)
  }
}

export interface ConsoleRequest {
  method: string
  url: string
  headers: Record<string, string>
  body?: string
}

const shellQuote = (value: string) => `'${value.replace(/'/g, `'\\''`)}'`

/** A curl command for the request. The bearer token is never written out: it becomes $OC_TOKEN. */
export function toCurl(request: ConsoleRequest, origin: string, withToken: boolean): string {
  const parts = [`curl -sS -i -X ${request.method} ${shellQuote(origin + request.url)}`]
  if (withToken) parts.push(`-H "Authorization: Bearer $OC_TOKEN"`)
  for (const [name, value] of Object.entries(request.headers)) {
    if (name.toLowerCase() === 'authorization' || !value) continue
    parts.push(`-H ${shellQuote(`${name}: ${value}`)}`)
  }
  if (request.body && request.body.trim()) parts.push(`--data-raw ${shellQuote(request.body)}`)
  return parts.join(' \\\n  ')
}

/** RFC 8288 Link header: relation to URL. */
export function parseLink(header: string | undefined | null): Record<string, string> {
  const links: Record<string, string> = {}
  if (!header) return links
  for (const part of header.split(/,(?=\s*<)/)) {
    const match = part.match(/<([^>]*)>\s*;\s*rel="?([^";]+)"?/)
    if (match) links[match[2]] = match[1]
  }
  return links
}

/** Whether a response is an RFC 9457 problem detail. */
export function isProblem(contentType: string | undefined, body: unknown): boolean {
  if ((contentType ?? '').toLowerCase().startsWith('application/problem+json')) return true
  return !!body && typeof body === 'object' && 'type' in body && 'status' in body && 'title' in body
}

/** Whether a content type is text that can be shown, rather than a binary file. */
export function isTextual(contentType: string | undefined): boolean {
  const type = (contentType ?? '').toLowerCase()
  return type === '' || type.includes('json') || type.startsWith('text/') || type.includes('xml') || type.includes('yaml')
}

/** The body for display: JSON indented, anything else as it came. */
export function prettyBody(text: string, contentType: string | undefined): string {
  if ((contentType ?? '').includes('json') || /^\s*[[{]/.test(text)) {
    try {
      return JSON.stringify(JSON.parse(text), null, 2)
    } catch {
      return text
    }
  }
  return text
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** A new key for the Idempotency-Key header. */
export function newIdempotencyKey(): string {
  const c = globalThis.crypto as Crypto | undefined
  if (c?.randomUUID) return c.randomUUID()
  return `key-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/** Headers that tell the REST story of a response, with what they mean. */
export const NOTABLE_HEADERS: Record<string, string> = {
  etag: 'Version of the resource: send it as If-Match to change it safely, or If-None-Match to get 304',
  location: 'URL of the resource this request created or started',
  link: 'Related pages (rel="next" is the next page of the collection)',
  'x-request-id': 'Identifies this request in the runtime log and in problem details',
  'idempotent-replayed': 'This answer was replayed for a repeated Idempotency-Key; nothing was done again',
  'retry-after': 'Seconds to wait before retrying',
  allow: 'Methods this resource supports',
  'www-authenticate': 'Why authentication failed',
  'content-disposition': 'A file to save',
}

export interface HistoryEntry {
  at: string
  operationId: string
  method: string
  url: string
  headers: Record<string, string>
  body?: string
  status: number
  durationMs: number
}

const HISTORY_KEY = 'oc.apiConsole.history'
export const HISTORY_SIZE = 25

/** The request history, newest first; tolerates missing or corrupt storage. */
export function loadHistory(storage: Pick<Storage, 'getItem'> | undefined): HistoryEntry[] {
  try {
    const raw = storage?.getItem(HISTORY_KEY)
    const parsed = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

/** Adds an entry, newest first, keeping HISTORY_SIZE. Authorization headers are never stored. */
export function addToHistory(storage: Pick<Storage, 'getItem' | 'setItem'> | undefined, entry: HistoryEntry): HistoryEntry[] {
  const headers = Object.fromEntries(Object.entries(entry.headers).filter(([name]) => name.toLowerCase() !== 'authorization'))
  const history = [{ ...entry, headers }, ...loadHistory(storage)].slice(0, HISTORY_SIZE)
  try {
    storage?.setItem(HISTORY_KEY, JSON.stringify(history))
  } catch {
    // storage may be full or blocked; the history then lives for this page only
  }
  return history
}

export const METHOD_STYLE: Record<string, string> = {
  GET: 'text-emerald-400 bg-emerald-500/10 border-emerald-500/30',
  POST: 'text-sky-400 bg-sky-500/10 border-sky-500/30',
  PUT: 'text-amber-400 bg-amber-500/10 border-amber-500/30',
  PATCH: 'text-violet-400 bg-violet-500/10 border-violet-500/30',
  DELETE: 'text-rose-400 bg-rose-500/10 border-rose-500/30',
}

export function statusStyle(status: number): string {
  if (status >= 200 && status < 300) return 'text-emerald-400 bg-emerald-500/10 border-emerald-500/30'
  if (status >= 300 && status < 400) return 'text-sky-400 bg-sky-500/10 border-sky-500/30'
  if (status >= 400 && status < 500) return 'text-amber-400 bg-amber-500/10 border-amber-500/30'
  return 'text-rose-400 bg-rose-500/10 border-rose-500/30'
}
