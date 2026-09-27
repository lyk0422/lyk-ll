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

import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 例行计划/日历 HTTP 层测试：状态码（201/200/400/404/409/422）、冲突证据与幂等端到端语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoutineControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-03T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM routine_completion_report");
        jdbc.update("DELETE FROM routine_check_item");
        jdbc.update("DELETE FROM routine_plan");
        jdbc.update("DELETE FROM flight_occupancy");
        jdbc.update("DELETE FROM flight_calendar");
        jdbc.update("DELETE FROM runway_lock");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM assessment_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_event");
        jdbc.update("DELETE FROM weather_reading");
        jdbc.update("MERGE INTO flight_calendar (version, published_at, request_id, created_at) KEY (version)"
                + " VALUES (1, ?, NULL, ?)", java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0));
    }

    @Test
    void publishPlanThenStartAndCompleteOverHttp() throws Exception {
        var planReq = new PlanPublishRequest("h-p", "RW-H1", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        String planBody = objectMapper.writeValueAsString(planReq);
        MvcResult created = mockMvc.perform(post("/api/routine-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(planBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.evidence").isString())
                .andReturn();
        long planId = objectMapper.readTree(created.getResponse().getContentAsString()).get("planId").asLong();

        // 同 requestId 同参重放，仍为首次结果
        mockMvc.perform(post("/api/routine-plans").contentType(MediaType.APPLICATION_JSON).content(planBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        var startReq = new PlanStartRequest("h-s", 1, T0.plus(Duration.ofMinutes(10)));
        mockMvc.perform(post("/api/routine-plans/" + planId + "/start").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("STARTED"))
                .andExpect(jsonPath("$.version").value(2));

        var completeReq = new PlanCompletionRequest("h-done", 2, T0.plus(Duration.ofMinutes(70)));
        mockMvc.perform(post("/api/routine-plans/" + planId + "/completion").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.late").value(false))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void occupancyConflictReturns422WithEvidence() throws Exception {
        var calReq = new CalendarPublishRequest("h-cal", 1, T0,
                List.of(new FlightWindowInput("RW-H2", T0, T0.plus(Duration.ofHours(1)))));
        mockMvc.perform(post("/api/flight-calendar/versions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(calReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        var planReq = new PlanPublishRequest("h-conf", "RW-H2", T0, T0.plus(Duration.ofHours(2)), 60, 2);
        mockMvc.perform(post("/api/routine-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(planReq)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OCCUPANCY_CONFLICT"))
                .andExpect(jsonPath("$.evidence").isString())
                .andExpect(jsonPath("$.evidence").value(org.hamcrest.Matchers.containsString("\"conflict\":true")));
    }

    @Test
    void invalidParamsReturn400AndMissingPlan404() throws Exception {
        var badWindow = new PlanPublishRequest("h-bad", "RW-H3",
                T0.plus(Duration.ofHours(1)), T0, 60, 1);
        mockMvc.perform(post("/api/routine-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badWindow)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PLAN_WINDOW"));

        mockMvc.perform(get("/api/routine-plans/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTINE_PLAN_NOT_FOUND"));
    }

    @Test
    void staleCalendarVersionOnPublishReturns422AndCalendarConflict409() throws Exception {
        // 发布计划依据版本1
        MvcResult created = mockMvc.perform(post("/api/routine-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PlanPublishRequest("h-v", "RW-H4",
                                T0, T0.plus(Duration.ofHours(2)), 60, 1))))
                .andExpect(status().isCreated())
                .andReturn();
        long planId = objectMapper.readTree(created.getResponse().getContentAsString()).get("planId").asLong();

        // 推进日历到版本2
        mockMvc.perform(post("/api/flight-calendar/versions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CalendarPublishRequest("h-cal2", 1,
                                T0, List.of()))))
                .andExpect(status().isCreated());

        // 仍以 expectedVersion=1 再推进 → 409
        mockMvc.perform(post("/api/flight-calendar/versions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CalendarPublishRequest("h-cal3", 1,
                                T0, List.of()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CALENDAR_VERSION_CONFLICT"));

        // 开始计划携带过期聚合版本1 → 409
        mockMvc.perform(post("/api/routine-plans/" + planId + "/start").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PlanStartRequest("h-vs", 1, T0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_VERSION_CONFLICT"));
    }
}
