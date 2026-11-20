/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { CalendarClock, CheckCircle2, AlertTriangle, Loader2, Trash2, X } from 'lucide-react'
import {
  scheduleApi,
  problemMessage,
  type CronValidationResult,
  type JobScheduleConfig,
  type MisfirePolicy,
  type OverlapPolicy,
} from '../lib/api'

const CRON_PRESETS: ReadonlyArray<{ label: string; cron: string }> = [
  { label: 'Every 5 min', cron: '*/5 * * * *' },
  { label: 'Hourly', cron: '@hourly' },
  { label: 'Every 6 hours', cron: '0 */6 * * *' },
  { label: 'Nightly 02:00', cron: '0 2 * * *' },
  { label: 'Weekdays 08:00', cron: '0 8 * * MON-FRI' },
  { label: 'Monthly 1st 03:00', cron: '0 3 1 * *' },
]

const COMMON_TIMEZONES = [
  'UTC',
  'Europe/Rome',
  'Europe/London',
  'Europe/Berlin',
  'Europe/Paris',
  'America/New_York',
  'America/Chicago',
  'America/Los_Angeles',
  'Asia/Tokyo',
  'Asia/Singapore',
  'Australia/Sydney',
]

export interface ScheduleModalProps {
  open: boolean
  jobId: string
  jobName: string
  initialSchedule?: JobScheduleConfig | null
  initialEtag?: string | null
  onClose: () => void
  onSaved: () => void | Promise<void>
}

export default function ScheduleModal({
  open,
  jobId,
  jobName,
  initialSchedule,
  initialEtag,
  onClose,
  onSaved,
}: ScheduleModalProps) {
  const titleId = useId()
  const cronId = useId()
  const cronHintId = useId()
  const cronErrId = useId()
  const tzId = useId()
  const jitterId = useId()
  const maxFailId = useId()
  const startAtId = useId()
  const endAtId = useId()

  const dialogRef = useRef<HTMLDialogElement | null>(null)
  const detectedTz = useMemo(() => {
    try {
      return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
    } catch {
      return 'UTC'
    }
  }, [])

  const timezoneOptions = useMemo(() => {
    const extra = initialSchedule?.timezone ? [initialSchedule.timezone] : []
    return Array.from(new Set([detectedTz, 'UTC', ...COMMON_TIMEZONES, ...extra]))
  }, [detectedTz, initialSchedule])

  const [cron, setCron] = useState(initialSchedule?.cron ?? '0 2 * * *')
  const [timezone, setTimezone] = useState(initialSchedule?.timezone ?? detectedTz)
  const [enabled, setEnabled] = useState(initialSchedule?.enabled ?? true)
  const [overlapPolicy, setOverlapPolicy] = useState<OverlapPolicy>(initialSchedule?.overlapPolicy ?? 'SKIP')
  const [misfirePolicy, setMisfirePolicy] = useState<MisfirePolicy>(initialSchedule?.misfirePolicy ?? 'SKIP')
  const [jitterSeconds, setJitterSeconds] = useState(
    initialSchedule?.jitterSeconds !== undefined && initialSchedule?.jitterSeconds !== null
      ? String(initialSchedule.jitterSeconds)
      : '',
  )
  const [maxFailures, setMaxFailures] = useState(
    initialSchedule?.maxConsecutiveFailures !== undefined && initialSchedule?.maxConsecutiveFailures !== null
      ? String(initialSchedule.maxConsecutiveFailures)
      : '',
  )
  const [startAt, setStartAt] = useState(initialSchedule?.startAt ?? '')
  const [endAt, setEndAt] = useState(initialSchedule?.endAt ?? '')
  const [etag, setEtag] = useState<string | null>(initialEtag ?? null)
  const [hasExisting, setHasExisting] = useState(Boolean(initialSchedule))

  const [validation, setValidation] = useState<CronValidationResult | null>(null)
  const [validating, setValidating] = useState(false)
  const [saving, setSaving] = useState(false)
  const [removing, setRemoving] = useState(false)
  const [submitError, setSubmitError] = useState('')

  // Fetch latest server schedule + ETag asynchronously when open
  useEffect(() => {
    if (!open) return
    let cancelled = false
    scheduleApi.get(jobId).then((res) => {
      if (cancelled) return
      const s = res.data.schedule
      setCron(s.cron)
      setTimezone(s.timezone || 'UTC')
      setEnabled(s.enabled)
      setOverlapPolicy(s.overlapPolicy ?? 'SKIP')
      setMisfirePolicy(s.misfirePolicy ?? 'SKIP')
      setJitterSeconds(s.jitterSeconds !== undefined && s.jitterSeconds !== null ? String(s.jitterSeconds) : '')
      setMaxFailures(
        s.maxConsecutiveFailures !== undefined && s.maxConsecutiveFailures !== null
          ? String(s.maxConsecutiveFailures)
          : '',
      )
      setStartAt(s.startAt ?? '')
      setEndAt(s.endAt ?? '')
      setEtag(res.etag ?? null)
      setHasExisting(true)
    }).catch(() => {
      // Leave initial form values intact when job does not yet have a saved schedule
    })
    return () => {
      cancelled = true
    }
  }, [open, jobId])

  // Native <dialog> open/close lifecycle using showModal() so browser manages focus trap, inert page & Esc
  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    if (open) {
      if (!dialog.open) {
        if (typeof dialog.showModal === 'function') dialog.showModal()
        else dialog.setAttribute('open', '')
      }
    } else if (dialog.open) {
      if (typeof dialog.close === 'function') dialog.close()
      else dialog.removeAttribute('open')
    }
  }, [open])

  // Live debounced cron validation preview
  useEffect(() => {
    if (!open || !cron.trim()) return
    let cancelled = false
    const timer = setTimeout(async () => {
      setValidating(true)
      try {
        const res = await scheduleApi.validateCron({
          cron: cron.trim(),
          timezone: timezone.trim() || 'UTC',
          count: 5,
          startAt: startAt.trim() || null,
          endAt: endAt.trim() || null,
        })
        if (!cancelled) setValidation(res.data)
      } catch {
        if (!cancelled) setValidation(null)
      } finally {
        if (!cancelled) setValidating(false)
      }
    }, 250)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [open, cron, timezone, startAt, endAt])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (saving || removing) return
    setSubmitError('')
    setSaving(true)
    try {
      const payload: JobScheduleConfig = {
        cron: cron.trim(),
        timezone: timezone.trim() || 'UTC',
        enabled,
        overlapPolicy,
        misfirePolicy,
        jitterSeconds: jitterSeconds.trim() === '' ? null : Number(jitterSeconds),
        maxConsecutiveFailures: maxFailures.trim() === '' ? null : Number(maxFailures),
        startAt: startAt.trim() || null,
        endAt: endAt.trim() || null,
      }
      await scheduleApi.set(jobId, payload, hasExisting ? etag : null)
      await onSaved()
      onClose()
    } catch (err) {
      setSubmitError(problemMessage(err, 'Failed to save schedule.'))
    } finally {
      setSaving(false)
    }
  }

  const handleRemove = async () => {
    if (saving || removing) return
    setSubmitError('')
    setRemoving(true)
    try {
      await scheduleApi.delete(jobId, etag)
      await onSaved()
      onClose()
    } catch (err) {
      setSubmitError(problemMessage(err, 'Failed to remove schedule.'))
    } finally {
      setRemoving(false)
    }
  }

  const formatFireTime = (iso: string) => {
    try {
      return new Intl.DateTimeFormat(undefined, {
        dateStyle: 'medium',
        timeStyle: 'medium',
        timeZone: timezone || 'UTC',
      }).format(new Date(iso))
    } catch {
      return iso
    }
  }

  if (!open) return null

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby={titleId}
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
      onClose={() => onClose()}
      className="w-full max-w-2xl rounded-xl border border-border bg-card text-foreground shadow-2xl p-0 backdrop:bg-slate-950/75"
    >
      <div className="flex items-center justify-between border-b border-border px-6 py-4 bg-slate-900/60">
        <div className="flex items-center gap-2.5">
          <div className="p-2 rounded-lg bg-cyan-500/10 border border-cyan-500/20 text-cyan-400">
            <CalendarClock className="w-5 h-5" aria-hidden="true" />
          </div>
          <div>
            <h2 id={titleId} className="text-base font-bold text-foreground">
              Configure Schedule — {jobName}
            </h2>
            <p className="text-xs text-muted-foreground font-mono">Job ID: {jobId}</p>
          </div>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close schedule modal"
          className="p-1.5 rounded-lg text-muted-foreground hover:text-foreground hover:bg-secondary/60 transition-colors"
        >
          <X className="w-4 h-4" aria-hidden="true" />
        </button>
      </div>

      <form onSubmit={handleSubmit} className="p-6 space-y-5 max-h-[80vh] overflow-y-auto custom-scrollbar">
        {/* Quick presets */}
        <div>
          <span className="block text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-2">
            Quick Presets
          </span>
          <div className="flex flex-wrap gap-2">
            {CRON_PRESETS.map((preset) => {
              const active = cron.trim() === preset.cron
              return (
                <button
                  key={preset.cron}
                  type="button"
                  onClick={() => setCron(preset.cron)}
                  className={`px-2.5 py-1 rounded-md text-xs font-medium border transition-colors ${
                    active
                      ? 'bg-primary text-primary-foreground border-primary'
                      : 'bg-secondary/60 text-foreground border-border hover:bg-secondary'
                  }`}
                >
                  {preset.label}
                </button>
              )
            })}
          </div>
        </div>

        {/* Cron & Timezone */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div className="flex flex-col gap-1.5">
            <label htmlFor={cronId} className="text-xs font-semibold text-foreground">
              Cron Expression <span aria-hidden="true" className="text-red-400">*</span>
            </label>
            <span id={cronHintId} className="text-[11px] text-muted-foreground">
              5-field POSIX, 6-field Spring cron, or macro (@hourly, @daily)
            </span>
            <input
              id={cronId}
              name="cron"
              type="text"
              required
              autoFocus
              autoCorrect="off"
              autoCapitalize="none"
              spellCheck={false}
              value={cron}
              onChange={(e) => setCron(e.target.value)}
              aria-describedby={validation && !validation.valid ? `${cronHintId} ${cronErrId}` : cronHintId}
              aria-invalid={validation ? !validation.valid : undefined}
              placeholder="0 2 * * *"
              className="px-3 py-2 rounded-md border border-border bg-slate-950 font-mono text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <label htmlFor={tzId} className="text-xs font-semibold text-foreground">
              Timezone (IANA)
            </label>
            <span className="text-[11px] text-muted-foreground">
              Browser detected: <span className="font-mono text-cyan-400">{detectedTz}</span>
            </span>
            <input
              id={tzId}
              name="timezone"
              type="text"
              list={`${tzId}-list`}
              value={timezone}
              onChange={(e) => setTimezone(e.target.value)}
              className="px-3 py-2 rounded-md border border-border bg-slate-950 font-mono text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
            />
            <datalist id={`${tzId}-list`}>
              {timezoneOptions.map((tz) => (
                <option key={tz} value={tz} />
              ))}
            </datalist>
          </div>
        </div>

        {/* Live validation & next 5 fire times */}
        <div
          aria-live="polite"
          className={`rounded-lg border p-3.5 text-xs ${
            validation && !validation.valid
              ? 'bg-red-500/10 border-red-500/30 text-red-300'
              : 'bg-slate-900/70 border-border text-slate-300'
          }`}
        >
          <div className="flex items-center justify-between gap-2 font-semibold mb-1.5">
            <span className="flex items-center gap-1.5">
              {validating ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin text-cyan-400" aria-hidden="true" />
              ) : validation?.valid ? (
                <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" aria-hidden="true" />
              ) : (
                <AlertTriangle className="w-3.5 h-3.5 text-red-400" aria-hidden="true" />
              )}
              {validation
                ? validation.valid
                  ? validation.humanDescription || 'Valid cron schedule'
                  : 'Invalid schedule configuration'
                : 'Checking expression…'}
            </span>
            {validation?.valid && validation.normalizedCron && (
              <span className="font-mono text-[10px] text-muted-foreground">
                Normalized: {validation.normalizedCron}
              </span>
            )}
          </div>

          {validation && !validation.valid && validation.error && (
            <p id={cronErrId} className="text-red-300">
              {validation.error}
            </p>
          )}

          {validation?.valid && validation.nextFireTimes.length > 0 && (
            <div className="mt-2 pt-2 border-t border-border/60">
              <p className="text-[11px] uppercase tracking-wider text-muted-foreground mb-1">
                Next {validation.nextFireTimes.length} Upcoming Runs ({validation.timezone})
              </p>
              <ul className="grid grid-cols-1 sm:grid-cols-2 gap-1 font-mono text-[11px]">
                {validation.nextFireTimes.map((iso) => (
                  <li key={iso} className="text-cyan-300/90">
                    • {formatFireTime(iso)}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>

        {/* Enable toggle */}
        <div className="flex items-center gap-2.5">
          <input
            id={`${titleId}-enabled`}
            name="enabled"
            type="checkbox"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
            className="h-4 w-4 accent-cyan-500 rounded border-border"
          />
          <label htmlFor={`${titleId}-enabled`} className="text-sm font-medium text-foreground">
            Schedule active (automatically trigger runs on cron ticks)
          </label>
        </div>

        {/* Policies */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <fieldset className="rounded-lg border border-border p-3 space-y-2">
            <legend className="px-1 text-xs font-semibold text-foreground">Overlap Policy</legend>
            {(['SKIP', 'COALESCE', 'STOP_PREVIOUS'] as const).map((p) => (
              <label key={p} className="flex items-start gap-2 text-xs cursor-pointer">
                <input
                  type="radio"
                  name="overlapPolicy"
                  value={p}
                  checked={overlapPolicy === p}
                  onChange={() => setOverlapPolicy(p)}
                  className="mt-0.5 accent-cyan-500"
                />
                <span>
                  <strong className="font-mono">{p}</strong>{' '}
                  <span className="text-muted-foreground">
                    {p === 'SKIP'
                      ? 'Skip tick while active'
                      : p === 'COALESCE'
                        ? 'Run once right after active run finishes'
                        : 'Stop running crawl and start new'}
                  </span>
                </span>
              </label>
            ))}
          </fieldset>

          <fieldset className="rounded-lg border border-border p-3 space-y-2">
            <legend className="px-1 text-xs font-semibold text-foreground">Misfire Policy</legend>
            {(['SKIP', 'FIRE_ONCE'] as const).map((m) => (
              <label key={m} className="flex items-start gap-2 text-xs cursor-pointer">
                <input
                  type="radio"
                  name="misfirePolicy"
                  value={m}
                  checked={misfirePolicy === m}
                  onChange={() => setMisfirePolicy(m)}
                  className="mt-0.5 accent-cyan-500"
                />
                <span>
                  <strong className="font-mono">{m}</strong>{' '}
                  <span className="text-muted-foreground">
                    {m === 'SKIP'
                      ? 'Skip missed ticks after grace period'
                      : 'Run catch-up execution once after downtime'}
                  </span>
                </span>
              </label>
            ))}
          </fieldset>
        </div>

        {/* Advanced guardrails */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div className="flex flex-col gap-1">
            <label htmlFor={jitterId} className="text-xs font-semibold text-foreground">
              Jitter Window (seconds, 0–3600)
            </label>
            <input
              id={jitterId}
              name="jitterSeconds"
              type="text"
              inputMode="numeric"
              pattern="\d*"
              placeholder="0"
              value={jitterSeconds}
              onChange={(e) => setJitterSeconds(e.target.value)}
              className="px-3 py-2 rounded-md border border-border bg-slate-950 font-mono text-sm text-foreground"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor={maxFailId} className="text-xs font-semibold text-foreground">
              Auto-Pause After N Consecutive Failures
            </label>
            <input
              id={maxFailId}
              name="maxConsecutiveFailures"
              type="text"
              inputMode="numeric"
              pattern="\d*"
              placeholder="5 (0 disables)"
              value={maxFailures}
              onChange={(e) => setMaxFailures(e.target.value)}
              className="px-3 py-2 rounded-md border border-border bg-slate-950 font-mono text-sm text-foreground"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor={startAtId} className="text-xs font-semibold text-foreground">
              Active From (optional ISO-8601 UTC)
            </label>
            <input
              id={startAtId}
              name="startAt"
              type="text"
              placeholder="2026-11-20T00:00:00Z"
              value={startAt}
              onChange={(e) => setStartAt(e.target.value)}
              className="px-3 py-2 rounded-md border border-border bg-slate-950 font-mono text-xs text-foreground"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor={endAtId} className="text-xs font-semibold text-foreground">
              Active Until (optional ISO-8601 UTC)
            </label>
            <input
              id={endAtId}
              name="endAt"
              type="text"
              placeholder="2027-01-01T00:00:00Z"
              value={endAt}
              onChange={(e) => setEndAt(e.target.value)}
              className="px-3 py-2 rounded-md border border-border bg-slate-950 font-mono text-xs text-foreground"
            />
          </div>
        </div>

        {submitError && (
          <div role="alert" className="rounded-md border border-red-500/30 bg-red-500/10 p-3 text-xs text-red-300">
            {submitError}
          </div>
        )}

        <div className="flex flex-wrap items-center justify-between gap-3 pt-2 border-t border-border">
          {hasExisting ? (
            <button
              type="button"
              onClick={handleRemove}
              disabled={saving || removing}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md border border-red-500/30 bg-red-500/10 text-red-400 hover:bg-red-500/20 text-xs font-semibold disabled:opacity-50"
            >
              {removing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Trash2 className="w-3.5 h-3.5" />}
              Remove Schedule
            </button>
          ) : (
            <span />
          )}

          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-md border border-border bg-secondary hover:bg-secondary/80 text-xs font-semibold text-foreground"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={saving || removing || (validation !== null && !validation.valid)}
              className="btn-primary text-xs inline-flex items-center gap-1.5 disabled:opacity-50"
            >
              {saving && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
              Save Schedule
            </button>
          </div>
        </div>
      </form>
    </dialog>
  )
}
