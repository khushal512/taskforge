package com.taskforge.api;

import com.taskforge.application.JobApplicationService;
import com.taskforge.application.JobNotFoundException;
import com.taskforge.domain.Job;
import com.taskforge.domain.JobType;
import com.taskforge.domain.ReportPayload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JobController.class)
class JobControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JobApplicationService service;

    @Test
    void postReturnsCreatedJobAndLocation() throws Exception {
        Job job = Job.create(UUID.randomUUID(), new ReportPayload("Report"), Instant.parse("2026-10-16T10:15:30Z"));
        when(service.submit(JobType.GENERATE_REPORT, "Report")).thenReturn(job);

        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"reportName\":\"Report\"}}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/jobs/" + job.id()))
                .andExpect(jsonPath("$.id").value(job.id().toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attemptCount").value(0));
    }

    @Test
    void blankReportNameReturnsFieldError() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"reportName\":\"   \"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors['payload.reportName']").exists());
    }

    @Test
    void unknownAndServerManagedFieldsAreRejected() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"reportName\":\"Report\"},\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"reportName\":\"Report\",\"extra\":1}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedJsonAndInvalidIdReturnSafeBadRequests() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/jobs/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid job ID"));
    }

    @Test
    void missingJobReturnsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new JobNotFoundException(id));

        mvc.perform(get("/api/v1/jobs/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }

    @Test
    void unexpectedFailureDoesNotExposeInternalDetails() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new IllegalStateException("internal database detail"));

        mvc.perform(get("/api/v1/jobs/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(not(containsString("database detail"))));
    }

    @Test
    void unsupportedMethodIsNotAnInternalServerError() throws Exception {
        mvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedContentTypeIsNotAnInternalServerError() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.TEXT_PLAIN).content("report"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unsupportedResponseTypeKeepsItsHttpStatus() throws Exception {
        Job job = Job.create(UUID.randomUUID(), new ReportPayload("Report"), Instant.parse("2026-10-16T10:15:30Z"));
        when(service.get(job.id())).thenReturn(job);

        mvc.perform(get("/api/v1/jobs/{id}", job.id()).accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable());
    }
}
