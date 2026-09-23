package com.example.starter.evidence;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 证据登记与保管事件 API 测试。
 */
class EvidenceApiTest extends AbstractIntegrationTest {

    private String registerBody(String evidenceId, String digest) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "evidenceId", evidenceId,
                "sha256", digest,
                "collectedAt", "2026-09-20T10:00:00Z",
                "expiresAt", "2026-09-23T10:00:00Z"));
    }

    @Test
    void registerCreatesEvidenceAndIsQueryable() throws Exception {
        mockMvc.perform(post("/api/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("EV-1", digestOf("a"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evidenceId").value("EV-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.replayed").value(false));

        mockMvc.perform(get("/api/evidence/EV-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sha256").value(digestOf("a")))
                .andExpect(jsonPath("$.expiresAt").value("2026-09-23T10:00:00Z"));
    }

    @Test
    void registerSameIdAndDigestIsIdempotentReplay() throws Exception {
        mockMvc.perform(post("/api/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("EV-2", digestOf("b"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("EV-2", digestOf("b"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get("/api/evidence/EV-2/custody-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].eventType").value("REGISTERED"));
    }

    @Test
    void registerSameIdDifferentDigestReturns409() throws Exception {
        mockMvc.perform(post("/api/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("EV-3", digestOf("c"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("EV-3", digestOf("other"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVIDENCE_DIGEST_CONFLICT"));
    }

    @Test
    void registerRejectsMalformedDigest() throws Exception {
        mockMvc.perform(post("/api/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("EV-4", "not-a-digest")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void getMissingEvidenceReturns404() throws Exception {
        mockMvc.perform(get("/api/evidence/EV-MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_FOUND"));
    }

    @Test
    void custodyEventsRecordLifecycleInOrder() throws Exception {
        mockMvc.perform(post("/api/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("EV-5", digestOf("d"))))
                .andExpect(status().isCreated());

        String holdResponse = mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "hold-req-1",
                                "caseId", "CASE-1",
                                "evidenceIds", java.util.List.of("EV-5")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String holdId = objectMapper.readTree(holdResponse).get("holdId").asText();

        mockMvc.perform(post("/api/holds/{holdId}/release", holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "release-req-1",
                                "expectedVersion", 1))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/evidence/EV-5/custody-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].eventType").value("REGISTERED"))
                .andExpect(jsonPath("$[1].eventType").value("HOLD_CREATED"))
                .andExpect(jsonPath("$[2].eventType").value("HOLD_RELEASED"));
    }
}
