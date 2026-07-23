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
import { useState } from 'react'
import {
  Wand2,
  Plus,
  Trash2,
  Play,
  Copy,
  Check,
  Loader2,
  AlertCircle,
  FileText,
  Database,
  Braces,
  ChevronDown,
  ChevronUp,
  Info,
} from 'lucide-react'
import { narrativizationApi } from '../lib/api'

// ─── Types ────────────────────────────────────────────────────────────────────

type FieldType = 'STRING' | 'DOUBLE' | 'FLOAT' | 'INTEGER' | 'LONG' | 'BOOLEAN' | 'DATE' | 'TIMESTAMP'

interface FieldDto {
  id: string        // local UI key only
  name: string
  type: FieldType
  description: string
}

interface TemplateCopilotResponse {
  template: string
  mockData: Record<string, unknown>
}

const CONNECTOR_TYPES = ['iceberg', 'filesystem', 'alfresco', 'custom'] as const
const FIELD_TYPES: FieldType[] = ['STRING', 'DOUBLE', 'FLOAT', 'INTEGER', 'LONG', 'BOOLEAN', 'DATE', 'TIMESTAMP']

const DEFAULT_FIELDS: FieldDto[] = [
  { id: crypto.randomUUID(), name: 'id',        type: 'STRING',    description: 'Primary record identifier' },
  { id: crypto.randomUUID(), name: 'amount',    type: 'DOUBLE',    description: 'Transaction monetary value' },
  { id: crypto.randomUUID(), name: 'region',    type: 'STRING',    description: 'Geographical region code' },
  { id: crypto.randomUUID(), name: 'timestamp', type: 'TIMESTAMP', description: 'Event timestamp' },
]

// ─── Small helpers ────────────────────────────────────────────────────────────

function Badge({ children, color }: { children: React.ReactNode; color: string }) {
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-mono font-semibold ${color}`}>
      {children}
    </span>
  )
}

function typeColor(t: FieldType) {
  const map: Record<FieldType, string> = {
    STRING:    'bg-blue-500/15 text-blue-400',
    DOUBLE:    'bg-amber-500/15 text-amber-400',
    FLOAT:     'bg-amber-500/15 text-amber-400',
    INTEGER:   'bg-purple-500/15 text-purple-400',
    LONG:      'bg-purple-500/15 text-purple-400',
    BOOLEAN:   'bg-green-500/15 text-green-400',
    DATE:      'bg-cyan-500/15 text-cyan-400',
    TIMESTAMP: 'bg-cyan-500/15 text-cyan-400',
  }
  return map[t] ?? 'bg-slate-500/15 text-slate-400'
}

// ─── Main Component ───────────────────────────────────────────────────────────

export default function Narrativization() {
  const [connectorType, setConnectorType] = useState<string>('iceberg')
  const [fields, setFields] = useState<FieldDto[]>(DEFAULT_FIELDS)
  const [response, setResponse]           = useState<TemplateCopilotResponse | null>(null)
  const [isGenerating, setIsGenerating]   = useState(false)
  const [error, setError]                 = useState<string | null>(null)
  const [copiedTemplate, setCopiedTemplate] = useState(false)
  const [copiedMock, setCopiedMock]         = useState(false)
  const [mockExpanded, setMockExpanded]     = useState(true)
  const [rendered, setRendered]             = useState<string | null>(null)

  // ── Field management ────────────────────────────────────────────────────────

  const addField = () =>
    setFields(prev => [...prev, { id: crypto.randomUUID(), name: '', type: 'STRING', description: '' }])

  const removeField = (id: string) =>
    setFields(prev => prev.filter(f => f.id !== id))

  const updateField = (id: string, patch: Partial<FieldDto>) =>
    setFields(prev => prev.map(f => f.id === id ? { ...f, ...patch } : f))

  // ── API call ────────────────────────────────────────────────────────────────

  const generate = async () => {
    const validFields = fields.filter(f => f.name.trim())
    if (validFields.length === 0) {
      setError('Add at least one field with a name before generating.')
      return
    }
    setIsGenerating(true)
    setError(null)
    setResponse(null)
    setRendered(null)
    try {
      const payload = {
        connectorType,
        fields: validFields.map(({ name, type, description }) => ({ name, type, description })),
      }
      const res = await narrativizationApi.generate(payload)
      setResponse(res.data)
      // Render the template against mock data locally for preview
      if (res.data?.template && res.data?.mockData) {
        setRendered(renderMustache(res.data.template, res.data.mockData))
      }
    } catch (err: any) {
      const msg = err?.response?.data?.message ?? err?.message ?? 'Unknown error'
      setError(`Copilot API error: ${msg}`)
    } finally {
      setIsGenerating(false)
    }
  }

  // ── Local Mustache preview renderer (simple, no sections) ───────────────────

  const renderMustache = (template: string, data: Record<string, unknown>): string =>
    template.replace(/\{\{(\w+)\}\}/g, (_, key) => {
      const val = data[key]
      return val !== undefined ? String(val) : `{{${key}}}`
    })

  // ── Copy helpers ─────────────────────────────────────────────────────────────

  const copyTemplate = async () => {
    if (!response?.template) return
    await navigator.clipboard.writeText(response.template)
    setCopiedTemplate(true)
    setTimeout(() => setCopiedTemplate(false), 2000)
  }

  const copyMock = async () => {
    if (!response?.mockData) return
    await navigator.clipboard.writeText(JSON.stringify(response.mockData, null, 2))
    setCopiedMock(true)
    setTimeout(() => setCopiedMock(false), 2000)
  }

  // ── Render ───────────────────────────────────────────────────────────────────

  return (
    <div className="flex flex-col gap-6">

      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold flex items-center gap-2">
            <Wand2 className="w-6 h-6 text-primary" />
            Auto-Narrativization Copilot
          </h1>
          <p className="text-muted text-sm mt-1">
            Generate Mustache narrative templates and mock datasets from connector schemas using Ollama or OpenAI.
          </p>
        </div>
        <a
          href="https://github.com/opencrawling/opencrawling/wiki/Auto-Narrativization-Copilot"
          target="_blank"
          rel="noreferrer"
          className="flex items-center gap-1.5 text-xs text-muted hover:text-foreground transition-colors"
        >
          <Info className="w-4 h-4" />
          Docs
        </a>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">

        {/* ── LEFT PANEL: Schema Editor ─────────────────────────────────────── */}
        <div className="flex flex-col gap-4">

          {/* Connector type */}
          <div className="bg-card border border-border rounded-xl p-5 flex flex-col gap-4">
            <div className="flex items-center gap-2 font-semibold text-sm">
              <Database className="w-4 h-4 text-primary" />
              Connector Type
            </div>
            <div className="grid grid-cols-2 gap-2">
              {CONNECTOR_TYPES.map(ct => (
                <button
                  key={ct}
                  id={`connector-type-${ct}`}
                  onClick={() => setConnectorType(ct)}
                  className={`
                    px-3 py-2 rounded-lg text-sm font-medium border transition-colors capitalize
                    ${connectorType === ct
                      ? 'bg-primary/10 text-primary border-primary/30'
                      : 'border-border text-muted hover:text-foreground hover:border-border/80'}
                  `}
                >
                  {ct === 'iceberg' && '🧊 '}
                  {ct === 'filesystem' && '📁 '}
                  {ct === 'alfresco' && '🏢 '}
                  {ct === 'custom' && '⚙️ '}
                  {ct}
                </button>
              ))}
            </div>
          </div>

          {/* Field list */}
          <div className="bg-card border border-border rounded-xl p-5 flex flex-col gap-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2 font-semibold text-sm">
                <Braces className="w-4 h-4 text-primary" />
                Schema Fields
                <span className="ml-1 text-xs text-muted font-normal">
                  ({fields.filter(f => f.name.trim()).length} valid)
                </span>
              </div>
              <button
                id="add-field-btn"
                onClick={addField}
                className="flex items-center gap-1.5 text-xs text-primary hover:text-primary/80 transition-colors"
              >
                <Plus className="w-3.5 h-3.5" />
                Add field
              </button>
            </div>

            <div className="flex flex-col gap-2 max-h-80 overflow-y-auto custom-scrollbar pr-1">
              {fields.map((field, idx) => (
                <div
                  key={field.id}
                  className="grid grid-cols-[1fr_auto_1fr_auto] gap-2 items-center group"
                >
                  {/* Name */}
                  <input
                    id={`field-name-${idx}`}
                    type="text"
                    placeholder="fieldName"
                    value={field.name}
                    onChange={e => updateField(field.id, { name: e.target.value })}
                    className="bg-background border border-border rounded-lg px-3 py-1.5 text-sm font-mono
                               focus:outline-none focus:ring-1 focus:ring-primary/50 placeholder:text-muted"
                  />
                  {/* Type */}
                  <select
                    id={`field-type-${idx}`}
                    value={field.type}
                    onChange={e => updateField(field.id, { type: e.target.value as FieldType })}
                    className="bg-background border border-border rounded-lg px-2 py-1.5 text-xs font-mono
                               focus:outline-none focus:ring-1 focus:ring-primary/50 cursor-pointer"
                  >
                    {FIELD_TYPES.map(t => (
                      <option key={t} value={t}>{t}</option>
                    ))}
                  </select>
                  {/* Description */}
                  <input
                    id={`field-desc-${idx}`}
                    type="text"
                    placeholder="description"
                    value={field.description}
                    onChange={e => updateField(field.id, { description: e.target.value })}
                    className="bg-background border border-border rounded-lg px-3 py-1.5 text-sm
                               focus:outline-none focus:ring-1 focus:ring-primary/50 placeholder:text-muted"
                  />
                  {/* Remove */}
                  <button
                    id={`remove-field-${idx}`}
                    onClick={() => removeField(field.id)}
                    className="opacity-0 group-hover:opacity-100 transition-opacity p-1 rounded hover:bg-red-500/10 hover:text-red-400 text-muted"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
              ))}
            </div>

            {/* Preview badges */}
            {fields.some(f => f.name.trim()) && (
              <div className="flex flex-wrap gap-1.5 pt-1 border-t border-border">
                {fields.filter(f => f.name.trim()).map(f => (
                  <Badge key={f.id} color={typeColor(f.type)}>
                    {f.name}: {f.type}
                  </Badge>
                ))}
              </div>
            )}
          </div>

          {/* Generate button */}
          <button
            id="generate-btn"
            onClick={generate}
            disabled={isGenerating}
            className="
              flex items-center justify-center gap-2 px-5 py-3 rounded-xl font-semibold
              bg-primary text-primary-foreground hover:bg-primary/90 active:scale-[0.98]
              transition-all disabled:opacity-50 disabled:cursor-not-allowed
            "
          >
            {isGenerating
              ? <><Loader2 className="w-4 h-4 animate-spin" /> Generating…</>
              : <><Play className="w-4 h-4" /> Generate Narrative Template</>}
          </button>

          {error && (
            <div className="flex items-start gap-2 p-3 rounded-lg bg-red-500/10 border border-red-500/20 text-red-400 text-sm">
              <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
              {error}
            </div>
          )}
        </div>

        {/* ── RIGHT PANEL: Output ──────────────────────────────────────────── */}
        <div className="flex flex-col gap-4">

          {/* Template output */}
          <div className="bg-card border border-border rounded-xl p-5 flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2 font-semibold text-sm">
                <FileText className="w-4 h-4 text-primary" />
                Mustache Template
              </div>
              {response?.template && (
                <button
                  id="copy-template-btn"
                  onClick={copyTemplate}
                  className="flex items-center gap-1 text-xs text-muted hover:text-foreground transition-colors"
                >
                  {copiedTemplate ? <Check className="w-3.5 h-3.5 text-green-400" /> : <Copy className="w-3.5 h-3.5" />}
                  {copiedTemplate ? 'Copied!' : 'Copy'}
                </button>
              )}
            </div>
            <div className="min-h-[80px] bg-background rounded-lg p-4 font-mono text-sm border border-border relative">
              {!response && !isGenerating && (
                <span className="text-muted italic">
                  Template will appear here after generation…
                </span>
              )}
              {isGenerating && (
                <div className="flex items-center gap-2 text-muted">
                  <Loader2 className="w-4 h-4 animate-spin" />
                  Calling Copilot API…
                </div>
              )}
              {response?.template && (
                <pre className="whitespace-pre-wrap text-primary leading-relaxed">
                  {response.template}
                </pre>
              )}
            </div>
          </div>

          {/* Live render preview */}
          {rendered && (
            <div className="bg-card border border-border rounded-xl p-5 flex flex-col gap-3">
              <div className="flex items-center gap-2 font-semibold text-sm">
                <Wand2 className="w-4 h-4 text-green-400" />
                Rendered Preview
                <span className="text-xs text-muted font-normal">(template + mock data)</span>
              </div>
              <div className="bg-green-500/5 border border-green-500/20 rounded-lg p-4 text-sm text-green-300 leading-relaxed">
                {rendered}
              </div>
            </div>
          )}

          {/* Mock data output */}
          <div className="bg-card border border-border rounded-xl p-5 flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <button
                className="flex items-center gap-2 font-semibold text-sm"
                onClick={() => setMockExpanded(p => !p)}
              >
                <Braces className="w-4 h-4 text-primary" />
                Mock Dataset
                {mockExpanded
                  ? <ChevronUp className="w-3.5 h-3.5 text-muted" />
                  : <ChevronDown className="w-3.5 h-3.5 text-muted" />}
              </button>
              {response?.mockData && (
                <button
                  id="copy-mock-btn"
                  onClick={copyMock}
                  className="flex items-center gap-1 text-xs text-muted hover:text-foreground transition-colors"
                >
                  {copiedMock ? <Check className="w-3.5 h-3.5 text-green-400" /> : <Copy className="w-3.5 h-3.5" />}
                  {copiedMock ? 'Copied!' : 'Copy JSON'}
                </button>
              )}
            </div>

            {mockExpanded && (
              <div className="min-h-[80px] bg-background rounded-lg p-4 font-mono text-xs border border-border overflow-x-auto">
                {!response && !isGenerating && (
                  <span className="text-muted italic">Mock data will appear here…</span>
                )}
                {isGenerating && (
                  <div className="flex items-center gap-2 text-muted">
                    <Loader2 className="w-4 h-4 animate-spin" />
                    Generating…
                  </div>
                )}
                {response?.mockData && (
                  <table className="w-full text-left border-collapse">
                    <thead>
                      <tr className="text-muted border-b border-border">
                        <th className="pb-2 pr-8 font-medium">Field</th>
                        <th className="pb-2 pr-8 font-medium">Type</th>
                        <th className="pb-2 font-medium">Mock Value</th>
                      </tr>
                    </thead>
                    <tbody>
                      {Object.entries(response.mockData).map(([key, val]) => {
                        const fieldDef = fields.find(f => f.name === key)
                        return (
                          <tr key={key} className="border-b border-border/40 last:border-0">
                            <td className="py-1.5 pr-8 text-foreground font-semibold">{key}</td>
                            <td className="py-1.5 pr-8">
                              {fieldDef && <Badge color={typeColor(fieldDef.type)}>{fieldDef.type}</Badge>}
                            </td>
                            <td className="py-1.5 text-amber-300">
                              {typeof val === 'string' ? `"${val}"` : String(val)}
                            </td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                )}
              </div>
            )}
          </div>

          {/* How it works hint */}
          <div className="flex items-start gap-3 p-4 rounded-xl bg-primary/5 border border-primary/15 text-xs text-muted">
            <Info className="w-4 h-4 text-primary shrink-0 mt-0.5" />
            <div className="leading-relaxed">
              The Copilot calls <span className="text-foreground font-medium">Ollama</span> (default) or OpenAI via Spring AI.
              If no AI model is available it falls back to a <span className="text-foreground font-medium">deterministic generator</span> —
              so you always get a valid template regardless of infrastructure.
              Override the engine with <code className="text-primary">spring.ai.copilot.engine=openai</code>.
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
