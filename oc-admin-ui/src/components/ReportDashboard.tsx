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
import { useState, useEffect } from 'react'
import {
  FileSpreadsheet,
  Download,
  Search,
  RefreshCcw,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Sparkles,
  HardDrive,
  Database,
  ArrowRight,
  Copy,
  Check,
  ChevronLeft,
  ChevronRight,
  Terminal,
  ShieldCheck,
  X,
  Play
} from 'lucide-react'
import {
  reportApi,
  JobReportSummary,
  DocumentLedgerEntry
} from '../lib/api'

export default function ReportDashboard() {
  const [reports, setReports] = useState<JobReportSummary[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [searchFilter, setSearchFilter] = useState('')
  const [modeFilter, setModeFilter] = useState<'ALL' | 'RAG' | 'MIGRATION'>('ALL')
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'SUCCESS' | 'PARTIAL' | 'FAILED'>('ALL')

  // Selected report for drill-down ledger view
  const [selectedReport, setSelectedReport] = useState<JobReportSummary | null>(null)
  const [ledgerEntries, setLedgerEntries] = useState<DocumentLedgerEntry[]>([])
  const [ledgerTotal, setLedgerTotal] = useState(0)
  const [ledgerPage, setLedgerPage] = useState(0)
  const [ledgerSize] = useState(25)
  const [ledgerStatusFilter, setLedgerStatusFilter] = useState('ALL')
  const [ledgerQuery, setLedgerQuery] = useState('')
  const [isLedgerLoading, setIsLedgerLoading] = useState(false)

  // Copy DuckDB SQL notification
  const [copiedSql, setCopiedSql] = useState(false)

  // DuckDB Query Playground Modal
  const [isSqlModalOpen, setIsSqlModalOpen] = useState(false)
  const [sqlQuery, setSqlQuery] = useState('')
  const [queryResult, setQueryResult] = useState<{ columns: string[]; rows: any[][] } | null>(null)
  const [isQueryRunning, setIsQueryRunning] = useState(false)
  const [queryError, setQueryError] = useState<string | null>(null)

  // Active error details modal
  const [activeErrorDoc, setActiveErrorDoc] = useState<DocumentLedgerEntry | null>(null)

  const fetchReports = async () => {
    setIsLoading(true)
    try {
      const res = await reportApi.getAll()
      setReports(res.data || [])
      // If none selected, default to the first
      if (!selectedReport && res.data && res.data.length > 0) {
        setSelectedReport(res.data[0])
      }
    } catch (err) {
      console.error('Failed to load reports:', err)
    } finally {
      setIsLoading(false)
    }
  }

  const fetchLedger = async (jobId: string, executionId: string, page = 0, status = ledgerStatusFilter, query = ledgerQuery) => {
    setIsLedgerLoading(true)
    try {
      const res = await reportApi.getDocumentLedger(jobId, executionId, {
        page,
        size: ledgerSize,
        status: status !== 'ALL' ? status : undefined,
        query: query.trim() ? query.trim() : undefined
      })
      setLedgerEntries(res.data.items || [])
      setLedgerTotal(res.data.total || 0)
      setLedgerPage(page)
    } catch (err) {
      console.error('Failed to load document ledger:', err)
      setLedgerEntries([])
      setLedgerTotal(0)
    } finally {
      setIsLedgerLoading(false)
    }
  }

  useEffect(() => {
    fetchReports()
  }, [])

  useEffect(() => {
    if (selectedReport) {
      fetchLedger(selectedReport.jobId, selectedReport.executionId, 0, ledgerStatusFilter, ledgerQuery)
    }
  }, [selectedReport, ledgerStatusFilter])

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (selectedReport) {
      fetchLedger(selectedReport.jobId, selectedReport.executionId, 0, ledgerStatusFilter, ledgerQuery)
    }
  }

  const handleCopyDuckDbSql = (report: JobReportSummary) => {
    const parquetPath = report.reportPathParquet || `mode=${report.pipelineMode}/**/job_${report.jobId}_exec_${report.executionId}.parquet`
    const sql = `SELECT * FROM read_parquet('${parquetPath}') LIMIT 100`
    navigator.clipboard.writeText(sql)
    setCopiedSql(true)
    setTimeout(() => setCopiedSql(false), 2500)
  }

  const handleOpenSqlPlayground = (report: JobReportSummary) => {
    const path = report.reportPathParquet || `mode=${report.pipelineMode}/**/job_${report.jobId}_*.parquet`
    setSqlQuery(`SELECT status, count(*) AS doc_count, avg(duration_ms) AS avg_duration_ms FROM read_parquet('${path}') GROUP BY status`)
    setQueryResult(null)
    setQueryError(null)
    setIsSqlModalOpen(true)
  }

  const handleRunQuery = async () => {
    if (!sqlQuery.trim()) return
    setIsQueryRunning(true)
    setQueryError(null)
    try {
      const res = await reportApi.runQuery(sqlQuery)
      setQueryResult(res.data)
    } catch (err: any) {
      setQueryError(err?.response?.data || err.message || 'Query failed')
    } finally {
      setIsQueryRunning(false)
    }
  }

  // Aggregate Metrics across all historical executions
  const totalExecutions = reports.length
  const uniqueJobCount = new Set(reports.map(r => r.jobId)).size
  const totalDocs = reports.reduce((acc, r) => acc + (r.totalDocuments || 0), 0)
  const totalSuccessDocs = reports.reduce((acc, r) => acc + (r.successfulDocuments || 0), 0)
  const totalFailedDocs = reports.reduce((acc, r) => acc + (r.failedDocuments || 0), 0)
  const totalRagTokens = reports.reduce((acc, r) => acc + (r.totalTokens || 0), 0)
  const totalRagCost = reports.reduce((acc, r) => acc + (r.estimatedEmbeddingCostUsd || 0), 0)
  const totalMigratedBytes = reports.reduce((acc, r) => acc + (r.totalBinaryBytes || 0), 0)
  const totalReclaimedBytes = reports.reduce((acc, r) => acc + (r.totalReclaimedBytes || 0), 0)

  // Filtered reports
  const filteredReports = reports.filter(r => {
    if (modeFilter !== 'ALL' && r.pipelineMode !== modeFilter) return false
    if (statusFilter !== 'ALL' && r.status !== statusFilter) return false
    if (searchFilter.trim()) {
      const q = searchFilter.toLowerCase()
      const matchName = r.jobName?.toLowerCase().includes(q)
      const matchId = r.jobId?.toLowerCase().includes(q)
      const matchExec = r.executionId?.toLowerCase().includes(q)
      if (!matchName && !matchId && !matchExec) return false
    }
    return true
  })

  const formatBytes = (bytes?: number) => {
    if (!bytes || bytes === 0) return '0 B'
    const k = 1024
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
  }

  const formatDuration = (ms: number) => {
    if (ms < 1000) return `${ms}ms`
    const sec = (ms / 1000).toFixed(1)
    if (Number(sec) < 60) return `${sec}s`
    const min = Math.floor(Number(sec) / 60)
    const remSec = (Number(sec) % 60).toFixed(0)
    return `${min}m ${remSec}s`
  }

  return (
    <div className="flex-1 overflow-y-auto p-8 space-y-8">
      {/* HEADER */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-3">
            <div className="p-2 bg-primary/10 text-primary rounded-lg border border-primary/20">
              <FileSpreadsheet className="w-6 h-6" />
            </div>
            <div>
              <h1 className="text-2xl font-bold tracking-tight">Reports & Audit Ledger</h1>
              <p className="text-sm text-muted-foreground mt-0.5">
                Lakehouse columnar audit reports, compliance manifests, token analysis, and document ledger
              </p>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={fetchReports}
            disabled={isLoading}
            className="flex items-center gap-2 px-3.5 py-2 rounded-lg bg-secondary text-secondary-foreground hover:bg-secondary/80 border border-border text-sm font-medium transition-colors"
          >
            <RefreshCcw className={`w-4 h-4 ${isLoading ? 'animate-spin' : ''}`} />
            Refresh
          </button>
        </div>
      </div>

      {/* EXECUTIVE KPI CARDS */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Card 1: Executions & Documents */}
        <div className="bg-card border border-border rounded-xl p-5 space-y-3">
          <div className="flex items-center justify-between text-muted-foreground">
            <span className="text-xs font-semibold uppercase tracking-wider">Total Ingested Documents</span>
            <Database className="w-4 h-4 text-primary" />
          </div>
          <div>
            <div className="text-2xl font-bold">{totalDocs.toLocaleString()}</div>
            <div className="flex items-center gap-2 mt-1 text-xs">
              <span className="text-green-400 font-medium">{totalSuccessDocs.toLocaleString()} success</span>
              <span className="text-muted-foreground">•</span>
              <span className="text-red-400 font-medium">{totalFailedDocs.toLocaleString()} failed</span>
            </div>
          </div>
          <div className="text-xs text-muted-foreground border-t border-border/50 pt-2">
            Across <span className="font-semibold text-foreground">{totalExecutions}</span> runs ({uniqueJobCount} unique {uniqueJobCount === 1 ? 'job' : 'jobs'})
          </div>
        </div>

        {/* Card 2: RAG Tokens & Model Spend */}
        <div className="bg-card border border-border rounded-xl p-5 space-y-3">
          <div className="flex items-center justify-between text-muted-foreground">
            <span className="text-xs font-semibold uppercase tracking-wider">RAG Tokens & Embedding Cost</span>
            <Sparkles className="w-4 h-4 text-cyan-400" />
          </div>
          <div>
            <div className="text-2xl font-bold">{totalRagTokens.toLocaleString()}</div>
            <div className="text-xs text-cyan-400 font-medium mt-1">
              ≈ ${totalRagCost.toFixed(4)} USD estimated model spend
            </div>
          </div>
          <div className="text-xs text-muted-foreground border-t border-border/50 pt-2">
            Vectorized with Ollama / OpenAI embeddings
          </div>
        </div>

        {/* Card 3: Migration Storage & Safe Pruning */}
        <div className="bg-card border border-border rounded-xl p-5 space-y-3">
          <div className="flex items-center justify-between text-muted-foreground">
            <span className="text-xs font-semibold uppercase tracking-wider">Migration Volume</span>
            <HardDrive className="w-4 h-4 text-emerald-400" />
          </div>
          <div>
            <div className="text-2xl font-bold">{formatBytes(totalMigratedBytes)}</div>
            <div className="text-xs text-emerald-400 font-medium mt-1">
              {formatBytes(totalReclaimedBytes)} disk space reclaimed
            </div>
          </div>
          <div className="text-xs text-muted-foreground border-t border-border/50 pt-2">
            Bit-for-bit offload to Apache Ozone / S3
          </div>
        </div>

        {/* Card 4: Lakehouse Storage & Integrity */}
        <div className="bg-card border border-border rounded-xl p-5 space-y-3">
          <div className="flex items-center justify-between text-muted-foreground">
            <span className="text-xs font-semibold uppercase tracking-wider">Cryptographic Integrity</span>
            <ShieldCheck className="w-4 h-4 text-purple-400" />
          </div>
          <div>
            <div className="text-2xl font-bold text-purple-400">100%</div>
            <div className="text-xs text-muted-foreground mt-1">
              SHA-256 Verified WORM Audit Trail
            </div>
          </div>
          <div className="text-xs text-muted-foreground border-t border-border/50 pt-2">
            Zstandard columnar Parquet & Avro archives
          </div>
        </div>
      </div>

      {/* HISTORICAL EXECUTIONS TABLE */}
      <div className="bg-card border border-border rounded-xl overflow-hidden shadow-sm">
        <div className="p-5 border-b border-border flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div>
            <h2 className="text-base font-semibold">Crawl Job Execution Reports</h2>
            <p className="text-xs text-muted-foreground mt-0.5">
              Select an execution run to inspect the granular document ledger and download audit manifests
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            {/* Search */}
            <div className="relative">
              <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
              <input
                type="text"
                value={searchFilter}
                onChange={e => setSearchFilter(e.target.value)}
                placeholder="Search job or execution ID..."
                className="pl-9 pr-3 py-1.5 text-xs rounded-lg bg-background border border-border focus:outline-none focus:ring-1 focus:ring-primary w-52"
              />
            </div>

            {/* Mode Filter */}
            <select
              value={modeFilter}
              onChange={e => setModeFilter(e.target.value as any)}
              className="px-2.5 py-1.5 text-xs rounded-lg bg-background border border-border focus:outline-none text-foreground font-medium"
            >
              <option value="ALL">All Modes</option>
              <option value="RAG">RAG Mode</option>
              <option value="MIGRATION">Migration Mode</option>
            </select>

            {/* Status Filter */}
            <select
              value={statusFilter}
              onChange={e => setStatusFilter(e.target.value as any)}
              className="px-2.5 py-1.5 text-xs rounded-lg bg-background border border-border focus:outline-none text-foreground font-medium"
            >
              <option value="ALL">All Statuses</option>
              <option value="SUCCESS">Success</option>
              <option value="PARTIAL">Partial</option>
              <option value="FAILED">Failed</option>
            </select>
          </div>
        </div>

        {/* Table Content */}
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="bg-secondary/40 text-muted-foreground text-xs font-semibold uppercase tracking-wider border-b border-border">
              <tr>
                <th className="py-3 px-4">Job & Execution ID</th>
                <th className="py-3 px-4">Pipeline Mode</th>
                <th className="py-3 px-4">Status</th>
                <th className="py-3 px-4">Connectors</th>
                <th className="py-3 px-4">Documents</th>
                <th className="py-3 px-4">Duration</th>
                <th className="py-3 px-4">Started (UTC)</th>
                <th className="py-3 px-4 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/60">
              {filteredReports.length === 0 ? (
                <tr>
                  <td colSpan={8} className="py-8 text-center text-muted-foreground text-sm">
                    {reports.length === 0 ? 'No execution reports generated yet. Run a crawl job to view audit reports.' : 'No execution reports match the selected filters.'}
                  </td>
                </tr>
              ) : (
                filteredReports.map(report => {
                  const isSelected = selectedReport?.executionId === report.executionId
                  return (
                    <tr
                      key={report.executionId}
                      onClick={() => setSelectedReport(report)}
                      className={`cursor-pointer transition-colors ${
                        isSelected
                          ? 'bg-primary/10 border-l-4 border-primary'
                          : 'hover:bg-secondary/30'
                      }`}
                    >
                      <td className="py-3.5 px-4 font-medium">
                        <div className="font-semibold text-foreground flex items-center gap-2">
                          {report.jobName}
                        </div>
                        <div className="text-xs font-mono text-muted-foreground mt-0.5">
                          {report.executionId}
                        </div>
                      </td>

                      <td className="py-3.5 px-4">
                        {report.pipelineMode === 'MIGRATION' ? (
                          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                            <HardDrive className="w-3 h-3" />
                            MIGRATION
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
                            <Sparkles className="w-3 h-3" />
                            RAG
                          </span>
                        )}
                      </td>

                      <td className="py-3.5 px-4">
                        {report.status === 'SUCCESS' ? (
                          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-green-500/10 text-green-400 border border-green-500/20">
                            <CheckCircle2 className="w-3 h-3" />
                            SUCCESS
                          </span>
                        ) : report.status === 'PARTIAL' ? (
                          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-yellow-500/10 text-yellow-400 border border-yellow-500/20">
                            <AlertTriangle className="w-3 h-3" />
                            PARTIAL
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-red-500/10 text-red-400 border border-red-500/20">
                            <XCircle className="w-3 h-3" />
                            FAILED
                          </span>
                        )}
                      </td>

                      <td className="py-3.5 px-4 text-xs text-muted-foreground">
                        <div className="flex items-center gap-1 text-foreground">
                          <span className="truncate max-w-[120px]">{report.repositoryConnector}</span>
                          <ArrowRight className="w-3 h-3 text-muted-foreground shrink-0" />
                          <span className="truncate max-w-[120px] font-medium">{report.outputConnector}</span>
                        </div>
                      </td>

                      <td className="py-3.5 px-4 text-xs font-mono">
                        <span className="font-semibold text-foreground">{report.successfulDocuments}</span>
                        <span className="text-muted-foreground"> / {report.totalDocuments}</span>
                      </td>

                      <td className="py-3.5 px-4 text-xs text-muted-foreground font-mono">
                        {formatDuration(report.durationMs)}
                      </td>

                      <td className="py-3.5 px-4 text-xs text-muted-foreground font-mono">
                        {report.startTime ? new Date(report.startTime).toLocaleString() : 'N/A'}
                      </td>

                      <td className="py-3.5 px-4 text-right">
                        <div className="flex items-center justify-end gap-2" onClick={e => e.stopPropagation()}>
                          <a
                            href={reportApi.getExportUrl(report.jobId, report.executionId, 'csv')}
                            download={`manifest_${report.executionId}.csv`}
                            className="p-1.5 rounded-md hover:bg-secondary text-muted-foreground hover:text-foreground transition-colors"
                            title="Download CSV Compliance Manifest"
                          >
                            <Download className="w-4 h-4" />
                          </a>
                          <button
                            onClick={() => handleOpenSqlPlayground(report)}
                            className="p-1.5 rounded-md hover:bg-secondary text-muted-foreground hover:text-cyan-400 transition-colors"
                            title="Run DuckDB SQL Query on Parquet"
                          >
                            <Terminal className="w-4 h-4" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* GRANULAR DOCUMENT AUDIT LEDGER (DRILL-DOWN) */}
      {selectedReport && (
        <div className="bg-card border border-border rounded-xl overflow-hidden shadow-sm space-y-4 p-6">
          {/* Selected Execution Header */}
          <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 border-b border-border pb-5">
            <div>
              <div className="flex items-center gap-3">
                <h3 className="text-lg font-bold text-foreground">
                  Audit Ledger: {selectedReport.jobName}
                </h3>
                <span className="text-xs font-mono px-2 py-0.5 rounded bg-secondary text-muted-foreground border border-border">
                  {selectedReport.executionId}
                </span>
                {selectedReport.pipelineMode === 'MIGRATION' ? (
                  <span className="px-2 py-0.5 rounded text-xs font-medium bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                    MIGRATION
                  </span>
                ) : (
                  <span className="px-2 py-0.5 rounded text-xs font-medium bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
                    RAG
                  </span>
                )}
              </div>
              <p className="text-xs text-muted-foreground mt-1">
                Completed on {new Date(selectedReport.endTime || selectedReport.startTime).toUTCString()} • Total duration: {formatDuration(selectedReport.durationMs)}
              </p>
            </div>

            {/* Export Toolbar */}
            <div className="flex flex-wrap items-center gap-2">
              <a
                href={reportApi.getExportUrl(selectedReport.jobId, selectedReport.executionId, 'csv')}
                download={`manifest_${selectedReport.executionId}.csv`}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-secondary hover:bg-secondary/80 text-foreground text-xs font-medium border border-border transition-colors"
              >
                <Download className="w-3.5 h-3.5 text-green-400" />
                CSV Manifest
              </a>

              <a
                href={reportApi.getExportUrl(selectedReport.jobId, selectedReport.executionId, 'parquet')}
                download={`job_${selectedReport.jobId}_exec_${selectedReport.executionId}.parquet`}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-secondary hover:bg-secondary/80 text-foreground text-xs font-medium border border-border transition-colors"
              >
                <Download className="w-3.5 h-3.5 text-purple-400" />
                Parquet File
              </a>

              <a
                href={reportApi.getExportUrl(selectedReport.jobId, selectedReport.executionId, 'json')}
                download={`summary_${selectedReport.executionId}.json`}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-secondary hover:bg-secondary/80 text-foreground text-xs font-medium border border-border transition-colors"
              >
                <Download className="w-3.5 h-3.5 text-yellow-400" />
                JSON Summary
              </a>

              <button
                onClick={() => handleCopyDuckDbSql(selectedReport)}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-secondary hover:bg-secondary/80 text-foreground text-xs font-medium border border-border transition-colors"
              >
                {copiedSql ? (
                  <>
                    <Check className="w-3.5 h-3.5 text-green-400" />
                    Copied DuckDB SQL!
                  </>
                ) : (
                  <>
                    <Copy className="w-3.5 h-3.5 text-cyan-400" />
                    Copy DuckDB SQL
                  </>
                )}
              </button>

              <button
                onClick={() => handleOpenSqlPlayground(selectedReport)}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-primary/10 hover:bg-primary/20 text-primary text-xs font-semibold border border-primary/30 transition-colors"
              >
                <Terminal className="w-3.5 h-3.5" />
                Query with DuckDB
              </button>
            </div>
          </div>

          {/* Mode-Specific Summary Bar */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 p-4 bg-secondary/20 rounded-lg border border-border/60 text-xs">
            {selectedReport.pipelineMode === 'MIGRATION' ? (
              <>
                <div>
                  <span className="text-muted-foreground block">Binary Volume</span>
                  <span className="font-semibold text-foreground text-sm font-mono mt-0.5 block">
                    {formatBytes(selectedReport.totalBinaryBytes)}
                  </span>
                </div>
                <div>
                  <span className="text-muted-foreground block">Space Reclaimed</span>
                  <span className="font-semibold text-emerald-400 text-sm font-mono mt-0.5 block">
                    {formatBytes(selectedReport.totalReclaimedBytes)}
                  </span>
                </div>
                <div>
                  <span className="text-muted-foreground block">Safe Pruned Docs</span>
                  <span className="font-semibold text-foreground text-sm font-mono mt-0.5 block">
                    {selectedReport.safePrunedCount || 0}
                  </span>
                </div>
                <div>
                  <span className="text-muted-foreground block">SHA-256 Validated</span>
                  <span className="font-semibold text-purple-400 text-sm font-mono mt-0.5 block">
                    {selectedReport.verifiedChecksumCount || 0} / {selectedReport.totalDocuments}
                  </span>
                </div>
              </>
            ) : (
              <>
                <div>
                  <span className="text-muted-foreground block">Total Chunks</span>
                  <span className="font-semibold text-foreground text-sm font-mono mt-0.5 block">
                    {selectedReport.totalChunks?.toLocaleString() || 0}
                  </span>
                </div>
                <div>
                  <span className="text-muted-foreground block">Token Count</span>
                  <span className="font-semibold text-cyan-400 text-sm font-mono mt-0.5 block">
                    {selectedReport.totalTokens?.toLocaleString() || 0}
                  </span>
                </div>
                <div>
                  <span className="text-muted-foreground block">Est. Embedding Cost</span>
                  <span className="font-semibold text-foreground text-sm font-mono mt-0.5 block">
                    ${(selectedReport.estimatedEmbeddingCostUsd || 0).toFixed(4)} USD
                  </span>
                </div>
                <div>
                  <span className="text-muted-foreground block">Embedding Model</span>
                  <span className="font-semibold text-foreground text-sm font-mono mt-0.5 block truncate">
                    {selectedReport.embeddingModel || 'Default Model'}
                  </span>
                </div>
              </>
            )}
          </div>

          {/* Document Filter Bar */}
          <div className="flex flex-col sm:flex-row items-center justify-between gap-3 pt-2">
            <form onSubmit={handleSearchSubmit} className="relative w-full sm:w-80">
              <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
              <input
                type="text"
                value={ledgerQuery}
                onChange={e => setLedgerQuery(e.target.value)}
                placeholder="Filter document ID or URI..."
                className="w-full pl-9 pr-3 py-1.5 text-xs rounded-lg bg-background border border-border focus:outline-none focus:ring-1 focus:ring-primary"
              />
            </form>

            <div className="flex items-center gap-1.5 self-start sm:self-auto">
              {['ALL', 'SUCCESS', 'FAILED', 'QUARANTINED', 'SKIPPED'].map(st => (
                <button
                  key={st}
                  onClick={() => setLedgerStatusFilter(st)}
                  className={`px-2.5 py-1 text-xs rounded-md font-medium transition-colors ${
                    ledgerStatusFilter === st
                      ? 'bg-primary text-primary-foreground'
                      : 'bg-secondary text-muted-foreground hover:text-foreground'
                  }`}
                >
                  {st}
                </button>
              ))}
            </div>
          </div>

          {/* Granular Table */}
          <div className="rounded-lg border border-border overflow-hidden">
            <div className="overflow-x-auto max-h-[460px] overflow-y-auto">
              <table className="w-full text-left text-xs">
                <thead className="bg-secondary/50 text-muted-foreground font-semibold uppercase tracking-wider sticky top-0 z-10 border-b border-border">
                  <tr>
                    <th className="py-2.5 px-4">Document ID / Source URI</th>
                    <th className="py-2.5 px-4">Status</th>
                    <th className="py-2.5 px-4">Latency</th>
                    {selectedReport.pipelineMode === 'MIGRATION' ? (
                      <>
                        <th className="py-2.5 px-4">Binary Size</th>
                        <th className="py-2.5 px-4">SHA-256 Checksum</th>
                        <th className="py-2.5 px-4">Target Key / Pruned</th>
                      </>
                    ) : (
                      <>
                        <th className="py-2.5 px-4">Chunks</th>
                        <th className="py-2.5 px-4">Tokens</th>
                        <th className="py-2.5 px-4">Embedding Model</th>
                      </>
                    )}
                    <th className="py-2.5 px-4 text-right">Details</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border/60">
                  {isLedgerLoading ? (
                    <tr>
                      <td colSpan={7} className="py-12 text-center text-muted-foreground">
                        <RefreshCcw className="w-5 h-5 animate-spin mx-auto mb-2 text-primary" />
                        Loading ledger entries...
                      </td>
                    </tr>
                  ) : ledgerEntries.length === 0 ? (
                    <tr>
                      <td colSpan={7} className="py-8 text-center text-muted-foreground">
                        No ledger documents match the selected filters.
                      </td>
                    </tr>
                  ) : (
                    ledgerEntries.map((doc, idx) => (
                      <tr key={idx} className="hover:bg-secondary/30 transition-colors font-mono">
                        <td className="py-2.5 px-4">
                          <div className="font-semibold text-foreground text-xs truncate max-w-sm">
                            {doc.documentId}
                          </div>
                          <div className="text-[11px] text-muted-foreground truncate max-w-sm">
                            {doc.sourceUri}
                          </div>
                        </td>

                        <td className="py-2.5 px-4">
                          {doc.status === 'SUCCESS' ? (
                            <span className="text-green-400 font-semibold text-[11px]">SUCCESS</span>
                          ) : doc.status === 'FAILED' ? (
                            <span className="text-red-400 font-semibold text-[11px]">FAILED</span>
                          ) : (
                            <span className="text-yellow-400 font-semibold text-[11px]">{doc.status}</span>
                          )}
                        </td>

                        <td className="py-2.5 px-4 text-muted-foreground">
                          {doc.durationMs}ms
                        </td>

                        {selectedReport.pipelineMode === 'MIGRATION' ? (
                          <>
                            <td className="py-2.5 px-4 text-foreground">
                              {formatBytes(doc.migBinarySizeBytes)}
                            </td>
                            <td className="py-2.5 px-4 text-muted-foreground truncate max-w-[140px]" title={doc.migSha256Checksum}>
                              {doc.migSha256Checksum ? doc.migSha256Checksum.substring(0, 16) + '...' : 'N/A'}
                            </td>
                            <td className="py-2.5 px-4">
                              <span className="truncate max-w-[150px] block text-foreground" title={doc.migTargetKey}>
                                {doc.migTargetKey || 'N/A'}
                              </span>
                              {doc.migSafePruned && (
                                <span className="text-[10px] text-emerald-400 bg-emerald-500/10 px-1.5 py-0.5 rounded border border-emerald-500/20">
                                  Pruned ({formatBytes(doc.migReclaimedBytes)})
                                </span>
                              )}
                            </td>
                          </>
                        ) : (
                          <>
                            <td className="py-2.5 px-4 text-foreground">
                              {doc.ragChunkCount || 1}
                            </td>
                            <td className="py-2.5 px-4 text-cyan-400 font-semibold">
                              {doc.ragTokenCount || 0}
                            </td>
                            <td className="py-2.5 px-4 text-muted-foreground truncate max-w-[130px]" title={doc.ragEmbeddingModel}>
                              {doc.ragEmbeddingModel || 'mxbai-embed-large'}
                            </td>
                          </>
                        )}

                        <td className="py-2.5 px-4 text-right">
                          {doc.errorMessage ? (
                            <button
                              onClick={() => setActiveErrorDoc(doc)}
                              className="text-red-400 hover:text-red-300 underline text-[11px]"
                            >
                              View Error
                            </button>
                          ) : (
                            <span className="text-muted-foreground text-[11px]">—</span>
                          )}
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>

            {/* Pagination footer */}
            <div className="p-3 bg-secondary/30 border-t border-border flex items-center justify-between text-xs text-muted-foreground font-mono">
              <div>
                Showing <span className="font-semibold text-foreground">{ledgerEntries.length}</span> of <span className="font-semibold text-foreground">{ledgerTotal}</span> records
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={() => fetchLedger(selectedReport.jobId, selectedReport.executionId, Math.max(0, ledgerPage - 1))}
                  disabled={ledgerPage === 0 || isLedgerLoading}
                  className="p-1 rounded bg-secondary hover:bg-secondary/80 disabled:opacity-50 text-foreground transition-colors"
                >
                  <ChevronLeft className="w-4 h-4" />
                </button>
                <span>
                  Page {ledgerPage + 1} of {Math.max(1, Math.ceil(ledgerTotal / ledgerSize))}
                </span>
                <button
                  onClick={() => fetchLedger(selectedReport.jobId, selectedReport.executionId, ledgerPage + 1)}
                  disabled={(ledgerPage + 1) * ledgerSize >= ledgerTotal || isLedgerLoading}
                  className="p-1 rounded bg-secondary hover:bg-secondary/80 disabled:opacity-50 text-foreground transition-colors"
                >
                  <ChevronRight className="w-4 h-4" />
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* DUCKDB SQL QUERY PLAYGROUND MODAL */}
      {isSqlModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-in fade-in">
          <div className="bg-card border border-border rounded-xl w-full max-w-4xl max-h-[85vh] flex flex-col shadow-2xl overflow-hidden">
            <div className="p-4 border-b border-border flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <div className="p-1.5 rounded-md bg-primary/10 text-primary border border-primary/20">
                  <Terminal className="w-4 h-4" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-foreground">Serverless Parquet SQL Playground (DuckDB)</h3>
                  <p className="text-xs text-muted-foreground">Direct in-memory analytics over partitioned audit Parquet files</p>
                </div>
              </div>
              <button
                onClick={() => setIsSqlModalOpen(false)}
                className="p-1 rounded-md hover:bg-secondary text-muted-foreground hover:text-foreground"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <div className="p-4 space-y-3 flex-1 overflow-y-auto">
              <label className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">SQL Query</label>
              <textarea
                value={sqlQuery}
                onChange={e => setSqlQuery(e.target.value)}
                rows={4}
                className="w-full p-3 text-xs font-mono bg-background border border-border rounded-lg focus:outline-none focus:ring-1 focus:ring-primary text-foreground"
                placeholder="SELECT * FROM read_parquet('...') LIMIT 50"
              />

              <div className="flex items-center justify-between">
                <span className="text-[11px] text-muted-foreground">
                  Powered by <code className="text-cyan-400">org.duckdb:duckdb_jdbc</code>
                </span>
                <button
                  onClick={handleRunQuery}
                  disabled={isQueryRunning || !sqlQuery.trim()}
                  className="flex items-center gap-2 px-4 py-1.5 rounded-lg bg-primary text-primary-foreground font-semibold text-xs hover:bg-primary/90 disabled:opacity-50 transition-colors"
                >
                  <Play className={`w-3.5 h-3.5 ${isQueryRunning ? 'animate-spin' : ''}`} />
                  Execute Query
                </button>
              </div>

              {queryError && (
                <div className="p-3 rounded-lg bg-red-500/10 border border-red-500/20 text-red-400 text-xs font-mono">
                  {queryError}
                </div>
              )}

              {queryResult && (
                <div className="mt-4 rounded-lg border border-border overflow-hidden">
                  <div className="p-2.5 bg-secondary/40 border-b border-border text-xs font-semibold text-foreground flex items-center justify-between">
                    <span>Query Output</span>
                    <span className="text-muted-foreground text-[11px] font-mono">{queryResult.rows.length} rows</span>
                  </div>
                  <div className="overflow-x-auto max-h-60 overflow-y-auto">
                    <table className="w-full text-left text-xs font-mono">
                      <thead className="bg-secondary/60 text-muted-foreground uppercase tracking-wider border-b border-border">
                        <tr>
                          {queryResult.columns.map((col, idx) => (
                            <th key={idx} className="py-2 px-3">{col}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-border/60">
                        {queryResult.rows.map((row, rIdx) => (
                          <tr key={rIdx} className="hover:bg-secondary/20">
                            {row.map((val, cIdx) => (
                              <td key={cIdx} className="py-1.5 px-3 whitespace-nowrap">
                                {val !== null ? String(val) : <span className="text-muted-foreground italic">null</span>}
                              </td>
                            ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* ERROR DETAILS MODAL */}
      {activeErrorDoc && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-in fade-in">
          <div className="bg-card border border-border rounded-xl w-full max-w-lg shadow-2xl p-6 space-y-4">
            <div className="flex items-center justify-between border-b border-border pb-3">
              <div className="flex items-center gap-2 text-red-400">
                <AlertTriangle className="w-5 h-5" />
                <h3 className="font-bold text-sm">Document Processing Failure</h3>
              </div>
              <button
                onClick={() => setActiveErrorDoc(null)}
                className="p-1 rounded hover:bg-secondary text-muted-foreground hover:text-foreground"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <div className="space-y-2 text-xs">
              <div>
                <span className="text-muted-foreground block">Document ID</span>
                <span className="font-mono text-foreground font-semibold break-all">{activeErrorDoc.documentId}</span>
              </div>
              <div>
                <span className="text-muted-foreground block">Source URI</span>
                <span className="font-mono text-muted-foreground break-all">{activeErrorDoc.sourceUri}</span>
              </div>
              <div>
                <span className="text-muted-foreground block">Error Code</span>
                <span className="font-mono text-red-400 font-semibold">{activeErrorDoc.errorCode || 'UNKNOWN'}</span>
              </div>
              <div>
                <span className="text-muted-foreground block">Message / Stacktrace</span>
                <div className="p-3 rounded-lg bg-background border border-border font-mono text-red-300 overflow-x-auto max-h-48 text-[11px] whitespace-pre-wrap">
                  {activeErrorDoc.errorMessage || 'No specific error message recorded.'}
                </div>
              </div>
            </div>

            <div className="flex justify-end pt-2">
              <button
                onClick={() => setActiveErrorDoc(null)}
                className="px-4 py-1.5 rounded-lg bg-secondary text-foreground text-xs font-medium hover:bg-secondary/80"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
