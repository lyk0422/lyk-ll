package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 例行巡检编排 HTTP 层测试：状态码与统一错误报文语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InspectionPlanControllerTest {

    private static final Instant T0 = Instant.parse("2026-09-27T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM plan_check_report");
        jdbc.update("DELETE FROM inspection_plan");
        jdbc.update("DELETE FROM flight_calendar");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM assessment_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_event");
        jdbc.update("DELETE FROM weather_reading");
    }

    @Test
    void publishReturns201AndDetailQueryable() throws Exception {
        PlanPublishRequest request = new PlanPublishRequest("http-p1", "RW-H1", T0,
                T0.plus(Duration.ofHours(1)), 30, List.of());
        String body = mockMvc.perform(post("/api/inspection-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.calendarVersion").value(1))
                .andExpect(jsonPath("$.conflictEvidence").isString())
                .andReturn().getResponse().getContentAsString();
        long planId = objectMapper.readTree(body).get("planId").asLong();

        mockMvc.perform(get("/api/inspection-plans/" + planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runwayId").value("RW-H1"));
    }

    @Test
    void invalidBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/inspection-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        PlanPublishRequest inverted = new PlanPublishRequest("http-p2", "RW-H2",
                T0.plus(Duration.ofHours(1)), T0, 30, List.of());
        mockMvc.perform(post("/api/inspection-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(inverted)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WINDOW_INVALID"));
    }

    @Test
    void unknownPlanReturns404() throws Exception {
        mockMvc.perform(get("/api/inspection-plans/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAN_NOT_FOUND"));
    }

    @Test
    void businessRejectionsReturn422() throws Exception {
        PlanPublishRequest tooLong = new PlanPublishRequest("http-p3", "RW-H3", T0,
                T0.plus(Duration.ofMinutes(30)), 45, List.of());
        mockMvc.perform(post("/api/inspection-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tooLong)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DURATION_EXCEEDS_WINDOW"));

        PlanPublishRequest overlap = new PlanPublishRequest("http-p4", "RW-H3", T0,
                T0.plus(Duration.ofHours(1)), 30,
                List.of(new FlightWindow(T0.plus(Duration.ofMinutes(10)), T0.plus(Duration.ofMinutes(20)))));
        mockMvc.perform(post("/api/inspection-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overlap)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FLIGHT_WINDOW_CONFLICT"));
    }

    @Test
    void versionConflictReturns409AndCalendarFlowWorks() throws Exception {
        PlanPublishRequest request = new PlanPublishRequest("http-p5", "RW-H4", T0,
                T0.plus(Duration.ofHours(1)), 30, List.of());
        String body = mockMvc.perform(post("/api/inspection-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long planId = objectMapper.readTree(body).get("planId").asLong();

        mockMvc.perform(post("/api/inspection-plans/" + planId + "/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PlanStartRequest("http-s1", 99, 1, T0.plus(Duration.ofMinutes(5))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        mockMvc.perform(post("/api/flight-calendars/RW-H4/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CalendarChangeRequest("http-cal1", 1, T0.plus(Duration.ofMinutes(2))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.affectedPlanIds[0]").value(planId));

        mockMvc.perform(get("/api/flight-calendars/RW-H4/impact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.affectedPlans[0].planId").value(planId))
                .andExpect(jsonPath("$.affectedPlans[0].status").value("PENDING_REVIEW"));

        mockMvc.perform(post("/api/inspection-plans/" + planId + "/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PlanStartRequest("http-s2", 2, 2, T0.plus(Duration.ofMinutes(5))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version").value(3));

        mockMvc.perform(post("/api/inspection-plans/" + planId + "/check-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CheckReportRequest("http-c1", 3, T0.plus(Duration.ofMinutes(20)),
                                        List.of("道面检查"), true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.late").value(false));
    }
}
