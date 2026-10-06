package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层语义测试：状态码、错误报文与幂等回放。
 */
@AutoConfigureMockMvc
class WebApiTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void ingestViaHttpAndReplaySameRequestId() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String body = """
                {"requestId":"%s","stationId":1,"measuredAt":"2026-10-06T00:00:00Z","speedMs":8.0}
                """.formatted(requestId);
        MvcResult first = mockMvc.perform(post("/api/lines/1/wind-readings")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deduplicated").value(false))
                .andReturn();
        // 同键同参：返回首次结果
        mockMvc.perform(post("/api/lines/1/wind-readings")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));
        // 同键异参：409
        String changed = """
                {"requestId":"%s","stationId":1,"measuredAt":"2026-10-06T00:00:00Z","speedMs":9.0}
                """.formatted(requestId);
        mockMvc.perform(post("/api/lines/1/wind-readings")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void unknownLineReturns404() throws Exception {
        mockMvc.perform(get("/api/lines/999")).andExpect(status().isNotFound());
        String body = """
                {"requestId":"%s","stationId":1,"measuredAt":"2026-10-06T00:00:00Z","speedMs":8.0}
                """.formatted(UUID.randomUUID().toString());
        mockMvc.perform(post("/api/lines/999/wind-readings")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidPayloadReturns400() throws Exception {
        mockMvc.perform(post("/api/lines/1/wind-readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stationId\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void boardingForbiddenDuringControlAndLineViewReflectsStatus() throws Exception {
        // 触发缓停
        for (int i = 0; i <= 10; i++) {
            ingest(STATION_3, T0.plusSeconds(60L * i), 17.0);
            ingest(STATION_4, T0.plusSeconds(60L * i), 17.0);
        }
        mockMvc.perform(get("/api/lines/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SLOW_STOP"))
                .andExpect(jsonPath("$.activeEventId").isNumber());
        mockMvc.perform(post("/api/lines/1/boardings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"%s\",\"passengerCount\":2}"
                                .formatted(UUID.randomUUID().toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("线路管制中禁止上客: SLOW_STOP"));
    }
}
