package com.example.starter.runway;

import com.example.starter.runway.dto.CalendarPublishRequest;
import com.example.starter.runway.dto.EmergencyReportRequest;
import com.example.starter.runway.dto.FlightWindowInput;
import com.example.starter.runway.dto.PlanCheckReportRequest;
import com.example.starter.runway.dto.PlanPublishRequest;
import com.example.starter.runway.dto.PlanStartRequest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 例行窗口/紧急抢占 HTTP 层测试：状态码、幂等与冲突证据的端到端语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoutineWindowControllerTest {

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
        jdbc.update("DELETE FROM routine_check_item");
        jdbc.update("DELETE FROM routine_plan");
        jdbc.update("DELETE FROM runway_emergency");
        jdbc.update("DELETE FROM flight_occupancy");
        jdbc.update("DELETE FROM flight_calendar_version");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM assessment_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_event");
        jdbc.update("DELETE FROM weather_reading");
    }

    @Test
    void fullFlowOverHttpWithStatusCodesAndEvidenceHeaders() throws Exception {
        // 发布日历 v1
        CalendarPublishRequest calendar = new CalendarPublishRequest("h-cal-1", T0, List.of(
                new FlightWindowInput("CA1", "RW01", T0.plus(Duration.ofMinutes(40)),
                        T0.plus(Duration.ofMinutes(50)))));
        mockMvc.perform(post("/api/flight-calendars")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(calendar)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        // 冲突发布 → 422 且响应头携带冲突版本与数量
        PlanPublishRequest badPlan = new PlanPublishRequest("h-plan-bad", "RW01",
                T0.plus(Duration.ofMinutes(20)), T0.plus(Duration.ofMinutes(50)), 30, List.of("A"));
        mockMvc.perform(post("/api/routine-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badPlan)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PLAN_FLIGHT_CONFLICT"))
                .andExpect(header().string("X-Conflict-Calendar-Version", "1"))
                .andExpect(header().string("X-Conflict-Count", "1"));

        // Bean 校验非法（跑道为空）→ 400 VALIDATION_FAILED
        PlanPublishRequest invalid = new PlanPublishRequest("h-plan-invalid", "",
                T0.plus(Duration.ofMinutes(10)), T0.plus(Duration.ofMinutes(40)), 30, List.of("A"));
        mockMvc.perform(post("/api/routine-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 业务参数非法（巡检时长超出窗口）→ 400 INVALID_WINDOW
        PlanPublishRequest outside = new PlanPublishRequest("h-plan-outside", "RW01",
                T0, T0.plus(Duration.ofMinutes(20)), 30, List.of("A"));
        mockMvc.perform(post("/api/routine-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(outside)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSPECTION_OUTSIDE_WINDOW"));

        // 合法发布 → 201
        PlanPublishRequest plan = new PlanPublishRequest("h-plan-ok", "RW01",
                T0, T0.plus(Duration.ofMinutes(40)), 30, List.of("A", "B"));
        String planBody = objectMapper.writeValueAsString(plan);
        mockMvc.perform(post("/api/routine-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.version").value(1));

        // 同键同参重放 → 201 同一结果（计划不新增由服务层测试断言）
        mockMvc.perform(post("/api/routine-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        // 详情 404
        mockMvc.perform(get("/api/routine-plans/999999"))
                .andExpect(status().isNotFound());

        // 查询同跑道同窗存活计划
        Long planId = jdbc.queryForObject(
                "SELECT id FROM routine_plan WHERE request_id = 'h-plan-ok'", Long.class);

        // 开始
        PlanStartRequest start = new PlanStartRequest("h-start", 1, 1, T0);
        mockMvc.perform(post("/api/routine-plans/" + planId + "/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(start)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version").value(2));

        // 检查回报 ×2，第二次正常完成
        PlanCheckReportRequest check1 = new PlanCheckReportRequest("h-c1", 2, "A",
                T0.plus(Duration.ofMinutes(10)));
        mockMvc.perform(post("/api/routine-plans/" + planId + "/checks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(check1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planCompleted").value(false))
                .andExpect(jsonPath("$.planVersion").value(3));
        PlanCheckReportRequest check2 = new PlanCheckReportRequest("h-c2", 3, "B",
                T0.plus(Duration.ofMinutes(20)));
        mockMvc.perform(post("/api/routine-plans/" + planId + "/checks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(check2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planCompleted").value(true))
                .andExpect(jsonPath("$.planStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.planVersion").value(4));
    }

    @Test
    void emergencyOverHttpPreemptsAndImpactQueryWorks() throws Exception {
        CalendarPublishRequest calendar = new CalendarPublishRequest("h-cal-2", T0, List.of(
                new FlightWindowInput("CA9", "RW99", T0, T0.plus(Duration.ofMinutes(30)))));
        mockMvc.perform(post("/api/flight-calendars")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(calendar)))
                .andExpect(status().isCreated());

        PlanPublishRequest plan = new PlanPublishRequest("h-plan-em", "RW02",
                T0.plus(Duration.ofHours(2)), T0.plus(Duration.ofHours(3)), 30, List.of("A"));
        mockMvc.perform(post("/api/routine-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(plan)))
                .andExpect(status().isCreated());
        Long planId = jdbc.queryForObject(
                "SELECT id FROM routine_plan WHERE request_id = 'h-plan-em'", Long.class);

        EmergencyReportRequest emergency = new EmergencyReportRequest("h-em-1", "RW02",
                "跑道侵入险情", T0.plus(Duration.ofHours(2).plusMinutes(5)));
        mockMvc.perform(post("/api/emergencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emergency)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.cancelledPlanIds[0]").value(planId));

        mockMvc.perform(get("/api/routine-plans/" + planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 日历影响查询：端点相接无冲突
        mockMvc.perform(get("/api/flight-calendars/impact")
                        .param("runwayId", "RW02")
                        .param("windowStart", T0.plus(Duration.ofHours(4)).toString())
                        .param("windowEnd", T0.plus(Duration.ofHours(5)).toString())
                        .param("durationMinutes", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calendarVersion").value(1))
                .andExpect(jsonPath("$.conflicts.length()").value(0));
    }
}
