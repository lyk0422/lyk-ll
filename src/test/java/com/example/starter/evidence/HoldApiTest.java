package com.example.starter.evidence;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 法律保全创建/解除 API 测试，覆盖幂等重放、请求指纹与版本冲突。
 */
class HoldApiTest extends AbstractIntegrationTest {

    private void register(String evidenceId) throws Exception {
        mockMvc.perform(post("/api/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "evidenceId", evidenceId,
                                "sha256", digestOf(evidenceId),
                                "collectedAt", "2026-09-20T10:00:00Z",
                                "expiresAt", "2026-09-23T10:00:00Z"))))
                .andExpect(status().isCreated());
    }

    private String createHoldBody(String requestId, String caseId, List<String> evidenceIds) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "requestId", requestId, "caseId", caseId, "evidenceIds", evidenceIds));
    }

    @Test
    void createHoldReturnsActiveWithVersion() throws Exception {
        register("EV-H1");
        mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createHoldBody("req-h1", "CASE-9", List.of("EV-H1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.caseId").value("CASE-9"))
                .andExpect(jsonPath("$.evidenceIds[0]").value("EV-H1"))
                .andExpect(jsonPath("$.replayed").value(false));
    }

    @Test
    void createHoldReplayReturnsSameHold() throws Exception {
        register("EV-H2");
        String first = mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createHoldBody("req-h2", "CASE-9", List.of("EV-H2"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String holdId = objectMapper.readTree(first).get("holdId").asText();

        mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createHoldBody("req-h2", "CASE-9", List.of("EV-H2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId))
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void createHoldSameKeyDifferentParamsReturns409() throws Exception {
        register("EV-H3");
        mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createHoldBody("req-h3", "CASE-9", List.of("EV-H3"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createHoldBody("req-h3", "CASE-OTHER", List.of("EV-H3"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_FINGERPRINT_CONFLICT"));
    }

    @Test
    void createHoldOnMissingEvidenceReturns404() throws Exception {
        mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createHoldBody("req-h4", "CASE-9", List.of("EV-MISSING"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_FOUND"));
    }

    @Test
    void releaseHoldRequiresMatchingVersionAndIsReplayable() throws Exception {
        register("EV-H5");
        String response = mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createHoldBody("req-h5", "CASE-9", List.of("EV-H5"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String holdId = objectMapper.readTree(response).get("holdId").asText();

        mockMvc.perform(post("/api/holds/{holdId}/release", holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "rel-h5-bad", "expectedVersion", 7))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_VERSION_CONFLICT"));

        mockMvc.perform(post("/api/holds/{holdId}/release", holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "rel-h5", "expectedVersion", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(post("/api/holds/{holdId}/release", holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "rel-h5", "expectedVersion", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.status").value("RELEASED"));

        mockMvc.perform(post("/api/holds/{holdId}/release", holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "rel-h5-other", "expectedVersion", 2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_ALREADY_RELEASED"));
    }

    @Test
    void registryVersionIncreasesWithMutations() throws Exception {
        mockMvc.perform(get("/api/registry/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));

        register("EV-H6");
        mockMvc.perform(get("/api/registry/version"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createHoldBody("req-h6", "CASE-9", List.of("EV-H6"))))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/registry/version"))
                .andExpect(jsonPath("$.version").value(2));
    }
}
