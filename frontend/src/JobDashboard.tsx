import { type FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ApiRequestError,
  getJob,
  submitReport,
  type JobResponse,
  type JobStatus,
} from './api/client'
import JobInsights from './JobInsights'

const POLL_DELAY_MS = 3_000
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function isActive(status: JobStatus): boolean {
  return status === 'QUEUED' || status === 'RUNNING' || status === 'RETRY_WAIT'
}

function displayError(error: unknown): string {
  if (error instanceof ApiRequestError) return error.message
  return 'Could not reach the backend. Check that TaskForge is running, then try again.'
}

function validateReportName(value: string): string | null {
  const name = value.trim()
  if (!name) return 'Enter a report name.'
  if (name.length > 100) return 'Use 100 characters or fewer.'
  if (/[\u0000-\u001f\u007f-\u009f]/.test(name)) return 'Use a single-line report name.'
  return null
}

function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

export default function JobDashboard() {
  const [reportName, setReportName] = useState('')
  const [submissionError, setSubmissionError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [lookupId, setLookupId] = useState('')
  const [lookupError, setLookupError] = useState('')
  const [lookingUp, setLookingUp] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [pollError, setPollError] = useState('')
  const [jobs, setJobs] = useState<JobResponse[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const latestRequest = useRef(new Map<string, number>())

  const selectedJob = jobs.find((job) => job.id === selectedId) ?? null
  const activeIds = jobs.filter((job) => isActive(job.status)).map((job) => job.id).sort().join('|')
  const counts = useMemo(() => ({
    visible: jobs.length,
    succeeded: jobs.filter((job) => job.status === 'SUCCEEDED').length,
    failed: jobs.filter((job) => job.status === 'FAILED').length,
    active: jobs.filter((job) => isActive(job.status)).length,
  }), [jobs])

  const saveJob = useCallback((incoming: JobResponse) => {
    setJobs((current) => {
      const existing = current.find((job) => job.id === incoming.id)
      if (!existing) return [incoming, ...current]
      if (Date.parse(incoming.updatedAt) < Date.parse(existing.updatedAt)) return current
      return current.map((job) => job.id === incoming.id ? incoming : job)
    })
  }, [])

  const loadJob = useCallback(async (id: string, signal?: AbortSignal) => {
    const version = (latestRequest.current.get(id) ?? 0) + 1
    latestRequest.current.set(id, version)
    const job = await getJob(id, signal)
    if (!signal?.aborted && latestRequest.current.get(id) === version) saveJob(job)
    return job
  }, [saveJob])

  useEffect(() => {
    if (!activeIds) return

    const ids = activeIds.split('|')
    const controller = new AbortController()
    let stopped = false
    let timer: number

    async function poll() {
      const results = await Promise.allSettled(ids.map((id) => loadJob(id, controller.signal)))
      if (stopped) return

      let failed = false
      for (const result of results) if (result.status === 'rejected') failed = true
      setPollError(failed ? 'Some job statuses could not be refreshed. Retrying shortly.' : '')
      timer = window.setTimeout(poll, POLL_DELAY_MS)
    }

    timer = window.setTimeout(poll, POLL_DELAY_MS)
    return () => {
      stopped = true
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [activeIds, loadJob])

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const error = validateReportName(reportName)
    if (error) {
      setSubmissionError(error)
      return
    }

    setSubmitting(true)
    setSubmissionError('')
    try {
      const job = await submitReport(reportName.trim())
      saveJob(job)
      setSelectedId(job.id)
      setReportName('')
    } catch (cause) {
      setSubmissionError(displayError(cause))
    } finally {
      setSubmitting(false)
    }
  }

  async function handleLookup(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const id = lookupId.trim()
    if (!UUID_PATTERN.test(id)) {
      setLookupError('Enter a valid job UUID.')
      return
    }

    setLookingUp(true)
    setLookupError('')
    try {
      const job = await loadJob(id)
      setSelectedId(job.id)
      setLookupId('')
    } catch (cause) {
      setLookupError(displayError(cause))
    } finally {
      setLookingUp(false)
    }
  }

  async function refreshSelected() {
    if (!selectedJob || refreshing) return
    setRefreshing(true)
    setPollError('')
    try {
      await loadJob(selectedJob.id)
    } catch (cause) {
      setPollError(displayError(cause))
    } finally {
      setRefreshing(false)
    }
  }

  return (
    <>
      <section aria-labelledby="overview-heading" className="section-block">
        <div className="section-heading">
          <h2 id="overview-heading">Overview</h2>
          <p>Jobs submitted or looked up in this browser tab.</p>
        </div>
        <div className="summary-grid">
          {[
            ['Visible jobs', counts.visible],
            ['Succeeded', counts.succeeded],
            ['Failed', counts.failed],
            ['Active or waiting', counts.active],
          ].map(([label, value]) => (
            <div className="summary-card" key={label}>
              <span>{label}</span>
              <strong>{value}</strong>
            </div>
          ))}
        </div>
        <p className="note">
          TaskForge has no list-all-jobs API. These counts use the last fetched
          status of session-visible jobs, not database-wide totals. They reset on reload.
        </p>
      </section>

      <section className="section-block two-column" aria-label="Job actions">
        <div className="content-card">
          <p className="eyebrow">New job</p>
          <h2>Submit a report preview</h2>
          <p className="muted">The worker creates simulated text, not a downloadable report.</p>
          <form onSubmit={handleSubmit} noValidate>
            <label htmlFor="report-name">Report name</label>
            <input
              id="report-name"
              name="reportName"
              type="text"
              maxLength={100}
              autoComplete="off"
              placeholder="Quarterly report"
              value={reportName}
              onChange={(event) => { setReportName(event.target.value); setSubmissionError('') }}
              aria-invalid={Boolean(submissionError)}
              aria-describedby={submissionError ? 'report-help report-error' : 'report-help'}
            />
            <p id="report-help" className="field-help">One line, 1–100 characters.</p>
            {submissionError && <p id="report-error" className="form-error" role="alert">{submissionError}</p>}
            <button className="primary-button" type="submit" disabled={submitting}>
              {submitting ? 'Submitting…' : 'Submit job'}
            </button>
          </form>
        </div>

        <div className="content-card">
          <p className="eyebrow">Existing job</p>
          <h2>Track by ID</h2>
          <p className="muted">Look up a known job and add it to this tab’s overview.</p>
          <form onSubmit={handleLookup} noValidate>
            <label htmlFor="job-id">Job UUID</label>
            <input
              id="job-id"
              name="jobId"
              type="text"
              autoComplete="off"
              placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
              value={lookupId}
              onChange={(event) => { setLookupId(event.target.value); setLookupError('') }}
              aria-invalid={Boolean(lookupError)}
              aria-describedby={lookupError ? 'lookup-error' : undefined}
            />
            {lookupError && <p id="lookup-error" className="form-error" role="alert">{lookupError}</p>}
            <button type="submit" disabled={lookingUp}>
              {lookingUp ? 'Looking up…' : 'Track job'}
            </button>
          </form>
        </div>
      </section>

      <section className="section-block two-column details-layout" aria-label="Tracked jobs">
        <div className="content-card">
          <h2>This tab’s jobs</h2>
          {jobs.length === 0 ? (
            <p className="muted">Submit a job or look one up to see it here.</p>
          ) : (
            <ul className="job-list">
              {jobs.map((job) => (
                <li key={job.id}>
                  <button
                    type="button"
                    className={`job-choice ${selectedId === job.id ? 'job-choice--selected' : ''}`}
                    onClick={() => setSelectedId(job.id)}
                    aria-pressed={selectedId === job.id}
                  >
                    <span>{job.payload.reportName}</span>
                    <span className={`status-pill status-pill--${job.status.toLowerCase()}`}>{job.status}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="content-card">
          <div className="details-heading">
            <h2>Job status</h2>
            {selectedJob && <button type="button" onClick={refreshSelected} disabled={refreshing}>
              {refreshing ? 'Refreshing…' : 'Refresh now'}
            </button>}
          </div>
          {pollError && <p className="form-error" role="alert">{pollError}</p>}
          {!selectedJob ? (
            <p className="muted">Select a tracked job to inspect its current state.</p>
          ) : (
            <>
              <p className="status-line">
                <span className={`status-pill status-pill--${selectedJob.status.toLowerCase()}`}>
                  {selectedJob.status}
                </span>
                {isActive(selectedJob.status) && <span className="muted">Automatically refreshing every 3 seconds</span>}
              </p>
              <dl className="job-facts">
                <div><dt>Job ID</dt><dd className="job-id">{selectedJob.id}</dd></div>
                <div><dt>Type</dt><dd>{selectedJob.jobType}</dd></div>
                <div><dt>Attempts started</dt><dd>{selectedJob.attemptCount}</dd></div>
                <div><dt>Created</dt><dd>{formatTime(selectedJob.createdAt)}</dd></div>
                <div><dt>Last updated</dt><dd>{formatTime(selectedJob.updatedAt)}</dd></div>
                {selectedJob.failureReason &&
                  <div><dt>Failure reason</dt><dd>{selectedJob.failureReason}</dd></div>}
              </dl>
            </>
          )}
        </div>
      </section>

      <JobInsights key={selectedJob?.id ?? 'none'} job={selectedJob} />
    </>
  )
}
