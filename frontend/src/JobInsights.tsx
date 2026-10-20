import { useEffect, useState } from 'react'
import {
  ApiRequestError,
  getAttempts,
  getResult,
  type JobAttemptResponse,
  type JobResponse,
  type JobResultResponse,
} from './api/client'

type LoadState<T> =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; value: T }
  | { kind: 'error'; message: string }

function requestError(error: unknown): string {
  if (error instanceof ApiRequestError) {
    if (error.code === 'JOB_NOT_FOUND') return 'This job could no longer be found.'
    if (error.code === 'RESULT_UNAVAILABLE') return 'This successful job has no stored preview (it may predate result storage).'
    return error.message
  }
  return 'Could not reach the backend. Check that TaskForge is running, then try again.'
}

function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

export default function JobInsights({ job }: { job: JobResponse | null }) {
  const [history, setHistory] = useState<LoadState<JobAttemptResponse[]>>({ kind: 'idle' })
  const [result, setResult] = useState<LoadState<JobResultResponse>>({ kind: 'idle' })
  const [refreshKey, setRefreshKey] = useState(0)

  useEffect(() => {
    if (!job) return

    const controller = new AbortController()
    const id = job.id
    setHistory({ kind: 'loading' })
    getAttempts(id, controller.signal)
      .then((attempts) => {
        if (!controller.signal.aborted) setHistory({ kind: 'ready', value: attempts })
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setHistory({ kind: 'error', message: requestError(error) })
      })

    if (job.status === 'SUCCEEDED') {
      setResult({ kind: 'loading' })
      getResult(id, controller.signal)
        .then((preview) => {
          if (!controller.signal.aborted) setResult({ kind: 'ready', value: preview })
        })
        .catch((error: unknown) => {
          if (!controller.signal.aborted) setResult({ kind: 'error', message: requestError(error) })
        })
    } else {
      setResult({ kind: 'idle' })
    }

    return () => controller.abort()
  }, [job?.id, job?.status, job?.attemptCount, refreshKey])

  return (
    <section className="section-block two-column" aria-label="Job execution details">
      <div className="content-card">
        <div className="details-heading">
          <h2>Attempt history</h2>
          {job && <button type="button" onClick={() => setRefreshKey((value) => value + 1)}>Refresh details</button>}
        </div>
        {!job && <p className="muted">Select a tracked job to see its execution attempts.</p>}
        {job && history.kind === 'loading' && <p className="muted" role="status">Loading attempts…</p>}
        {job && history.kind === 'error' && <p className="form-error" role="alert">{history.message}</p>}
        {job && history.kind === 'ready' && history.value.length === 0 &&
          <p className="muted">No attempts have started for this job yet.</p>}
        {job && history.kind === 'ready' && history.value.length > 0 && (
          <ol className="attempt-list">
            {history.value.map((attempt) => (
              <li className="attempt-card" key={attempt.attemptNumber}>
                <div className="attempt-heading">
                  <strong>Attempt {attempt.attemptNumber}</strong>
                  <span className={`status-pill status-pill--${attempt.outcome.toLowerCase()}`}>{attempt.outcome}</span>
                </div>
                <dl className="job-facts">
                  <div><dt>Started</dt><dd>{formatTime(attempt.startedAt)}</dd></div>
                  <div><dt>Finished</dt><dd>{attempt.finishedAt ? formatTime(attempt.finishedAt) : 'Not finished'}</dd></div>
                  <div><dt>Start time source</dt><dd>{attempt.startTimeSource.replaceAll('_', ' ')}</dd></div>
                  {attempt.failureReason && <div><dt>Failure reason</dt><dd>{attempt.failureReason}</dd></div>}
                  {attempt.nextEligibleAt && <div><dt>Retry eligible</dt><dd>{formatTime(attempt.nextEligibleAt)}</dd></div>}
                </dl>
              </li>
            ))}
          </ol>
        )}
      </div>

      <div className="content-card">
        <h2>Stored result</h2>
        <p className="muted">A simulated text preview, not a downloadable report file.</p>
        {!job && <p className="muted">Select a tracked job to inspect its result.</p>}
        {job && job.status === 'FAILED' && <p className="muted">This job failed; no successful result is available.</p>}
        {job && job.status !== 'SUCCEEDED' && job.status !== 'FAILED' &&
          <p className="muted">The result is not ready while this job is {job.status.toLowerCase().replace('_', ' ')}.</p>}
        {job?.status === 'SUCCEEDED' && result.kind === 'loading' &&
          <p className="muted" role="status">Loading stored preview…</p>}
        {job?.status === 'SUCCEEDED' && result.kind === 'error' &&
          <p className="form-error" role="alert">{result.message}</p>}
        {job?.status === 'SUCCEEDED' && result.kind === 'ready' && (
          <div className="preview-panel">
            <p className="muted">Saved by attempt {result.value.attemptNumber}</p>
            <pre className="preview-text">{result.value.preview}</pre>
          </div>
        )}
      </div>
    </section>
  )
}
