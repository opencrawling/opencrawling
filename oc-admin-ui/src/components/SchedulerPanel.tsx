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
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  AlertTriangle,
  CalendarClock,
  Clock,
  Edit,
  History,
  Loader2,
  Pause,
  Play,
  Plus,
  RefreshCcw,
  Trash2,
  X,
} from 'lucide-react'
import {
  jobApi,
  problemMessage,
  scheduleApi,
  type DispatchOutcome,
  type Job,
  type JobScheduleResource,
  type ScheduleDispatchRecord,
  type SchedulerOverviewResource,
} from '../lib/api'
import { useAuth } from '../lib/useAuth'
import ScheduleModal from './ScheduleModal'

const outcomeStyles: Record<DispatchOutcome, string> = {
  STARTED: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20',
  SKIPPED_OVERLAP: 'bg-yellow-500/10 text-yellow-400 border-yellow-500/20',
  SKIPPED_CAPACITY: 'bg-amber-500/10 text-amber-400 border-amber-500/20',
  MISSED_SKIPPED: 'bg-slate-500/10 text-slate-300 border-slate-500/30',
  REJECTED: 'bg-red-500/10 text-red-400 border-red-500/20',
  AUTO_PAUSED: 'bg-red-500/15 text-red-300 border-red-500/30',
}

export default function SchedulerPanel() {
  const { me } = useAuth()
  const authzOn = me?.managementAuthorization ?? false

  const [overview, setOverview] = useState<SchedulerOverviewResource | null>(null)
  const [schedules, setSchedules] = useState<JobScheduleResource[]>([])
  const [jobs, setJobs] = useState<Job[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'PAUSED' | 'AUTO_PAUSED'>('ALL')
  const [searchQuery, setSearchQuery] = useState('')
  const [busyJobId, setBusyJobId] = useState<string | null>(null)

  // Modal state for creating / editing a schedule
  const [modalTarget, setModalTarget] = useState<{
    jobId: string
    jobName: string
    schedule?: JobScheduleResource
  } | null>(null)
  const [addingJobId, setAddingJobId] = useState('')

  // Dispatch audit drawer
  const [dispatchJob, setDispatchJob] = useState<JobScheduleResource | null>(null)
  const [dispatches, setDispatches] = useState<ScheduleDispatchRecord[]>([])
  const [dispatchesLoading, setDispatchesLoading] = useState(false)

  const permissionsByJob = useMemo(
    () => Object.fromEntries(jobs.map((j) => [j.id, j.permissions])),
    [jobs],
  )
  const canManageJob = (jobId: string) =>
    !authzOn || (permissionsByJob[jobId] ?? []).includes('MANAGE')

  const refresh = useCallback(async (showSpinner = false) => {
    if (showSpinner) setLoading(true)
    setError('')
    try {
      const [ovRes, listRes, jobsRes] = await Promise.all([
        scheduleApi.overview('24h', 50),
        scheduleApi.list(),
        jobApi.getAll(),
      ])
      setOverview(ovRes.data)
      setSchedules(listRes.data)
      setJobs(jobsRes.data)
    } catch (err) {
      setError(problemMessage(err, 'Failed to load scheduler overview.'))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    let active = true
    const load = async () => {
      try {
        const [ovRes, listRes, jobsRes] = await Promise.all([
          scheduleApi.overview('24h', 50),
          scheduleApi.list(),
          jobApi.getAll(),
        ])
        if (!active) return
        setOverview(ovRes.data)
        setSchedules(listRes.data)
        setJobs(jobsRes.data)
      } catch (err) {
        if (active) setError(problemMessage(err, 'Failed to load scheduler overview.'))
      } finally {
        if (active) setLoading(false)
      }
    }
    void load()
    const timer = setInterval(() => void load(), 10000)
    return () => {
      active = false
      clearInterval(timer)
    }
  }, [])

  const loadDispatches = async (item: JobScheduleResource) => {
    setDispatchJob(item)
    setDispatchesLoading(true)
    try {
      const res = await scheduleApi.dispatches(item.jobId)
      setDispatches(res.data)
    } catch (err) {
      setError(problemMessage(err, 'Failed to load dispatch history.'))
    } finally {
      setDispatchesLoading(false)
    }
  }

  const handleToggle = async (item: JobScheduleResource) => {
    setBusyJobId(item.jobId)
    setError('')
    try {
      const latest = await scheduleApi.get(item.jobId)
      const currentlyEnabled = item.state?.enabled ?? item.schedule.enabled
      if (currentlyEnabled) {
        await scheduleApi.pause(item.jobId, latest.etag)
      } else {
        await scheduleApi.resume(item.jobId, latest.etag)
      }
      await refresh(false)
    } catch (err) {
      setError(problemMessage(err, 'Failed to update schedule state.'))
    } finally {
      setBusyJobId(null)
    }
  }

  const handleDelete = async (item: JobScheduleResource) => {
    if (!confirm(`Remove the recurring cron schedule from "${item.jobName}"?`)) return
    setBusyJobId(item.jobId)
    setError('')
    try {
      await scheduleApi.delete(item.jobId)
      if (dispatchJob?.jobId === item.jobId) setDispatchJob(null)
      await refresh(false)
    } catch (err) {
      setError(problemMessage(err, 'Failed to remove schedule.'))
    } finally {
      setBusyJobId(null)
    }
  }

  const unscheduledManageableJobs = useMemo(() => {
    const scheduledIds = new Set(schedules.map((s) => s.jobId))
    return jobs.filter((j) => !scheduledIds.has(j.id) && (!authzOn || j.permissions.includes('MANAGE')))
  }, [jobs, schedules, authzOn])

  const filteredSchedules = useMemo(() => {
    return schedules.filter((item) => {
      const matchesQuery =
        !searchQuery.trim() ||
        item.jobName.toLowerCase().includes(searchQuery.toLowerCase()) ||
        item.schedule.cron.toLowerCase().includes(searchQuery.toLowerCase())
      if (!matchesQuery) return false
      const active = item.state?.enabled ?? item.schedule.enabled
      const autoPaused = item.state?.lastOutcome === 'AUTO_PAUSED'
      if (statusFilter === 'ACTIVE') return active
      if (statusFilter === 'PAUSED') return !active
      if (statusFilter === 'AUTO_PAUSED') return autoPaused
      return true
    })
  }, [schedules, searchQuery, statusFilter])

  // Aggregate next 24h upcoming executions into hourly UTC buckets
  const hourlyBuckets = useMemo(() => {
    const counts = Array.from({ length: 24 }, (_, hour) => ({ hour, items: [] as string[] }))
    for (const u of overview?.upcoming ?? []) {
      const d = new Date(u.scheduledAt)
      if (!Number.isNaN(d.getTime())) {
        counts[d.getUTCHours()].items.push(u.jobName)
      }
    }
    return counts
  }, [overview])

  const nextRunIso = overview?.upcoming?.[0]?.scheduledAt ?? null

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-foreground flex items-center gap-2.5">
            <CalendarClock className="w-6 h-6 text-cyan-400" aria-hidden="true" />
            Native Job Scheduler
          </h1>
          <p className="text-sm text-muted-foreground mt-0.5">
            Cluster-safe recurring cron schedules, overlap policies, and dispatch audit history.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {unscheduledManageableJobs.length > 0 && (
            <div className="flex items-center gap-2">
              <label htmlFor="add-schedule-job" className="sr-only">
                Select job to schedule
              </label>
              <select
                id="add-schedule-job"
                value={addingJobId}
                onChange={(e) => setAddingJobId(e.target.value)}
                className="px-3 py-2 rounded-md border border-border bg-card text-xs text-foreground"
              >
                <option value="">Attach schedule to job…</option>
                {unscheduledManageableJobs.map((j) => (
                  <option key={j.id} value={j.id}>
                    {j.name}
                  </option>
                ))}
              </select>
              <button
                type="button"
                disabled={!addingJobId}
                onClick={() => {
                  const chosen = unscheduledManageableJobs.find((j) => j.id === addingJobId)
                  if (chosen) {
                    setModalTarget({ jobId: chosen.id, jobName: chosen.name })
                    setAddingJobId('')
                  }
                }}
                className="btn-primary text-xs inline-flex items-center gap-1.5 disabled:opacity-40"
              >
                <Plus className="w-3.5 h-3.5" aria-hidden="true" />
                Add Schedule
              </button>
            </div>
          )}

          <button
            type="button"
            onClick={() => refresh(true)}
            className="p-2 rounded-md border border-border bg-card hover:bg-secondary/60 text-foreground"
            title="Refresh scheduler overview"
            aria-label="Refresh scheduler overview"
          >
            <RefreshCcw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} aria-hidden="true" />
          </button>
        </div>
      </div>

      {error && (
        <div role="alert" className="rounded-lg border border-red-500/30 bg-red-500/10 p-3 text-xs text-red-300">
          {error}
        </div>
      )}

      {/* KPI Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="card-container !p-4">
          <p className="text-xs text-muted-foreground">Active Schedules</p>
          <p className="text-2xl font-bold text-emerald-400 mt-1">{overview?.activeSchedules ?? 0}</p>
          <p className="text-[11px] text-muted-foreground mt-1">
            of {overview?.totalScheduledJobs ?? 0} configured
          </p>
        </div>

        <div className="card-container !p-4">
          <p className="text-xs text-muted-foreground">Paused / Circuit-Broken</p>
          <p className="text-2xl font-bold text-yellow-400 mt-1">{overview?.pausedSchedules ?? 0}</p>
          <p className="text-[11px] text-muted-foreground mt-1">
            {overview?.autoPausedSchedules ? (
              <span className="text-red-400 font-semibold">{overview.autoPausedSchedules} auto-paused</span>
            ) : (
              '0 auto-paused'
            )}
          </p>
        </div>

        <div className="card-container !p-4">
          <p className="text-xs text-muted-foreground">Skipped / Missed (24h)</p>
          <p className="text-2xl font-bold text-foreground mt-1">{overview?.skippedLast24h ?? 0}</p>
          <p className="text-[11px] text-muted-foreground mt-1">
            Engine: {overview?.schedulerEnabled === false ? 'Standby / CLI' : 'Active'}
          </p>
        </div>

        <div className="card-container !p-4">
          <p className="text-xs text-muted-foreground">Next Scheduled Fire</p>
          <p className="text-sm font-mono font-semibold text-cyan-400 mt-1 truncate" title={nextRunIso ?? 'None'}>
            {nextRunIso ? new Date(nextRunIso).toLocaleString() : 'None scheduled'}
          </p>
          <p className="text-[11px] text-muted-foreground mt-1">
            Default TZ: {overview?.defaultTimezone ?? 'UTC'}
          </p>
        </div>
      </div>

      {/* 24h Upcoming Execution Strip */}
      <div className="card-container !p-4 space-y-2.5">
        <div className="flex items-center justify-between text-xs">
          <h2 className="font-semibold text-foreground flex items-center gap-1.5">
            <Clock className="w-4 h-4 text-cyan-400" aria-hidden="true" />
            Upcoming 24h Execution Density (UTC Hours)
          </h2>
          <span className="text-[11px] text-muted-foreground">
            Highlighted amber slots indicate 2+ jobs aligned in the same hour
          </span>
        </div>
        <div className="grid grid-cols-6 sm:grid-cols-12 md:[grid-template-columns:repeat(24,minmax(0,1fr))] gap-1">
          {hourlyBuckets.map((b) => {
            const count = b.items.length
            const uniqueCount = new Set(b.items).size
            const tone =
              count === 0
                ? 'bg-slate-900/60 border-border text-slate-600'
                : uniqueCount >= 2
                  ? 'bg-amber-500/20 border-amber-500/40 text-amber-300 font-bold'
                  : 'bg-cyan-500/20 border-cyan-500/40 text-cyan-300 font-semibold'
            return (
              <div
                key={b.hour}
                title={
                  count === 0
                    ? `${String(b.hour).padStart(2, '0')}:00 UTC — idle`
                    : `${String(b.hour).padStart(2, '0')}:00 UTC — ${count} run(s): ${Array.from(new Set(b.items)).join(', ')}`
                }
                className={`rounded border px-1 py-1.5 text-center font-mono text-[10px] ${tone}`}
              >
                <div>{String(b.hour).padStart(2, '0')}</div>
                <div className="text-[9px]">{count || '·'}</div>
              </div>
            )
          })}
        </div>
      </div>

      {/* Main scheduled jobs table */}
      <div className="bg-card border border-border rounded-xl overflow-hidden shadow-sm">
        <div className="p-4 border-b border-border flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div className="relative flex-1 max-w-sm">
            <label htmlFor="schedule-search" className="sr-only">
              Filter scheduled jobs
            </label>
            <input
              id="schedule-search"
              type="search"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search job name or cron…"
              className="w-full px-3 py-1.5 rounded-md border border-border bg-slate-950 text-xs text-foreground"
            />
          </div>
          <div className="flex items-center gap-1.5 flex-wrap">
            {(['ALL', 'ACTIVE', 'PAUSED', 'AUTO_PAUSED'] as const).map((tab) => (
              <button
                key={tab}
                type="button"
                onClick={() => setStatusFilter(tab)}
                className={`px-2.5 py-1 rounded text-xs font-medium border ${
                  statusFilter === tab
                    ? 'bg-primary/20 border-primary/40 text-primary'
                    : 'bg-secondary/40 border-border text-muted-foreground hover:text-foreground'
                }`}
              >
                {tab.replace('_', ' ')}
              </button>
            ))}
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <caption className="sr-only">Configured pipeline job cron schedules</caption>
            <thead className="bg-slate-900/50 border-b border-border text-[11px] uppercase tracking-wider text-muted-foreground">
              <tr>
                <th scope="col" className="px-4 py-3 font-semibold">Job</th>
                <th scope="col" className="px-4 py-3 font-semibold">Cron &amp; Timezone</th>
                <th scope="col" className="px-4 py-3 font-semibold">Policies</th>
                <th scope="col" className="px-4 py-3 font-semibold">Status &amp; Next Fire</th>
                <th scope="col" className="px-4 py-3 font-semibold">Last Outcome</th>
                <th scope="col" className="px-4 py-3 font-semibold text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {loading && schedules.length === 0 ? (
                <tr>
                  <td colSpan={6} className="py-10 text-center">
                    <Loader2 className="w-6 h-6 animate-spin text-primary mx-auto" />
                  </td>
                </tr>
              ) : filteredSchedules.length === 0 ? (
                <tr>
                  <td colSpan={6} className="py-10 text-center text-muted-foreground italic">
                    No matching job schedules configured.
                  </td>
                </tr>
              ) : (
                filteredSchedules.map((item) => {
                  const enabled = item.state?.enabled ?? item.schedule.enabled
                  const autoPaused = item.state?.lastOutcome === 'AUTO_PAUSED'
                  const manageable = canManageJob(item.jobId)
                  const isBusy = busyJobId === item.jobId
                  return (
                    <tr key={item.jobId} className="hover:bg-slate-800/30 transition-colors">
                      <td className="px-4 py-3 font-semibold text-foreground">
                        <div>{item.jobName}</div>
                        <div className="text-[10px] font-mono text-muted-foreground">{item.jobId}</div>
                      </td>
                      <td className="px-4 py-3 font-mono">
                        <span className="px-2 py-0.5 rounded bg-slate-900 border border-border text-cyan-300">
                          {item.schedule.cron}
                        </span>
                        <div className="text-[10px] text-muted-foreground mt-1">
                          {item.schedule.timezone || 'UTC'}
                        </div>
                      </td>
                      <td className="px-4 py-3 text-[11px] font-mono text-muted-foreground">
                        <div>Overlap: {item.schedule.overlapPolicy || 'SKIP'}</div>
                        <div>Misfire: {item.schedule.misfirePolicy || 'SKIP'}</div>
                      </td>
                      <td className="px-4 py-3">
                        {autoPaused ? (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold bg-red-500/15 text-red-300 border border-red-500/30">
                            <AlertTriangle className="w-3 h-3" aria-hidden="true" />
                            AUTO PAUSED
                          </span>
                        ) : enabled ? (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                            Active
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold bg-yellow-500/10 text-yellow-400 border border-yellow-500/20">
                            Paused
                          </span>
                        )}
                        <div className="text-[10px] font-mono text-muted-foreground mt-1">
                          {item.state?.nextFireAt
                            ? new Date(item.state.nextFireAt).toLocaleString()
                            : 'No pending tick'}
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        {item.state?.lastOutcome ? (
                          <span
                            className={`inline-block px-2 py-0.5 rounded border text-[10px] font-semibold ${outcomeStyles[item.state.lastOutcome]}`}
                          >
                            {item.state.lastOutcome}
                          </span>
                        ) : (
                          <span className="text-muted-foreground italic">Never fired</span>
                        )}
                        {item.state?.lastDetail && (
                          <div className="text-[10px] text-muted-foreground mt-1 max-w-[200px] truncate" title={item.state.lastDetail}>
                            {item.state.lastDetail}
                          </div>
                        )}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <div className="inline-flex items-center gap-1">
                          {manageable && (
                            <button
                              type="button"
                              disabled={isBusy}
                              onClick={() => handleToggle(item)}
                              title={enabled ? 'Pause schedule' : 'Resume schedule'}
                              aria-label={enabled ? `Pause schedule for ${item.jobName}` : `Resume schedule for ${item.jobName}`}
                              className="p-1.5 rounded hover:bg-secondary text-foreground disabled:opacity-40"
                            >
                              {enabled ? <Pause className="w-4 h-4 text-yellow-400" /> : <Play className="w-4 h-4 text-emerald-400" />}
                            </button>
                          )}
                          <button
                            type="button"
                            onClick={() => loadDispatches(item)}
                            title="View dispatch audit history"
                            aria-label={`View dispatch audit history for ${item.jobName}`}
                            className="p-1.5 rounded hover:bg-secondary text-cyan-400"
                          >
                            <History className="w-4 h-4" />
                          </button>
                          {manageable && (
                            <>
                              <button
                                type="button"
                                onClick={() =>
                                  setModalTarget({ jobId: item.jobId, jobName: item.jobName, schedule: item })
                                }
                                title="Edit schedule"
                                aria-label={`Edit schedule for ${item.jobName}`}
                                className="p-1.5 rounded hover:bg-secondary text-foreground"
                              >
                                <Edit className="w-4 h-4" />
                              </button>
                              <button
                                type="button"
                                disabled={isBusy}
                                onClick={() => handleDelete(item)}
                                title="Remove schedule"
                                aria-label={`Remove schedule for ${item.jobName}`}
                                className="p-1.5 rounded hover:bg-red-500/10 text-red-400 disabled:opacity-40"
                              >
                                <Trash2 className="w-4 h-4" />
                              </button>
                            </>
                          )}
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

      {/* Dispatch Audit History Panel */}
      {dispatchJob && (
        <div className="card-container space-y-3">
          <div className="flex items-center justify-between border-b border-border pb-3">
            <div>
              <h2 className="text-sm font-bold text-foreground">
                Dispatch Audit History — {dispatchJob.jobName}
              </h2>
              <p className="text-xs text-muted-foreground font-mono">{dispatchJob.jobId}</p>
            </div>
            <button
              type="button"
              onClick={() => setDispatchJob(null)}
              aria-label="Close dispatch audit history"
              className="p-1.5 rounded hover:bg-secondary text-muted-foreground hover:text-foreground"
            >
              <X className="w-4 h-4" />
            </button>
          </div>

          {dispatchesLoading ? (
            <div className="py-6 text-center">
              <Loader2 className="w-5 h-5 animate-spin text-primary mx-auto" />
            </div>
          ) : dispatches.length === 0 ? (
            <p className="text-xs text-muted-foreground italic py-4">
              No dispatch events recorded for this schedule yet.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="border-b border-border text-[11px] uppercase text-muted-foreground">
                  <tr>
                    <th scope="col" className="py-2 pr-4">Dispatched At</th>
                    <th scope="col" className="py-2 pr-4">Outcome</th>
                    <th scope="col" className="py-2 pr-4">Execution ID</th>
                    <th scope="col" className="py-2 pr-4">Runtime</th>
                    <th scope="col" className="py-2">Detail</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border/60 font-mono text-[11px]">
                  {dispatches.map((d) => (
                    <tr key={d.id}>
                      <td className="py-2 pr-4">{new Date(d.dispatchedAt).toLocaleString()}</td>
                      <td className="py-2 pr-4">
                        <span className={`px-2 py-0.5 rounded border text-[10px] font-semibold ${outcomeStyles[d.outcome]}`}>
                          {d.outcome}
                        </span>
                      </td>
                      <td className="py-2 pr-4">{d.executionId ?? '-'}</td>
                      <td className="py-2 pr-4">{d.runtimeId ?? '-'}</td>
                      <td className="py-2 font-sans text-muted-foreground">{d.detail ?? ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Modal Editor */}
      {modalTarget && (
        <ScheduleModal
          open={Boolean(modalTarget)}
          jobId={modalTarget.jobId}
          jobName={modalTarget.jobName}
          initialSchedule={modalTarget.schedule?.schedule}
          onClose={() => setModalTarget(null)}
          onSaved={() => refresh(false)}
        />
      )}
    </div>
  )
}
