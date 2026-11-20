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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { BookOpen, Check, ChevronDown, ChevronRight, Clock, Copy, Download, History, KeyRound, Loader2, Play, Plus, RefreshCw,
  Search, ShieldCheck, Trash2, X } from 'lucide-react'
import { anonymousConsoleApi, consoleApi } from '../lib/consoleClient'
import { useAuth } from '../lib/useAuth'
import {
  addToHistory, buildUrl, filterOperations, matchPath, formatBytes, groupByTag, isProblem, isTextual, jsonError, loadHistory, METHOD_STYLE,
  missingRequired, newIdempotencyKey, NOTABLE_HEADERS, parseLink, parseOperations, prettyBody, statusStyle, toCurl,
  type ApiOperation, type HistoryEntry, type OpenApiDocument,
} from '../lib/apiConsole'

const OPENAPI_URL = '/v3/api-docs/v1'
const BODY_METHODS = ['POST', 'PUT', 'PATCH']

interface HeaderRow { name: string; value: string; enabled: boolean }

interface ConsoleResponse {
  status: number
  statusText: string
  durationMs: number
  headers: Record<string, string>
  contentType: string
  bytes: ArrayBuffer
  text: string | null
  json: unknown
}

const decoder = new TextDecoder()

const safeStorage = (): Storage | undefined => {
  try {
    return window.localStorage
  } catch {
    return undefined
  }
}

function CopyButton({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [done, setDone] = useState(false)
  return (
    <button
      type="button"
      onClick={() => { navigator.clipboard?.writeText(text).then(() => { setDone(true); setTimeout(() => setDone(false), 1500) }) }}
      className="flex items-center gap-1 px-2 py-1 text-xs rounded border border-border hover:bg-secondary"
      title={label}
    >
      {done ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
      {done ? 'Copied' : label}
    </button>
  )
}

function MethodBadge({ method }: { method: string }) {
  return (
    <span className={`inline-block min-w-[52px] text-center text-[10px] font-bold px-1.5 py-0.5 rounded border ${METHOD_STYLE[method] ?? ''}`}>
      {method}
    </span>
  )
}

/**
 * The API console: try every Platform API operation from the browser, with the signed-in user's token, and inspect
 * what comes back, including the REST mechanics (ETags, cursors, idempotency, problem details).
 */
export default function ApiConsole() {
  const [doc, setDoc] = useState<OpenApiDocument | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({})
  const [selected, setSelected] = useState<ApiOperation | null>(null)
  const [values, setValues] = useState<Record<string, string>>({})
  const [headers, setHeaders] = useState<HeaderRow[]>([])
  const [body, setBody] = useState('')
  const [contentType, setContentType] = useState('application/json')
  const [tokenWanted, setTokenWanted] = useState(true)
  // Without sign-in there is no token to send, so the requests (and their curl) go without one.
  const signedIn = useAuth().status === 'signed-in'
  const sendToken = signedIn && tokenWanted
  const [sending, setSending] = useState(false)
  const [response, setResponse] = useState<ConsoleResponse | null>(null)
  const [requestError, setRequestError] = useState<string | null>(null)
  const [tab, setTab] = useState<'body' | 'headers' | 'rest' | 'curl' | 'history'>('body')
  const [history, setHistory] = useState<HistoryEntry[]>(() => loadHistory(safeStorage()))
  const [showDocs, setShowDocs] = useState(true)
  const sendRef = useRef<() => void>(() => {})

  const [reloads, setReloads] = useState(0)

  useEffect(() => {
    let cancelled = false
    consoleApi.get(OPENAPI_URL)
      .then((res) => {
        if (res.status !== 200) throw new Error(`${OPENAPI_URL} answered ${res.status}`)
        if (!cancelled) setDoc(JSON.parse(decoder.decode(res.data as ArrayBuffer)))
      })
      .catch((e) => { if (!cancelled) setLoadError(e instanceof Error ? e.message : String(e)) })
    return () => { cancelled = true }
  }, [reloads])

  const loadDoc = () => {
    setLoadError(null)
    setReloads((n) => n + 1)
  }

  const operations = useMemo(() => parseOperations(doc), [doc])
  const groups = useMemo(() => groupByTag(filterOperations(operations, search), doc?.tags ?? []), [operations, search, doc])

  const select = useCallback((operation: ApiOperation, preset?: Partial<HistoryEntry>) => {
    setSelected(operation)
    setResponse(null)
    setRequestError(null)
    setTab('body')
    const nextValues: Record<string, string> = {}
    const rows: HeaderRow[] = [{ name: 'Accept', value: 'application/json, application/problem+json', enabled: true }]
    let nextBody = ''
    let type = 'application/json'
    if (operation.body) {
      type = operation.body.contentTypes.find((t) => t.includes('json')) ?? operation.body.contentTypes[0]
      nextBody = operation.body.example !== undefined ? JSON.stringify(operation.body.example, null, 2) : ''
    }
    if (preset?.url) {
      // Restore a request from the history: query values and path values from its URL.
      const url = new URL(preset.url, window.location.origin)
      url.searchParams.forEach((value, name) => { nextValues[`query:${name}`] = value })
      const pathValues = matchPath(operation.path, url.pathname) ?? {}
      Object.entries(pathValues).forEach(([name, value]) => { nextValues[`path:${name}`] = value })
      for (const [name, value] of Object.entries(preset.headers ?? {})) {
        if (!['accept', 'content-type'].includes(name.toLowerCase())) rows.push({ name, value, enabled: true })
      }
      if (preset.body !== undefined) nextBody = preset.body
    }
    setValues(nextValues)
    setHeaders(rows)
    setBody(nextBody)
    setContentType(type)
  }, [])

  const setValue = (key: string, value: string) => setValues((v) => ({ ...v, [key]: value }))

  const setHeader = (name: string, value: string) => setHeaders((rows) => {
    const index = rows.findIndex((r) => r.name.toLowerCase() === name.toLowerCase())
    if (index >= 0) return rows.map((r, i) => (i === index ? { ...r, value, enabled: true } : r))
    return [...rows, { name, value, enabled: true }]
  })

  const request = useMemo(() => {
    if (!selected) return null
    const pathValues: Record<string, string> = {}
    const queryValues: Record<string, string> = {}
    const requestHeaders: Record<string, string> = {}
    for (const p of selected.parameters) {
      const value = values[`${p.in}:${p.name}`] ?? ''
      if (p.in === 'path') pathValues[p.name] = value
      if (p.in === 'query') queryValues[p.name] = value
      if (p.in === 'header' && value) requestHeaders[p.name] = value
    }
    for (const [key, value] of Object.entries(values)) {
      if (key.startsWith('query:') && !(key.slice(6) in queryValues)) queryValues[key.slice(6)] = value
    }
    for (const row of headers) {
      if (row.enabled && row.name.trim()) requestHeaders[row.name.trim()] = row.value
    }
    const hasBody = BODY_METHODS.includes(selected.method) && !!selected.body
    if (hasBody) requestHeaders['Content-Type'] = contentType
    return {
      method: selected.method,
      url: buildUrl(selected.path, pathValues, queryValues),
      headers: requestHeaders,
      body: hasBody ? body : undefined,
    }
  }, [selected, values, headers, body, contentType])

  const missing = selected ? missingRequired(selected, values) : []
  const bodyProblem = request?.body && contentType.includes('json') ? jsonError(request.body) : null

  const send = useCallback(async (override?: { url?: string }) => {
    if (!selected || !request) return
    setSending(true)
    setRequestError(null)
    const url = override?.url ?? request.url
    const started = performance.now()
    try {
      const client = sendToken ? consoleApi : anonymousConsoleApi
      const res = await client.request({ method: request.method, url, headers: request.headers, data: request.body })
      const durationMs = Math.round(performance.now() - started)
      const responseHeaders: Record<string, string> = {}
      Object.entries(res.headers ?? {}).forEach(([name, value]) => { responseHeaders[name.toLowerCase()] = String(value) })
      const type = responseHeaders['content-type'] ?? ''
      const bytes = (res.data as ArrayBuffer) ?? new ArrayBuffer(0)
      const text = isTextual(type) ? decoder.decode(bytes) : null
      let json: unknown = null
      if (text && type.includes('json')) {
        try { json = JSON.parse(text) } catch { json = null }
      }
      setResponse({ status: res.status, statusText: res.statusText, durationMs, headers: responseHeaders, contentType: type, bytes, text, json })
      setTab('body')
      setHistory(addToHistory(safeStorage(), {
        at: new Date().toISOString(), operationId: selected.id, method: request.method, url, headers: request.headers,
        body: request.body, status: res.status, durationMs,
      }))
    } catch (e) {
      setRequestError(e instanceof Error ? e.message : String(e))
    } finally {
      setSending(false)
    }
  }, [selected, request, sendToken])

  useEffect(() => {
    sendRef.current = () => { if (!missing.length && !bodyProblem && !sending) send() }
  })

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); sendRef.current() }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const download = () => {
    if (!response) return
    const disposition = response.headers['content-disposition'] ?? ''
    const name = disposition.match(/filename="?([^";]+)"?/)?.[1] ?? 'response.bin'
    const url = URL.createObjectURL(new Blob([response.bytes], { type: response.contentType || 'application/octet-stream' }))
    const link = document.createElement('a')
    link.href = url
    link.download = name
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  const followLocation = (location: string) => {
    const path = new URL(location, window.location.origin).pathname
    const target = operations.find((o) => o.method === 'GET' && matchPath(o.path, path) !== null)
    if (target) select(target, { url: path, headers: {} })
  }

  const nextCursor = (response?.json as { page?: { nextCursor?: string } } | null)?.page?.nextCursor
  const links = parseLink(response?.headers['link'])

  if (loadError) {
    return (
      <div className="card-container max-w-2xl">
        <h1 className="text-xl font-bold mb-2">API Console</h1>
        <p className="text-red-400 text-sm mb-4">Could not load the API description: {loadError}</p>
        <button onClick={loadDoc} className="btn-secondary flex items-center gap-2"><RefreshCw className="w-4 h-4" />Retry</button>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4 h-[calc(100vh-8rem)]">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">API Console</h1>
          <p className="text-muted text-sm">
            Try every Platform API operation and inspect the answer. Requests use your sign-in when there is one; the API description is
            at <a className="underline" href={OPENAPI_URL} target="_blank" rel="noreferrer">{OPENAPI_URL}</a>.
          </p>
        </div>
        {signedIn ? (
          <label className="flex items-center gap-2 text-sm cursor-pointer select-none" title="Send requests with your access token">
            <input type="checkbox" checked={tokenWanted} onChange={(e) => setTokenWanted(e.target.checked)} />
            <ShieldCheck className="w-4 h-4" /> Send my token
          </label>
        ) : (
          <span className="text-sm text-muted" title="The runtime has no sign-in configured">Sign-in is off: requests carry no token</span>
        )}
      </div>

      <div className="flex-1 min-h-0 grid grid-cols-1 lg:grid-cols-[300px_1fr] gap-4">
        {/* Operation explorer */}
        <aside className="card-container !p-0 flex flex-col min-h-0">
          <div className="p-3 border-b border-border relative">
            <Search className="absolute left-5 top-1/2 -translate-y-1/2 w-4 h-4 text-muted" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search operations"
              className="w-full pl-8 pr-2 py-1.5 bg-background border border-border rounded text-sm"
              aria-label="Search operations"
            />
          </div>
          <div className="flex-1 overflow-y-auto custom-scrollbar p-2">
            {!doc && <p className="text-muted text-sm p-3 flex items-center gap-2"><Loader2 className="w-4 h-4 animate-spin" />Loading…</p>}
            {groups.map((group) => (
              <div key={group.tag} className="mb-1">
                <button
                  className="w-full flex items-center gap-1 px-2 py-1.5 text-xs font-semibold uppercase tracking-wide text-muted hover:text-foreground"
                  onClick={() => setCollapsed((c) => ({ ...c, [group.tag]: !c[group.tag] }))}
                  title={group.description}
                >
                  {collapsed[group.tag] ? <ChevronRight className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
                  {group.tag} <span className="ml-auto font-normal">{group.operations.length}</span>
                </button>
                {!collapsed[group.tag] && group.operations.map((operation) => (
                  <button
                    key={operation.id}
                    onClick={() => select(operation)}
                    className={`w-full text-left flex items-start gap-2 px-2 py-1.5 rounded text-xs hover:bg-secondary ${selected?.id === operation.id ? 'bg-primary/10 border border-primary/20' : ''}`}
                    title={operation.summary}
                  >
                    <MethodBadge method={operation.method} />
                    <span className="min-w-0">
                      <span className="block truncate">{operation.summary}</span>
                      <span className="block truncate font-mono text-[10px] text-muted">{operation.path.replace('/api/v1', '') || '/'}</span>
                    </span>
                  </button>
                ))}
              </div>
            ))}
          </div>
        </aside>

        {/* Request and response */}
        <section className="min-h-0 overflow-y-auto custom-scrollbar flex flex-col gap-4">
          {!selected && (
            <div className="card-container text-muted text-sm">
              Pick an operation on the left. Its documentation, an example request and a curl command appear here; send it
              with the button or Ctrl+Enter.
            </div>
          )}

          {selected && request && (
            <>
              <div className="card-container flex flex-col gap-4">
                <div className="flex items-center gap-2">
                  <MethodBadge method={selected.method} />
                  <code className="flex-1 min-w-0 truncate px-2 py-1.5 bg-background border border-border rounded text-sm" title={request.url}>
                    {request.url}
                  </code>
                  <button
                    onClick={() => send()}
                    disabled={sending || missing.length > 0 || !!bodyProblem}
                    className="btn-primary flex items-center gap-2 disabled:opacity-40"
                    title={missing.length ? `Missing: ${missing.join(', ')}` : 'Send (Ctrl+Enter)'}
                  >
                    {sending ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}Send
                  </button>
                </div>

                <div>
                  <button className="flex items-center gap-1 text-sm font-semibold" onClick={() => setShowDocs((s) => !s)}>
                    {showDocs ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
                    <BookOpen className="w-4 h-4" /> {selected.summary}
                    <span className="font-mono text-xs text-muted ml-2">{selected.id}</span>
                  </button>
                  {showDocs && (
                    <div className="mt-2 text-sm text-muted space-y-2">
                      <p className="whitespace-pre-line">{selected.description}</p>
                      <div className="flex flex-wrap gap-1">
                        {selected.responses.map((r) => (
                          <span key={r.code} className={`text-[11px] px-1.5 py-0.5 rounded border ${statusStyle(Number(r.code))}`} title={r.description}>
                            {r.code}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}
                </div>

                {selected.parameters.length > 0 && (
                  <div>
                    <h3 className="text-xs font-semibold uppercase text-muted mb-2">Parameters</h3>
                    <div className="grid gap-2">
                      {selected.parameters.map((p) => {
                        const key = `${p.in}:${p.name}`
                        return (
                          <div key={key} className="grid grid-cols-1 md:grid-cols-[200px_1fr] gap-1 md:gap-3 items-start">
                            <label className="text-sm font-mono pt-1.5" htmlFor={key}>
                              {p.name}{p.required && <span className="text-red-400">*</span>}
                              <span className="ml-1 text-[10px] text-muted font-sans">{p.in}</span>
                            </label>
                            <div>
                              <div className="flex gap-2">
                                {p.enumValues ? (
                                  <select id={key} value={values[key] ?? ''} onChange={(e) => setValue(key, e.target.value)}
                                    className="flex-1 px-2 py-1.5 bg-background border border-border rounded text-sm">
                                    <option value="">—</option>
                                    {p.enumValues.map((v) => <option key={v} value={v}>{v}</option>)}
                                  </select>
                                ) : (
                                  <input id={key} value={values[key] ?? ''} onChange={(e) => setValue(key, e.target.value)}
                                    className="flex-1 px-2 py-1.5 bg-background border border-border rounded text-sm font-mono" />
                                )}
                                {p.name === 'Idempotency-Key' && (
                                  <button onClick={() => setValue(key, newIdempotencyKey())} className="px-2 text-xs rounded border border-border hover:bg-secondary"
                                    title="Generate a new key">
                                    <KeyRound className="w-4 h-4" />
                                  </button>
                                )}
                              </div>
                              {p.description && <p className="text-xs text-muted mt-1">{p.description}</p>}
                            </div>
                          </div>
                        )
                      })}
                    </div>
                  </div>
                )}

                <div>
                  <div className="flex items-center justify-between mb-2">
                    <h3 className="text-xs font-semibold uppercase text-muted">Headers</h3>
                    <div className="flex flex-wrap gap-1">
                      <button className="px-2 py-1 text-xs rounded border border-border hover:bg-secondary"
                        onClick={() => setHeader('X-Request-Id', `console-${Date.now().toString(36)}`)}>+ X-Request-Id</button>
                      {selected.method === 'POST' && !selected.parameters.some((p) => p.name === 'Idempotency-Key') && (
                        <button className="px-2 py-1 text-xs rounded border border-border hover:bg-secondary"
                          onClick={() => setHeader('Idempotency-Key', newIdempotencyKey())}>+ Idempotency-Key</button>
                      )}
                      <button className="px-2 py-1 text-xs rounded border border-border hover:bg-secondary flex items-center gap-1"
                        onClick={() => setHeaders((rows) => [...rows, { name: '', value: '', enabled: true }])}>
                        <Plus className="w-3 h-3" />Header
                      </button>
                    </div>
                  </div>
                  <div className="grid gap-1">
                    {headers.map((row, i) => (
                      <div key={i} className="flex gap-2 items-center">
                        <input type="checkbox" checked={row.enabled} aria-label="Send this header"
                          onChange={(e) => setHeaders((rows) => rows.map((r, j) => (j === i ? { ...r, enabled: e.target.checked } : r)))} />
                        <input value={row.name} placeholder="Name"
                          onChange={(e) => setHeaders((rows) => rows.map((r, j) => (j === i ? { ...r, name: e.target.value } : r)))}
                          className="w-48 px-2 py-1 bg-background border border-border rounded text-sm font-mono" />
                        <input value={row.value} placeholder="Value"
                          onChange={(e) => setHeaders((rows) => rows.map((r, j) => (j === i ? { ...r, value: e.target.value } : r)))}
                          className="flex-1 px-2 py-1 bg-background border border-border rounded text-sm font-mono" />
                        <button onClick={() => setHeaders((rows) => rows.filter((_, j) => j !== i))} aria-label="Remove header"
                          className="p-1 rounded hover:bg-secondary"><Trash2 className="w-4 h-4" /></button>
                      </div>
                    ))}
                    {sendToken && <p className="text-xs text-muted">Authorization: Bearer … is added from your sign-in.</p>}
                  </div>
                </div>

                {BODY_METHODS.includes(selected.method) && selected.body && (
                  <div>
                    <div className="flex items-center justify-between mb-2">
                      <h3 className="text-xs font-semibold uppercase text-muted">Body</h3>
                      <div className="flex items-center gap-2">
                        {selected.body.contentTypes.length > 1 && (
                          <select value={contentType} onChange={(e) => setContentType(e.target.value)}
                            className="px-2 py-1 bg-background border border-border rounded text-xs">
                            {selected.body.contentTypes.map((t) => <option key={t} value={t}>{t}</option>)}
                          </select>
                        )}
                        <span className="text-xs text-muted">{contentType}</span>
                        {selected.body.example !== undefined && (
                          <button className="px-2 py-1 text-xs rounded border border-border hover:bg-secondary"
                            onClick={() => setBody(JSON.stringify(selected.body!.example, null, 2))}>Reset to example</button>
                        )}
                      </div>
                    </div>
                    {contentType.startsWith('multipart/') ? (
                      <p className="text-sm text-muted">File uploads are best tried with curl; see the curl tab after you send, or the code sample below.</p>
                    ) : (
                      <textarea value={body} onChange={(e) => setBody(e.target.value)} spellCheck={false} rows={Math.min(18, Math.max(6, body.split('\n').length + 1))}
                        className={`w-full px-3 py-2 bg-background border rounded text-sm font-mono ${bodyProblem ? 'border-red-500/60' : 'border-border'}`}
                        aria-label="Request body" />
                    )}
                    {bodyProblem && <p className="text-xs text-red-400 mt-1">Not valid JSON: {bodyProblem}</p>}
                  </div>
                )}
                {missing.length > 0 && <p className="text-xs text-amber-400">Required: {missing.join(', ')}</p>}
              </div>

              {(response || requestError) && (
                <div className="card-container flex flex-col gap-3">
                  {requestError && <p className="text-red-400 text-sm">The request could not be sent: {requestError}</p>}
                  {response && (
                    <>
                      <div className="flex flex-wrap items-center gap-3 text-sm">
                        <span className={`px-2 py-0.5 rounded border font-semibold ${statusStyle(response.status)}`}>
                          {response.status} {response.statusText}
                        </span>
                        <span className="flex items-center gap-1 text-muted"><Clock className="w-4 h-4" />{response.durationMs} ms</span>
                        <span className="text-muted">{formatBytes(response.bytes.byteLength)}</span>
                        <span className="text-muted font-mono text-xs">{response.contentType || 'no body'}</span>
                        {response.headers['x-request-id'] && (
                          <span className="text-muted text-xs font-mono ml-auto">request {response.headers['x-request-id']}</span>
                        )}
                      </div>
                      <div className="flex gap-1 border-b border-border">
                        {(['body', 'headers', 'rest', 'curl', 'history'] as const).map((t) => (
                          <button key={t} onClick={() => setTab(t)}
                            className={`px-3 py-1.5 text-sm capitalize border-b-2 -mb-px ${tab === t ? 'border-primary text-foreground' : 'border-transparent text-muted hover:text-foreground'}`}>
                            {t === 'rest' ? 'REST' : t}
                          </button>
                        ))}
                      </div>

                      {tab === 'body' && (
                        <div className="flex flex-col gap-3">
                          {isProblem(response.contentType, response.json) && (
                            <ProblemCard problem={response.json as Problem} />
                          )}
                          {response.text === null ? (
                            <div className="flex items-center gap-3 text-sm">
                              <span className="text-muted">Binary content ({formatBytes(response.bytes.byteLength)}).</span>
                              <button onClick={download} className="btn-secondary flex items-center gap-2"><Download className="w-4 h-4" />Download</button>
                            </div>
                          ) : response.text ? (
                            <div className="relative">
                              <div className="absolute right-2 top-2 flex gap-1">
                                <CopyButton text={prettyBody(response.text, response.contentType)} />
                                {response.headers['content-disposition'] && (
                                  <button onClick={download} className="flex items-center gap-1 px-2 py-1 text-xs rounded border border-border hover:bg-secondary">
                                    <Download className="w-3.5 h-3.5" />Save
                                  </button>
                                )}
                              </div>
                              <pre className="max-h-[480px] overflow-auto custom-scrollbar bg-slate-950 border border-border rounded p-3 text-xs font-mono whitespace-pre">
                                {prettyBody(response.text, response.contentType)}
                              </pre>
                            </div>
                          ) : (
                            <p className="text-sm text-muted">No body.</p>
                          )}
                        </div>
                      )}

                      {tab === 'headers' && (
                        <div className="overflow-x-auto">
                          <table className="w-full text-sm">
                            <tbody>
                              {Object.entries(response.headers).sort(([a], [b]) => a.localeCompare(b)).map(([name, value]) => (
                                <tr key={name} className="border-b border-border/50 align-top">
                                  <td className={`py-1.5 pr-4 font-mono whitespace-nowrap ${NOTABLE_HEADERS[name] ? 'text-primary' : 'text-muted'}`}>{name}</td>
                                  <td className="py-1.5 font-mono break-all">
                                    {value}
                                    {NOTABLE_HEADERS[name] && <div className="font-sans text-xs text-muted">{NOTABLE_HEADERS[name]}</div>}
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}

                      {tab === 'rest' && (
                        <RestDetails
                          response={response}
                          links={links}
                          nextCursor={nextCursor}
                          onUseIfMatch={(etag) => setHeader('If-Match', etag)}
                          onUseIfNoneMatch={(etag) => setHeader('If-None-Match', etag)}
                          onFollow={followLocation}
                          onNextPage={(cursor) => { setValue('query:cursor', cursor); setTimeout(() => sendRef.current(), 0) }}
                          supportsCursor={selected.parameters.some((p) => p.name === 'cursor')}
                        />
                      )}

                      {tab === 'curl' && (
                        <CodeBlock text={toCurl(request, window.location.origin, sendToken)}
                          note={`${sendToken ? 'Set OC_TOKEN to your access token. ' : ''}The command repeats this request exactly.`} />
                      )}

                      {tab === 'history' && (
                        <HistoryList history={history} operations={operations} onRestore={(op, entry) => select(op, entry)}
                          onClear={() => { try { safeStorage()?.removeItem('oc.apiConsole.history') } catch { /* ignore */ } setHistory([]) }} />
                      )}
                    </>
                  )}
                </div>
              )}

              {!response && history.length > 0 && (
                <div className="card-container">
                  <HistoryList history={history} operations={operations} onRestore={(op, entry) => select(op, entry)}
                    onClear={() => { try { safeStorage()?.removeItem('oc.apiConsole.history') } catch { /* ignore */ } setHistory([]) }} />
                </div>
              )}

              {selected.codeSample && (
                <div className="card-container">
                  <h3 className="text-xs font-semibold uppercase text-muted mb-2">Code sample</h3>
                  <CodeBlock text={selected.codeSample} note="From the API description. $OC_URL is the runtime's address." />
                </div>
              )}
            </>
          )}
        </section>
      </div>
    </div>
  )
}

function CodeBlock({ text, note }: { text: string; note?: string }) {
  return (
    <div>
      <div className="relative">
        <div className="absolute right-2 top-2"><CopyButton text={text} /></div>
        <pre className="overflow-auto custom-scrollbar bg-slate-950 border border-border rounded p-3 text-xs font-mono whitespace-pre">{text}</pre>
      </div>
      {note && <p className="text-xs text-muted mt-1">{note}</p>}
    </div>
  )
}

interface Problem {
  type?: string
  title?: string
  status?: number
  detail?: string
  errors?: { field: string; message: string }[]
  [extension: string]: unknown
}

function ProblemCard({ problem }: { problem: Problem }) {
  if (!problem) return null
  return (
    <div className="border border-amber-500/30 bg-amber-500/5 rounded p-3 text-sm">
      <div className="flex flex-wrap items-baseline gap-2">
        <span className="font-semibold text-amber-300">{problem.title}</span>
        <span className="font-mono text-xs text-muted">{problem.type}</span>
      </div>
      {problem.detail && <p className="mt-1">{problem.detail}</p>}
      {Array.isArray(problem.errors) && problem.errors.length > 0 && (
        <table className="mt-2 text-xs">
          <tbody>
            {problem.errors.map((e, i) => (
              <tr key={i}><td className="pr-3 font-mono text-amber-200">{e.field}</td><td>{e.message}</td></tr>
            ))}
          </tbody>
        </table>
      )}
      <div className="mt-2 flex flex-wrap gap-3 text-xs text-muted">
        {Object.entries(problem).filter(([k]) => !['type', 'title', 'status', 'detail', 'errors'].includes(k)).map(([k, v]) => (
          <span key={k}><span className="font-mono">{k}</span>: {typeof v === 'object' ? JSON.stringify(v) : String(v)}</span>
        ))}
      </div>
    </div>
  )
}

function RestDetails({ response, links, nextCursor, onUseIfMatch, onUseIfNoneMatch, onFollow, onNextPage, supportsCursor }: {
  response: ConsoleResponse
  links: Record<string, string>
  nextCursor?: string
  onUseIfMatch: (etag: string) => void
  onUseIfNoneMatch: (etag: string) => void
  onFollow: (location: string) => void
  onNextPage: (cursor: string) => void
  supportsCursor: boolean
}) {
  const h = response.headers
  const rows: { label: string; value: string; detail: string; action?: React.ReactNode }[] = []
  if (h['etag']) rows.push({
    label: 'ETag', value: h['etag'], detail: 'The version you read. Send it back to change the resource only if nobody else did (412 otherwise).',
    action: (
      <div className="flex gap-1">
        <button className="px-2 py-1 text-xs rounded border border-border hover:bg-secondary" onClick={() => onUseIfMatch(h['etag'])}>Use as If-Match</button>
        <button className="px-2 py-1 text-xs rounded border border-border hover:bg-secondary" onClick={() => onUseIfNoneMatch(h['etag'])}>Use as If-None-Match</button>
      </div>
    ),
  })
  if (response.status === 304) rows.push({ label: '304 Not Modified', value: '', detail: 'Your copy is current: If-None-Match matched the ETag.' })
  if (h['location']) rows.push({
    label: 'Location', value: h['location'], detail: 'The resource this request created or started.',
    action: <button className="px-2 py-1 text-xs rounded border border-border hover:bg-secondary" onClick={() => onFollow(h['location'])}>Open it</button>,
  })
  if (nextCursor || links.next) rows.push({
    label: 'Next page', value: nextCursor ?? links.next, detail: 'The collection continues: page.nextCursor and Link: rel="next".',
    action: supportsCursor && nextCursor
      ? <button className="px-2 py-1 text-xs rounded border border-border hover:bg-secondary" onClick={() => onNextPage(nextCursor)}>Load next page</button>
      : undefined,
  })
  if (h['idempotent-replayed']) rows.push({ label: 'Idempotent replay', value: h['idempotent-replayed'], detail: 'Same Idempotency-Key and body as before: this is the first answer again; nothing was done twice.' })
  if (h['retry-after']) rows.push({ label: 'Retry-After', value: h['retry-after'], detail: 'Wait this many seconds before retrying.' })
  if (h['allow']) rows.push({ label: 'Allow', value: h['allow'], detail: 'The methods this resource supports.' })
  if (h['www-authenticate']) rows.push({ label: 'WWW-Authenticate', value: h['www-authenticate'], detail: 'Authentication is missing or invalid; sign in again or check the token.' })
  if (h['x-request-id']) rows.push({ label: 'Request id', value: h['x-request-id'], detail: 'Quote it when reporting a problem: it finds this request in the runtime log.' })
  if (rows.length === 0) return <p className="text-sm text-muted">No ETag, Location, paging, idempotency or retry information in this answer.</p>
  return (
    <div className="grid gap-2">
      {rows.map((row) => (
        <div key={row.label} className="border border-border rounded p-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-semibold text-sm">{row.label}</span>
            {row.value && <code className="text-xs break-all">{row.value}</code>}
            <span className="ml-auto">{row.action}</span>
          </div>
          <p className="text-xs text-muted mt-1">{row.detail}</p>
        </div>
      ))}
    </div>
  )
}

function HistoryList({ history, operations, onRestore, onClear }: {
  history: HistoryEntry[]
  operations: ApiOperation[]
  onRestore: (operation: ApiOperation, entry: HistoryEntry) => void
  onClear: () => void
}) {
  if (history.length === 0) return <p className="text-sm text-muted">No requests yet.</p>
  return (
    <div>
      <div className="flex items-center justify-between mb-2">
        <h3 className="text-xs font-semibold uppercase text-muted flex items-center gap-1"><History className="w-3.5 h-3.5" />History</h3>
        <button onClick={onClear} className="text-xs text-muted hover:text-foreground flex items-center gap-1"><X className="w-3 h-3" />Clear</button>
      </div>
      <div className="grid gap-1">
        {history.map((entry, i) => {
          const operation = operations.find((o) => o.id === entry.operationId)
          return (
            <button key={i} disabled={!operation} onClick={() => operation && onRestore(operation, entry)}
              className="flex items-center gap-2 text-left text-xs px-2 py-1.5 rounded hover:bg-secondary disabled:opacity-50">
              <MethodBadge method={entry.method} />
              <span className="font-mono truncate flex-1">{entry.url}</span>
              <span className={`px-1.5 rounded border ${statusStyle(entry.status)}`}>{entry.status}</span>
              <span className="text-muted w-16 text-right">{entry.durationMs} ms</span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
