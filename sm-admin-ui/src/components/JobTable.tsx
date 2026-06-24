import { useState, useEffect } from 'react'
import { 
  Play, 
  Pause, 
  Square, 
  RefreshCcw, 
  MoreHorizontal,
  ChevronDown,
  Search,
  Filter,
  Loader2
} from 'lucide-react'
import { jobApi } from '../lib/api'

type JobStatus = 'Running' | 'Paused' | 'Error' | 'Finished' | 'Ready'

interface Job {
  id: string
  name: string
  type: string
  status: JobStatus
  documents: number
  lastRun: string
}

const statusStyles: Record<JobStatus, string> = {
  Running: 'bg-cyan-500/10 text-cyan-500 border-cyan-500/20',
  Paused: 'bg-yellow-500/10 text-yellow-500 border-yellow-500/20',
  Error: 'bg-red-500/10 text-red-500 border-red-500/20',
  Finished: 'bg-green-500/10 text-green-500 border-green-500/20',
  Ready: 'bg-slate-500/10 text-slate-400 border-slate-500/20',
}

export default function JobTable() {
  const [jobs, setJobs] = useState<Job[]>([])
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('All')
  const [isLoading, setIsLoading] = useState(false)
  const [actionLoading, setActionLoading] = useState<string | null>(null)
  const [showFilterMenu, setShowFilterMenu] = useState(false)

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

  useEffect(() => {
    fetchJobs(true)
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

  const filteredJobs = jobs.filter(j => {
    const matchesSearch = j.name.toLowerCase().includes(searchQuery.toLowerCase()) || j.id.includes(searchQuery)
    const matchesStatus = statusFilter === 'All' || j.status.toLowerCase() === statusFilter.toLowerCase()
    return matchesSearch && matchesStatus
  })

  return (
    <div className="space-y-6 animate-in fade-in duration-500">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">Job Management</h1>
          <p className="text-muted text-sm">Monitor and control your data ingestion pipelines.</p>
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
             onClick={triggerRunAll}
             disabled={isLoading || jobs.length === 0}
             className="btn-primary flex items-center gap-2"
           >
              <Play className="w-4 h-4 fill-current" />
              Run All
           </button>
        </div>
      </div>

      <div className="card-container !p-0 overflow-hidden">
        <div className="p-4 border-b border-border flex items-center gap-4 bg-slate-900/50">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted" />
            <input 
              type="text" 
              placeholder="Search jobs..." 
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full bg-background border border-border rounded-md py-2 pl-10 pr-4 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground"
            />
          </div>
          <div className="flex items-center gap-2 text-sm text-muted relative">
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
                <th className="px-6 py-4 font-semibold">Job Name</th>
                <th className="px-6 py-4 font-semibold">Type</th>
                <th className="px-6 py-4 font-semibold">Status</th>
                <th className="px-6 py-4 font-semibold text-right">Documents</th>
                <th className="px-6 py-4 font-semibold">Last Run</th>
                <th className="px-6 py-4 font-semibold text-center">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {isLoading && jobs.length === 0 ? (
                <tr>
                  <td colSpan={6} className="text-center py-8">
                    <Loader2 className="w-8 h-8 animate-spin text-primary mx-auto" />
                  </td>
                </tr>
              ) : filteredJobs.length === 0 ? (
                <tr>
                  <td colSpan={6} className="text-center py-8 text-sm text-muted italic">
                    No jobs found matching criteria.
                  </td>
                </tr>
              ) : (
                filteredJobs.map((job) => (
                  <tr key={job.id} className="hover:bg-slate-800/30 transition-colors group">
                    <td className="px-6 py-4">
                      <div className="font-medium text-foreground">{job.name}</div>
                      <div className="text-xs text-muted truncate max-w-[200px]">ID: {job.id}</div>
                    </td>
                    <td className="px-6 py-4 text-sm text-muted">{job.type}</td>
                    <td className="px-6 py-4">
                      <span className={`px-2.5 py-1 rounded-full text-xs font-medium border ${statusStyles[job.status]}`}>
                        {job.status}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-sm text-right font-mono">
                      {job.documents.toLocaleString()}
                    </td>
                    <td className="px-6 py-4 text-sm text-muted">
                      {job.lastRun}
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex items-center justify-center gap-2">
                        <button 
                          onClick={() => triggerAction(job.id, 'start')}
                          disabled={job.status === 'Running' || actionLoading !== null}
                          className="p-1.5 rounded-md hover:bg-cyan-500/10 hover:text-cyan-500 transition-colors disabled:opacity-30" 
                          title="Start"
                        >
                          <Play className="w-4 h-4 fill-current" />
                        </button>
                        <button 
                          onClick={() => triggerAction(job.id, 'pause')}
                          disabled={job.status !== 'Running' || actionLoading !== null}
                          className="p-1.5 rounded-md hover:bg-yellow-500/10 hover:text-yellow-500 transition-colors disabled:opacity-30" 
                          title="Pause"
                        >
                          <Pause className="w-4 h-4 fill-current" />
                        </button>
                        <button 
                          onClick={() => triggerAction(job.id, 'stop')}
                          disabled={(job.status !== 'Running' && job.status !== 'Paused') || actionLoading !== null}
                          className="p-1.5 rounded-md hover:bg-red-500/10 hover:text-red-500 transition-colors disabled:opacity-30" 
                          title="Stop"
                        >
                          <Square className="w-4 h-4 fill-current" />
                        </button>
                        <button className="p-1.5 rounded-md hover:bg-slate-700 transition-colors">
                          <MoreHorizontal className="w-4 h-4" />
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
    </div>
  )
}
