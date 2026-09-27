package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 紧急接报与例行计划抢占“同一事务”原子性的失败注入测试：
 * 破坏 routine_plan 表使抢占步骤失败时，紧急事件、关闭通告必须随事务整体回滚，
 * 不得出现“已关闭跑道却未完成抢占”的中间状态。
 */
@SpringBootTest
class EmergencyPreemptionAtomicityTest {

    private static final Instant T0 = Instant.parse("2026-10-02T00:00:00Z");

    @Autowired
    private RoutineService routineService;
    @Autowired
    private RunwayService runwayService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

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

    @AfterEach
    void restoreSchema() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            boolean missing = jdbc.queryForList(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'ROUTINE_PLAN'")
                    .isEmpty();
            if (missing) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            }
        }
    }

    @Test
    void emergencyReportRollsBackWhenPlanPreemptionFails() throws Exception {
        PlanResponse started = routineService.publishPlan(new PlanPublishRequest("atom-plan", "RWZ",
                T0, T0.plus(Duration.ofHours(2)), 60, 1));
        routineService.startPlan(started.planId(), new PlanStartRequest("atom-start", 1, T0));

        // 故障注入：删除计划表，使紧急接报事务中的抢占步骤失败
        jdbc.execute("DROP TABLE routine_plan");

        assertThrows(Exception.class, () -> runwayService.report(
                new ReportRequest("atom-em", "RWZ", EventType.FOD, T0.plus(Duration.ofMinutes(30)))));

        // 重建表后验证：紧急事件、关闭通告均随事务回滚
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_event WHERE runway_id = 'RWZ'", Integer.class);
        Integer closeNotams = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notam_record n JOIN runway_event e ON n.event_id = e.id"
                        + " WHERE e.runway_id = 'RWZ' AND n.action = 'CLOSE'", Integer.class);
        Integer idem = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'atom-em'", Integer.class);
        assertEquals(0, events, "抢占失败时紧急事件必须回滚");
        assertEquals(0, closeNotams, "关闭通告必须随事务回滚");
        assertEquals(0, idem, "失败请求不得登记幂等记录");
    }
}
