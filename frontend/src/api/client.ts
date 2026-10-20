export type JobStatus = 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED'
export type AttemptOutcome = 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED'
export type StartTimeSource = 'RECORDED' | 'LEGACY_DERIVED' | 'LEGACY_SYNTHETIC'

export interface JobResponse {
  id: string
  jobType: 'GENERATE_REPORT'
  payload: { reportName: string }
  status: JobStatus
  attemptCount: number
  createdAt: string
  updatedAt: string
  failureReason: string | null
}

export interface JobAttemptResponse {
  attemptNumber: number
  startedAt: string
  finishedAt: string | null
  startTimeSource: StartTimeSource
  outcome: AttemptOutcome
  failureReason: string | null
  nextEligibleAt: string | null
}

export interface JobResultResponse {
  jobId: string
  attemptNumber: number
  preview: string
}

export interface ApiErrorBody {
  code: string
  message: string
  fieldErrors: Record<string, string>
}

export class ApiRequestError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string,
    readonly fieldErrors: Record<string, string> = {},
  ) {
    super(message)
    this.name = 'ApiRequestError'
  }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...options,
    headers: { Accept: 'application/json', ...options.headers },
  })

  if (!response.ok) {
    const body = await response.json().catch(() => null) as Partial<ApiErrorBody> | null
    throw new ApiRequestError(
      body?.message ?? `Request failed (HTTP ${response.status})`,
      response.status,
      body?.code ?? 'HTTP_ERROR',
      body?.fieldErrors ?? {},
    )
  }

  return response.json() as Promise<T>
}

export function getReadiness(signal?: AbortSignal): Promise<{ status: string }> {
  return request('/actuator/health/readiness', { signal })
}

export function submitReport(reportName: string, signal?: AbortSignal): Promise<JobResponse> {
  return request('/api/v1/jobs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jobType: 'GENERATE_REPORT', payload: { reportName } }),
    signal,
  })
}

export function getJob(id: string, signal?: AbortSignal): Promise<JobResponse> {
  return request(`/api/v1/jobs/${encodeURIComponent(id)}`, { signal, cache: 'no-store' })
}

export function getAttempts(id: string, signal?: AbortSignal): Promise<JobAttemptResponse[]> {
  return request(`/api/v1/jobs/${encodeURIComponent(id)}/attempts`, { signal, cache: 'no-store' })
}

export function getResult(id: string, signal?: AbortSignal): Promise<JobResultResponse> {
  return request(`/api/v1/jobs/${encodeURIComponent(id)}/result`, { signal, cache: 'no-store' })
}
