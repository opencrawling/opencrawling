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
import { useState, useEffect } from 'react'
import { 
  Play, 
  Pause, 
  Square, 
  RefreshCcw, 
  MoreHorizontal,
  ChevronDown,
  Search,
  Loader2,
  Plus,
  Trash2,
  Edit,
  ArrowRight,
  ShieldCheck,
  Plug2,
  Settings2,
  FolderOpen,
  AlertCircle,
  Cpu,
  X
} from 'lucide-react'
import { jobApi, connectorApi } from '../lib/api'

type JobStatus = 'Running' | 'Paused' | 'Error' | 'Finished' | 'Ready'

interface Job {
  id: string
  name: string
  repositoryConnector: string
  outputConnector: string
  authorityConnector: string
  path: string
  status: JobStatus
  currentStage: string
  documents: number
  lastRun: string
  embeddingModel: string
}

const statusStyles: Record<JobStatus, string> = {
  Running: 'bg-cyan-500/10 text-cyan-500 border-cyan-500/20',
  Paused: 'bg-yellow-500/10 text-yellow-500 border-yellow-500/20',
  Error: 'bg-red-500/10 text-red-500 border-red-500/20',
  Finished: 'bg-green-500/10 text-green-500 border-green-500/20',
  Ready: 'bg-slate-500/10 text-slate-400 border-slate-500/20',
}

const getStageProgress = (stage: string): number => {
  switch (stage) {
    case 'Scanning': return 20;
    case 'Extracting': return 40;
    case 'Chunking': return 60;
    case 'Embedding': return 80;
    case 'Indexing': return 95;
    case 'Completed': return 100;
    default: return 0;
  }
}

interface JobTableProps {
  setActiveView: (view: 'dashboard' | 'jobs' | 'connectors' | 'logs' | 'settings') => void
}

export default function JobTable({ setActiveView }: JobTableProps) {
  const [jobs, setJobs] = useState<Job[]>([])
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('All')
  const [isLoading, setIsLoading] = useState(false)
  const [actionLoading, setActionLoading] = useState<string | null>(null)
  const [showFilterMenu, setShowFilterMenu] = useState(false)

  // Connector lists for dropdown configurations
  const [repositoryConnectors, setRepositoryConnectors] = useState<any[]>([])
  const [outputConnectors, setOutputConnectors] = useState<any[]>([])
  const [authorityConnectors, setAuthorityConnectors] = useState<any[]>([])

  // Modal / Form state
  const [isFormOpen, setIsFormOpen] = useState(false)
  const [editingJob, setEditingJob] = useState<Job | null>(null)
  
  // Form fields state
  const [formName, setFormName] = useState('')
  const [formRepository, setFormRepository] = useState('')
  const [formOutput, setFormOutput] = useState('')
  const [formAuthority, setFormAuthority] = useState('')
  const [formPath, setFormPath] = useState('')
  const [formEmbeddingModel, setFormEmbeddingModel] = useState('mxbai-embed-large')
  const [formError, setFormError] = useState('')
  const [isSaving, setIsSaving] = useState(false)

  const fetchJobs = async (showLoader = false) => {
    if (showLoader) setIsLoading(true)
    try {
      const res = await jobApi.getAll()
      setJobs(res.data || [])
    } catch (err) {
      console.error("Failed to fetch jobs:", err)
    } finally {
      if (showLoader) setIsLoading(false)
    }
  }

  const fetchConnectors = async () => {
    try {
      const [repos, outputs, auths] = await Promise.all([
        connectorApi.getAll('repository'),
        connectorApi.getAll('output'),
        connectorApi.getAll('authority')
      ])
      setRepositoryConnectors(repos.data || [])
      setOutputConnectors(outputs.data || [])
      setAuthorityConnectors(auths.data || [])
    } catch (err) {
      console.error("Failed to fetch connectors:", err)
    }
  }

  useEffect(() => {
    fetchJobs(true)
    fetchConnectors()
    const interval = setInterval(() => fetchJobs(false), 3000)
    return () => clearInterval(interval)
  }, [])

  const triggerAction = async (id: string, action: 'start' | 'pause' | 'stop') => {
    setActionLoading(`${id}-${action}`)
    try {
      if (action === 'start') await jobApi.start(id)
      else if (action === 'pause') await jobApi.pause(id)
      else if (action === 'stop') await jobApi.stop(id)
      await fetchJobs(false)
    } catch (err) {
      console.error(`Failed to ${action} job ${id}:`, err)
    } finally {
      setActionLoading(null)
    }
  }

  const triggerRunAll = async () => {
    setIsLoading(true)
    try {
      await Promise.all(jobs.map(j => jobApi.start(j.id)))
      await fetchJobs(false)
    } catch (err) {
      console.error("Failed to run all jobs:", err)
    } finally {
      setIsLoading(false)
    }
  }

  const handleDeleteJob = async (id: string, name: string) => {
    if (!confirm(`Are you sure you want to delete job "${name}"?`)) return
    try {
      await jobApi.delete(id)
      await fetchJobs(false)
    } catch (err) {
      console.error("Failed to delete job:", err)
    }
  }

  const openCreateForm = () => {
    setEditingJob(null)
    setFormName('')
    setFormRepository(repositoryConnectors[0]?.name || '')
    setFormOutput(outputConnectors[0]?.name || '')
    setFormAuthority('')
    setFormPath('')
    setFormEmbeddingModel('mxbai-embed-large')
    setFormError('')
    setIsFormOpen(true)
  }

  const openEditForm = (job: Job) => {
    setEditingJob(job)
    setFormName(job.name)
    setFormRepository(job.repositoryConnector)
    setFormOutput(job.outputConnector)
    setFormAuthority(job.authorityConnector || '')
    setFormPath(job.path)
    setFormEmbeddingModel(job.embeddingModel || 'mxbai-embed-large')
    setFormError('')
    setIsFormOpen(true)
  }

  const handleSaveJob = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!formName.trim()) {
      setFormError('Job name is required')
      return
    }
    if (!formRepository) {
      setFormError('Repository Connector is required')
      return
    }
    if (!formOutput) {
      setFormError('Output Connector is required')
      return
    }
    if (!formPath.trim()) {
      setFormError('Scan path is required')
      return
    }

    setIsSaving(true)
    setFormError('')
    try {
      const jobData = {
        id: editingJob ? editingJob.id : 'new',
        name: formName,
        repositoryConnector: formRepository,
        outputConnector: formOutput,
        authorityConnector: formAuthority,
        path: formPath,
        status: editingJob ? editingJob.status : 'Ready',
        currentStage: editingJob ? editingJob.currentStage : 'Idle',
        documents: editingJob ? editingJob.documents : 0,
        lastRun: editingJob ? editingJob.lastRun : 'N/A',
        embeddingModel: formEmbeddingModel
      }
      await jobApi.create(jobData)
      await fetchJobs(false)
      setIsFormOpen(false)
    } catch (err) {
      console.error("Failed to save job:", err)
      setFormError('Failed to save job configuration')
    } finally {
      setIsSaving(false)
    }
  }

  const filteredJobs = jobs.filter(j => {
    const matchesSearch = j.name.toLowerCase().includes(searchQuery.toLowerCase()) || 
                          j.id.includes(searchQuery) ||
                          j.repositoryConnector.toLowerCase().includes(searchQuery.toLowerCase()) ||
                          j.outputConnector.toLowerCase().includes(searchQuery.toLowerCase())
    const matchesStatus = statusFilter === 'All' || j.status.toLowerCase() === statusFilter.toLowerCase()
    return matchesSearch && matchesStatus
  })

  return (
    <div className="space-y-6 animate-in fade-in duration-500">
      {/* Top Header Section */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">Pipeline Job Management</h1>
          <p className="text-muted text-sm">Configure, monitor, and run data ingestion pipelines from source repositories to vector stores.</p>
        </div>
        <div className="flex items-center gap-3">
          <button 
            onClick={() => fetchJobs(true)} 
            disabled={isLoading}
            className="btn-secondary flex items-center gap-2"
          >
            <RefreshCcw className={`w-4 h-4 ${isLoading ? 'animate-spin' : ''}`} />
            Refresh
          </button>
          <button 
            onClick={openCreateForm}
            className="btn-primary flex items-center gap-2 bg-gradient-to-r from-cyan-500 to-blue-500 text-black border border-cyan-400/25 shadow-lg shadow-cyan-500/20"
          >
            <Plus className="w-4 h-4 text-black" />
            New Pipeline Job
          </button>
        </div>
      </div>

      {/* Main Table Card */}
      <div className="card-container !p-0 overflow-hidden border-border bg-card/65 backdrop-blur-md">
        <div className="p-4 border-b border-border flex flex-col sm:flex-row items-center gap-4 bg-slate-900/40">
          <div className="relative flex-1 w-full">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted" />
            <input 
              type="text" 
              placeholder="Search pipelines, jobs, or connectors..." 
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full bg-background border border-border rounded-md py-2 pl-10 pr-4 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground"
            />
          </div>
          <div className="flex items-center gap-2 text-sm text-muted relative self-end sm:self-auto">
            <span>Show:</span>
            <button 
              onClick={() => setShowFilterMenu(!showFilterMenu)}
              className="flex items-center gap-1 hover:text-foreground capitalize bg-secondary/50 px-2 py-1 rounded border border-border"
            >
              {statusFilter} <ChevronDown className="w-4 h-4" />
            </button>
            {showFilterMenu && (
              <div className="absolute right-0 top-8 bg-card border border-border rounded-md shadow-lg py-1 z-10 w-32 animate-in fade-in slide-in-from-top-2 duration-150">
                {['All', 'Running', 'Paused', 'Finished', 'Error', 'Ready'].map(status => (
                  <button
                    key={status}
                    onClick={() => {
                      setStatusFilter(status)
                      setShowFilterMenu(false)
                    }}
                    className="w-full text-left px-3 py-1.5 text-xs hover:bg-secondary transition-colors text-foreground capitalize"
                  >
                    {status}
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left">
            <thead>
              <tr className="bg-slate-900/50 border-b border-border text-xs uppercase tracking-wider text-muted">
                <th className="px-6 py-4 font-semibold">Job Details</th>
                <th className="px-6 py-4 font-semibold">Pipeline Flow</th>
                <th className="px-6 py-4 font-semibold">Scan Path</th>
                <th className="px-6 py-4 font-semibold">Status</th>
                <th className="px-6 py-4 font-semibold text-right">Documents</th>
                <th className="px-6 py-4 font-semibold">Last Run</th>
                <th className="px-6 py-4 font-semibold text-center">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {isLoading && jobs.length === 0 ? (
                <tr>
                  <td colSpan={7} className="text-center py-8">
                    <Loader2 className="w-8 h-8 animate-spin text-primary mx-auto" />
                  </td>
                </tr>
              ) : filteredJobs.length === 0 ? (
                <tr>
                  <td colSpan={7} className="text-center py-8 text-sm text-muted italic">
                    No jobs found matching criteria.
                  </td>
                </tr>
              ) : (
                filteredJobs.map((job) => (
                  <tr key={job.id} className="hover:bg-slate-800/30 transition-colors group">
                    {/* Job Details */}
                    <td className="px-6 py-4">
                      <div className="font-semibold text-foreground text-sm">{job.name}</div>
                      <div className="text-[10px] text-indigo-400 font-bold tracking-wide mt-1.5 flex items-center gap-1 font-mono uppercase bg-indigo-500/10 border border-indigo-500/20 px-2 py-0.5 rounded w-max" title="Embedding Model">
                        <Cpu className="w-3 h-3 text-indigo-400" />
                        {job.embeddingModel || 'mxbai-embed-large'}
                      </div>
                      <div className="text-xs text-muted-foreground mt-0.5 font-mono">ID: {job.id}</div>
                    </td>
                    
                    {/* Pipeline Flow */}
                    <td className="px-6 py-4">
                      <div className="flex items-center gap-1.5 flex-wrap">
                        <span className="inline-flex items-center gap-1 text-[11px] font-semibold bg-cyan-500/10 text-cyan-400 px-2 py-0.5 rounded border border-cyan-500/20" title="Repository Source">
                          <Plug2 className="w-3 h-3" />
                          {job.repositoryConnector || 'N/A'}
                        </span>
                        
                        {job.authorityConnector && (
                          <>
                            <ArrowRight className="w-3 h-3 text-muted-foreground" />
                            <span className="inline-flex items-center gap-1 text-[11px] font-semibold bg-purple-500/10 text-purple-400 px-2 py-0.5 rounded border border-purple-500/20" title="Security Authority">
                              <ShieldCheck className="w-3 h-3" />
                              {job.authorityConnector}
                            </span>
                          </>
                        )}
                        
                        <ArrowRight className="w-3 h-3 text-muted-foreground" />
                        
                        <span className="inline-flex items-center gap-1 text-[11px] font-semibold bg-emerald-500/10 text-emerald-400 px-2 py-0.5 rounded border border-emerald-500/20" title="Vector Output">
                          <Settings2 className="w-3 h-3" />
                          {job.outputConnector || 'N/A'}
                        </span>
                      </div>
                    </td>
                    
                    {/* Scan Path */}
                    <td className="px-6 py-4">
                      <div className="flex items-center gap-1.5 text-sm text-muted-foreground">
                        <FolderOpen className="w-4 h-4 text-cyan-500/60 flex-shrink-0" />
                        <span className="truncate max-w-[180px] font-mono text-xs" title={job.path}>{job.path}</span>
                      </div>
                    </td>
                    
                    {/* Status & Ingestion Pipeline Stage */}
                    <td className="px-6 py-4">
                      <div className="flex flex-col gap-1.5 min-w-[140px]">
                        <div className="flex items-center gap-1.5">
                          <span className={`px-2.5 py-0.5 rounded-full text-[10px] font-semibold border ${statusStyles[job.status]}`}>
                            {job.status}
                          </span>
                          {job.status === 'Running' && (
                            <Loader2 className="w-3.5 h-3.5 text-cyan-400 animate-spin flex-shrink-0" />
                          )}
                        </div>
                        {job.status === 'Running' && job.currentStage && (
                          <div className="space-y-1 pr-4 animate-in fade-in duration-300">
                            <div className="text-[10px] text-cyan-400 font-semibold tracking-wide flex justify-between gap-2">
                              <span className="capitalize truncate max-w-[85px]">{job.currentStage}...</span>
                              <span>{getStageProgress(job.currentStage)}%</span>
                            </div>
                            <div className="w-full bg-slate-800 rounded-full h-1 overflow-hidden">
                              <div 
                                className="bg-cyan-500 h-full rounded-full transition-all duration-500" 
                                style={{ width: `${getStageProgress(job.currentStage)}%` }}
                              />
                            </div>
                          </div>
                        )}
                        {job.status === 'Finished' && (
                          <span className="text-[10px] text-green-400/80 font-medium font-mono pl-1">Completed</span>
                        )}
                        {job.status === 'Paused' && (
                          <span className="text-[10px] text-yellow-400/80 font-medium font-mono pl-1">Paused</span>
                        )}
                        {job.status === 'Error' && (
                          <span className="text-[10px] text-red-400/80 font-medium font-mono pl-1">Failed</span>
                        )}
                      </div>
                    </td>
                    
                    {/* Documents */}
                    <td className="px-6 py-4 text-sm text-right font-mono text-foreground font-semibold">
                      {job.documents.toLocaleString()}
                    </td>
                    
                    {/* Last Run */}
                    <td className="px-6 py-4 text-sm text-muted-foreground">
                      {job.lastRun}
                    </td>
                    
                    {/* Actions */}
                    <td className="px-6 py-4">
                      <div className="flex items-center justify-center gap-1">
                        <button 
                          onClick={() => triggerAction(job.id, 'start')}
                          disabled={job.status === 'Running' || actionLoading !== null}
                          className="p-1.5 rounded hover:bg-cyan-500/10 hover:text-cyan-500 transition-colors disabled:opacity-30" 
                          title="Start crawl job"
                        >
                          <Play className="w-4 h-4 fill-current" />
                        </button>
                        <button 
                          onClick={() => triggerAction(job.id, 'pause')}
                          disabled={job.status !== 'Running' || actionLoading !== null}
                          className="p-1.5 rounded hover:bg-yellow-500/10 hover:text-yellow-500 transition-colors disabled:opacity-30" 
                          title="Pause crawl job"
                        >
                          <Pause className="w-4 h-4 fill-current" />
                        </button>
                        <button 
                          onClick={() => triggerAction(job.id, 'stop')}
                          disabled={(job.status !== 'Running' && job.status !== 'Paused') || actionLoading !== null}
                          className="p-1.5 rounded hover:bg-red-500/10 hover:text-red-500 transition-colors disabled:opacity-30" 
                          title="Stop crawl job"
                        >
                          <Square className="w-4 h-4 fill-current" />
                        </button>
                        
                        <div className="h-4 w-px bg-border mx-1" />

                        <button 
                          onClick={() => openEditForm(job)}
                          className="p-1.5 rounded hover:bg-slate-700 hover:text-foreground transition-colors"
                          title="Edit pipeline job"
                        >
                          <Edit className="w-4 h-4" />
                        </button>
                        <button 
                          onClick={() => handleDeleteJob(job.id, job.name)}
                          className="p-1.5 rounded hover:bg-red-500/10 hover:text-red-500 transition-colors"
                          title="Delete job"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        <div className="p-4 border-t border-border flex items-center justify-between text-sm text-muted">
          <span>Showing {filteredJobs.length} of {jobs.length} jobs</span>
          <div className="flex gap-2">
            <button className="px-3 py-1 border border-border rounded hover:bg-secondary disabled:opacity-50" disabled>Previous</button>
            <button className="px-3 py-1 border border-border rounded hover:bg-secondary disabled:opacity-50" disabled>Next</button>
          </div>
        </div>
      </div>

      {/* CONFIGURATION MODAL (Ingestion Pipeline Settings) */}
      {isFormOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-sm overflow-y-auto">
          <div className="bg-card border border-border rounded-xl w-full max-w-2xl overflow-hidden shadow-2xl animate-in zoom-in-95 duration-150 flex flex-col max-h-[90vh]">
            
            {/* Header */}
            <div className="p-6 border-b border-border flex items-center justify-between bg-slate-900/50">
              <div>
                <h2 className="text-xl font-bold flex items-center gap-2">
                  {editingJob ? <Edit className="w-5 h-5 text-primary" /> : <Plus className="w-5 h-5 text-primary" />}
                  {editingJob ? 'Edit Pipeline Job' : 'Create Ingestion Pipeline'}
                </h2>
                <p className="text-xs text-muted-foreground mt-1">
                  Configure the sources, security authorities, and target vector stores for document ingestion.
                </p>
              </div>
              <button 
                type="button"
                onClick={() => setIsFormOpen(false)}
                className="text-muted hover:text-foreground p-1 rounded-md hover:bg-secondary transition-colors"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            {/* Form */}
            <form onSubmit={handleSaveJob} className="flex-1 overflow-y-auto p-6 space-y-6">
              
              {/* Form Error Callout */}
              {formError && (
                <div className="p-3 bg-red-500/10 border border-red-500/20 text-red-400 text-sm rounded-lg flex items-center gap-2">
                  <AlertCircle className="w-4 h-4" />
                  <span>{formError}</span>
                </div>
              )}

              {/* Warning if no connectors configured */}
              {(repositoryConnectors.length === 0 || outputConnectors.length === 0) && (
                <div className="p-4 bg-amber-500/10 border border-amber-500/20 rounded-lg text-amber-400 text-sm flex gap-3 items-start">
                  <AlertCircle className="w-5 h-5 flex-shrink-0 mt-0.5 text-amber-500" />
                  <div>
                    <span className="font-bold text-amber-500">Missing Connectors!</span> You need at least one configured Repository Connector and one Output Connector to establish a pipeline job.
                    <button 
                      type="button" 
                      onClick={() => { setIsFormOpen(false); setActiveView('connectors'); }}
                      className="mt-2 block text-xs underline font-semibold text-cyan-400 hover:text-cyan-300 transition-colors"
                    >
                      Go configure connectors now →
                    </button>
                  </div>
                </div>
              )}

              {/* Job Info Section */}
              <div className="space-y-4">
                <h3 className="text-sm font-semibold text-muted-foreground uppercase tracking-wider">Job Details</h3>
                <div className="space-y-2">
                  <label className="text-sm font-medium">Job Name</label>
                  <input 
                    type="text"
                    value={formName}
                    onChange={(e) => setFormName(e.target.value)}
                    placeholder="e.g. Wiki_Filesystem_Ingest"
                    className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground"
                    required
                  />
                </div>
              </div>

              {/* Pipeline Mapping Visual */}
              <div className="space-y-3">
                <h3 className="text-sm font-semibold text-muted-foreground uppercase tracking-wider">Pipeline Preview</h3>
                <div className="bg-slate-950 p-4 rounded-lg border border-border flex flex-col md:flex-row items-center justify-between gap-3">
                  
                  {/* Repository Source Card */}
                  <div className="flex-1 text-center p-3 rounded bg-cyan-500/5 border border-cyan-500/20 w-full min-w-0">
                    <div className="text-[10px] text-cyan-400 font-bold uppercase tracking-wider mb-1 flex items-center justify-center gap-1">
                      <Plug2 className="w-3 h-3" /> Source
                    </div>
                    <div className="text-xs font-bold truncate text-foreground">
                      {formRepository || <span className="text-muted italic font-normal">No Source Selected</span>}
                    </div>
                  </div>

                  <ArrowRight className="w-4 h-4 text-muted flex-shrink-0 rotate-90 md:rotate-0" />

                  {/* Authority (if selected) */}
                  {formAuthority ? (
                    <>
                      <div className="flex-1 text-center p-3 rounded bg-purple-500/5 border border-purple-500/20 w-full min-w-0 animate-in fade-in zoom-in-95 duration-200">
                        <div className="text-[10px] text-purple-400 font-bold uppercase tracking-wider mb-1 flex items-center justify-center gap-1">
                          <ShieldCheck className="w-3 h-3" /> Security
                        </div>
                        <div className="text-xs font-bold truncate text-foreground">
                          {formAuthority}
                        </div>
                      </div>
                      <ArrowRight className="w-4 h-4 text-muted flex-shrink-0 rotate-90 md:rotate-0" />
                    </>
                  ) : null}

                  {/* Output target Card */}
                  <div className="flex-1 text-center p-3 rounded bg-emerald-500/5 border border-emerald-500/20 w-full min-w-0">
                    <div className="text-[10px] text-emerald-400 font-bold uppercase tracking-wider mb-1 flex items-center justify-center gap-1">
                      <Settings2 className="w-3 h-3" /> Destination
                    </div>
                    <div className="text-xs font-bold truncate text-foreground">
                      {formOutput || <span className="text-muted italic font-normal">No Target Selected</span>}
                    </div>
                  </div>

                </div>
              </div>

              {/* Pipeline Connectors Configuration */}
              <div className="space-y-4">
                <h3 className="text-sm font-semibold text-muted-foreground uppercase tracking-wider">Configure Ingestion Pipeline</h3>
                
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {/* Repositories */}
                  <div className="space-y-2">
                    <label className="text-sm font-medium flex items-center gap-1.5">
                      <Plug2 className="w-4 h-4 text-cyan-400" />
                      Repository Connector (Source)
                    </label>
                    <select 
                      value={formRepository}
                      onChange={(e) => setFormRepository(e.target.value)}
                      className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground"
                      required
                    >
                      <option value="">Select source connector...</option>
                      {repositoryConnectors.map(c => (
                        <option key={c.name} value={c.name}>{c.name} ({c.className.split('.').pop()})</option>
                      ))}
                    </select>
                  </div>

                  {/* Outputs */}
                  <div className="space-y-2">
                    <label className="text-sm font-medium flex items-center gap-1.5">
                      <Settings2 className="w-4 h-4 text-emerald-400" />
                      Output Connector (Target Vector Store)
                    </label>
                    <select 
                      value={formOutput}
                      onChange={(e) => setFormOutput(e.target.value)}
                      className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground"
                      required
                    >
                      <option value="">Select target connector...</option>
                      {outputConnectors.map(c => (
                        <option key={c.name} value={c.name}>{c.name} ({c.className.split('.').pop()})</option>
                      ))}
                    </select>
                  </div>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {/* Authority (Optional) */}
                  <div className="space-y-2">
                    <label className="text-sm font-medium flex items-center gap-1.5">
                      <ShieldCheck className="w-4 h-4 text-purple-400" />
                      Authority Connector (Security Group, Optional)
                    </label>
                    <select 
                      value={formAuthority}
                      onChange={(e) => setFormAuthority(e.target.value)}
                      className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground"
                    >
                      <option value="">None (Public Access)</option>
                      {authorityConnectors.map(c => (
                        <option key={c.name} value={c.name}>{c.name} ({c.className.split('.').pop()})</option>
                      ))}
                    </select>
                  </div>

                  {/* Path / Directory */}
                  <div className="space-y-2">
                    <label className="text-sm font-medium flex items-center gap-1.5">
                      <FolderOpen className="w-4 h-4 text-amber-400" />
                      Crawl Scan Path / URL
                    </label>
                    <input 
                      type="text"
                      value={formPath}
                      onChange={(e) => setFormPath(e.target.value)}
                      placeholder="/Users/me/documents or https://website.com"
                      className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground font-mono"
                      required
                    />
                  </div>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {/* Embedding Model */}
                  <div className="space-y-2">
                    <label className="text-sm font-medium flex items-center gap-1.5">
                      <Cpu className="w-4 h-4 text-indigo-400" />
                      Embedding Model Profile
                    </label>
                    <select 
                      value={formEmbeddingModel}
                      onChange={(e) => setFormEmbeddingModel(e.target.value)}
                      className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground"
                      required
                    >
                      <option value="mxbai-embed-large">mxbai-embed-large (1024-dim)</option>
                      <option value="nomic-embed-text">nomic-embed-text (768-dim)</option>
                      <option value="all-minilm">all-minilm (384-dim)</option>
                    </select>
                  </div>
                </div>
              </div>

              {/* Actions Footer */}
              <div className="flex justify-end gap-3 pt-6 border-t border-border mt-8 bg-slate-900/20">
                <button 
                  type="button" 
                  onClick={() => setIsFormOpen(false)} 
                  className="btn-secondary"
                >
                  Cancel
                </button>
                <button 
                  type="submit" 
                  disabled={isSaving || repositoryConnectors.length === 0 || outputConnectors.length === 0} 
                  className="btn-primary flex items-center gap-2 min-w-[120px] justify-center bg-cyan-500 hover:bg-cyan-600 text-black font-semibold"
                >
                  {isSaving ? <Loader2 className="w-4 h-4 animate-spin" /> : null}
                  {editingJob ? 'Update Pipeline' : 'Create Pipeline'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
