package com.example.starter.deicing.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 契约端到端测试：状态码、幂等、409 异参、插队禁止、UTC 时刻序列化。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeicingControllerIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private com.example.starter.deicing.service.TimeSource timeSource;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM deicing_allocation");
        jdbcTemplate.update("DELETE FROM deicing_request");
        timeSource.pin(Clock.fixed(T0, ZoneOffset.UTC));
    }

    private String oneStepJson(String requestId, String flight, int duration, String takeoff) {
        return """
                {"requestId":"%s","flightNumber":"%s","standCode":"STAND-%s",
                 "type":"ONE_STEP","requiredBays":1,"durationMinutes":%d,
                 "targetTakeoffTime":"%s"}
                """.formatted(requestId, flight, flight, duration, takeoff);
    }

    @Test
    void applyCreatesThenReplaysOkAndConflictOnDifferentParams() throws Exception {
        String takeoff = T0.plus(Duration.ofMinutes(60)).toString();

        // 首次 201
        mockMvc.perform(post("/api/deicing/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oneStepJson("REQ-W1", "CA101", 30, takeoff)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replay").value(false))
                .andExpect(jsonPath("$.item.status").value("QUEUED"))
                .andExpect(jsonPath("$.item.deadline").value("2026-09-25T10:30:00Z"));

        // 同键同参重放 200
        mockMvc.perform(post("/api/deicing/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oneStepJson("REQ-W1", "CA101", 30, takeoff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay").value(true));

        // 同键异参 409
        mockMvc.perform(post("/api/deicing/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oneStepJson("REQ-W1", "CA101", 25, takeoff)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    void unknownRequestReturns404() throws Exception {
        mockMvc.perform(get("/api/deicing/applications/NOPE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationErrorReturns400() throws Exception {
        mockMvc.perform(post("/api/deicing/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"","flightNumber":"CA101","standCode":"S1",
                                 "type":"ONE_STEP","requiredBays":0,"durationMinutes":30,
                                 "targetTakeoffTime":"2026-09-25T11:00:00Z"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startedJobCannotBeCutInByLaterFlight() throws Exception {
        // 早截止航班 A 出队并开始作业
        mockMvc.perform(post("/api/deicing/applications").contentType(MediaType.APPLICATION_JSON)
                        .content(oneStepJson("REQ-A", "CA101", 30,
                                T0.plus(Duration.ofMinutes(60)).toString())))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/deicing/dequeue").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-A\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/deicing/jobs/start").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.status").value("IN_PROGRESS"));

        // B 需要全部 4 车位但截止更早：队首判定不考虑作业中记录，B 正常排队；
        // 其分配只能在 A 的占用结束（端点相接）之后开始，A 不会被插队或抢占
        String fourBays = """
                {"requestId":"REQ-B","flightNumber":"CA202","standCode":"STAND-CA202",
                 "type":"ONE_STEP","requiredBays":4,"durationMinutes":20,
                 "targetTakeoffTime":"%s"}
                """.formatted(T0.plus(Duration.ofMinutes(45)).toString());
        mockMvc.perform(post("/api/deicing/applications").contentType(MediaType.APPLICATION_JSON)
                        .content(fourBays))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/deicing/dequeue").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-B\"}"))
                .andExpect(status().isOk());

        // A 仍在作业中，其占用完整保留 [10:00,10:30)
        mockMvc.perform(get("/api/deicing/bays/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bays[0].occupancies[0].requestId").value("REQ-A"))
                .andExpect(jsonPath("$.bays[0].occupancies[0].start").value("2026-09-25T10:00:00Z"))
                .andExpect(jsonPath("$.bays[0].occupancies[0].end").value("2026-09-25T10:30:00Z"));
        mockMvc.perform(get("/api/deicing/applications/REQ-A"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }
}
