package com.taskforge.api;

import com.taskforge.application.JobApplicationService;
import com.taskforge.application.SubmissionResult;
import com.taskforge.domain.Job;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobApplicationService service;

    JobController(JobApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<JobResponse> submit(@Valid @RequestBody CreateJobRequest request,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        SubmissionResult result = service.submitWithOptionalKey(
                request.jobType(), request.payload().reportName(), key);
        Job job = result.job();
        URI location = URI.create("/api/v1/jobs/" + job.id());
        if (result.replayed()) {
            return ResponseEntity.ok().location(location).body(JobResponse.from(job));
        }
        return ResponseEntity.created(location).body(JobResponse.from(job));
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(service.get(id));
    }

    @GetMapping("/{id}/attempts")
    public List<JobAttemptResponse> attempts(@PathVariable UUID id) {
        return service.getAttempts(id).stream().map(JobAttemptResponse::from).toList();
    }

    @GetMapping("/{id}/result")
    public JobResultResponse result(@PathVariable UUID id) {
        return JobResultResponse.from(id, service.getResult(id));
    }
}
