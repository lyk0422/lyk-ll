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
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 恢复开放与通告发布原子性的失败注入测试：通告写入失败时整个恢复事务回滚，
 * 不得出现“先开放后补通告”的中间状态。
 */
@SpringBootTest
class ReopenAtomicityFailureTest {

    private static final Instant T0 = Instant.parse("2026-09-26T03:00:00Z");

    @Autowired
    private RunwayService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM assessment_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_event");
        jdbc.update("DELETE FROM weather_reading");
    }

    @AfterEach
    void restoreSchema() throws Exception {
        // 恢复测试中破坏的表结构，保证共享上下文的其他测试不受影响
        try (Connection connection = dataSource.getConnection()) {
            boolean tableMissing = jdbc.queryForList(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'NOTAM_RECORD'")
                    .isEmpty();
            if (tableMissing) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            }
        }
    }

    @Test
    void reopenRollsBackWhenNotamWriteFails() throws Exception {
        ReportResponse reported = service.report(
                new ReportRequest("atom-fail", "RW20", EventType.PAVEMENT_DAMAGE, T0));
        service.startInspection(reported.eventId(), new InspectionRequest(T0.plus(Duration.ofMinutes(3)), 2));
        service.assess(reported.eventId(),
                new AssessmentRequest("atom-fail-a", new BigDecimal("0.60"), AssessmentType.CLEAR_TO_REOPEN,
                        T0.plus(Duration.ofMinutes(6))));

        // 真实数据库故障注入：删除通告表，使通告发布随恢复开放同一事务失败
        jdbc.execute("DROP TABLE notam_record");

        assertThrows(Exception.class, () -> service.reopen(reported.eventId(),
                new ReopenRequest("atom-fail-r", T0.plus(Duration.ofMinutes(9)))));

        // 重新建表后验证：事件状态、进行中占位均随事务回滚，没有先开放
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        String status = jdbc.queryForObject("SELECT status FROM runway_event WHERE id = ?",
                String.class, reported.eventId());
        Integer activeFlags = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_event WHERE id = ? AND active_flag = 1",
                Integer.class, reported.eventId());
        Integer reopenNotams = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notam_record WHERE event_id = ? AND action = 'REOPEN'",
                Integer.class, reported.eventId());
        assertEquals("ASSESSED", status, "通告失败时开放必须回滚");
        assertEquals(1, activeFlags, "进行中占位不得释放");
        // 重建后的通告表为空（关闭通告随 DROP 一并删除），重点是不存在恢复通告
        assertEquals(0, reopenNotams, "失败事务不得留下恢复通告");
        Integer idemRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE scope = 'reopen'", Integer.class);
        assertEquals(0, idemRows, "失败请求不得登记幂等记录");
    }
}
