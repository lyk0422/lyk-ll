package com.example.starter.ride;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 层测试：验证 REST 入口、参数校验与错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class RideApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM daily_check_item");
        jdbc.update("DELETE FROM daily_check");
        jdbc.update("DELETE FROM trial_run");
        jdbc.update("DELETE FROM runtime_reading");
        jdbc.update("DELETE FROM ride_event");
        jdbc.update("DELETE FROM ride");
    }

    private long createRide() throws Exception {
        String body = mvc.perform(post("/api/rides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"摩天轮\",\"maxLoad\":10,\"maxSpeed\":30}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_CHECK"))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"rideId\":(\\d+).*", "$1"));
    }

    @Test
    @DisplayName("REST 主流程：日检→3次试运行→开放→读数→归档")
    void restHappyPath() throws Exception {
        long id = createRide();
        mvc.perform(post("/api/rides/{id}/daily-check", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-check\",\"items\":["
                                + "{\"itemName\":\"压杠\",\"passed\":true},"
                                + "{\"itemName\":\"安全带\",\"passed\":true},"
                                + "{\"itemName\":\"联锁装置\",\"passed\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED"))
                .andExpect(jsonPath("$.allPassed").value(true));
        for (int i = 1; i <= 3; i++) {
            mvc.perform(post("/api/rides/{id}/trial-runs", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"a-t" + i + "\",\"normal\":true}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/rides/{id}/open", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-open\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(post("/api/rides/{id}/readings", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loadValue\":10,\"speedValue\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exceeded").value(false))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(post("/api/rides/{id}/archive", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-arch\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(get("/api/rides/{id}/events", id))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("REST 错误语义：未日检开放409、缺参400、异参重放409、设备不存在404")
    void restErrorSemantics() throws Exception {
        long id = createRide();
        // 未日检禁开放 → 409
        mvc.perform(post("/api/rides/{id}/open", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-open\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
        // 缺少 requestId → 400
        mvc.perform(post("/api/rides/{id}/daily-check", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"itemName\":\"压杠\",\"passed\":true}]}"))
                .andExpect(status().isBadRequest());
        // 设备不存在 → 404
        mvc.perform(get("/api/rides/{id}", 999999))
                .andExpect(status().isNotFound());

        // 同键异参 → 409
        mvc.perform(post("/api/rides/{id}/daily-check", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-check\",\"items\":["
                                + "{\"itemName\":\"压杠\",\"passed\":true}]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/rides/{id}/daily-check", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-check\",\"items\":["
                                + "{\"itemName\":\"压杠\",\"passed\":false}]}"))
                .andExpect(status().isConflict());
    }
}
