package com.example.starter.pump.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

/**
 * H2 集成测试基类：每个用例前清理业务流水表（保留合成级别/分区/泵站），避免顺序依赖。
 * 使用可控时钟，业务测试可显式推进“当前时刻”。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestTimeConfig.class)
public abstract class AbstractIntegrationTest {

    private static final List<String> BUSINESS_TABLES = List.of(
            "gate_opening_log",
            "pump_task",
            "pump_sequence",
            "level_reading",
            "late_reading_event",
            "miss_contact_order",
            "review_archive",
            "pumpdown_event",
            "gate_receipt",
            "strong_drain_task",
            "strong_drain_target",
            "strong_drain_sequence",
            "late_tide_event",
            "tide_reading",
            "regime_trace",
            "tide_control_config",
            "idempotent_request");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ControlledTimeProvider clock;

    @BeforeEach
    void cleanBusinessTables() {
        clock.setNow(LocalDateTime.of(2026, 9, 26, 8, 0));
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : BUSINESS_TABLES) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    protected long count(String table) {
        Long cnt = jdbc.queryForObject("SELECT COUNT(1) FROM " + table, Long.class);
        return cnt == null ? 0 : cnt;
    }
}
