# Local demo: submit a job and inspect its result

This walkthrough uses PowerShell from the repository root. It runs the unauthenticated API only on your machine. The handler returns a stored **simulated text preview**, not a generated report file. Do not expose these ports to an untrusted network.

## 1. Start the stack

Install Java 21, Maven 3.9+, and Docker Desktop with Docker Engine running. Choose your own local PostgreSQL password in the PowerShell session; the value below is a placeholder, not a project credential.

```powershell
$env:DATABASE_PASSWORD = 'choose-a-local-password'
docker compose --profile app up --build -d --wait
$hostPort = if ($env:SERVER_PORT) { $env:SERVER_PORT } else { '8080' }
$base = "http://127.0.0.1:$hostPort"
Invoke-RestMethod "$base/actuator/health/readiness"
```

Compose builds the Java 21 application image, starts PostgreSQL, waits for its healthcheck, then waits for the app readiness healthcheck. Readiness should report `UP` when the database is reachable. The default app and database host ports are 8080 and 5432, both bound to `127.0.0.1`. The container uses `postgres:5432` internally. If either host port is occupied, set `SERVER_PORT` or `DATABASE_PORT` in this PowerShell session **before** starting Compose; `$base` uses the selected app host port.

An existing Compose database volume keeps the password with which PostgreSQL was first initialized. Reuse that password, or set a distinct `COMPOSE_PROJECT_NAME` before `up` to create a separate project and volume. Do not delete a volume to make the demo start.

## 2. Submit one allowlisted job

These commands generate a fresh key containing only permitted characters. A new key gives `201 Created`; the response contains a generated `id` and a `Location` header pointing to that job.

```powershell
$key = 'demo-' + [guid]::NewGuid().ToString('N')
$body = '{"jobType":"GENERATE_REPORT","payload":{"reportName":"Local demo preview"}}'
$created = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$base/api/v1/jobs" -ContentType 'application/json' -Headers @{ 'Idempotency-Key' = $key } -Body $body
$job = $created.Content | ConvertFrom-Json
$jobId = $job.id
[pscustomobject]@{ StatusCode = [int]$created.StatusCode; JobId = $jobId; Location = $created.Headers.Location; InitialStatus = $job.status }
```

Only `jobType=GENERATE_REPORT` and a valid, one-line `payload.reportName` are accepted. The server supplies ID, status, attempt count, and timestamps. The worker may complete before you inspect the first response, so do not require its status to remain `QUEUED`.

## 3. Poll with a deadline

The worker runs asynchronously. This loop checks the persisted status for at most 45 seconds; it does not assume a particular completion time.

```powershell
$deadline = (Get-Date).AddSeconds(45)
do {
    $current = Invoke-RestMethod "$base/api/v1/jobs/$jobId"
    if ($current.status -in @('SUCCEEDED', 'FAILED')) { break }
    Start-Sleep -Seconds 1
} while ((Get-Date) -lt $deadline)
if ($current.status -notin @('SUCCEEDED', 'FAILED')) { throw "Job did not finish before the demo deadline; last status: $($current.status)" }
$current | Select-Object id, status, attemptCount, failureReason
```

`GET /api/v1/jobs/{id}` returns `200 OK` for an existing job. It can show `QUEUED`, `RUNNING`, `RETRY_WAIT`, `SUCCEEDED`, or `FAILED`. `attemptCount` counts started attempts. A successful simulated handler normally yields `SUCCEEDED`; a real failure is reported rather than hidden by this script.

## 4. Inspect attempts and the stored preview

```powershell
$attempts = @(Invoke-RestMethod "$base/api/v1/jobs/$jobId/attempts")
$attempts | Select-Object attemptNumber, startTimeSource, startedAt, finishedAt, outcome, failureReason, nextEligibleAt
if ($current.status -ne 'SUCCEEDED') { throw 'The job failed; inspect its attempt history and safe failure reason.' }
$result = Invoke-RestMethod "$base/api/v1/jobs/$jobId/result"
$result | Select-Object jobId, attemptNumber, preview
```

The history endpoint returns `200 OK` with attempts in ascending `attemptNumber` order, or `[]` for an existing job that has not started. `RUNNING` has no finish time; `RETRY_WAIT` records a scheduled retry. Historical attempts keep their own outcomes even if a later attempt succeeds. A successful result GET returns `200 OK` with `jobId`, successful `attemptNumber`, and `preview` from PostgreSQL. It never reruns the handler. Legacy start-time labels `LEGACY_DERIVED` and `LEGACY_SYNTHETIC` identify reconstructed timestamps, not observed starts.

## 5. Observe an idempotency replay

```powershell
$replayed = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$base/api/v1/jobs" -ContentType 'application/json' -Headers @{ 'Idempotency-Key' = $key } -Body $body
$replayedJob = $replayed.Content | ConvertFrom-Json
[pscustomobject]@{ StatusCode = [int]$replayed.StatusCode; SameId = ($replayedJob.id -eq $jobId); Location = $replayed.Headers.Location; CurrentStatus = $replayedJob.status }
```

The same key and normalized request return `200 OK`, the original ID, and a `Location` header for it. The body is the job's **current** representation, not a saved copy of the first response. Reusing the key for a different logical request returns `409 IDEMPOTENCY_CONFLICT`. A supplied invalid key returns `400 INVALID_REQUEST`; omitting the key creates another job with `201`.

For GET requests, a malformed UUID returns `400 INVALID_REQUEST` and an unknown valid UUID returns `404 JOB_NOT_FOUND`. A result that is pending, failed, or missing from a historical successful job returns `409` with `RESULT_NOT_READY`, `RESULT_FAILED`, or `RESULT_UNAVAILABLE`. Error bodies use `code`, `message`, and `fieldErrors`; they do not expose database details.

## Stop only this stack

When finished, and only if you started this Compose project, run:

```powershell
docker compose --profile app down
```

This stops its containers but preserves the named PostgreSQL volume and its jobs. If you set `COMPOSE_PROJECT_NAME`, keep the same value for `down`. Do not use `down -v` unless you deliberately intend to delete that project's data. See the [README](../README.md) for Maven startup and configuration, and the [architecture guide](architecture.md) for the transaction flow behind this demo.
