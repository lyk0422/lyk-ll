package com.example.starter.museum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 幂等性测试（HTTP 层）：同键同参返回首次结果，同键异参返回 409。
 */
@AutoConfigureMockMvc
class IdempotencyTest extends MuseumTestSupport {

    @Autowired
    private MockMvc mockMvc;

    private String applyBody(String requestId, String borrower) {
        return """
                {"requestId":"%s","artifactId":1,"showcaseId":1,"borrower":"%s",
                 "startDate":"2026-02-01","endDate":"2027-02-01"}
                """.formatted(requestId, borrower);
    }

    @Test
    void sameKeySameParamsReturnsFirstResult() throws Exception {
        String requestId = UUID.randomUUID().toString();
        MvcResult first = mockMvc.perform(post("/api/loans")
                .contentType(MediaType.APPLICATION_JSON).content(applyBody(requestId, "某省博物馆")))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/loans")
                .contentType(MediaType.APPLICATION_JSON).content(applyBody(requestId, "某省博物馆")))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, count("loan_contract"));
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        String requestId = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/loans")
                .contentType(MediaType.APPLICATION_JSON).content(applyBody(requestId, "某省博物馆")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loans")
                .contentType(MediaType.APPLICATION_JSON).content(applyBody(requestId, "另一博物馆")))
                .andExpect(status().isConflict());
        assertEquals(1, count("loan_contract"));
    }

    @Test
    void rejectedRequestReplayReturnsSameError() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String body = """
                {"requestId":"%s","artifactId":1,"showcaseId":1,"borrower":"某省博物馆",
                 "startDate":"2026-01-01","endDate":"2029-01-02"}
                """.formatted(requestId);
        mockMvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, count("loan_contract"));
    }

    @Test
    void readingReplayReturnsFirstResultWithoutDuplicate() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String body = """
                {"requestId":"%s","sampledAt":"2026-01-01T01:00:00Z",
                 "temperature":20.5,"humidity":55.0}
                """.formatted(requestId);
        MvcResult first = mockMvc.perform(post("/api/showcases/1/readings")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/showcases/1/readings")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, count("climate_reading"));
        // 同键异参：409
        String altered = """
                {"requestId":"%s","sampledAt":"2026-01-01T01:00:00Z",
                 "temperature":21.5,"humidity":55.0}
                """.formatted(requestId);
        mockMvc.perform(post("/api/showcases/1/readings")
                .contentType(MediaType.APPLICATION_JSON).content(altered))
                .andExpect(status().isConflict());
        assertEquals(1, count("climate_reading"));
        assertTrue(count("idempotency_record") >= 1);
    }
}
