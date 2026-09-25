package com.example.starter.ventilation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * H2 集成测试基类：每个场景前清理业务流水，避免顺序依赖；合成主数据保留。
 */
@SpringBootTest
public abstract class AbstractVentilationIntegrationTest {

    private static final List<String> TABLES_IN_DELETE_ORDER = List.of(
            "event_log",
            "device_feedback",
            "device_command",
            "shared_damper_commitment",
            "zone_activation_lock",
            "mode_session",
            "idem_request"
    );

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @org.junit.jupiter.api.BeforeEach
    void cleanTables() {
        jdbc.update("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : TABLES_IN_DELETE_ORDER) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("SET REFERENTIAL_INTEGRITY TRUE");
        clock.reset();
    }
}
