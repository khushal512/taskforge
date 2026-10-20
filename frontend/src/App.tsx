import { useCallback, useEffect, useRef, useState } from 'react'
import { getReadiness } from './api/client'
import JobDashboard from './JobDashboard'

type Readiness = 'checking' | 'ready' | 'unavailable'

function App() {
  const [readiness, setReadiness] = useState<Readiness>('checking')
  const controller = useRef<AbortController | null>(null)

  const refreshReadiness = useCallback(() => {
    controller.current?.abort()
    const requestController = new AbortController()
    controller.current = requestController
    setReadiness('checking')

    getReadiness(requestController.signal)
      .then((health) => {
        if (!requestController.signal.aborted) {
          setReadiness(health.status === 'UP' ? 'ready' : 'unavailable')
        }
      })
      .catch(() => {
        if (!requestController.signal.aborted) setReadiness('unavailable')
      })
  }, [])

  useEffect(() => {
    refreshReadiness()
    return () => controller.current?.abort()
  }, [refreshReadiness])

  const healthText = {
    checking: 'Checking backend readiness…',
    ready: 'Backend ready',
    unavailable: 'Backend unavailable',
  }[readiness]

  return (
    <div className="page-shell">
      <header className="site-header">
        <div className="brand-mark" aria-hidden="true">TF</div>
        <div>
          <p className="eyebrow">Local development dashboard</p>
          <h1>TaskForge</h1>
        </div>
      </header>

      <main>
        <section className="hero" aria-labelledby="intro-heading">
          <div>
            <p className="eyebrow">Background jobs, made visible</p>
            <h2 id="intro-heading">Follow a job from queue to result.</h2>
            <p>
              TaskForge stores job state in PostgreSQL and runs an allowlisted
              report-preview handler in the background. This dashboard is for a
              trusted local environment; it has no sign-in or job ownership checks.
            </p>
          </div>
          <div className="health-panel">
            <p className="panel-label">Service readiness</p>
            <p className={`health-state health-state--${readiness}`} role="status" aria-live="polite">
              <span className="health-dot" aria-hidden="true" />{healthText}
            </p>
            <p className="muted">Checks whether Spring Boot and PostgreSQL are ready.</p>
            <button type="button" onClick={refreshReadiness}>Check again</button>
          </div>
        </section>

        <JobDashboard />
      </main>
    </div>
  )
}

export default App
