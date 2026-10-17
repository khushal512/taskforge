package com.taskforge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "taskforge.worker.scheduling-enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class JobApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void postThenGetReadsTheSameJobFromPostgres() throws Exception {
        String request = "{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"reportName\":\" Quarterly report \"}}";
        String responseBody = mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobType").value("GENERATE_REPORT"))
                .andExpect(jsonPath("$.payload.reportName").value("Quarterly report"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode created = json.readTree(responseBody);
        UUID id = UUID.fromString(created.get("id").asText());
        assertThat(jdbc.queryForObject("select count(*) from jobs where id = ?", Integer.class, id)).isEqualTo(1);

        mvc.perform(get("/api/v1/jobs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.payload.reportName").value("Quarterly report"))
                .andExpect(jsonPath("$.createdAt").value(created.get("createdAt").asText()))
                .andExpect(jsonPath("$.updatedAt").value(created.get("updatedAt").asText()));
    }

    @Test
    void rejectsInvalidAndExtraFieldsWithoutCreatingJobs() throws Exception {
        int before = jdbc.queryForObject("select count(*) from jobs", Integer.class);

        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"reportName\":\"   \"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['payload.reportName']").exists());
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"reportName\":\"Report\"},\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"GENERATE_REPORT\",\"payload\":{\"reportName\":\"bad\\nname\"}}"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select count(*) from jobs", Integer.class)).isEqualTo(before);
    }

    @Test
    void malformedJsonAndMissingJobsReturnSafeErrors() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.trace").doesNotExist());

        mvc.perform(get("/api/v1/jobs/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid job ID"));

        mvc.perform(get("/api/v1/jobs/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }
}
