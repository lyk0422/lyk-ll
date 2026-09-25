package com.example.starter.runway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * REST 入口测试：接报主流程、参数校验 400、未知事件 404。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunwayClosureApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM closure_status_timeline");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_closure_event");
    }

    @Test
    void reportClosureViaRestReturnsTempClosedEvent() throws Exception {
        mockMvc.perform(post("/api/runways/01L/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rep-rest-1\",\"eventType\":\"FOD\","
                                + "\"reportedAt\":\"2026-09-26T08:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TEMP_CLOSED"))
                .andExpect(jsonPath("$.runwayId").value("01L"));
    }

    @Test
    void missingRequestIdReturns400() throws Exception {
        mockMvc.perform(post("/api/runways/01L/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"FOD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownEventReturns404() throws Exception {
        mockMvc.perform(get("/api/closures/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameKeyDifferentParamsViaRestReturns409() throws Exception {
        String body = "{\"requestId\":\"rep-rest-2\",\"eventType\":\"FOD\","
                + "\"reportedAt\":\"2026-09-26T08:00:00Z\"}";
        mockMvc.perform(post("/api/runways/01L/closures")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/runways/01L/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rep-rest-2\",\"eventType\":\"PAVEMENT_DAMAGE\","
                                + "\"reportedAt\":\"2026-09-26T08:00:00Z\"}"))
                .andExpect(status().isConflict());
    }
}
