/*
 * Copyright © ${year} the original author or authors (michael@michaelcizmar.com)
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
  RefreshCw,
  Activity,
  Database,
  Search,
  UploadCloud,
  Loader2,
  AlertCircle,
} from 'lucide-react'
import { vespaInsightsApi, problemMessage } from '../lib/api'

interface VespaModelInsightsProps {
  endpoint: string
}

interface HealthResult {
  up: boolean
  message: string
}

interface DocumentTypeCount {
  documentType: string
  dimensionLabel: string
  count: number
  available: boolean
}

interface QueryHit {
  chunkId: string
  text: string
  uri: string
  relevance: number
}

interface QueryResult {
  hits: QueryHit[]
  totalCount: number
  degraded: boolean
  message: string | null
}

interface DeployResult {
  success: boolean
  message: string
  rawResponse: string | null
}

const DOCUMENT_TYPE_OPTIONS = [
  { value: 'opencrawling_chunk_1024', label: 'opencrawling_chunk_1024 (1024-dim)' },
  { value: 'opencrawling_chunk_768', label: 'opencrawling_chunk_768 (768-dim)' },
  { value: 'opencrawling_chunk_384', label: 'opencrawling_chunk_384 (384-dim)' },
  { value: 'opencrawling_chunk', label: 'opencrawling_chunk (default/fallback)' },
]

export default function VespaModelInsights({ endpoint }: VespaModelInsightsProps) {
  const [isLoadingInsights, setIsLoadingInsights] = useState(false)
  const [health, setHealth] = useState<HealthResult | null>(null)
  const [documentCounts, setDocumentCounts] = useState<DocumentTypeCount[] | null>(null)
  const [insightsError, setInsightsError] = useState<string | null>(null)

  const [queryText, setQueryText] = useState('')
  const [rankProfile, setRankProfile] = useState<'default' | 'semantic' | 'hybrid'>('hybrid')
  const [queryDocumentType, setQueryDocumentType] = useState('opencrawling_chunk_1024')
  const [isRunningQuery, setIsRunningQuery] = useState(false)
  const [queryResult, setQueryResult] = useState<QueryResult | null>(null)

  const [configServerEndpoint, setConfigServerEndpoint] = useState('http://localhost:19071')
  const [isDeploying, setIsDeploying] = useState(false)
  const [deployResult, setDeployResult] = useState<DeployResult | null>(null)
  const [selectedFile, setSelectedFile] = useState<File | null>(null)

  const handleRefreshInsights = async () => {
    if (!endpoint) return
    setIsLoadingInsights(true)
    setInsightsError(null)
    try {
      const [healthRes, countsRes] = await Promise.all([
        vespaInsightsApi.getHealth(endpoint),
        vespaInsightsApi.getDocumentCounts(endpoint),
      ])
      setHealth(healthRes.data)
      setDocumentCounts(countsRes.data)
    } catch (error) {
      setInsightsError(problemMessage(error, 'Failed to reach the admin backend.'))
    } finally {
      setIsLoadingInsights(false)
    }
  }

  const handleRunQuery = async () => {
    if (!endpoint || !queryText.trim()) return
    setIsRunningQuery(true)
    setQueryResult(null)
    try {
      const response = await vespaInsightsApi.runQuery({
        endpoint,
        documentType: queryDocumentType,
        queryText,
        rankProfile,
      })
      setQueryResult(response.data)
    } catch (error) {
      setQueryResult({
        hits: [],
        totalCount: 0,
        degraded: true,
        message: problemMessage(error, 'Query failed.'),
      })
    } finally {
      setIsRunningQuery(false)
    }
  }

  const handleDeployBundled = async () => {
    if (!configServerEndpoint) return
    if (!confirm('Deploy the bundled OpenCrawling schema to this Vespa config server? This activates a new application package.')) return
    setIsDeploying(true)
    setDeployResult(null)
    try {
      const response = await vespaInsightsApi.deployBundledSchema(configServerEndpoint)
      setDeployResult(response.data)
    } catch (error) {
      setDeployResult({
        success: false,
        message: problemMessage(error, 'Deploy failed.'),
        rawResponse: null,
      })
    } finally {
      setIsDeploying(false)
    }
  }

  const handleDeployCustom = async () => {
    if (!configServerEndpoint || !selectedFile) return
    if (!confirm(`Deploy "${selectedFile.name}" to this Vespa config server? This activates a new application package.`)) return
    setIsDeploying(true)
    setDeployResult(null)
    try {
      const response = await vespaInsightsApi.deployCustomSchema(configServerEndpoint, selectedFile)
      setDeployResult(response.data)
    } catch (error) {
      setDeployResult({
        success: false,
        message: problemMessage(error, 'Deploy failed.'),
        rawResponse: null,
      })
    } finally {
      setIsDeploying(false)
    }
  }

  const maxCount = Math.max(1, ...(documentCounts ?? []).map((dc) => dc.count))

  return (
    <div className="pt-4 mt-4 border-t border-border space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Activity className="w-4 h-4 text-violet-400" />
          <h4 className="text-sm font-semibold">Vespa Model Insights</h4>
        </div>
        <button
          type="button"
          onClick={handleRefreshInsights}
          disabled={isLoadingInsights || !endpoint}
          className="btn-secondary flex items-center gap-2 text-xs px-3 py-1.5"
        >
          {isLoadingInsights ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
          Refresh
        </button>
      </div>
      <p className="text-xs text-muted-foreground -mt-2">
        Live data straight from this Vespa instance's own APIs - not polled automatically, refresh manually.
      </p>

      {insightsError && (
        <div className="p-3 rounded-lg border border-red-500/30 bg-red-500/10 text-red-400 text-xs flex items-start gap-2">
          <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
          {insightsError}
        </div>
      )}

      {health && (
        <div
          className={`inline-flex items-center gap-2 px-3 py-1 rounded-full text-xs font-medium border ${
            health.up ? 'bg-green-500/10 text-green-500 border-green-500/20' : 'bg-red-500/10 text-red-500 border-red-500/20'
          }`}
        >
          <span className={`w-2 h-2 rounded-full ${health.up ? 'bg-green-500' : 'bg-red-500'} animate-pulse`} />
          {health.message}
        </div>
      )}

      {documentCounts && (
        <div className="space-y-2">
          <div className="flex items-center gap-2">
            <Database className="w-3.5 h-3.5 text-muted-foreground" />
            <span className="text-xs font-medium text-muted-foreground">Chunks per document type</span>
          </div>
          {documentCounts.map((dc) => (
            <div key={dc.documentType} className="flex items-center gap-3 text-sm">
              <div className="w-48 font-mono text-xs truncate" title={dc.documentType}>{dc.documentType}</div>
              <div className="w-24 text-xs text-muted-foreground">{dc.dimensionLabel}</div>
              <div className="flex-1 h-2 bg-slate-800 rounded-full overflow-hidden">
                <div
                  className={`h-full rounded-full ${dc.available ? 'bg-violet-500' : 'bg-slate-700'}`}
                  style={{ width: `${dc.available ? Math.max(4, (dc.count / maxCount) * 100) : 0}%` }}
                />
              </div>
              <div className="w-20 text-right text-xs font-mono">{dc.available ? dc.count.toLocaleString() : 'N/A'}</div>
            </div>
          ))}
        </div>
      )}

      <div className="space-y-2 pt-2">
        <div className="flex items-center gap-2">
          <Search className="w-3.5 h-3.5 text-muted-foreground" />
          <span className="text-xs font-medium text-muted-foreground">Query tester</span>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-[1fr_auto_auto] gap-2">
          <input
            value={queryText}
            onChange={(e) => setQueryText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                handleRunQuery()
              }
            }}
            placeholder="Try a search query..."
            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
          />
          <select
            value={rankProfile}
            onChange={(e) => setRankProfile(e.target.value as 'default' | 'semantic' | 'hybrid')}
            className="bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
          >
            <option value="default">Default (BM25)</option>
            <option value="semantic">Semantic (Vector)</option>
            <option value="hybrid">Hybrid</option>
          </select>
          <select
            value={queryDocumentType}
            onChange={(e) => setQueryDocumentType(e.target.value)}
            className="bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none"
          >
            {DOCUMENT_TYPE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>
        <button
          type="button"
          onClick={handleRunQuery}
          disabled={isRunningQuery || !queryText.trim() || !endpoint}
          className="btn-primary flex items-center gap-2 text-sm px-3 py-1.5"
        >
          {isRunningQuery ? <Loader2 className="w-4 h-4 animate-spin" /> : <Search className="w-4 h-4" />}
          Run Query
        </button>

        {queryResult && (
          <div className="space-y-2 pt-1">
            {queryResult.degraded && queryResult.message && (
              <div className="p-2 rounded-md border border-amber-500/20 bg-amber-500/5 text-xs text-amber-300">
                {queryResult.message}
              </div>
            )}
            <p className="text-xs text-muted-foreground">{queryResult.totalCount} total match(es)</p>
            {queryResult.hits.map((hit, index) => (
              <div key={`${hit.chunkId}-${index}`} className="p-3 rounded-md border border-border bg-slate-900/40 space-y-1">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-mono text-xs text-primary truncate">{hit.chunkId}</span>
                  <span className="font-mono text-xs text-muted-foreground flex-shrink-0">relevance: {hit.relevance.toFixed(3)}</span>
                </div>
                <p className="text-sm">{hit.text}</p>
                <p className="text-xs text-muted-foreground font-mono truncate">{hit.uri}</p>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="pt-4 mt-2 border-t border-border space-y-3">
        <div className="flex items-center gap-2">
          <UploadCloud className="w-4 h-4 text-amber-400" />
          <h4 className="text-sm font-semibold text-amber-300">Schema Deployment</h4>
        </div>
        <p className="text-xs text-muted-foreground">
          Deploying activates a new Vespa application package. This is optional - OpenCrawling also runs fine against a
          schema deployed by your own CI/CD or the <span className="font-mono">vespa</span> CLI, which remains the
          recommended path for production. Both are fully supported.
        </p>
        <div className="space-y-2">
          <label className="text-sm font-medium">Config Server Endpoint</label>
          <input
            value={configServerEndpoint}
            onChange={(e) => setConfigServerEndpoint(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && e.preventDefault()}
            placeholder="http://localhost:19071"
            className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:ring-2 focus:ring-primary/50 outline-none font-mono"
          />
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="button"
            onClick={handleDeployBundled}
            disabled={isDeploying || !configServerEndpoint}
            className="btn-secondary flex items-center gap-2 text-xs px-3 py-1.5"
          >
            {isDeploying ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <UploadCloud className="w-3.5 h-3.5" />}
            Deploy Bundled OpenCrawling Schema
          </button>
          <div className="flex items-center gap-2">
            <input
              type="file"
              accept=".zip,.gz,.tar.gz"
              onChange={(e) => setSelectedFile(e.target.files?.[0] ?? null)}
              className="text-xs text-muted-foreground max-w-[180px]"
            />
            <button
              type="button"
              onClick={handleDeployCustom}
              disabled={isDeploying || !selectedFile || !configServerEndpoint}
              className="btn-secondary flex items-center gap-2 text-xs px-3 py-1.5"
            >
              Upload &amp; Deploy
            </button>
          </div>
        </div>
        {deployResult && (
          <div
            className={`p-3 rounded-lg border text-xs ${
              deployResult.success
                ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400'
                : 'bg-red-500/10 border-red-500/30 text-red-400'
            }`}
          >
            {deployResult.message}
          </div>
        )}
      </div>
    </div>
  )
}
