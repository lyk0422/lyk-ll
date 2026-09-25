package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP 层全链路：报备→抽样→快检→复检→处置留样→详情与预警台账，及错误语义。
 */
class ApiFlowTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullFlowOverHttp() throws Exception {
        long batchId = postBatch("api-reg", "山东寿光", "番茄");

        mockMvc.perform(post("/api/batches/{id}/samples", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-sam\",\"item\":\"有机磷\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("NORMAL"));

        mockMvc.perform(post("/api/batches/{id}/rapid-tests", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-test\",\"item\":\"有机磷\",\"inhibitionRate\":55}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("SUSPECTED"))
                .andExpect(jsonPath("$.batchStatus").value("SUSPECTED"));

        mockMvc.perform(post("/api/batches/{id}/rechecks", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-recheck\",\"result\":\"FAIL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchStatus").value("DISPOSED"))
                .andExpect(jsonPath("$.retentionUntil").exists());

        mockMvc.perform(get("/api/batches/{id}", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.status").value("DISPOSED"))
                .andExpect(jsonPath("$.samples.length()").value(1))
                .andExpect(jsonPath("$.rapidTests.length()").value(1))
                .andExpect(jsonPath("$.rechecks.length()").value(1))
                .andExpect(jsonPath("$.retention.retainUntil").exists());
    }

    @Test
    void alertLedgerAndRecomputeOverHttp() throws Exception {
        for (int i = 1; i <= 3; i++) {
            long batchId = postBatch("al-reg-" + i, "山东寿光", "番茄");
            mockMvc.perform(post("/api/batches/{id}/samples", batchId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"al-sam-" + i + "\",\"item\":\"有机磷\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/batches/{id}/rapid-tests", batchId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"al-test-" + i
                                    + "\",\"item\":\"有机磷\",\"inhibitionRate\":70}"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/origin-alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].origin").value("山东寿光"))
                .andExpect(jsonPath("$[0].triggerCount").value(3));

        mockMvc.perform(get("/api/origin-alerts/recompute")
                        .param("origin", "山东寿光")
                        .param("variety", "番茄")
                        .param("date", "2026-09-26"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspectedCount").value(3))
                .andExpect(jsonPath("$.shouldAlert").value(true))
                .andExpect(jsonPath("$.alertExists").value(true));
    }

    @Test
    void duplicateSubmissionReturns409Body() throws Exception {
        long batchId = postBatch("dup-reg", "山东寿光", "番茄");
        String body = "{\"requestId\":\"dup-sam\",\"item\":\"有机磷\"}";
        mockMvc.perform(post("/api/batches/{id}/samples", batchId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/batches/{id}/samples", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"dup-sam-2\",\"item\":\"有机磷\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void sameKeyDifferentParamsReturns409OverHttp() throws Exception {
        postBatch("idem-reg", "山东寿光", "番茄");

        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"idem-reg\",\"origin\":\"云南昆明\","
                                + "\"variety\":\"番茄\",\"quantity\":1000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void validationFailureReturns400() throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origin\":\"山东寿光\",\"variety\":\"番茄\",\"quantity\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownBatchReturns404() throws Exception {
        mockMvc.perform(get("/api/batches/{id}", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    private long postBatch(String requestId, String origin, String variety) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"origin\":\"" + origin
                                + "\",\"variety\":\"" + variety + "\",\"quantity\":1000}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(node.get("status").asText()).isEqualTo("REGISTERED");
        return node.get("id").asLong();
    }
}
