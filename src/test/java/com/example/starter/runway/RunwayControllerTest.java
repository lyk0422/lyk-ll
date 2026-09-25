package com.example.starter.runway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.starter.runway.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层测试：状态码、错误报文与幂等/业务规则的端到端语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunwayControllerTest {

    private static final Instant T0 = Instant.parse("2026-09-26T05:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RunwayService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM assessment_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_event");
        jdbc.update("DELETE FROM weather_reading");
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        ReportRequest first = new ReportRequest("http-1", "RW30", EventType.FOD, T0);
        mockMvc.perform(post("/api/runway-events/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.notamNo").isString());

        ReportRequest conflict = new ReportRequest("http-1", "RW30", EventType.PAVEMENT_DAMAGE, T0);
        mockMvc.perform(post("/api/runway-events/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflict)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 同键同参重放仍是首次结果
        mockMvc.perform(post("/api/runway-events/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void concurrentActiveEventSecondReportReturns409() throws Exception {
        service.report(new ReportRequest("http-2", "RW31", EventType.FOD, T0));
        mockMvc.perform(post("/api/runway-events/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReportRequest(
                                "http-2b", "RW31", EventType.FOD, T0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUNWAY_ACTIVE_EVENT_EXISTS"));
    }

    @Test
    void lowVisibilitySingleInspectionReturns422AndNothingPersisted() throws Exception {
        ReportResponse reported = service.report(new ReportRequest("http-3", "RW32", EventType.FOD, T0));
        service.addWeatherReading(new WeatherRequest("RW32", 300, T0.plus(Duration.ofMinutes(1))));

        mockMvc.perform(post("/api/runway-events/" + reported.eventId() + "/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new InspectionRequest(T0.plus(Duration.ofMinutes(5)), 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOW_VISIBILITY_REQUIRES_DUAL"));

        mockMvc.perform(post("/api/runway-events/" + reported.eventId() + "/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new InspectionRequest(T0.plus(Duration.ofMinutes(6)), 2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.late").value(false));
    }

    @Test
    void frictionBelowThresholdReopenReturns422() throws Exception {
        ReportResponse reported = service.report(new ReportRequest("http-4", "RW33", EventType.FOD, T0));
        service.startInspection(reported.eventId(), new InspectionRequest(T0.plus(Duration.ofMinutes(3)), 2));
        service.assess(reported.eventId(),
                new AssessmentRequest("http-4a", new BigDecimal("0.30"), AssessmentType.CLEAR_TO_REOPEN,
                        T0.plus(Duration.ofMinutes(6))));

        mockMvc.perform(post("/api/runway-events/" + reported.eventId() + "/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReopenRequest("http-4r", T0.plus(Duration.ofMinutes(8))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FRICTION_BELOW_THRESHOLD"));
    }

    @Test
    void unknownEventReturns404AndTrajectoryIsRecomputable() throws Exception {
        mockMvc.perform(get("/api/runway-events/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));

        ReportResponse reported = service.report(new ReportRequest("http-5", "RW34", EventType.FOD, T0));
        service.startInspection(reported.eventId(), new InspectionRequest(T0.plus(Duration.ofMinutes(3)), 2));
        service.assess(reported.eventId(),
                new AssessmentRequest("http-5a", new BigDecimal("0.35"), AssessmentType.CLEAR_TO_REOPEN,
                        T0.plus(Duration.ofMinutes(6))));
        mockMvc.perform(get("/api/runway-events/" + reported.eventId() + "/friction-trajectory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].frictionCoefficient").value(0.35))
                .andExpect(jsonPath("$[0].passed").value(true));
    }

    @Test
    void invalidBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/runway-events/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
