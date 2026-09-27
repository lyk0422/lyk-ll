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
 * 例行计划/航班日历 HTTP 层测试：状态码（201/200/400/404/409/422）、幂等与紧急抢占端到端语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoutinePlanControllerTest {

    private static final Instant T0 = Instant.parse("2026-09-27T06:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoutinePlanService planService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM plan_completion");
        jdbc.update("DELETE FROM plan_check_item");
        jdbc.update("DELETE FROM inspection_plan");
        jdbc.update("DELETE FROM runway_inspection_occupancy");
        jdbc.update("DELETE FROM runway_coordination_lock");
        jdbc.update("DELETE FROM flight_window");
        jdbc.update("DELETE FROM flight_calendar");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM assessment_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_event");
        jdbc.update("DELETE FROM weather_reading");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    @Test
    void publishCalendarPlanStartCheckCompleteOverHttp() throws Exception {
        CalendarPublishRequest calendar = new CalendarPublishRequest("h-cal-1", 0,
                T0.plus(Duration.ofMinutes(1)), List.of(new FlightOccupancyInput("RWH1", "HX-1",
                T0.plus(Duration.ofHours(2)), T0.plus(Duration.ofHours(3)))));
        mockMvc.perform(post("/api/flight-calendars/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(json(calendar)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.windowCount").value(1));

        PlanPublishRequest plan = new PlanPublishRequest("h-plan-1", "RWH1",
                T0, T0.plus(Duration.ofMinutes(60)), T0.plus(Duration.ofMinutes(10)), 20, 1);
        var planResult = mockMvc.perform(post("/api/inspection-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(json(plan)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.evidence.calendarVersion").value(1))
                .andExpect(jsonPath("$.evidence.checkedWindows[0].flightNo").value("HX-1"))
                .andReturn();
        long planId = objectMapper.readTree(planResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/inspection-plans/" + planId + "/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PlanStartRequest("h-start-1", 1, T0.plus(Duration.ofMinutes(10))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.calendarVersion").value(1));

        mockMvc.perform(post("/api/inspection-plans/" + planId + "/checks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PlanCheckItemRequest("h-chk-1", 2, "LIGHTING", CheckResult.PASS,
                                "ok", T0.plus(Duration.ofMinutes(12))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.result").value("PASS"));

        mockMvc.perform(post("/api/inspection-plans/" + planId + "/completion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PlanCompleteRequest("h-done-1", 3,
                                T0.plus(Duration.ofMinutes(28))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.late").value(false));

        mockMvc.perform(get("/api/inspection-plans/" + planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks[0].itemCode").value("LIGHTING"))
                .andExpect(jsonPath("$.completionCount").value(1))
                .andExpect(jsonPath("$.lateCompletionCount").value(0));
    }

    @Test
    void emergencyAbortThenLateCompletionDoesNotReopenOverHttp() throws Exception {
        planService.publishCalendar(new CalendarPublishRequest("h-cal-2", 0,
                T0.plus(Duration.ofMinutes(1)), List.of(new FlightOccupancyInput("RWH2", "HX-2",
                T0.plus(Duration.ofHours(2)), T0.plus(Duration.ofHours(3))))));
        PlanResponse plan = planService.publishPlan(new PlanPublishRequest("h-plan-2", "RWH2",
                T0, T0.plus(Duration.ofMinutes(60)), T0.plus(Duration.ofMinutes(10)), 20, 1));
        planService.startPlan(plan.id(), new PlanStartRequest("h-start-2", 1,
                T0.plus(Duration.ofMinutes(10))));
        planService.reportCheck(plan.id(), new PlanCheckItemRequest("h-chk-2", 2, "MARKING",
                CheckResult.PASS, null, T0.plus(Duration.ofMinutes(12))));

        mockMvc.perform(post("/api/runway-events/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ReportRequest("h-emg-2", "RWH2", EventType.FOD,
                                T0.plus(Duration.ofMinutes(15))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(get("/api/inspection-plans/" + plan.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABORTED"))
                .andExpect(jsonPath("$.checks.length()").value(1))
                .andExpect(jsonPath("$.abortedByEventId").isNumber());

        mockMvc.perform(post("/api/inspection-plans/" + plan.id() + "/completion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PlanCompleteRequest("h-late-1", 3,
                                T0.plus(Duration.ofMinutes(25))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABORTED"))
                .andExpect(jsonPath("$.late").value(true))
                .andExpect(jsonPath("$.version").value(4));

        mockMvc.perform(get("/api/inspection-plans/" + plan.id()))
                .andExpect(jsonPath("$.lateCompletionCount").value(1));
    }

    @Test
    void flightConflictReturns422AndUnknownPlanReturns404() throws Exception {
        planService.publishCalendar(new CalendarPublishRequest("h-cal-3", 0,
                T0.plus(Duration.ofMinutes(1)), List.of(new FlightOccupancyInput("RWH3", "HX-3",
                T0.plus(Duration.ofMinutes(20)), T0.plus(Duration.ofMinutes(30))))));
        PlanPublishRequest conflict = new PlanPublishRequest("h-plan-bad", "RWH3",
                T0, T0.plus(Duration.ofMinutes(60)), T0.plus(Duration.ofMinutes(10)), 20, 1);
        mockMvc.perform(post("/api/inspection-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(json(conflict)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FLIGHT_OCCUPANCY_CONFLICT"));

        mockMvc.perform(get("/api/inspection-plans/888888"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAN_NOT_FOUND"));
    }

    @Test
    void invalidBodiesReturn400AndCalendarImpactListsStalePlans() throws Exception {
        mockMvc.perform(post("/api/flight-calendars/publish")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        planService.publishCalendar(new CalendarPublishRequest("h-cal-4", 0,
                T0.plus(Duration.ofMinutes(1)), List.of(new FlightOccupancyInput("RWN", "NX",
                T0.plus(Duration.ofHours(2)), T0.plus(Duration.ofHours(3))))));
        PlanResponse stalePlan = planService.publishPlan(new PlanPublishRequest("h-plan-4", "RWH4",
                T0, T0.plus(Duration.ofMinutes(60)), T0.plus(Duration.ofMinutes(10)), 20, 1));
        planService.publishCalendar(new CalendarPublishRequest("h-cal-5", 1,
                T0.plus(Duration.ofMinutes(2)), List.of(new FlightOccupancyInput("RWN", "NX",
                T0.plus(Duration.ofHours(2)), T0.plus(Duration.ofHours(3))))));

        mockMvc.perform(get("/api/flight-calendars/impact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calendarVersion").value(2))
                .andExpect(jsonPath("$.impactedPlans[0].runwayId").value("RWH4"))
                .andExpect(jsonPath("$.impactedPlans[0].status").value("REVIEW_PENDING"));

        // 仍冲突时重审 422：新版本在该跑道窗口内新增正长度相交航班
        planService.publishCalendar(new CalendarPublishRequest("h-cal-6", 2,
                T0.plus(Duration.ofMinutes(3)), List.of(new FlightOccupancyInput("RWH4", "HX-4",
                T0.plus(Duration.ofMinutes(15)), T0.plus(Duration.ofMinutes(25))))));
        mockMvc.perform(post("/api/inspection-plans/" + stalePlan.id() + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PlanReviewRequest("h-review-bad", 2, 3))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FLIGHT_OCCUPANCY_CONFLICT"));
    }
}
