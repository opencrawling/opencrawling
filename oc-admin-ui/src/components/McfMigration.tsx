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
import { useState, useEffect, type ComponentType } from 'react'
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts'
import {
  ArrowRightLeft,
  ArrowRight,
  Loader2,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Info,
  HardDrive,
  Server,
  Database,
  Network,
  Search,
  Scissors,
  Plug2,
  RotateCcw,
  Rocket,
  ShieldAlert,
  Download,
  Play,
} from 'lucide-react'
import {
  mcfMigrationApi,
  jobApi,
  type McfMigrationResponse,
  type McfConnectionSummary,
  type McfJobSummary,
  type McfMigrationNote,
} from '../lib/api'

function downloadJson(data: unknown, filename: string) {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

type WizardStep = 'configure' | 'planning' | 'review' | 'applying' | 'results'

const STEPS: { id: WizardStep; label: string }[] = [
  { id: 'configure', label: 'Configure' },
  { id: 'review', label: 'Plan & Review' },
  { id: 'results', label: 'Apply & Results' },
]

function stepIndex(step: WizardStep): number {
  if (step === 'configure') return 0
  if (step === 'planning' || step === 'review') return 1
  return 2
}

interface ClassInfo {
  icon: ComponentType<{ className?: string }>
  color: string
  bg: string
  border: string
}

function classifyConnector(className?: string): ClassInfo {
  const cls = className || ''
  if (cls.includes('filesystem.FileConnector') || cls.includes('FileSystem')) {
    return { icon: HardDrive, color: 'text-blue-400', bg: 'bg-blue-400/10', border: 'border-blue-500/20' }
  }
  if (cls.includes('Alfresco') || cls.includes('alfresco')) {
    return { icon: Server, color: 'text-amber-400', bg: 'bg-amber-400/10', border: 'border-amber-500/20' }
  }
  if (cls.includes('mfiles') || cls.includes('MFiles')) {
    return { icon: Database, color: 'text-purple-400', bg: 'bg-purple-400/10', border: 'border-purple-500/20' }
  }
  if (cls.includes('vespa') || cls.includes('Vespa')) {
    return { icon: Network, color: 'text-emerald-400', bg: 'bg-emerald-400/10', border: 'border-emerald-500/20' }
  }
  if (cls.includes('elasticsearch')) {
    return { icon: Search, color: 'text-green-400', bg: 'bg-green-400/10', border: 'border-green-500/20' }
  }
  if (cls.includes('contentlimiter') || cls.includes('ContentLimiter')) {
    return { icon: Scissors, color: 'text-orange-400', bg: 'bg-orange-400/10', border: 'border-orange-500/20' }
  }
  return { icon: Plug2, color: 'text-slate-400', bg: 'bg-slate-400/10', border: 'border-slate-500/20' }
}

function ConnectorBadge({ className, label }: { className?: string; label: string }) {
  const info = classifyConnector(className)
  const Icon = info.icon
  return (
    <span
      className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium border whitespace-nowrap ${info.bg} ${info.color} ${info.border}`}
    >
      <Icon className="w-3.5 h-3.5" />
      {label}
    </span>
  )
}

function StatusBadge({ supported }: { supported: boolean }) {
  return supported ? (
    <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-green-500/10 text-green-400 border border-green-500/20 whitespace-nowrap">
      <CheckCircle2 className="w-3.5 h-3.5" /> Migrated
    </span>
  ) : (
    <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-slate-500/10 text-slate-400 border border-slate-500/20 whitespace-nowrap">
      <XCircle className="w-3.5 h-3.5" /> Skipped
    </span>
  )
}

const NOTE_STYLES: Record<string, string> = {
  RUNTIME_RISK: 'bg-red-500/10 text-red-400 border-red-500/20',
  SCOPE_CHANGE: 'bg-amber-500/10 text-amber-400 border-amber-500/20',
  DROPPED: 'bg-slate-500/10 text-slate-400 border-slate-500/20',
  DEFAULTED: 'bg-blue-500/10 text-blue-400 border-blue-500/20',
  CONVERTED: 'bg-cyan-500/10 text-cyan-400 border-cyan-500/20',
}

function NoteBadge({ kind }: { kind: string }) {
  return (
    <span
      className={`px-2 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wide border whitespace-nowrap ${
        NOTE_STYLES[kind] ?? NOTE_STYLES.DROPPED
      }`}
    >
      {kind.replace('_', ' ')}
    </span>
  )
}

function NoteRow({ note }: { note: McfMigrationNote }) {
  const isRuntimeRisk = note.kind === 'RUNTIME_RISK'
  const isScopeChange = note.kind === 'SCOPE_CHANGE'
  const borderClass = isRuntimeRisk
    ? 'border-l-red-500 bg-red-500/5'
    : isScopeChange
    ? 'border-l-amber-500 bg-amber-500/5'
    : 'border-l-slate-700 bg-secondary/30'
  return (
    <div className={`flex items-start gap-3 px-3 py-2 rounded-md border-l-2 ${borderClass}`}>
      <NoteBadge kind={note.kind} />
      <div className="text-xs text-muted leading-relaxed">
        <p>
          <span className="text-foreground font-medium">{note.field}</span> — {note.message}
        </p>
        {note.recommendedAction && (
          <p className="mt-1 text-[11px] text-cyan-400/80">→ {note.recommendedAction}</p>
        )}
      </div>
    </div>
  )
}

function ApplyResultBanner({ name, result }: { name: string; result: { success: boolean; detail: string } }) {
  return (
    <div
      className={`flex items-center gap-3 p-3 rounded-lg border ${
        result.success
          ? 'bg-green-500/10 border-green-500/20 text-green-400'
          : 'bg-red-500/10 border-red-500/20 text-red-400'
      }`}
    >
      {result.success ? <CheckCircle2 className="w-5 h-5 flex-shrink-0" /> : <XCircle className="w-5 h-5 flex-shrink-0" />}
      <div className="text-sm">
        <span className="font-bold">{name}</span>
        <span className="text-muted"> — {result.detail}</span>
      </div>
    </div>
  )
}

export default function McfMigration() {
  const [step, setStep] = useState<WizardStep>('configure')
  const [mcfUrl, setMcfUrl] = useState('http://localhost:8345/mcf-api-service/json')
  const [mcfUsername, setMcfUsername] = useState('')
  const [mcfPassword, setMcfPassword] = useState('')
  const [defaultEmbeddingDimensions, setDefaultEmbeddingDimensions] = useState(384)
  const [error, setError] = useState<string | null>(null)
  const [planResult, setPlanResult] = useState<McfMigrationResponse | null>(null)
  const [applyResult, setApplyResult] = useState<McfMigrationResponse | null>(null)
  const [selectedConnections, setSelectedConnections] = useState<Set<string>>(new Set())
  const [selectedJobs, setSelectedJobs] = useState<Set<string>>(new Set())

  const handleAnalyze = async () => {
    setError(null)
    setStep('planning')
    try {
      const res = await mcfMigrationApi.plan({ mcfUrl, mcfUsername, mcfPassword, defaultEmbeddingDimensions })
      setPlanResult(res.data)
      setSelectedConnections(new Set(res.data.connections.filter((c) => c.supported).map((c) => c.name)))
      setSelectedJobs(new Set(res.data.jobs.filter((j) => j.supported).map((j) => j.name)))
      setStep('review')
    } catch (err: any) {
      setError(err?.response?.data?.error || err?.message || 'Failed to reach ManifoldCF.')
      setStep('configure')
    }
  }

  const toggleConnection = (name: string) => {
    setSelectedConnections((prev) => {
      const next = new Set(prev)
      if (next.has(name)) next.delete(name)
      else next.add(name)
      return next
    })
  }

  const toggleJob = (name: string) => {
    setSelectedJobs((prev) => {
      const next = new Set(prev)
      if (next.has(name)) next.delete(name)
      else next.add(name)
      return next
    })
  }

  const handleApply = async () => {
    if (!planResult) return
    // The backend (MigrationOptions.isNameSelected) treats an EMPTY selection list as "no filter —
    // apply everything," matching the CLI's --only-connections/--only-jobs default. That collides
    // with this checkbox UI, where an empty Set means "the user unchecked all of them." Block
    // submission whenever that ambiguity could cause something the user deselected to be silently
    // migrated anyway, rather than silently reinterpreting "none" as "all."
    const migratableConnections = planResult.connections.filter((c) => c.supported).length
    const migratableJobs = planResult.jobs.filter((j) => j.supported).length
    if (selectedConnections.size === 0 && selectedJobs.size === 0) return
    if (migratableConnections > 0 && selectedConnections.size === 0) {
      setError('You deselected every connector. Re-select at least one — an empty selection cannot be told apart from "no filter" and would migrate all of them.')
      return
    }
    if (migratableJobs > 0 && selectedJobs.size === 0) {
      setError('You deselected every job. Re-select at least one — an empty selection cannot be told apart from "no filter" and would migrate all of them.')
      return
    }
    if (!confirm(`Apply this migration to OpenCrawling? This will create/update ${selectedConnections.size} connector(s) and ${selectedJobs.size} job(s).`)) {
      return
    }
    setError(null)
    setStep('applying')
    try {
      const res = await mcfMigrationApi.apply({
        mcfUrl,
        mcfUsername,
        mcfPassword,
        defaultEmbeddingDimensions,
        selectedConnections: Array.from(selectedConnections),
        selectedJobs: Array.from(selectedJobs),
      })
      setApplyResult(res.data)
      setStep('results')
    } catch (err: any) {
      setError(err?.response?.data?.error || err?.message || 'Failed to apply migration.')
      setStep('review')
    }
  }

  const handleReset = () => {
    setPlanResult(null)
    setApplyResult(null)
    setSelectedConnections(new Set())
    setSelectedJobs(new Set())
    setError(null)
    setStep('configure')
  }

  return (
    <div className="space-y-8 animate-in fade-in duration-500 max-w-5xl mx-auto">
      <div>
        <h1 className="text-3xl font-bold tracking-tight flex items-center gap-3">
          <ArrowRightLeft className="w-7 h-7 text-primary" />
          ManifoldCF Migration
        </h1>
        <p className="text-muted mt-1">
          Migrate Apache ManifoldCF repository, output, and transformation connections into OpenCrawling.
          Anything without a direct connector mapping is skipped and clearly reported — never guessed.
        </p>
      </div>

      <StepIndicator current={stepIndex(step)} />

      {error && (
        <div className="flex items-center gap-3 p-4 bg-red-500/10 border border-red-500/20 text-red-400 rounded-lg animate-in fade-in slide-in-from-top-2 duration-300">
          <XCircle className="w-5 h-5 flex-shrink-0" />
          <div>
            <span className="font-bold">Something went wrong.</span> {error}
          </div>
        </div>
      )}

      {(step === 'configure' || step === 'planning') && (
        <ConfigureStep
          mcfUrl={mcfUrl}
          setMcfUrl={setMcfUrl}
          mcfUsername={mcfUsername}
          setMcfUsername={setMcfUsername}
          mcfPassword={mcfPassword}
          setMcfPassword={setMcfPassword}
          defaultEmbeddingDimensions={defaultEmbeddingDimensions}
          setDefaultEmbeddingDimensions={setDefaultEmbeddingDimensions}
          isAnalyzing={step === 'planning'}
          onAnalyze={handleAnalyze}
        />
      )}

      {step === 'review' && planResult && (
        <ReviewStep
          plan={planResult}
          onApply={handleApply}
          selectedConnections={selectedConnections}
          selectedJobs={selectedJobs}
          onToggleConnection={toggleConnection}
          onToggleJob={toggleJob}
        />
      )}

      {step === 'applying' && (
        <div className="card-container flex flex-col items-center justify-center gap-4 py-20 animate-in fade-in duration-300">
          <Loader2 className="w-10 h-10 animate-spin text-primary" />
          <div className="text-center">
            <p className="font-semibold text-lg">Applying migration…</p>
            <p className="text-sm text-muted mt-1">Creating connectors, then jobs, on this OpenCrawling instance.</p>
          </div>
        </div>
      )}

      {step === 'results' && applyResult && (
        <ResultsStep result={applyResult} onReset={handleReset} />
      )}
    </div>
  )
}

function StepIndicator({ current }: { current: number }) {
  return (
    <div className="flex items-center">
      {STEPS.map((s, i) => (
        <div key={s.id} className="flex items-center flex-1 last:flex-initial">
          <div className="flex items-center gap-2">
            <div
              className={`w-8 h-8 rounded-full flex items-center justify-center text-sm font-bold border-2 transition-colors duration-300 ${
                i < current
                  ? 'bg-primary border-primary text-primary-foreground'
                  : i === current
                  ? 'border-primary text-primary bg-primary/10'
                  : 'border-border text-muted'
              }`}
            >
              {i < current ? <CheckCircle2 className="w-4 h-4" /> : i + 1}
            </div>
            <span
              className={`text-xs font-semibold uppercase tracking-wider ${
                i <= current ? 'text-foreground' : 'text-muted'
              }`}
            >
              {s.label}
            </span>
          </div>
          {i < STEPS.length - 1 && (
            <div className={`flex-1 h-0.5 mx-4 transition-colors duration-300 ${i < current ? 'bg-primary' : 'bg-border'}`} />
          )}
        </div>
      ))}
    </div>
  )
}

interface ConfigureStepProps {
  mcfUrl: string
  setMcfUrl: (v: string) => void
  mcfUsername: string
  setMcfUsername: (v: string) => void
  mcfPassword: string
  setMcfPassword: (v: string) => void
  defaultEmbeddingDimensions: number
  setDefaultEmbeddingDimensions: (v: number) => void
  isAnalyzing: boolean
  onAnalyze: () => void
}

function ConfigureStep(props: ConfigureStepProps) {
  return (
    <div className="card-container space-y-6 animate-in fade-in duration-300">
      <div>
        <h3 className="text-lg font-semibold">Source: ManifoldCF</h3>
        <p className="text-sm text-muted">Point this at a running ManifoldCF instance's REST API.</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div className="md:col-span-2">
          <label className="block text-sm font-medium text-muted mb-1.5">ManifoldCF API Base URL</label>
          <input
            type="text"
            value={props.mcfUrl}
            onChange={(e) => props.setMcfUrl(e.target.value)}
            placeholder="http://localhost:8345/mcf-api-service/json"
            className="w-full px-3 py-2 bg-input border border-border rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
        <div>
          <label className="block text-sm font-medium text-muted mb-1.5">Username (optional)</label>
          <input
            type="text"
            value={props.mcfUsername}
            onChange={(e) => props.setMcfUsername(e.target.value)}
            placeholder="admin"
            className="w-full px-3 py-2 bg-input border border-border rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
        <div>
          <label className="block text-sm font-medium text-muted mb-1.5">Password (optional)</label>
          <input
            type="password"
            value={props.mcfPassword}
            onChange={(e) => props.setMcfPassword(e.target.value)}
            placeholder="••••••••"
            className="w-full px-3 py-2 bg-input border border-border rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
        <div>
          <label className="block text-sm font-medium text-muted mb-1.5">Default Vespa embedding dimensions</label>
          <input
            type="number"
            value={props.defaultEmbeddingDimensions}
            onChange={(e) => props.setDefaultEmbeddingDimensions(parseInt(e.target.value, 10) || 384)}
            className="w-full px-3 py-2 bg-input border border-border rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
          <p className="text-xs text-muted mt-1">
            ManifoldCF's Vespa connector doesn't declare this statically — used when migrating a Vespa output connection.
          </p>
        </div>
      </div>

      <div className="flex justify-end pt-2">
        <button
          onClick={props.onAnalyze}
          disabled={props.isAnalyzing || !props.mcfUrl}
          className="flex items-center gap-2 px-5 py-2.5 rounded-md bg-gradient-to-r from-cyan-500 to-blue-500 text-black font-semibold border border-cyan-400/25 shadow-lg shadow-cyan-500/20 transition-opacity disabled:opacity-50 disabled:cursor-not-allowed hover:opacity-90"
        >
          {props.isAnalyzing ? <Loader2 className="w-4 h-4 animate-spin" /> : <Search className="w-4 h-4" />}
          {props.isAnalyzing ? 'Extracting & Planning…' : 'Analyze Configuration'}
        </button>
      </div>
    </div>
  )
}

interface ReviewStepProps {
  plan: McfMigrationResponse
  onApply: () => void
  selectedConnections: Set<string>
  selectedJobs: Set<string>
  onToggleConnection: (name: string) => void
  onToggleJob: (name: string) => void
}

function ReviewStep({ plan, onApply, selectedConnections, selectedJobs, onToggleConnection, onToggleJob }: ReviewStepProps) {
  const { summary } = plan
  const jobScore = summary.compatibilityScorePercentage
  const migratable = selectedConnections.size + selectedJobs.size

  const chartData = [
    { name: 'Migrated', value: summary.jobsMigrated, color: '#06b6d4' },
    { name: 'Skipped', value: summary.jobsTotal - summary.jobsMigrated, color: '#334155' },
  ].filter((d) => d.value > 0)

  const allNotes = [
    ...plan.connections.filter((c) => c.supported).flatMap((c) => c.notes.map((n) => ({ subject: c.name, note: n }))),
    ...plan.jobs.filter((j) => j.supported).flatMap((j) => j.notes.map((n) => ({ subject: j.name, note: n }))),
  ]

  return (
    <div className="space-y-6 animate-in fade-in duration-300">
      {/* Summary */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="card-container flex items-center justify-center">
          <div className="h-[160px] w-[160px] relative">
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie data={chartData} dataKey="value" innerRadius={55} outerRadius={72} startAngle={90} endAngle={-270}>
                  {chartData.map((d) => (
                    <Cell key={d.name} fill={d.color} stroke="none" />
                  ))}
                </Pie>
                <Tooltip contentStyle={{ backgroundColor: '#0f172a', border: '1px solid #1e293b', borderRadius: '8px' }} />
              </PieChart>
            </ResponsiveContainer>
            <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
              <span className="text-3xl font-bold text-primary">{jobScore}%</span>
              <span className="text-[10px] uppercase tracking-wider text-muted">Job compatibility</span>
            </div>
          </div>
        </div>

        <div className="lg:col-span-2 grid grid-cols-2 gap-4">
          <StatCard label="Connections migrated" value={`${summary.connectionsMigrated} / ${summary.connectionsTotal}`} accent="text-cyan-400" />
          <StatCard label="Jobs migrated" value={`${summary.jobsMigrated} / ${summary.jobsTotal}`} accent="text-emerald-400" />
          <StatCard label="Connections skipped" value={String(summary.connectionsTotal - summary.connectionsMigrated)} accent="text-slate-400" />
          <StatCard label="Jobs skipped" value={String(summary.jobsTotal - summary.jobsMigrated)} accent="text-slate-400" />
        </div>
      </div>

      {/* Connections */}
      <div className="card-container">
        <h3 className="text-lg font-semibold mb-1">Connections</h3>
        <p className="text-sm text-muted mb-4">Uncheck any migrated connection you don't want written to OpenCrawling.</p>
        <ConnectionsTable connections={plan.connections} selected={selectedConnections} onToggle={onToggleConnection} />
      </div>

      {/* Jobs */}
      <div className="card-container">
        <h3 className="text-lg font-semibold mb-1">Jobs</h3>
        <p className="text-sm text-muted mb-4">Uncheck any migrated job you don't want written to OpenCrawling.</p>
        <JobsTable jobs={plan.jobs} selected={selectedJobs} onToggle={onToggleJob} />
      </div>

      {/* Field-level warnings */}
      {allNotes.length > 0 && (
        <div className="card-container">
          <h3 className="text-lg font-semibold mb-1 flex items-center gap-2">
            <AlertTriangle className="w-5 h-5 text-amber-400" />
            Field-level fidelity notes
          </h3>
          <p className="text-sm text-muted mb-4">
            These connectors/jobs are being migrated, with the fidelity compromises below.{' '}
            <span className="text-amber-400 font-medium">Amber-highlighted</span> notes change actual crawl behavior, not just metadata;{' '}
            <span className="text-red-400 font-medium">red-highlighted</span> notes mean the item may not run correctly at all until verified.
          </p>
          <div className="space-y-2">
            {allNotes.map(({ subject, note }, i) => (
              <div key={`${subject}-${note.field}-${i}`}>
                <p className="text-xs font-semibold text-muted mb-1">{subject}</p>
                <NoteRow note={note} />
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Known limitations */}
      <div className="flex items-start gap-3 p-4 bg-blue-500/5 border border-blue-500/20 rounded-lg">
        <Info className="w-5 h-5 text-blue-400 flex-shrink-0 mt-0.5" />
        <div className="text-sm text-muted space-y-1.5">
          <p className="font-semibold text-foreground">Known target-system limitations</p>
          <p>• Dynamic connector resolution at job-start only covers Alfresco/Iceberg repositories and Qdrant/Vespa outputs — other combinations may not run as expected.</p>
          <p>• OpenCrawling has no scheduler; migrated jobs are created in a Ready state for manual/API start.</p>
          <p>• The filesystem connector scans every file under its root unconditionally — ManifoldCF include/exclude filters cannot be preserved.</p>
        </div>
      </div>

      <div className="flex justify-between items-center">
        <button
          onClick={() => downloadJson(plan, `manifoldcf-migration-plan-${Date.now()}.json`)}
          className="flex items-center gap-2 px-4 py-2.5 rounded-md bg-secondary hover:bg-secondary/80 font-medium text-sm transition-colors"
        >
          <Download className="w-4 h-4" />
          Download report (JSON)
        </button>
        <button
          onClick={onApply}
          disabled={migratable === 0}
          className="flex items-center gap-2 px-5 py-2.5 rounded-md bg-gradient-to-r from-cyan-500 to-blue-500 text-black font-semibold border border-cyan-400/25 shadow-lg shadow-cyan-500/20 transition-opacity disabled:opacity-50 disabled:cursor-not-allowed hover:opacity-90"
        >
          <Rocket className="w-4 h-4" />
          Apply {migratable} Selected Item{migratable === 1 ? '' : 's'} to OpenCrawling
        </button>
      </div>
    </div>
  )
}

function StatCard({ label, value, accent }: { label: string; value: string; accent: string }) {
  return (
    <div className="card-container py-4">
      <p className="text-xs text-muted font-medium uppercase tracking-wide">{label}</p>
      <p className={`text-2xl font-bold mt-1 ${accent}`}>{value}</p>
    </div>
  )
}

function ConnectionsTable({
  connections,
  selected,
  onToggle,
}: {
  connections: McfConnectionSummary[]
  selected: Set<string>
  onToggle: (name: string) => void
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-xs text-muted uppercase tracking-wider border-b border-border">
            <th className="pb-3 font-medium w-8"></th>
            <th className="pb-3 font-medium">Name</th>
            <th className="pb-3 font-medium">ManifoldCF Class</th>
            <th className="pb-3 font-medium">Status</th>
            <th className="pb-3 font-medium">Target / Reason</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {connections.map((c) => (
            <tr key={c.name} className="hover:bg-secondary/30 transition-colors">
              <td className="py-3">
                {c.supported && (
                  <input
                    type="checkbox"
                    checked={selected.has(c.name)}
                    onChange={() => onToggle(c.name)}
                    className="w-4 h-4 rounded border-border bg-input accent-cyan-500 cursor-pointer"
                    aria-label={`Apply connection ${c.name}`}
                  />
                )}
              </td>
              <td className="py-3 font-medium">{c.name}</td>
              <td className="py-3">
                <ConnectorBadge className={c.manifoldClass} label={c.manifoldClass.split('.').pop() || c.manifoldClass} />
              </td>
              <td className="py-3">
                <StatusBadge supported={c.supported} />
              </td>
              <td className="py-3 text-muted text-xs max-w-md">
                {c.supported ? (
                  <ConnectorBadge className={c.targetClass} label={c.targetClass?.split('.').pop() || ''} />
                ) : (
                  c.reason
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function JobsTable({
  jobs,
  selected,
  onToggle,
}: {
  jobs: McfJobSummary[]
  selected: Set<string>
  onToggle: (name: string) => void
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-xs text-muted uppercase tracking-wider border-b border-border">
            <th className="pb-3 font-medium w-8"></th>
            <th className="pb-3 font-medium">Name</th>
            <th className="pb-3 font-medium">Pipeline</th>
            <th className="pb-3 font-medium">Status</th>
            <th className="pb-3 font-medium">Reason</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {jobs.map((j) => (
            <tr key={j.name} className="hover:bg-secondary/30 transition-colors">
              <td className="py-3">
                {j.supported && (
                  <input
                    type="checkbox"
                    checked={selected.has(j.name)}
                    onChange={() => onToggle(j.name)}
                    className="w-4 h-4 rounded border-border bg-input accent-cyan-500 cursor-pointer"
                    aria-label={`Apply job ${j.name}`}
                  />
                )}
              </td>
              <td className="py-3 font-medium whitespace-nowrap">{j.name}</td>
              <td className="py-3">
                {j.supported ? (
                  <div className="flex items-center gap-2">
                    <ConnectorBadge label={j.repositoryConnector || '?'} />
                    {j.transformationConnector && (
                      <>
                        <ArrowRight className="w-3.5 h-3.5 text-muted flex-shrink-0" />
                        <ConnectorBadge label={j.transformationConnector} />
                      </>
                    )}
                    <ArrowRight className="w-3.5 h-3.5 text-muted flex-shrink-0" />
                    <ConnectorBadge label={j.outputConnector || '?'} />
                  </div>
                ) : (
                  <span className="text-xs text-muted italic">
                    {j.blockingConnectors.length > 0 ? j.blockingConnectors.join(', ') : '—'}
                  </span>
                )}
              </td>
              <td className="py-3">
                <StatusBadge supported={j.supported} />
              </td>
              <td className="py-3 text-muted text-xs max-w-sm">{j.reason || (j.notes.length > 0 ? `${j.notes.length} note(s) — see below` : '—')}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

type JobStartState = 'idle' | 'starting' | 'started' | 'error'

function ResultsStep({ result, onReset }: { result: McfMigrationResponse; onReset: () => void }) {
  const connectionResults = Object.entries(result.connectionResults || {})
  const jobResults = Object.entries(result.jobResults || {})
  const anyFailure = [...connectionResults, ...jobResults].some(([, r]) => !r.success)

  const [jobsList, setJobsList] = useState<{ id: string; name: string }[] | null>(null)
  const [jobStartState, setJobStartState] = useState<Record<string, JobStartState>>({})

  useEffect(() => {
    if (jobResults.some(([, r]) => r.success)) {
      jobApi
        .getAll()
        .then((res) => setJobsList(res.data || []))
        .catch(() => setJobsList([]))
    }
    // Only needs to run once, when this step's results first render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const handleStartJob = async (name: string) => {
    const job = jobsList?.find((j) => j.name === name)
    if (!job) return
    setJobStartState((prev) => ({ ...prev, [name]: 'starting' }))
    try {
      await jobApi.start(job.id)
      setJobStartState((prev) => ({ ...prev, [name]: 'started' }))
    } catch {
      setJobStartState((prev) => ({ ...prev, [name]: 'error' }))
    }
  }

  return (
    <div className="space-y-6 animate-in fade-in duration-300">
      <div
        className={`flex items-center gap-4 p-5 rounded-lg border ${
          anyFailure ? 'bg-amber-500/10 border-amber-500/20' : 'bg-green-500/10 border-green-500/20'
        }`}
      >
        {anyFailure ? (
          <ShieldAlert className="w-8 h-8 text-amber-400 flex-shrink-0" />
        ) : (
          <CheckCircle2 className="w-8 h-8 text-green-400 flex-shrink-0" />
        )}
        <div>
          <p className="font-bold text-lg">{anyFailure ? 'Migration applied with some failures' : 'Migration applied successfully'}</p>
          <p className="text-sm text-muted">
            {connectionResults.length} connector(s) and {jobResults.length} job(s) processed on this OpenCrawling instance.
          </p>
        </div>
      </div>

      {connectionResults.length > 0 && (
        <div className="card-container">
          <h3 className="text-sm font-semibold uppercase tracking-wide text-muted mb-3">Connections</h3>
          <div className="space-y-2">
            {connectionResults.map(([name, r]) => (
              <ApplyResultBanner key={name} name={name} result={r} />
            ))}
          </div>
        </div>
      )}

      {jobResults.length > 0 && (
        <div className="card-container">
          <h3 className="text-sm font-semibold uppercase tracking-wide text-muted mb-3">Jobs</h3>
          <div className="space-y-2">
            {jobResults.map(([name, r]) => {
              const startState = jobStartState[name] ?? 'idle'
              return (
                <div key={name} className="flex items-center gap-2">
                  <div className="flex-1">
                    <ApplyResultBanner name={name} result={r} />
                  </div>
                  {r.success && (
                    <button
                      onClick={() => handleStartJob(name)}
                      disabled={!jobsList || startState === 'starting' || startState === 'started'}
                      className="flex items-center gap-1.5 px-3 py-2.5 rounded-md bg-secondary hover:bg-secondary/80 text-xs font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed whitespace-nowrap"
                    >
                      {startState === 'starting' ? (
                        <Loader2 className="w-3.5 h-3.5 animate-spin" />
                      ) : startState === 'started' ? (
                        <CheckCircle2 className="w-3.5 h-3.5 text-green-400" />
                      ) : (
                        <Play className="w-3.5 h-3.5" />
                      )}
                      {startState === 'started' ? 'Started' : startState === 'error' ? 'Retry start' : 'Start this job'}
                    </button>
                  )}
                </div>
              )
            })}
          </div>
        </div>
      )}

      <div className="flex justify-between items-center">
        <button
          onClick={() => downloadJson(result, `manifoldcf-migration-result-${Date.now()}.json`)}
          className="flex items-center gap-2 px-4 py-2.5 rounded-md bg-secondary hover:bg-secondary/80 font-medium text-sm transition-colors"
        >
          <Download className="w-4 h-4" />
          Download report (JSON)
        </button>
        <button
          onClick={onReset}
          className="flex items-center gap-2 px-5 py-2.5 rounded-md bg-secondary hover:bg-secondary/80 font-medium transition-colors"
        >
          <RotateCcw className="w-4 h-4" />
          Run another migration
        </button>
      </div>
    </div>
  )
}
